package com.miao.helper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志脱敏（P0-1-4，安全要求：密钥/令牌不落盘、不进 logcat）。纯 JDK、无 Android 依赖，可单测。
 *
 * <p>覆盖：Authorization: Bearer 凭证、sk-/pk-/rk- 厂商 Key、JSON 与 key=value 形态的
 * apiKey/token/secret/password 字段值。刻意只匹配「明确的密钥形态」，避免误伤正常译文内容。</p>
 *
 * <p>仅使用 Java 8 正则 API（appendReplacement / 字符串 replaceAll），兼容 minSdk26。</p>
 */
public final class LogScrubber {
    private LogScrubber() {}

    private static final String MASK = "***";

    /** Bearer xxx（HTTP 授权头 / OpenAI 风格） */
    private static final Pattern BEARER =
            Pattern.compile("(?i)bearer[ \\t]+[A-Za-z0-9\\-._~+/=]+");

    /** 厂商密钥前缀 sk-/pk-/rk- 后接至少 6 位字符 */
    private static final Pattern VENDOR_KEY =
            Pattern.compile("(?i)\\b(?:sk|pk|rk)\\-[A-Za-z0-9\\-_]{6,}");

    /** JSON 形态："apiKey":"xxxx" */
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\"(?:api[_-]?key|access[_-]?token|secret|password|token)\"\\s*:\\s*\")[^\"]*(\")");

    /** key=value 形态（值可为引号串或裸串）：apiKey=xxxx、token: xxxx */
    private static final Pattern KV_SECRET = Pattern.compile(
            "(?i)((?:api[_-]?key|access[_-]?token|secret|password|token)\\s*[:=]\\s*)(?:\"[^\"]*\"|[A-Za-z0-9\\-._~+/=]{1,})");

    /** 返回脱敏后的文本；入参为 null 时返回 "null"（与 AppLog 对 null 的处理一致） */
    public static String scrub(String input) {
        if (input == null) return "null";
        String s = input;
        s = BEARER.matcher(s).replaceAll("Bearer " + MASK);
        s = maskVendorKey(s);
        s = JSON_SECRET.matcher(s).replaceAll("$1" + MASK + "$2");
        s = KV_SECRET.matcher(s).replaceAll("$1" + MASK);
        return s;
    }

    /** 厂商 Key 保留前缀类型（如 sk-***），便于排查是哪一类凭证而不泄露内容 */
    private static String maskVendorKey(String s) {
        Matcher m = VENDOR_KEY.matcher(s);
        StringBuffer sb = new StringBuffer(s.length());
        while (m.find()) {
            String g = m.group();
            int dash = g.indexOf('-');
            String prefix = dash >= 0 ? g.substring(0, dash + 1) : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(prefix + MASK));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 判断一段文本里是否疑似含密钥（供写日志前的断言/测试使用） */
    public static boolean looksSensitive(String input) {
        if (input == null) return false;
        return BEARER.matcher(input).find()
                || VENDOR_KEY.matcher(input).find()
                || JSON_SECRET.matcher(input).find()
                || KV_SECRET.matcher(input).find();
    }
}
