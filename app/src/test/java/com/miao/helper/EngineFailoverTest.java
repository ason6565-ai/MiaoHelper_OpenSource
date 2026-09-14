package com.miao.helper;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** EngineFailover / Engine 单元测试（P2-2 多引擎 fallback），纯 JVM、不发真实网络请求。 */
public class EngineFailoverTest {

    private Engine eng(String name, String key) {
        return new Engine(name, "https://" + name + ".example.com", key, "");
    }

    @Test
    public void testStartFiltersKeyless() {
        List<Engine> chain = Arrays.asList(eng("primary", "k1"), eng("nokey", " "), eng("backup", "k2"));
        EngineFailover f = EngineFailover.start(chain);
        assertEquals("无 Key 引擎应被剔除", 2, f.size());
        assertEquals("primary", f.current().name);
    }

    @Test
    public void testStartDedupKeepsFirst() {
        Engine a = eng("a", "same-key");
        Engine b = new Engine("a", "https://a.example.com", "same-key", ""); // 与 a 完全相同
        EngineFailover f = EngineFailover.start(Arrays.asList(a, b, eng("c", "k3")));
        assertEquals("重复引擎只保留优先级最前者", 2, f.size());
    }

    @Test
    public void testStartNullSafe() {
        EngineFailover f = EngineFailover.start(null);
        assertEquals(0, f.size());
        assertFalse(f.hasCurrent());
        assertNull(f.current());
        assertFalse(f.advance());
        assertFalse(f.hasBackup());
    }

    @Test
    public void testAdvanceOrderAndExhaust() {
        EngineFailover f = EngineFailover.start(Arrays.asList(eng("a", "1"), eng("b", "2"), eng("c", "3")));
        assertEquals(1, f.attemptNo());
        assertEquals("a", f.current().name);
        assertTrue(f.advance());
        assertEquals(2, f.attemptNo());
        assertEquals("b", f.current().name);
        assertTrue(f.advance());
        assertEquals("c", f.current().name);
        assertFalse("第三个之后应耗尽", f.advance());
        assertNull("耗尽后 current 为 null", f.current());
    }

    @Test
    public void testResetReturnsToPrimary() {
        EngineFailover f = EngineFailover.start(Arrays.asList(eng("a", "1"), eng("b", "2")));
        f.advance();
        assertEquals("b", f.current().name);
        f.reset();
        assertEquals("成功复位后应回到主引擎", "a", f.current().name);
        assertEquals(1, f.attemptNo());
    }

    @Test
    public void testHasBackup() {
        assertFalse(EngineFailover.start(Collections.singletonList(eng("a", "1"))).hasBackup());
        assertTrue(EngineFailover.start(Arrays.asList(eng("a", "1"), eng("b", "2"))).hasBackup());
    }

    @Test
    public void testSwitchableCodes() {
        for (int code : new int[]{401, 403, 408, 429, 500, 502, 503, 504}) {
            assertTrue("HTTP " + code + " 应切换备用引擎", EngineFailover.isSwitchableCode(code));
        }
        for (int code : new int[]{200, 400, 404, 409, 422, -1}) {
            assertFalse("HTTP " + code + " 不应切换（换引擎无意义）", EngineFailover.isSwitchableCode(code));
        }
    }

    @Test
    public void testSwitchableErrors() {
        assertTrue(EngineFailover.isSwitchableError(new SocketTimeoutException("read timed out")));
        assertTrue(EngineFailover.isSwitchableError(new UnknownHostException("no such host")));
        assertTrue(EngineFailover.isSwitchableError(new ConnectException("refused")));
        assertTrue(EngineFailover.isSwitchableError(new IOException("connection reset")));
        // 本地构造/解析类错误换引擎也救不回来
        assertFalse(EngineFailover.isSwitchableError(new IllegalArgumentException("bad body")));
        assertFalse(EngineFailover.isSwitchableError(new RuntimeException("npe")));
        assertFalse(EngineFailover.isSwitchableError(null));
    }

    @Test
    public void testFullFailoverFlowPrimaryToBackup() {
        // 纯状态机：主失败→备1失败→备2成功，验证游标路径
        Engine a = eng("primary", "k1"), b = eng("backup1", "k2"), c = eng("backup2", "k3");
        EngineFailover f = EngineFailover.start(Arrays.asList(a, b, c));

        assertEquals(a, f.current());
        // 主引擎 401（Key 失效）→ 切换
        assertTrue(EngineFailover.isSwitchableCode(401) && f.advance());
        assertEquals(b, f.current());
        // 备1 超时 → 再切换
        assertTrue(EngineFailover.isSwitchableError(new SocketTimeoutException()) && f.advance());
        assertEquals(c, f.current());
        // 备2 成功 → 复位，下次从主引擎重新开始
        f.reset();
        assertEquals(a, f.current());
    }

    @Test
    public void testEngineEffectiveModelAndToStringHidesKey() {
        Engine withModel = new Engine("e", "https://x", "secret", "model-x");
        assertEquals("model-x", withModel.effectiveModel("global"));
        Engine noModel = new Engine("e", "https://x", "secret", "");
        assertEquals("空模型回落到全局", "global", noModel.effectiveModel("global"));
        assertFalse("toString 不得泄露 Key", withModel.toString().contains("secret"));
    }
}
