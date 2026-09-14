package com.miao.helper;

import org.junit.Test;

import static org.junit.Assert.*;

/** LogLevel / LogScrubber 单元测试（P0-1-4 日志系统），纯 JVM。 */
public class LogCoreTest {

    // ---------------- LogLevel 门控 ----------------

    @Test
    public void testShouldLogByMinLevel() {
        // release 默认 INFO：DEBUG 被压制，INFO 及以上放行
        assertFalse(LogLevel.shouldLog(LogLevel.INFO, LogLevel.DEBUG));
        assertTrue(LogLevel.shouldLog(LogLevel.INFO, LogLevel.INFO));
        assertTrue(LogLevel.shouldLog(LogLevel.INFO, LogLevel.WARN));
        assertTrue(LogLevel.shouldLog(LogLevel.INFO, LogLevel.ERROR));
        // debug 包全开
        assertTrue(LogLevel.shouldLog(LogLevel.DEBUG, LogLevel.DEBUG));
        // NONE 全关
        assertFalse(LogLevel.shouldLog(LogLevel.NONE, LogLevel.ERROR));
    }

    @Test
    public void testClampOutOfRange() {
        assertEquals(LogLevel.DEBUG, LogLevel.clamp(-5));
        assertEquals(LogLevel.NONE, LogLevel.clamp(99));
        assertEquals(LogLevel.WARN, LogLevel.clamp(LogLevel.WARN));
    }

    @Test
    public void testTagAndName() {
        assertEquals("D", LogLevel.tagOf(LogLevel.DEBUG));
        assertEquals("W", LogLevel.tagOf(LogLevel.WARN));
        assertEquals("ERROR", LogLevel.nameOf(LogLevel.ERROR));
    }

    // ---------------- LogScrubber 脱敏 ----------------

    @Test
    public void testScrubBearer() {
        String raw = "conn header Authorization: Bearer sk-abcdef1234567890ABCDEF end";
        String out = LogScrubber.scrub(raw);
        assertFalse("Bearer 凭证不得保留", out.contains("sk-abcdef1234567890ABCDEF"));
        assertTrue("应替换为掩码", out.contains("***"));
    }

    @Test
    public void testScrubVendorKeyKeepsPrefix() {
        String raw = "using key sk-1234567890abcdef now";
        String out = LogScrubber.scrub(raw);
        assertFalse(out.contains("1234567890abcdef"));
        assertTrue("保留前缀类型便于排查", out.contains("sk-***"));
    }

    @Test
    public void testScrubJsonSecret() {
        String raw = "body={\"apiKey\":\"secret-value-xyz\",\"model\":\"v4\"}";
        String out = LogScrubber.scrub(raw);
        assertFalse(out.contains("secret-value-xyz"));
        assertTrue(out.contains("\"apiKey\":\"***\""));
        // 非敏感字段保留
        assertTrue(out.contains("\"model\":\"v4\""));
    }

    @Test
    public void testScrubKeyValue() {
        String raw = "token: abcd1234efgh5678 and password=p@ss-w0rd_xx";
        String out = LogScrubber.scrub(raw);
        assertFalse(out.contains("abcd1234efgh5678"));
        assertFalse(out.contains("p@ss-w0rd_xx"));
        assertTrue(out.contains("token: ***"));
    }

    @Test
    public void testNormalTextNotMangled() {
        // 正常译文/日志不应被误伤
        String normal = "请求成功 译文=今天天气真好，模型输出正常，耗时 132ms";
        assertEquals(normal, LogScrubber.scrub(normal));
    }

    @Test
    public void testNullAndLooksSensitive() {
        assertEquals("null", LogScrubber.scrub(null));
        assertTrue(LogScrubber.looksSensitive("Bearer abcdef123456"));
        assertTrue(LogScrubber.looksSensitive("\"secret\":\"x\""));
        assertFalse(LogScrubber.looksSensitive("普通中文日志，没有凭证"));
        assertFalse(LogScrubber.looksSensitive(null));
    }
}
