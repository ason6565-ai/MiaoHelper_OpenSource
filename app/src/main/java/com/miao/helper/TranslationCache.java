package com.miao.helper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 翻译结果缓存（P2-1）：内存 LRU + 磁盘持久化 + 有效期（TTL）可配置。
 *
 * <p>设计目标：
 * <ul>
 *   <li><b>纯 JDK、不依赖 Android 框架</b>：内存用 {@link LinkedHashMap}（accessOrder LRU），
 *       磁盘通过可注入的 {@link Store} 抽象，因此整套逻辑可在 testDebugUnitTest（本地 JVM）直接单测。</li>
 *   <li><b>杀进程后仍在</b>：put 同时写内存与磁盘 Store；内存未命中时回磁盘查找，命中回填内存。
 *       Android 侧用 {@link FileCacheStore}（目录注入），进程重启后构造同一目录即可恢复。</li>
 *   <li><b>有效期可配</b>：每条记录写入时带过期时间戳；ttlMs &lt;= 0 表示永不过期。运行时可 {@link #setTtl(long)}。</li>
 *   <li><b>线程安全</b>：对外方法均在实例锁内串行化，可被后台线程池并发调用。</li>
 *   <li><b>缓存只是优化、绝不阻塞主流程</b>：磁盘故障由 Store 实现吞掉并返回 null，不向上抛。</li>
 * </ul>
 *
 * 缓存 key 由调用方拼好（原文 + 人设 + 模型/temperature 等影响结果的参数），相同 key 直接返回缓存、不再请求 API。
 */
public class TranslationCache {

    /** 默认有效期：24 小时。 */
    public static final long DEFAULT_TTL_MS = 24L * 60 * 60 * 1000;
    /** 永不过期的 TTL 取值。 */
    public static final long TTL_FOREVER = 0L;
    /** 默认内存条数（与历史实现保持一致）。 */
    public static final int DEFAULT_MAX_MEM = 128;

    /** 磁盘持久化抽象：只负责按 key 存取「已编码字符串」，不感知条目结构，便于测试用内存 Map 替身。 */
    public interface Store {
        /** 读取原始编码串；不存在或损坏返回 null，不抛异常。 */
        String load(String key);
        /** 覆盖写入原始编码串。 */
        void save(String key, String encoded);
        /** 删除单个 key。 */
        void remove(String key);
        /** 清空全部持久化条目。 */
        void clear();
    }

    /** 单条缓存记录。expireAt == Long.MAX_VALUE 表示永不过期。 */
    static final class Entry {
        final String value;
        final long savedAt;
        final long expireAt;
        Entry(String value, long savedAt, long expireAt) {
            this.value = value;
            this.savedAt = savedAt;
            this.expireAt = expireAt;
        }
        boolean expired(long now) {
            return expireAt != Long.MAX_VALUE && now >= expireAt;
        }
    }

    private final LinkedHashMap<String, Entry> mem;
    private final int maxMem;
    private long ttlMs;
    private volatile Store disk;
    private final Object memLock = new Object();
    private final java.util.concurrent.atomic.AtomicLong hits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong misses = new java.util.concurrent.atomic.AtomicLong();

    public TranslationCache() {
        this(DEFAULT_MAX_MEM, DEFAULT_TTL_MS, null);
    }

    public TranslationCache(int maxMemEntries, long ttlMs, Store diskStore) {
        this.maxMem = Math.max(1, maxMemEntries);
        this.ttlMs = ttlMs;
        this.disk = diskStore;
        // accessOrder=true：get 也会把条目挪到最新，迭代序即「最久未用 → 最近使用」，天然 LRU
        this.mem = new LinkedHashMap<String, Entry>(Math.min(64, this.maxMem), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, com.miao.helper.TranslationCache.Entry> eldest) {
                return false; // 由 put 手动按 maxMem 淘汰，淘汰策略更可控
            }
        };
    }

    /** 可被子类覆盖的时钟，单测里用它推进时间验证 TTL，避免 Thread.sleep。 */
    protected long now() {
        return System.currentTimeMillis();
    }

    /** 当前磁盘层（可为 null）。 */
    public Store diskStore() { return disk; }

    /** 安装/替换磁盘层（Android 启动时注入 FileCacheStore）。 */
    public synchronized void attachDisk(Store store) {
        this.disk = store;
    }

    /** 配置有效期（毫秒）；&lt;=0 表示永不过期。只影响之后新写入的条目。 */
    public synchronized void setTtl(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public synchronized long getTtl() {
        return ttlMs;
    }

    /**
     * 取缓存：先内存（命中且未过期直接返回），未命中再查磁盘并回填内存。
     * 过期/损坏一律视为未命中并顺手清理，返回 null。
     */
    public String get(String key) {
        if (key == null) return null;
        long now = now();

        synchronized (memLock) {
            Entry e = mem.get(key); // LRU：本次访问会刷新其访问序
            if (e != null) {
                if (!e.expired(now)) {
                    hits.incrementAndGet();
                    return e.value;
                }
                mem.remove(key);
            }
        }

        // P2-21：磁盘 IO 移出内存锁，磁盘慢时不再让所有线程排队
        Store s = disk;
        if (s != null) {
            String raw;
            try { raw = s.load(key); } catch (Throwable t) { raw = null; }
            if (raw != null) {
                Entry de = decode(raw);
                if (de != null && !de.expired(now)) {
                    synchronized (memLock) { putMem(key, de); }   // 磁盘命中回填内存，但不再回写磁盘
                    hits.incrementAndGet();
                    return de.value;
                }
                try { s.remove(key); } catch (Throwable ignored) { }   // 过期或损坏的磁盘副本清掉
            }
        }

        misses.incrementAndGet();
        return null;
    }

    /** 写缓存：同时落内存与磁盘。value 为 null/空直接忽略。 */
    public void put(String key, String value) {
        if (key == null || value == null || value.isEmpty()) return;
        long now = now();
        long expire = ttlMs > 0 ? now + ttlMs : Long.MAX_VALUE;
        Entry e = new Entry(value, now, expire);
        synchronized (memLock) { putMem(key, e); }
        // P2-21：磁盘写不持内存锁
        Store s = disk;
        if (s != null) {
            try {
                s.save(key, encode(e));
            } catch (Throwable ignored) {
                // 磁盘写失败不影响内存缓存
            }
        }
    }

    /** 删除单个条目（内存 + 磁盘）。 */
    public void remove(String key) {
        if (key == null) return;
        synchronized (memLock) { mem.remove(key); }
        Store s = disk;
        if (s != null) {
            try { s.remove(key); } catch (Throwable ignored) { }
        }
    }

    /** 清空全部缓存并重置命中统计（对应历史 clearCache 语义）。 */
    public void clear() {
        synchronized (memLock) { mem.clear(); }
        Store s = disk;
        if (s != null) {
            try { s.clear(); } catch (Throwable ignored) { }
        }
        hits.set(0);
        misses.set(0);
    }

    public long hits() { return hits.get(); }
    public long misses() { return misses.get(); }
    public int memSize() { synchronized (memLock) { return mem.size(); } }
    public double hitRate() {
        long h = hits.get(), m = misses.get();
        long total = h + m;
        return total == 0 ? 0.0 : (double) h / total;
    }

    // ------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------

    private void putMem(String key, Entry e) {
        mem.put(key, e);
        while (mem.size() > maxMem) {
            Iterator<Map.Entry<String, Entry>> it = mem.entrySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();   // 淘汰最久未用；仅清内存，磁盘持久副本保留（之后仍可回填）
        }
    }

    /**
     * 条目编码为 Base64 字符串（供磁盘 Store 保存）。
     * 用「长度前缀 + UTF-8 字节」而非 writeUTF，规避 writeUTF 单条 64KB 上限，译文含换行/Emoji 也安全。
     */
    static String encode(Entry e) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            byte[] vb = e.value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeLong(e.savedAt);
            out.writeLong(e.expireAt);
            out.writeInt(vb.length);
            out.write(vb);
            out.flush();
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解码；任何损坏返回 null（不抛）。 */
    static Entry decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            byte[] data = Base64.getDecoder().decode(encoded);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            long savedAt = in.readLong();
            long expireAt = in.readLong();
            int len = in.readInt();
            if (len < 0 || len > data.length) return null;
            byte[] vb = new byte[len];
            in.readFully(vb);
            return new Entry(new String(vb, java.nio.charset.StandardCharsets.UTF_8), savedAt, expireAt);
        } catch (Throwable t) {
            return null;
        }
    }
}
