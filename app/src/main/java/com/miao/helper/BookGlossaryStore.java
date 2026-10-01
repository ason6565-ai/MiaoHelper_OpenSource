package com.miao.helper;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v5.0 每本书术语档：按书名保存用户自定义术语表（原文=译名），
 * 随文本/文件翻译加载注入（与 TermGlossary 动态学习叠加：用户预设优先，漂移自动归一）。
 *
 * 存储：内部私有文件 book_glossary.json，结构 { "书名": { "原文": "译名", ... }, ... }。
 * 纯 JDK + org.json，同步安全。
 */
public class BookGlossaryStore {

    private static final String FILE = "book_glossary.json";

    /** 读取某本书的术语表（原文 → 译名，保序）；无则返回空表 */
    public static synchronized Map<String, String> load(Context ctx, String book) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (ctx == null || book == null || book.trim().isEmpty()) return map;
        File f = new File(ctx.getFilesDir(), FILE);
        try {
            if (!f.exists()) return map;
            String json = new String(readAll(f), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);
            JSONObject b = root.optJSONObject(book.trim());
            if (b == null) return map;
            Iterator<String> it = b.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = b.optString(k, "");
                if (k != null && !k.trim().isEmpty() && !v.trim().isEmpty()) {
                    map.put(k.trim(), v.trim());
                }
            }
        } catch (Exception e) {
            AppLog.e("Glossary", "术语档读取失败（文件可能损坏），尝试留档并从 .tmp 恢复：" + e);
            // 损坏文件留档
            try {
                File bad = new File(ctx.getFilesDir(), FILE + ".bad-" + System.currentTimeMillis());
                f.renameTo(bad);
                // 尝试从 .tmp 恢复
                File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
                if (tmp.exists() && tmp.length() > 0) {
                    tmp.renameTo(f);
                    AppLog.i("Glossary", "已从 .tmp 恢复术语档");
                }
            } catch (Exception ex) {
                AppLog.e("Glossary", "术语档留档/恢复失败：" + ex);
            }
        }
        return map;
    }

    /** 保存某本书的术语表（整体覆盖该书；空表 = 清空该书） */
    public static synchronized void save(Context ctx, String book, Map<String, String> map) {
        if (ctx == null || book == null || book.trim().isEmpty()) return;
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            JSONObject root = new JSONObject();
            if (f.exists()) {
                root = new JSONObject(new String(readAll(f), StandardCharsets.UTF_8));
            }
            JSONObject b = new JSONObject();
            if (map != null) {
                for (Map.Entry<String, String> e : map.entrySet()) {
                    if (e.getKey() != null && !e.getKey().trim().isEmpty()
                            && e.getValue() != null && !e.getValue().trim().isEmpty()) {
                        b.put(e.getKey().trim(), e.getValue().trim());
                    }
                }
            }
            root.put(book.trim(), b);
            // 原子写：先写 .tmp，fsync 后 renameTo 覆盖，写一半被杀不会损坏原文件
            File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                // rename 失败（跨卷等），退化为复制覆盖
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
                tmp.delete();
            }
        } catch (Exception e) {
            AppLog.w("Glossary", "术语档写入失败：" + e);
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
