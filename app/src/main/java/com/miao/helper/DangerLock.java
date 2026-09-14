package com.miao.helper;

/**
 * 「千 万 别 点」触发的进程级锁定。
 * 锁定期内，翻译相关开关（实时翻译 / 翻译引擎 / 彻底替换）被强制固定、不可在界面改动；
 * 仅持有进程内静态状态，不做持久化——退出应用进程即自动解除。
 */
public final class DangerLock {
    private static volatile boolean locked = false;

    private DangerLock() {}

    public static void engage() {
        locked = true;
    }

    public static boolean isLocked() {
        return locked;
    }
}
