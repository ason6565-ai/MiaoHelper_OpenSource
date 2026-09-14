package com.miao.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 数据备份/恢复：自定义词库规则 + AI 扩展词库 + 自定义人设 打包为 .txt（JSON 格式，UTF-8）。
 * 导出文件可直接用文本编辑器查看/修改；导入时全量覆盖对应配置。
 */
public class BackupManager {

    public static final String BACKUP_TAG = "MiaoBackup";
    public static final int BACKUP_VERSION = 2;   // v2：词库按风格独立备份（customRules_风格 / apiLexicon_风格）

    private static JSONArray rulesArrayOf(List<String[]> rules) throws Exception {
        JSONArray arr = new JSONArray();
        for (String[] r : rules) {
            arr.put(new JSONObject()
                    .put("from", r[0])
                    .put("to", r.length > 1 ? r[1] : "")
                    .put("regex", r.length > 2 ? r[2] : "0"));
        }
        return arr;
    }

    /** 生成完整备份 JSON 文本（txt 内容） */
    public static String buildBackup() {
        try {
            JSONObject root = new JSONObject();
            root.put("tag", BACKUP_TAG);
            root.put("version", BACKUP_VERSION);
            root.put("app", "拟言助手");
            root.put("exported", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()));

            // 1. 自定义替换规则：按风格独立 [from, to, regex]
            JSONObject rulesByStyle = new JSONObject();
            String curStyle = StyleManager.currentName();
            rulesByStyle.put(curStyle, rulesArrayOf(Prefs.customRulesFor(curStyle)));
            root.put("rulesByStyle", rulesByStyle);

            // 2. AI 扩展词库：按风格独立（4.4.1 修复：旧备份读全局 key，实际扩展词库从未被备份）
            JSONObject lex = new JSONObject();
            lex.put("enabled", Prefs.apiLexiconEnabled());
            lex.put("currentStyle", curStyle);
            JSONObject lexStyles = new JSONObject();
            String[] ids = StyleManager.personaIds();
            for (int i = 0; i < ids.length; i++) {
                String d = Prefs.getApiLexiconDataForId(ids[i]);
                if (d != null && !d.isEmpty()) lexStyles.put(ids[i], d);
            }
            lex.put("styles", lexStyles);
            root.put("lexicon", lex);

            // 3. 自定义人设 [name, prompt, id]
            JSONArray personas = new JSONArray();
            java.util.List<String[]> custom = Prefs.customPersonas();
            for (int i = 0; i < custom.size(); i++) {
                String[] p = custom.get(i);
                personas.put(new JSONObject().put("name", p[0]).put("prompt", p[1]).put("id", Prefs.customPersonaId(i)));
            }
            root.put("personas", personas);

            return root.toString(2);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析并应用备份内容。返回错误消息；成功返回 null。 */
    public static String applyBackup(String content) {
        if (content == null || content.trim().isEmpty()) return "备份文件为空";
        try {
            JSONObject root = new JSONObject(content.trim());
            if (!BACKUP_TAG.equals(root.optString("tag"))) return "不是拟言助手备份文件";

            // 1. 规则：v2 按风格恢复；v1 全局规则迁入当前风格
            JSONObject rulesByStyle = root.optJSONObject("rulesByStyle");
            if (rulesByStyle != null) {
                java.util.Iterator<String> it = rulesByStyle.keys();
                while (it.hasNext()) {
                    String style = it.next();
                    List<String[]> rs = new ArrayList<>();
                    JSONArray arr = rulesByStyle.optJSONArray(style);
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            rs.add(new String[]{o.optString("from", ""), o.optString("to", ""), o.optString("regex", "0")});
                        }
                    }
                    Prefs.setCustomRulesFor(style, rs);
                }
            } else {
                List<String[]> rules = new ArrayList<>();
                JSONArray rulesArr = root.optJSONArray("rules");
                if (rulesArr != null) {
                    for (int i = 0; i < rulesArr.length(); i++) {
                        JSONObject o = rulesArr.getJSONObject(i);
                        rules.add(new String[]{o.optString("from", ""), o.optString("to", ""), o.optString("regex", "0")});
                    }
                }
                Prefs.setCustomRulesFor(StyleManager.currentName(), rules);
            }

            // 2. 扩展词库：v2 按风格恢复；v1 全局数据迁入当前风格
            JSONObject lex = root.optJSONObject("lexicon");
            if (lex != null) {
                Prefs.setApiLexiconEnabled(lex.optBoolean("enabled", false));
                JSONObject lexStyles = lex.optJSONObject("styles");
                if (lexStyles != null) {
                    java.util.Iterator<String> it = lexStyles.keys();
                    while (it.hasNext()) {
                        String style = it.next();
                        if (style.startsWith("p") || style.startsWith("c")) {
                            Prefs.setApiLexiconDataForId(style, lexStyles.optString(style, ""));
                        } else {
                            // 旧版备份按人设名存储：映射到当前隐藏编号；找不到则按名字兜底写入（兼容旧读取）
                            String id = StyleManager.idOfName(style);
                            if (id != null && !id.isEmpty() && !"custom-entry".equals(id)) {
                                Prefs.setApiLexiconDataForId(id, lexStyles.optString(style, ""));
                            } else {
                                Prefs.setApiLexiconDataForStyle(style, lexStyles.optString(style, ""));
                            }
                        }
                    }
                } else {
                    // v1：data 迁入当前风格（按隐藏编号）
                    Prefs.setApiLexiconDataForId(StyleManager.currentId(), lex.optString("data", ""));
                }
            }

            // 3. 自定义人设
            List<String[]> personas = new ArrayList<>();
            JSONArray pArr = root.optJSONArray("personas");
            if (pArr != null) {
                for (int i = 0; i < pArr.length(); i++) {
                    JSONObject o = pArr.getJSONObject(i);
                    String id = o.optString("id", "");
                    if (id.isEmpty()) {
                        personas.add(new String[]{o.optString("name", ""), o.optString("prompt", "")});
                    } else {
                        personas.add(new String[]{o.optString("name", ""), o.optString("prompt", ""), id});
                    }
                }
            }
            Prefs.setCustomPersonas(personas);

            return null;
        } catch (Exception e) {
            return "解析失败：" + e.getMessage();
        }
    }
}
