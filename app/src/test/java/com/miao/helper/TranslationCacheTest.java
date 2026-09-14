package com.miao.helper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * TranslationCache / FileCacheStore 单元测试（P2-1 翻译缓存）。
 * 纯 JVM：不依赖 Android 框架——内存 LRU 用 LinkedHashMap、磁盘用临时目录、时钟用可推进的子类。
 */
public class TranslationCacheTest {

    /** 可手动推进时间的缓存，避免用 Thread.sleep 验证 TTL。 */
    private static class ManualCache extends TranslationCache {
        long clock = 1_000_000L;
        ManualCache(int maxMem, long ttlMs, Store disk) { super(maxMem, ttlMs, disk); }
        @Override protected long now() { return clock; }
        void advance(long ms) { clock += ms; }
    }

    /** 内存假磁盘层，记录调用次数。 */
    private static class MemStore implements TranslationCache.Store {
        final Map<String, String> m = new HashMap<>();
        int saves, removes, clears;
        @Override public String load(String key) { return m.get(key); }
        @Override public void save(String key, String encoded) { m.put(key, encoded); saves++; }
        @Override public void remove(String key) { m.remove(key); removes++; }
        @Override public void clear() { m.clear(); clears++; }
    }

    private File tmpDir;

    @Before
    public void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("tc_test").toFile();
    }

    @After
    public void tearDown() {
        if (tmpDir != null && tmpDir.exists()) {
            File[] fs = tmpDir.listFiles();
            if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
                f.delete();
            //noinspection ResultOfMethodCallIgnored
            tmpDir.delete();
        }
    }

    // ---------------- 内存基本读写 ----------------

    @Test
    public void testPutThenGet_hit() {
        ManualCache c = new ManualCache(128, TranslationCache.TTL_FOREVER, null);
        c.put("k", "译文");
        assertEquals("写入后应命中", "译文", c.get("k"));
        assertEquals(1, c.hits());
    }

    @Test
    public void testMissReturnsNull() {
        ManualCache c = new ManualCache(128, TranslationCache.TTL_FOREVER, null);
        assertNull(c.get("not-exist"));
        assertEquals(1, c.misses());
    }

    @Test
    public void testDifferentKeysIsolated() {
        ManualCache c = new ManualCache(128, TranslationCache.TTL_FOREVER, null);
        c.put("a", "甲");
        c.put("b", "乙");
        assertEquals("甲", c.get("a"));
        assertEquals("乙", c.get("b"));
    }

    /** v4.8-⑤ 缓存命名空间隔离：T（普通翻译）/S（流式）/CAND（候选池）三类 key 互不串用。
     *  同一原文+同一人设下，不同请求类型的 key 前缀不同，命中必须各自独立。 */
    @Test
    public void testNamespacePrefixIsolated() {
        ManualCache c = new ManualCache(128, TranslationCache.TTL_FOREVER, null);
        String text = "model|0.4|傲娇|在干什么";
        c.put("T|" + text, "普通翻译结果");
        c.put("S|" + text, "流式结果");
        c.put("CAND|" + text, "候选池结果");
        assertEquals("普通翻译应命中自己的命名空间", "普通翻译结果", c.get("T|" + text));
        assertEquals("流式应命中自己的命名空间", "流式结果", c.get("S|" + text));
        assertEquals("候选池应命中自己的命名空间", "候选池结果", c.get("CAND|" + text));
        assertEquals("三类互不覆盖，内存应保留 3 条", 3, c.memSize());
    }

    // ---------------- TTL 有效期 ----------------

    @Test
    public void testTtlWithinWindowHit() {
        ManualCache c = new ManualCache(128, 1000L, null);
        c.put("k", "v");
        c.advance(999);
        assertEquals("未到过期时间应命中", "v", c.get("k"));
    }

    @Test
    public void testTtlExpiredReturnsNullAndPurged() {
        MemStore store = new MemStore();
        ManualCache c = new ManualCache(128, 1000L, store);
        c.put("k", "v");
        c.advance(1001);
        assertNull("过期后应返回 null", c.get("k"));
        assertEquals("过期条目应从内存移除", 0, c.memSize());
        assertTrue("过期磁盘副本应被清理", store.removes >= 1);
    }

    @Test
    public void testTtlZeroNeverExpires() {
        ManualCache c = new ManualCache(128, TranslationCache.TTL_FOREVER, null);
        c.put("k", "v");
        c.advance(10L * 365 * 24 * 60 * 60 * 1000); // 推进约十年
        assertEquals("ttl<=0 表示永不过期", "v", c.get("k"));
    }

    @Test
    public void testSetTtlOnlyAffectsNewEntries() {
        ManualCache c = new ManualCache(128, 10L, null);
        c.put("old", "o");
        c.advance(20);
        c.setTtl(1_000_000L);   // 调长有效期，不应复活旧条目
        c.put("newKey", "n");
        c.advance(50);
        assertNull("旧条目按写入时 TTL 仍过期", c.get("old"));
        assertEquals("新条目按新 TTL 命中", "n", c.get("newKey"));
    }

    // ---------------- 内存 LRU ----------------

    @Test
    public void testLruEvictsByAccessOrder() {
        ManualCache c = new ManualCache(2, TranslationCache.TTL_FOREVER, null);
        c.put("a", "1");
        c.put("b", "2");
        assertEquals("1", c.get("a"));   // 访问 a，使 b 成为最久未用
        c.put("c", "3");                 // 超上限，应淘汰 b
        assertNull("最久未用的 b 应被淘汰", c.get("b"));
        assertEquals("被访问过的 a 应保留", "1", c.get("a"));
        assertEquals("新写入的 c 应保留", "3", c.get("c"));
    }

    // ---------------- 磁盘回填 / 持久化 ----------------

    @Test
    public void testMemoryMissDiskHitBackfill() {
        MemStore store = new MemStore();
        ManualCache first = new ManualCache(128, TranslationCache.TTL_FOREVER, store);
        first.put("k", "磁盘译文");
        assertEquals("第一份缓存应已写磁盘", 1, store.saves);

        // 模拟「内存被清空、磁盘仍在」：新建缓存复用同一磁盘层
        ManualCache second = new ManualCache(128, TranslationCache.TTL_FOREVER, store);
        assertEquals(0, second.memSize());
        String got = second.get("k");
        assertEquals("内存 miss 应回磁盘命中", "磁盘译文", got);
        assertEquals("磁盘命中后应回填内存", 1, second.memSize());
    }

    @Test
    public void testDiskPersistenceSurvivesRestart() {
        // 模拟杀进程重启：两个缓存对象、同一个磁盘目录
        FileCacheStore store1 = new FileCacheStore(tmpDir);
        ManualCache before = new ManualCache(128, TranslationCache.TTL_FOREVER, store1);
        before.put("model|0.4|persona|你好", "hello");
        before.put("k2", "v2");

        FileCacheStore store2 = new FileCacheStore(tmpDir);
        ManualCache after = new ManualCache(128, TranslationCache.TTL_FOREVER, store2);
        assertEquals("重启后应从磁盘恢复", "hello", after.get("model|0.4|persona|你好"));
        assertEquals("v2", after.get("k2"));
    }

    @Test
    public void testExpiredDiskEntryPurged() {
        MemStore store = new MemStore();
        long now = 5_000_000L;
        // 手工塞一份「已过期」的磁盘编码
        TranslationCache.Entry expired = new TranslationCache.Entry("old", now - 2000, now - 1000);
        store.save("k", TranslationCache.encode(expired));

        ManualCache c = new ManualCache(128, TranslationCache.TTL_FOREVER, store) {
            @Override protected long now() { return now; }
        };
        assertNull("磁盘上的过期条目应视为未命中", c.get("k"));
        assertTrue("过期磁盘条目应被删除", store.removes >= 1);
    }

    @Test
    public void testClearRemovesMemAndDisk() {
        MemStore store = new MemStore();
        ManualCache c = new ManualCache(128, TranslationCache.TTL_FOREVER, store);
        c.put("a", "1");
        c.put("b", "2");
        c.clear();
        assertEquals(0, c.memSize());
        assertEquals("clear 应清磁盘", 1, store.clears);
        assertNull(c.get("a"));
    }

    // ---------------- FileCacheStore 本身 ----------------

    @Test
    public void testSpecialCharsAndLongValueRoundTrip() {
        FileCacheStore store = new FileCacheStore(tmpDir);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 70000; i++) big.append('字'); // 远超 writeUTF 的 64KB 上限
        String value = "首行\n次行\t中文 English emoji😺\u0001分隔" + big;
        // 经完整编码链路
        TranslationCache.Entry e = new TranslationCache.Entry(value, 1L, Long.MAX_VALUE);
        String encoded = TranslationCache.encode(e);
        store.save("k", encoded);
        String raw = store.load("k");
        TranslationCache.Entry decoded = TranslationCache.decode(raw);
        assertNotNull(decoded);
        assertEquals("特殊字符与超长文本应无损还原", value, decoded.value);
    }

    @Test
    public void testFileStoreDiskCap() {
        FileCacheStore store = new FileCacheStore(tmpDir, 2);
        ManualCache c = new ManualCache(128, TranslationCache.TTL_FOREVER, store);
        c.put("a", "1");
        Thread.yield();
        c.put("b", "2");
        try { Thread.sleep(5); } catch (InterruptedException ignored) { }
        c.put("c", "3");
        assertTrue("磁盘条数不应超过上限", store.count() <= 2);
    }

    @Test
    public void testCorruptDiskEntryReturnsNull() {
        FileCacheStore store = new FileCacheStore(tmpDir);
        store.save("bad", "这不是合法Base64编码###");
        ManualCache c = new ManualCache(128, TranslationCache.TTL_FOREVER, store);
        assertNull("损坏的磁盘条目应安全返回 null", c.get("bad"));
    }

    // ---------------- 统计 ----------------

    @Test
    public void testStats() {
        ManualCache c = new ManualCache(128, TranslationCache.TTL_FOREVER, null);
        c.put("a", "1");
        c.get("a");          // hit
        c.get("missing");    // miss
        assertEquals(1, c.hits());
        assertEquals(1, c.misses());
        assertEquals(0.5, c.hitRate(), 1e-9);
    }
}
