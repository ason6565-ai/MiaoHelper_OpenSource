package com.miao.helper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 轻量日志（P0-1-4）：DEBUG/INFO/WARN/ERROR 四级 + 级别门控 + 敏感信息脱敏 + 核心路径性能打点。
 * 内存环形缓冲（最近 800 行）+ 文件落盘（filesDir/logs/app.log，超限自动轮转）。
 * 线程安全；任何异常都吞掉，绝不能因为写日志反过来让应用崩；密钥/令牌经 {@link LogScrubber} 脱敏，绝不落盘。
 */
public final class AppLog {
    private static final int MAX_MEM = 800;
    private static final long MAX_FILE = 300 * 1024; // 单文件超过 300KB 就轮转
    /** 单次核心操作超过该毫秒数，traceEnd 自动升级为 W 级「偏慢」告警 */
    static final long SLOW_MS = 200L;
    private static final Deque<String> MEM = new ArrayDeque<>();
    private static File logFile;
    // P1-4 修复：异步落盘队列+单线程消费者，避免主线程同步写文件导致 ANR/卡顿
    private static final java.util.concurrent.BlockingQueue<String> WRITE_QUEUE =
            new java.util.concurrent.LinkedBlockingQueue<>(2000);
    private static volatile Thread writeThread = null;
    private static void ensureWriteThread() {
        if (writeThread != null) return;
        synchronized (AppLog.class) {
            if (writeThread != null) return;
            writeThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        String line = WRITE_QUEUE.take();
                        StringBuilder batch = new StringBuilder(line);
                        // 批量消费：队列里还有就一起写，减少 IO 次数
                        for (int i = 0; i < 50; i++) {
                            String next = WRITE_QUEUE.poll();
                            if (next == null) break;
                            batch.append('\n').append(next);
                        }
                        writeFile(batch.toString());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Throwable ignored) {}
                }
            }, "miao-log-writer");
            writeThread.setDaemon(true);
            writeThread.start();
        }
    }
    // P1-5 修复：SimpleDateFormat 非线程安全，改用 ThreadLocal 避免多线程并发 format 产生错误时间戳
    private static final ThreadLocal<SimpleDateFormat> TS = new ThreadLocal<SimpleDateFormat>() {
        @Override protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault());
        }
    };

    /** 最小输出级别：低于它的日志直接丢弃（省 IO、避免正式包外泄调试细节）。默认 INFO，init 时按包类型调整 */
    private static volatile int minLevel = LogLevel.INFO;

    private AppLog() {}

    public static synchronized void init(Context ctx) {
        try {
            // debug 包开 DEBUG 全量；正式包只留 INFO 及以上
            boolean debuggable = (ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            minLevel = debuggable ? LogLevel.DEBUG : LogLevel.INFO;
            // 5.0：用户可在设置页覆盖日志级别（持久化在 Prefs）
            try {
                int userLv = Prefs.logLevel();
                if (userLv > 0) minLevel = LogLevel.clamp(userLv);
            } catch (Throwable ignored) {}

            File dir = new File(ctx.getFilesDir(), "logs");
            if (!dir.exists()) dir.mkdirs();
            logFile = new File(dir, "app.log");
        } catch (Throwable t) {
            Log.e("Miao.AppLog", "init fail", t);
        }
        i("AppLog", "日志系统已初始化，级别=" + LogLevel.nameOf(minLevel)
                + "，日志文件：" + (logFile != null ? logFile.getAbsolutePath() : "null"));
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            i("AppLog", "MiaoHelper v" + pi.versionName + " (build " + pi.versionCode + ")");
        } catch (Throwable ignored) {}
    }

    /** 运行时调整最小级别（故障排查/设置页用） */
    public static void setLevel(int level) { minLevel = LogLevel.clamp(level); }
    public static int getLevel() { return minLevel; }

    public static void d(String tag, String msg) { log(LogLevel.DEBUG, tag, msg); }
    public static void i(String tag, String msg) { log(LogLevel.INFO, tag, msg); }
    public static void w(String tag, String msg) { log(LogLevel.WARN, tag, msg); }
    public static void e(String tag, String msg) { log(LogLevel.ERROR, tag, msg); }

    public static void w(String tag, String msg, Throwable t) {
        log(LogLevel.WARN, tag, msg + "\n" + Log.getStackTraceString(t));
    }

    public static void e(String tag, String msg, Throwable t) {
        log(LogLevel.ERROR, tag, msg + "\n" + Log.getStackTraceString(t));
    }

    private static void log(int level, String tag, String msg) {
        if (!LogLevel.shouldLog(minLevel, level)) return;   // 级别门控：不达最小级别直接丢弃
        if (msg == null) msg = "null";
        msg = LogScrubber.scrub(msg);                        // 安全：密钥/令牌脱敏后才允许输出
        final String lvl = LogLevel.tagOf(level);
        String line;
        try {
            line = TS.get().format(new Date()) + " " + lvl + "/" + tag + ": " + msg;
        } catch (Throwable th) {
            line = lvl + "/" + tag + ": " + msg;
        }
        switch (level) {
            case LogLevel.WARN:  Log.w("Miao." + tag, msg); break;
            case LogLevel.ERROR: Log.e("Miao." + tag, msg); break;
            case LogLevel.DEBUG: Log.d("Miao." + tag, msg); break;
            default:             Log.i("Miao." + tag, msg);
        }
        synchronized (AppLog.class) {
            MEM.addLast(line);
            while (MEM.size() > MAX_MEM) MEM.pollFirst();
        }
        // P1-4 修复：异步落盘，不阻塞调用线程
        ensureWriteThread();
        if (!WRITE_QUEUE.offer(line)) {
            // 队列满（极端情况），直接同步写一次兜底
            writeFile(line);
        }
    }

    // ---------------- 性能打点（核心路径耗时观测） ----------------

    /** 计时起点，与 {@link #traceEnd} 配对 */
    public static long traceStart() { return System.currentTimeMillis(); }

    /** 结束计时，输出一条耗时日志（≥{@link #SLOW_MS}ms 自动升级为 W 偏慢告警），并返回耗时毫秒 */
    public static long traceEnd(String tag, String label, long startMs) {
        long cost = Math.max(0L, System.currentTimeMillis() - startMs);
        if (cost >= SLOW_MS) w(tag, "[耗时·偏慢] " + label + " = " + cost + "ms");
        else i(tag, "[耗时] " + label + " = " + cost + "ms");
        return cost;
    }

    /** try-with-resources 自动打点：离开作用域自动结算耗时，无需手写 end */
    public static Trace trace(String tag, String label) { return new Trace(tag, label); }

    /** 用法：{@code try (AppLog.Trace t = AppLog.trace("Api","翻译请求")) { ...核心逻辑... }} */
    public static final class Trace implements AutoCloseable {
        private final String tag, label;
        private final long start;
        private boolean closed;
        Trace(String tag, String label) { this.tag = tag; this.label = label; this.start = traceStart(); }
        @Override public void close() {
            if (!closed) { closed = true; traceEnd(tag, label, start); }
        }
    }

    /** 追加写文件；超限则删旧重建（简单轮转，避免日志无限膨胀） */
    private static void writeFile(String line) {
        if (logFile == null) return;
        FileOutputStream fos = null;
        try {
            if (logFile.exists() && logFile.length() > MAX_FILE) {
                File old = new File(logFile.getParentFile(), "app.log.prev");
                if (old.exists()) old.delete();
                logFile.renameTo(old);
                logFile = new File(logFile.getParentFile(), "app.log");
            }
            fos = new FileOutputStream(logFile, true);
            // P1-4：批量内容已含行分隔符，末尾补一个换行；单行兜底也走这里
            fos.write((line.endsWith("\n") ? line : line + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
            // 写日志失败不影响主流程
        } finally {
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 取最近 N 条日志（最新在前），用于用户级日志视图。
     * 从内存环形缓冲尾部取 count 条，倒序返回。
     */
    public static synchronized java.util.List<String> getRecentLogs(int count) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (count <= 0) return result;
        int n = Math.min(count, MEM.size());
        // 从尾部取 n 条，倒序（最新在前）
        Object[] arr = MEM.toArray();
        for (int i = arr.length - 1; i >= 0 && result.size() < n; i--) {
            result.add((String) arr[i]);
        }
        return result;
    }

    /** 读出内存中的全部日志（最新在最下面） */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder(MEM.size() * 80);
        for (String s : MEM) sb.append(s).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() {
        MEM.clear();
        try {
            if (logFile != null && logFile.exists()) logFile.delete();
        } catch (Throwable ignored) {}
    }

    public static File file() { return logFile; }
}
