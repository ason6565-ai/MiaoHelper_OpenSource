package com.miao.helper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 翻译历史记录管理：应用私有目录 JSON 文件存储，最多 100 条。
 *
 * 相对旧版（SharedPreferences 全量读写）的改进：
 * 1. 读改写统一加 synchronized，杜绝主线程（本地翻译）与 API 回调线程并发互相覆盖；
 * 2. parse 失败不再静默吞掉并返回空列表（否则下一次 add 会把旧历史整体覆盖蒸发），
 *    而是先把损坏文件改名保留为 *.corrupt，再重建空库，保证历史不丢、可事后找回；
 * 3. 文件落盘用临时文件 + rename 的原子写，避免写一半进程被杀导致 JSON 损坏。
 */
public class HistoryManager {
    private static final String DIR = "miao_history";
    private static final String FILE = "history.json";
    private static final int MAX = 100;
    // P1-10 修复：历史记录异步单线程写（避免主线程 synchronized 块内 fsync 导致 ANR）
    private static final java.util.concurrent.ExecutorService SAVE_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "miao-history-saver");
                t.setDaemon(true);
                return t;
            });

    public static class Entry {
        public String original;
        public String translated;
        public String style;
        public long time;
        public boolean favorite;

        public Entry(String original, String translated, String style, long time) {
            this(original, translated, style, time, false);
        }

        public Entry(String original, String translated, String style, long time, boolean favorite) {
            this.original = original;
            this.translated = translated;
            this.style = style;
            this.time = time;
            this.favorite = favorite;
        }
    }

    private static File file() {
        Context c = Prefs.getContext();
        File dir = new File(c.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, FILE);
    }

    /** 读取全部历史（最新在前）；文件损坏时先备份再返回空库 */
    private static synchronized List<Entry> loadAllUnsafe() {
        List<Entry> list = new ArrayList<>();
        File f = file();
        if (!f.exists()) return list;
        String json;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
            json = new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AppLog.w("History", "读取历史文件失败：" + e);
            backupCorrupt();
            return list;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Entry(
                        o.optString("original", ""),
                        o.optString("translated", ""),
                        o.optString("style", ""),
                        o.optLong("time", 0),
                        o.optBoolean("favorite", false)
                ));
            }
        } catch (Exception e) {
            // JSON 损坏：备份后重建，绝不静默丢库
            AppLog.w("History", "历史 JSON 解析失败，备份损坏文件：" + e);
            backupCorrupt();
            return new ArrayList<>();
        }
        return list;
    }

    /** 读取全部历史（最新在前） */
    public static List<Entry> loadAll() {
        synchronized (HistoryManager.class) {
            return loadAllUnsafe();
        }
    }

    /** 添加一条历史（自动去重：同原文只保留最新） */
    public static void add(String original, String translated, String style) {
        if (original == null || original.isEmpty()) return;
        synchronized (HistoryManager.class) {
            List<Entry> list = loadAllUnsafe();
            for (int i = list.size() - 1; i >= 0; i--) {
                if (original.equals(list.get(i).original)) list.remove(i);
            }
            list.add(0, new Entry(original, translated, style, System.currentTimeMillis()));
            while (list.size() > MAX) list.remove(list.size() - 1);
            saveUnsafe(list);
        }
    }

    /** 删除指定位置 */
    public static void remove(int index) {
        synchronized (HistoryManager.class) {
            List<Entry> list = loadAllUnsafe();
            if (index >= 0 && index < list.size()) {
                list.remove(index);
                saveUnsafe(list);
            }
        }
    }

    /** 收藏/取消收藏（按索引，最新在前） */
    public static void setFavorite(int index, boolean fav) {
        synchronized (HistoryManager.class) {
            List<Entry> list = loadAllUnsafe();
            if (index >= 0 && index < list.size()) {
                list.get(index).favorite = fav;
                saveUnsafe(list);
            }
        }
    }

    /** 导出全部历史为 TXT 到系统下载目录（MediaStore，API 29+；老版本退化为应用目录） */
    public static String exportToDownloads(Context ctx) {
        List<Entry> list = loadAll();
        StringBuilder sb = new StringBuilder();
        sb.append("拟言助手翻译历史导出\n");
        sb.append("导出时间：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date())).append("\n");
        sb.append("共 ").append(list.size()).append(" 条\n");
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            sb.append("\n【").append(i + 1).append("】")
              .append(e.favorite ? "★" : "")
              .append(" ").append(e.style)
              .append(" ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                      .format(new java.util.Date(e.time)))
              .append("\n原文：").append(e.original)
              .append("\n译文：").append(e.translated).append("\n");
        }
        String name = "拟言助手翻译历史_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(new java.util.Date()) + ".txt";
        byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
                android.net.Uri uri = ctx.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return "导出失败：无法创建下载文件";
                try (java.io.OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                    if (out == null) return "导出失败：无法打开下载文件";
                    out.write(data);
                }
                return "已导出到 下载/" + name;
            } catch (Exception e) {
                AppLog.e("History", "导出到下载目录失败", e);
                return "导出失败：" + e.getMessage();
            }
        }
        // API 26-28：退化到应用目录，避免老版本存储权限折腾
        try {
            File f = new File(ctx.getExternalFilesDir(null), name);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                out.write(data);
            }
            return "已导出到应用目录：" + f.getAbsolutePath();
        } catch (Exception e) {
            AppLog.e("History", "导出到应用目录失败", e);
            return "导出失败：" + e.getMessage();
        }
    }

    /** 清空全部 */
    public static void clear() {
        synchronized (HistoryManager.class) {
            File f = file();
            if (f.exists()) f.delete();
        }
    }

    /** 原子写：先写临时文件再 rename，避免进程被杀产生半个 JSON。P1-10：异步执行，不阻塞调用线程 */
    private static void saveUnsafe(final List<Entry> list) {
        final List<Entry> snapshot = new ArrayList<>(list);
        SAVE_POOL.execute(() -> {
            File f = file();
            File tmp = new File(f.getParentFile(), FILE + ".tmp");
            try {
                JSONArray arr = new JSONArray();
                for (Entry e : snapshot) {
                    JSONObject o = new JSONObject();
                    o.put("original", e.original);
                    o.put("translated", e.translated);
                    o.put("style", e.style);
                    o.put("time", e.time);
                    o.put("favorite", e.favorite);
                    arr.put(o);
                }
                byte[] data = arr.toString().getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(data);
                    out.getFD().sync();
                }
                if (f.exists()) f.delete();
                tmp.renameTo(f);
            } catch (Exception e) {
                AppLog.e("History", "保存历史失败", e);
            }
        });
    }

    /** 把损坏的历史文件改名保留为 *.corrupt，便于事后找回 */
    private static void backupCorrupt() {
        try {
            File f = file();
            if (f.exists()) {
                File bak = new File(f.getParentFile(), FILE + ".corrupt-" + System.currentTimeMillis());
                f.renameTo(bak);
                AppLog.w("History", "损坏历史已备份为：" + bak.getName());
            }
        } catch (Exception ignored) {}
    }
}
