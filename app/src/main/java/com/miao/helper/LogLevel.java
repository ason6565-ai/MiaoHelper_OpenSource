package com.miao.helper;

/**
 * 日志级别（P0-1-4）。纯 JDK 常量与门控判定，不依赖 Android，便于单测。
 * 数值越大越严重；只有「消息级别 >= 当前最小级别」才允许输出。
 */
public final class LogLevel {
    public static final int DEBUG = 0;
    public static final int INFO  = 1;
    public static final int WARN  = 2;
    public static final int ERROR = 3;
    /** 高于 ERROR：关闭全部日志 */
    public static final int NONE  = 4;

    private LogLevel() {}

    /** 是否应输出：消息级别不低于设定的最小级别时为 true */
    public static boolean shouldLog(int minLevel, int messageLevel) {
        return clamp(messageLevel) >= clamp(minLevel);
    }

    /** 把任意整数约束到合法级别区间 */
    public static int clamp(int level) {
        if (level < DEBUG) return DEBUG;
        if (level > NONE) return NONE;
        return level;
    }

    /** 级别对应的单字符标记（落盘日志用） */
    public static String tagOf(int level) {
        switch (clamp(level)) {
            case DEBUG: return "D";
            case INFO:  return "I";
            case WARN:  return "W";
            case ERROR: return "E";
            default:    return "N";
        }
    }

    public static String nameOf(int level) {
        switch (clamp(level)) {
            case DEBUG: return "DEBUG";
            case INFO:  return "INFO";
            case WARN:  return "WARN";
            case ERROR: return "ERROR";
            default:    return "NONE";
        }
    }
}
