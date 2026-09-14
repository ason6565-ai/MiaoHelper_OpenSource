package com.miao.helper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 安全正则执行器：防止用户自定义正则导致 ReDoS（灾难性回溯）卡死主线程。
 *
 * 原理：
 *  - 正则编译和匹配都在单线程执行器里跑，不阻塞主线程
 *  - 每次执行设 50ms 超时，超时直接放弃该规则，返回原文本
 *  - 编译成功的正则做 LRU 缓存，避免重复编译
 *  - 注意：Java 正则引擎不响应 Thread.interrupt()，超时后工作线程仍在跑，
 *    但单线程执行器会让后续规则排队，不会影响主线程。极端情况下一个恶意
 *    正则会占住工作线程，但用户自定义规则通常很少，可接受。
 */
public class SafeRegex {

    // P0-2 修复：有界线程池（核心1/最大2/队列10/CallerRunsPolicy），
    // 灾难正则不可中断但线程数有上限（≤2），不会无界增长导致设备发热/被杀。
    private static final ExecutorService EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
            1, 2, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(10),
            r -> {
                Thread t = new Thread(r, "miao-safe-regex");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    private static final int MAX_REGEX_LEN = 200;

    private static final long TIMEOUT_MS = 50;
    private static final int MAX_CACHE = 64;

    // 简单 LRU 缓存：LinkedHashMap accessOrder
    private static final java.util.LinkedHashMap<String, Pattern> CACHE =
        new java.util.LinkedHashMap<String, Pattern>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Pattern> eldest) {
                return size() > MAX_CACHE;
            }
        };

    /**
     * 安全的正则替换。超时或异常时返回原文本。
     *
     * @param input   待处理文本
     * @param regex   用户输入的正则
     * @param replace 替换文本（已做 quoteReplacement 处理）
     * @return 替换后的文本，超时/异常时返回 input
     */
    public static String replaceAll(final String input, final String regex, final String replace) {
        if (input == null || regex == null || regex.isEmpty()) return input;

        // 过长正则直接拒绝，压缩灾难性回溯的构造空间
        if (regex.length() > MAX_REGEX_LEN) {
            AppLog.w("SafeRegex", "正则超长(" + regex.length() + ")，跳过：" + brief(regex));
            return input;
        }
        final Pattern pattern;
        try {
            pattern = compile(regex);
        } catch (PatternSyntaxException e) {
            AppLog.w("SafeRegex", "正则语法错误，跳过：" + regex);
            return input;
        }

        final String replacement = Matcher.quoteReplacement(replace == null ? "" : replace);

        Future<String> future = EXECUTOR.submit(new Callable<String>() {
            @Override public String call() {
                return pattern.matcher(input).replaceAll(replacement);
            }
        });

        try {
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            AppLog.w("SafeRegex", "正则执行超时(" + TIMEOUT_MS + "ms)，跳过：" + brief(regex));
            future.cancel(true); // 尝试中断（正则引擎可能不响应，但至少标记）
            return input;
        } catch (Exception e) {
            AppLog.w("SafeRegex", "正则执行异常，跳过：" + e.getMessage());
            return input;
        }
    }

    /**
     * 验证正则语法是否合法（用户保存规则时调用）。
     * @return null=合法，否则返回错误信息
     */
    public static String validate(String regex) {
        if (regex == null || regex.isEmpty()) return "正则不能为空";
        try {
            Pattern.compile(regex);
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** 编译并缓存正则 */
    private static Pattern compile(String regex) {
        synchronized (CACHE) {
            Pattern p = CACHE.get(regex);
            if (p != null) return p;
            p = Pattern.compile(regex);
            CACHE.put(regex, p);
            return p;
        }
    }

    private static String brief(String s) {
        if (s == null) return "null";
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }
}
