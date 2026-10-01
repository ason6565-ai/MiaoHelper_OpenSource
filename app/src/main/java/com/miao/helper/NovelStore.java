package com.miao.helper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * v5.1 小说生成故事库：单故事持久化。
 * 数据存 files/novel_stories/&lt;storyId&gt;.json；原子写（tmp+rename），读取损坏时回退 .bak。
 * 覆盖：故事设定、角色（含口风与台词样本）、大纲、事件池（未引出/已引出/已回收）、已生成章节。
 */
public class NovelStore {

    /** 角色 */
    public static class Character {
        public String name = "";
        public String desc = "";      // 人设描述
        public String voice = "";     // 口风（说话风格），生成时注入
        public List<String> samples = new ArrayList<>(); // 台词样本（最近 3 条）

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                o.put("desc", desc);
                o.put("voice", voice);
                JSONArray sa = new JSONArray();
                for (String s : samples) sa.put(s);
                o.put("samples", sa);
            } catch (Exception ignored) {}
            return o;
        }

        public static Character fromJson(JSONObject o) {
            Character c = new Character();
            try {
                c.name = o.optString("name", "");
                c.desc = o.optString("desc", "");
                c.voice = o.optString("voice", "");
                JSONArray sa = o.optJSONArray("samples");
                if (sa != null) for (int i = 0; i < sa.length(); i++) c.samples.add(sa.optString(i));
            } catch (Exception ignored) {}
            return c;
        }
    }

    /** 事件池条目：未引出(unused) / 已引出(introduced) / 已回收(resolved) */
    public static class Event {
        public String text = "";
        public String status = "unused";
        public String note = "";

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("text", text);
                o.put("status", status);
                o.put("note", note);
            } catch (Exception ignored) {}
            return o;
        }

        public static Event fromJson(JSONObject o) {
            Event e = new Event();
            try {
                e.text = o.optString("text", "");
                e.status = o.optString("status", "unused");
                e.note = o.optString("note", "");
            } catch (Exception ignored) {}
            return e;
        }
    }

    /** 已生成章节 */
    public static class Chapter {
        public String title = "";
        public String content = "";
        public List<String> eventRefs = new ArrayList<>(); // 本章引出的事件

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("title", title);
                o.put("content", content);
                JSONArray ea = new JSONArray();
                for (String s : eventRefs) ea.put(s);
                o.put("eventRefs", ea);
            } catch (Exception ignored) {}
            return o;
        }

        public static Chapter fromJson(JSONObject o) {
            Chapter ch = new Chapter();
            try {
                ch.title = o.optString("title", "");
                ch.content = o.optString("content", "");
                JSONArray ea = o.optJSONArray("eventRefs");
                if (ea != null) for (int i = 0; i < ea.length(); i++) ch.eventRefs.add(ea.optString(i));
            } catch (Exception ignored) {}
            return ch;
        }
    }

    /** 故事 */
    public static class Story {
        public String id = "";
        public String title = "";
        public String premise = "";              // 故事大体/设定
        public List<Character> characters = new ArrayList<>();
        public List<String> outline = new ArrayList<>();   // 大纲章节标题
        public List<Event> events = new ArrayList<>();     // 事件池
        public List<Chapter> chapters = new ArrayList<>(); // 已生成章节
        public String summary = "";              // 累计摘要（每章一行，用于上下文）

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("title", title);
                o.put("premise", premise);
                JSONArray ca = new JSONArray();
                for (Character c : characters) ca.put(c.toJson());
                o.put("characters", ca);
                JSONArray oa = new JSONArray();
                for (String s : outline) oa.put(s);
                o.put("outline", oa);
                JSONArray ea = new JSONArray();
                for (Event e : events) ea.put(e.toJson());
                o.put("events", ea);
                JSONArray ha = new JSONArray();
                for (Chapter h : chapters) ha.put(h.toJson());
                o.put("chapters", ha);
                o.put("summary", summary);
            } catch (Exception ignored) {}
            return o;
        }

        public static Story fromJson(JSONObject o) {
            Story s = new Story();
            try {
                s.id = o.optString("id", "");
                s.title = o.optString("title", "");
                s.premise = o.optString("premise", "");
                JSONArray ca = o.optJSONArray("characters");
                if (ca != null) for (int i = 0; i < ca.length(); i++) s.characters.add(Character.fromJson(ca.optJSONObject(i)));
                JSONArray oa = o.optJSONArray("outline");
                if (oa != null) for (int i = 0; i < oa.length(); i++) s.outline.add(oa.optString(i));
                JSONArray ea = o.optJSONArray("events");
                if (ea != null) for (int i = 0; i < ea.length(); i++) s.events.add(Event.fromJson(ea.optJSONObject(i)));
                JSONArray ha = o.optJSONArray("chapters");
                if (ha != null) for (int i = 0; i < ha.length(); i++) s.chapters.add(Chapter.fromJson(ha.optJSONObject(i)));
                s.summary = o.optString("summary", "");
            } catch (Exception ignored) {}
            return s;
        }
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "novel_stories");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 新建空故事并落盘 */
    public static Story create(Context ctx, String title) {
        Story s = new Story();
        s.id = newId();
        s.title = (title == null || title.trim().isEmpty()) ? "未命名故事" : title.trim();
        save(ctx, s);
        return s;
    }

    /** 加载故事；不存在返回 null，损坏尝试 .bak */
    public static Story load(Context ctx, String id) {
        if (id == null || id.isEmpty()) return null;
        File f = new File(dir(ctx), id + ".json");
        Story s = read(f);
        if (s == null) {
            File bak = new File(dir(ctx), id + ".json.bak");
            s = read(bak);
            if (s != null) AppLog.w("Novel", "主文件损坏，已从备份恢复 story=" + id);
        }
        return s;
    }

    private static Story read(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 8 * 1024 * 1024)];
            int n = in.read(buf);
            String json = new String(buf, 0, n, "UTF-8");
            return Story.fromJson(new JSONObject(json));
        } catch (Exception e) {
            AppLog.w("Novel", "读取故事失败 " + f.getName() + "：" + e.getMessage());
            return null;
        }
    }

    /** 原子写：先写 tmp，成功后再改名覆盖；旧文件备份为 .bak */
    public static void save(Context ctx, Story s) {
        if (s == null || s.id == null || s.id.isEmpty()) return;
        File d = dir(ctx);
        File tmp = new File(d, s.id + ".json.tmp");
        File dst = new File(d, s.id + ".json");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.toJson().toString().getBytes("UTF-8"));
            out.flush();
        } catch (Exception e) {
            AppLog.w("Novel", "写故事失败 " + s.id + "：" + e.getMessage());
            return;
        }
        try {
            File bak = new File(d, s.id + ".json.bak");
            if (dst.exists()) dst.renameTo(bak);
            if (!tmp.renameTo(dst)) {
                // rename 失败（如目标被占用）：退化为直接覆盖
                if (bak.exists()) bak.renameTo(dst);
            }
        } catch (Exception e) {
            AppLog.w("Novel", "落盘故事失败 " + s.id + "：" + e.getMessage());
        }
    }

    /** 列出全部故事 id（供多故事入口） */
    public static List<String> listIds(Context ctx) {
        List<String> ids = new ArrayList<>();
        File[] fs = dir(ctx).listFiles();
        if (fs == null) return ids;
        for (File f : fs) {
            String n = f.getName();
            if (n.endsWith(".json") && !n.endsWith(".tmp") && !n.endsWith(".bak")) {
                ids.add(n.substring(0, n.length() - 5));
            }
        }
        return ids;
    }
}
