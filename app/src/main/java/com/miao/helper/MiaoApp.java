package com.miao.helper;

import android.app.Application;

/**
 * 进程入口：在 Activity / Service 之前初始化 Prefs 与日志。
 * 避免“用户直接从系统设置开启无障碍服务、从不开 App”时 Prefs 未初始化而崩溃。
 */
public class MiaoApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        AppLog.init(this);
        // P2-1：翻译结果磁盘缓存（杀进程重启后仍可命中）；内部已容错，失败回退纯内存，不影响启动
        try {
            ApiMiaoifier.installDiskCache(new java.io.File(getCacheDir(), "trans_cache"));
        } catch (Throwable t) { AppLog.w("App", "翻译缓存初始化异常：" + t); }
        // 3.x→4.x 风格体系重构：旧 styleIndex 指向已删除的猫娘/旧序号，一次性迁移到新列表
        try { Prefs.migrateLegacyStyleIndex(); } catch (Throwable t) { AppLog.w("App", "风格索引迁移异常：" + t); }
        // 4.5：迁移按 App 风格（appStyleRules）与收藏风格（starredStyles）里的旧索引
        try { Prefs.migrateAppStyleAndStarred(); } catch (Throwable t) { AppLog.w("App", "App风格/收藏索引迁移异常：" + t); }

        // 全局未捕获异常：先落盘日志，再交回系统默认处理器（保留正常崩溃行为）
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            AppLog.e("CRASH", "线程[" + thread.getName() + "] 发生未捕获异常", ex);
            if (def != null) def.uncaughtException(thread, ex);
        });
        AppLog.i("App", "MiaoApp onCreate，进程启动");

        // 后台预加载 jieba 主词典（源为 gzip；AAPT 构建时自动解压 .gz 后缀 asset，APK 内为明文 dict.txt）
        new Thread(() -> {
            long t = System.currentTimeMillis();
            try (java.io.InputStream is = getAssets().open("dict.txt")) {
                com.miao.helper.jieba.WordDictionary.load(is);
                boolean ok = com.miao.helper.jieba.WordDictionary.isReady();
                AppLog.i("Jieba", "主词典加载" + (ok ? "成功" : "失败") + "，耗时=" + (System.currentTimeMillis() - t) + "ms");
            } catch (Throwable e) {
                AppLog.e("Jieba", "主词典加载异常，本地引擎回退整串替换", e);
            }
        }, "jieba-dict-loader").start();

        // 后台预热：DFA 脏词表（含 TinyPinyin 首次加载），不阻塞启动
        new Thread(() -> {
            try { BadWordFilter.warmUp(); }
            catch (Throwable t) { AppLog.w("App", "脏词表预热异常：" + t); }
        }, "miao-warmup").start();
    }
}
