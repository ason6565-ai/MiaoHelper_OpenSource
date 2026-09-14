package com.miao.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 配置存储。init 由 MiaoApp 在进程启动时调用，任何组件前保证可用。 */
public class Prefs {
    private static SharedPreferences sp;
    private static android.content.Context appContext;

    public static synchronized void init(Context c) {
        if (sp != null && appContext != null) return;   // 幂等：防 MiaoApp 与服务兜底重复初始化
        android.content.Context ac = c.getApplicationContext();
        sp = ac.getSharedPreferences("miao", Context.MODE_PRIVATE);
        appContext = ac;
        migrateLegacyApiKey();   // 旧版明文 API Key 一次性迁移为 AES-256-GCM 密文
    }

    private static SharedPreferences sp() {
        if (sp == null)
            throw new IllegalStateException("Prefs 未初始化，请先调用 Prefs.init()");
        return sp;
    }

    /** 应用级 Context（供文件存储等场景使用） */
    public static android.content.Context getContext() {
        if (appContext == null) throw new IllegalStateException("Prefs 未初始化，请先调用 Prefs.init()");
        return appContext;
    }

    public static boolean enabled()      { return sp().getBoolean("enabled", true); }
    public static boolean useApi()       { return sp().getBoolean("useApi", false); }
    public static boolean showFloat()    { return sp().getBoolean("showFloat", true); }
    public static boolean realtime()     { return sp().getBoolean("realtime", false); }
    /** 句尾口癖总开关（默认开；关闭后本地引擎不再追加句尾口癖） */
    public static boolean tailEnabled()  { return sp().getBoolean("tailEnabled", true); }
    /** API Key：优先读 AES-256-GCM 密文；兼容旧明文 */
    /** P1-15 修复：apiKeyEnc 解密失败标记（避免瞬时故障重复尝试+永久删Key），setApiKey 成功时复位 */
    private static volatile boolean keyDecryptFailed = false;

    public static String apiKey() {
        String enc = sp().getString("apiKeyEnc", null);
        if (enc != null) {
            if (keyDecryptFailed) return "";   // P1-15 修复：已标记解密失败，不再重复尝试（避免瞬时故障永久删Key）
            String dec = SecureStore.decrypt(enc);
            if (!dec.isEmpty()) return dec;
            // P1-15 修复：解密失败不立即删除密文（Keystore 瞬时不可用时删除=永久丢Key），
            // 改为标记失败+返回空串，用户重新配置时 setApiKey 会覆盖旧密文
            keyDecryptFailed = true;
            AppLog.w("Prefs", "apiKeyEnc 解密失败（可能是Keystore瞬时不可用或数据损坏），暂不删除密文，请重新配置API Key");
            return "";
        }
        String plain = sp().getString("apiKey", "");
        return plain == null ? "" : plain;
    }

    /** API Key 一律加密落盘，并清除旧明文；Keystore 极端不可用时退回明文保证可用 */
    public static void setApiKey(String v) {
        SharedPreferences.Editor e = sp().edit().remove("apiKey");
        if (v != null && !v.isEmpty()) {
            String enc = SecureStore.encrypt(v);
            if (enc != null) { e.putString("apiKeyEnc", enc); keyDecryptFailed = false; }
            else {
                // P1-15 修复：Keystore 极端不可用时退回明文，但必须记录日志（不能静默降级）
                e.putString("apiKey", v);
                keyDecryptFailed = false;
                AppLog.w("Prefs", "SecureStore.encrypt 失败，API Key 以明文临时存储（Keystore不可用）");
            }
        } else {
            e.remove("apiKeyEnc");
        }
        e.apply();
    }

    /** 首次升级：把历史明文 apiKey 迁移成密文，随后删除明文 */
    private static void migrateLegacyApiKey() {
        try {
            String plain = sp.getString("apiKey", null);
            if (plain != null && !plain.isEmpty() && !sp.contains("apiKeyEnc")) {
                String enc = SecureStore.encrypt(plain);
                if (enc != null)
                    sp.edit().putString("apiKeyEnc", enc).remove("apiKey").apply();
            }
        } catch (Exception ignored) {}
    }
    public static String  customPrompt() { return sp().getString("customPrompt", ""); }
    public static int     floatX()       { return sp().getInt("floatX", 100); }
    public static int     floatY()       { return sp().getInt("floatY", 400); }

    // ---- AI 模型版本 ----
    /** AI 模型预设（显示名, 模型 ID）。注意：旧的 deepseek-chat / deepseek-reasoner 已随 V4 发布停用。 */
    public static final String[][] PRESET_MODELS = {
        {"DeepSeek-V4-Flash（高速低成本）", "deepseek-v4-flash"},
        {"DeepSeek-V4-Pro（高性能）", "deepseek-v4-pro"},
    };
    /** 当前使用的模型 ID；默认 deepseek-v4-flash */
    public static String apiModel() { return sp().getString("apiModel", "deepseek-v4-flash"); }
    /** v5.0 P0-4 长文本/文件翻译/彻底替换专用模型；留空回落 apiModel() */
    public static String apiModelLong() { return sp().getString("apiModelLong", ""); }
    /** 自定义模型 ID（用户手输，选择「自定义」时生效） */
    public static String apiModelCustom() { return sp().getString("apiModelCustom", ""); }

    // ---- 采样参数 ----
    /** temperature（0.0-2.0，默认 0.4）；SeekBar 存整数 0-200，这里返回实际浮点值 */
    public static double apiTemperature() { return tempTranslate(); }
    /** 本地引擎温度（默认 0.4；本地无温度参数，仅占位记忆） */
    public static double tempLocal()     { return sp().getInt("tempLocal", 40) / 100.0; }
    /** AI 翻译引擎温度（默认 0.4） */
    public static double tempTranslate() { return sp().getInt("tempTranslate", 40) / 100.0; }
    /** AI 彻底替换引擎温度（默认 0.8，再创作需要更高随机度） */
    public static double tempReplace()   { return sp().getInt("tempReplace", 80) / 100.0; }
    /** AI 二次校验：译文可疑时再调一次 AI 对比原文，判断是否跑偏成对话（默认开启） */
    public static boolean aiVerify()      { return sp().getBoolean("aiVerify", true); }
    /** 审核拦截预览：debug 开发包强制开启内容审核（装机自测拦截效果用） */
    public static boolean forceGuard()    { return sp().getBoolean("forceGuard", false); }
    public static boolean localPreStyle() { return sp().getBoolean("localPreStyle", false); }   // v4.7: 本地前置打底（原文+打底对照输入），默认开
    /** AI 裁判严格度：1=最宽松(几乎不拦截)，3=默认平衡，5=最严格(任何添加都可能判跑偏) */
    public static int judgeStrictness()   { int v = sp().getInt("judgeStrictness", 3); return Math.max(1, Math.min(5, v)); }

    // ---- 4.6 多选一裁判档位 ----
    /** 生成数量（候选数）：1-16 连续档（默认 6）。滑条直调。 */
    public static int candidateCount() {
        int v = sp().getInt("candidateCount", 6);
        return Math.max(1, Math.min(16, v));
    }
    /** 裁判数量：1-3 连续档（默认 1）。滑条直调，与生成数量独立。 */
    public static int judgeCount() {
        int v = sp().getInt("judgeCount", 1);
        return Math.max(1, Math.min(3, v));
    }
    /** 兼容旧一键档位入口（UI 已改独立滑条，此处保留兼容）：preset 2=标准(6×1) 3=高质量(9×3) 4=至尊(16×3，裁判封顶3) */
    public static void setJudgePreset(int preset) {
        switch (preset) {
            case 0: sp().edit().putInt("candidateCount", 1).putInt("judgeCount", 1).apply(); break;
            case 1: sp().edit().putInt("candidateCount", 3).putInt("judgeCount", 1).apply(); break;
            case 3: sp().edit().putInt("candidateCount", 9).putInt("judgeCount", 3).apply(); break;
            case 4: sp().edit().putInt("candidateCount", 16).putInt("judgeCount", 3).apply(); break;
            default: sp().edit().putInt("candidateCount", 6).putInt("judgeCount", 1).apply();
        }
    }

    // ---- 多厂商 API ----
    /** 厂商预设（显示名, base_url, 默认模型）。最后一个「自定义」需手填。 */
    public static final String[][] PROVIDERS = {
        {"DeepSeek", "https://api.deepseek.com", "deepseek-v4-flash"},
        {"通义千问 Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"},
        {"智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"},
        {"Kimi 月之暗面", "https://api.moonshot.cn/v1", "kimi-k2.6"},   // 校准：moonshot-v1 系列 2026-08-31 已下线(404)，kimi-k2 系 2026-05-25 下线，官方现推 kimi-k2.6
        {"字节豆包", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-evolving"},   // 官方永续模型，不过期
        {"腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbos-latest"},  // 新增：混元 OpenAI 兼容接口，旗舰版
        {"SiliconFlow", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"},
        {"OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat"},
        {"OpenAI", "https://api.openai.com/v1", "gpt-5.4-mini"},        // 校准：gpt-4o-mini 已被 5.x 取代；5.6 系仍在 preview 期，取全量可用的 5.4-mini
        {"自定义", "", ""},
    };
    /** 当前厂商索引；默认 0=DeepSeek */
    public static int apiProvider() { return sp().getInt("apiProvider", 0); }
    /** API 接口地址（base_url）；默认 DeepSeek */
    public static String apiBaseUrl() { return sp().getString("apiBaseUrl", "https://api.deepseek.com"); }

    // ---- P2-1 翻译缓存有效期（毫秒）；<=0 表示永不过期，默认 24h ----
    public static long cacheTtlMs() {
        return sp().getLong("cacheTtlMs", TranslationCache.DEFAULT_TTL_MS);
    }
    public static void setCacheTtlMs(long ms) {
        sp().edit().putLong("cacheTtlMs", ms).apply();
    }

    /** 日志级别持久化（DEBUG=1, INFO=2, WARN=3, ERROR=4），默认 INFO */
    public static int logLevel() {
        return sp().getInt("logLevel", 2);
    }
    public static void setLogLevel(int lv) {
        sp().edit().putInt("logLevel", lv).apply();
    }

    // ---- P2-2 多引擎 fallback：备用引擎有序列表（Key 同样经 SecureStore 加密落盘）----
    /** 备用引擎列表，顺序即优先级；每项含 名称/根地址/模型/加密Key */
    public static List<Engine> backupEngines() {
        List<Engine> list = new ArrayList<>();
        String json = sp().getString("backupEnginesJson", "");
        if (json == null || json.trim().isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String key = SecureStore.decrypt(o.optString("kEnc", ""));
                list.add(new Engine(o.optString("n", "备用" + (i + 1)),
                        o.optString("b", ""), key, o.optString("m", "")));
            }
        } catch (Exception e) {
            AppLog.w("Prefs", "备用引擎配置解析失败，按无备用处理：" + e);
        }
        return list;
    }

    /** 覆盖写入备用引擎列表（无 Key 的项自动跳过） */
    public static void setBackupEngines(List<Engine> engines) {
        JSONArray arr = new JSONArray();
        if (engines != null) {
            for (Engine e : engines) {
                if (e == null || !e.hasKey()) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("n", e.name).put("b", e.baseUrl).put("m", e.model);
                    String enc = SecureStore.encrypt(e.apiKey);
                    o.put("kEnc", enc == null ? "" : enc);
                    arr.put(o);
                } catch (Exception ignored) { }
            }
        }
        sp().edit().putString("backupEnginesJson", arr.toString()).apply();
    }

    /**
     * 组装本次请求的引擎优先级链：主引擎（当前根地址/模型 + 主 Key）在前，备用引擎按序在后。
     * @param primaryKey 调用方传入的主 Key（通常即解密后的 apiKey）
     */
    public static List<Engine> engineChain(String primaryKey) {
        List<Engine> chain = new ArrayList<>();
        String mainKey = (primaryKey != null && !primaryKey.trim().isEmpty()) ? primaryKey.trim() : apiKey();
        chain.add(new Engine("主引擎", apiBaseUrl(), mainKey, apiModel()));
        chain.addAll(backupEngines());
        return chain;
    }

    // ---- 风格强度（1-5，默认3）----
    public static int styleIntensity() { return sp().getInt("styleIntensity", 3); }

    // ---- 扩写等级（0=自动随风格默认；1=严格等长，2=轻扩，3=标准适度，4=充分，5=自由发挥）----
    public static int expandLevel() { return sp().getInt("expandLevel", 0); }

    // ---- 输出模式：false=翻译（逐句人设化改写，保持结构）；true=彻底替换（AI 以人设自由再创作，随机性高）----
    public static boolean replaceMode() { return sp().getBoolean("replaceMode", false); }
    /** 4.4: AI 翻译失败（输出异常）时是否自动转本地词库兜底；false=直接报错不兜底 */
    public static boolean aiFallbackLocal() { return sp().getBoolean("aiFallbackLocal", true); }
public static boolean triggerEnabled() { return sp().getBoolean("triggerEnabled", false); }   // 4.4: 触发词模式（默认关闭）
public static String triggerWord() { return sp().getString("triggerWord", "?翻"); }    // 4.4: 触发词（默认 ?翻）

    // ---- v3.3 开源能力相关配置 ----
    /** 预览模式：开启后结果先显示在悬浮预览条，点“采用”才写入输入框；默认关，保持单击直接替换的习惯 */
    public static boolean previewMode()    { return sp().getBoolean("previewMode", false); }
    /** 主翻译是否优先走 SSE 逐字流式（端点不支持时自动回退非流式），默认开 */
    public static boolean streamEnabled()  { return sp().getBoolean("streamEnabled", true); }
    /** 应用内更新检查的版本信息 URL，留空则不检查（返回 JSON：versionCode/versionName/url/notes） */
    public static String updateUrl()       { return sp().getString("updateUrl", ""); }

    // ---- 双引擎模式：0=本地词库，1=AI API（云端）----
    public static final int ENGINE_LOCAL_RULES = 0;
    public static final int ENGINE_CLOUD_API   = 1;

    /** 当前引擎模式；旧版只有 useApi 布尔，首次读取时一次性迁移（true→1，false→0）并写回 */
    public static int engineMode() {
        if (sp().contains("engineMode")) return sp().getInt("engineMode", ENGINE_LOCAL_RULES);
        int migrated = useApi() ? ENGINE_CLOUD_API : ENGINE_LOCAL_RULES;
        sp().edit().putInt("engineMode", migrated).apply();
        return migrated;
    }

    /** 切换引擎模式，并同步旧 useApi 语义（仅云端 API 时为 true，保证旧的裁判/流式/彻底替换判断继续成立） */
    public static void setEngineMode(int mode) {
        if (mode < ENGINE_LOCAL_RULES || mode > ENGINE_CLOUD_API) mode = ENGINE_LOCAL_RULES;
        sp().edit()
           .putInt("engineMode", mode)
           .putBoolean("useApi", mode == ENGINE_CLOUD_API)
           .apply();
    }


    // ---- 悬浮球自定义 ----
    /** 悬浮球大小（dp，默认 36） */
    public static int floatSize() { return sp().getInt("floatSize", 36); }
    /** 悬浮球透明度（0-100，默认 90） */
    public static int floatOpacity() { return sp().getInt("floatOpacity", 90); }
    /** 悬浮球图标（已废弃：悬浮球只显示当前人格名，保留空实现避免旧引用报错） */
    public static String floatIcon() { return sp().getString("floatIcon", ""); }

    // ---- 风格选择：人设 ----
    /** 人设风格索引（对应 StyleManager.personaNames；方言体系已移除） */
    public static int     styleIndex()   { return sp().getInt("styleIndex", 0); }

    /**
     * 3.x→4.x 风格体系重构迁移（一次性）：
     * 旧内置列表 = 5 级猫娘 + 中性傲娇 + 傲娇猫娘 + 正太…雌小鬼（15 项）+ 自定义区；
     * 新列表 = 中性傲娇 + 傲娇 + 正太…雌小鬼（10 项）+ 自定义区。
     * 旧 styleIndex 映射：0-5→0（中性傲娇），6→1（傲娇），7-14→-5，自定义区整体前移 5 位。
     */
    public static void migrateLegacyStyleIndex() {
        if (sp().getBoolean("styleIndexMigratedV3", false)) return;
        int old = sp().getInt("styleIndex", 0);
        int next;
        if (old < 0) next = 0;
        else if (old <= 5) next = 0;     // 猫娘1-5级 / 中性傲娇 → 中性傲娇
        else if (old == 6) next = 1;     // 傲娇猫娘 → 傲娇
        else if (old <= 14) next = old - 5;   // 正太(7→2)…雌小鬼(14→9)
        else next = old - 5;             // 自定义区整体前移 5 位
        int maxIdx = Math.max(StyleManager.personaCount() - 1, 0);
        if (next < 0 || next > maxIdx) next = Math.min(Math.max(next, 0), maxIdx);
        sp().edit().putInt("styleIndex", next).putBoolean("styleIndexMigratedV3", true).apply();
    }

    /**
     * 4.5：迁移按 App 风格（appStyleRules）与收藏风格（starredStyles）里的旧索引。
     * 旧列表 = 5 猫娘 + 中性傲娇 + 傲娇猫娘 + 正太…雌小鬼（15 项）+ 自定义区；
     * 新列表 = 中性傲娇 + 傲娇 + 正太…雌小鬼（10 项）+ 自定义区。
     * 映射同 migrateLegacyStyleIndex：0-5→0，6→1，7-14→-5，自定义区整体前移 5 位。
     * appStyleRules 的 value 为纯数字时迁移（"-1"=跟随、"off"=关闭 不动）；
     * starredStyles 的元素为 "pN" 时迁移 N。
     */
    public static void migrateAppStyleAndStarred() {
        if (sp().getBoolean("appStyleStarredMigratedV1", false)) return;
        int maxIdx = Math.max(StyleManager.personaCount() - 1, 0);
        // appStyleRules
        try {
            JSONObject obj = new JSONObject(sp().getString("appStyleRules", "{}"));
            java.util.Iterator<String> keys = obj.keys();
            boolean changed = false;
            while (keys.hasNext()) {
                String pkg = keys.next();
                String val = obj.optString(pkg, "");
                if (val.equals("-1") || val.equals("off") || val.isEmpty()) continue;
                int old;
                try { old = Integer.parseInt(val); }
                catch (NumberFormatException ignored) { continue; }
                int next = mapOldStyleIndex(old);
                if (next < 0 || next > maxIdx) next = Math.min(Math.max(next, 0), maxIdx);
                if (next != old) { obj.put(pkg, String.valueOf(next)); changed = true; }
            }
            if (changed) sp().edit().putString("appStyleRules", obj.toString()).apply();
        } catch (Exception ignored) {}
        // starredStyles（元素为 "pN"）
        try {
            JSONArray arr = new JSONArray(sp().getString("starredStyles", "[]"));
            JSONArray out = new JSONArray();
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                String key = arr.optString(i, "");
                if (key.startsWith("p")) {
                    int old;
                    try { old = Integer.parseInt(key.substring(1)); }
                    catch (NumberFormatException ignored) { out.put(key); continue; }
                    int next = mapOldStyleIndex(old);
                    if (next < 0 || next > maxIdx) next = Math.min(Math.max(next, 0), maxIdx);
                    if (next != old) { changed = true; }
                    out.put("p" + next);
                } else {
                    out.put(key);
                }
            }
            if (changed) sp().edit().putString("starredStyles", out.toString()).apply();
        } catch (Exception ignored) {}
        sp().edit().putBoolean("appStyleStarredMigratedV1", true).apply();
    }

    /** 旧 15 项体系索引 → 新 10 项体系索引的映射（与 migrateLegacyStyleIndex 一致） */
    private static int mapOldStyleIndex(int old) {
        if (old < 0) return 0;
        if (old <= 5) return 0;          // 猫娘1-5级 / 中性傲娇 → 中性傲娇
        if (old == 6) return 1;          // 傲娇猫娘 → 傲娇
        return old - 5;                   // 正太(7→2)…雌小鬼(14→9)，自定义区整体前移 5 位
    }

    public static void set(String k, boolean v) { sp().edit().putBoolean(k, v).apply(); }
    public static void set(String k, String v)  {
        if ("apiKey".equals(k)) { setApiKey(v); return; }   // 敏感字段走加密
        sp().edit().putString(k, v).apply();
    }
    public static void set(String k, int v)     { sp().edit().putInt(k, v).apply(); }

    // ---- 自定义本地替换规则（4.4.1：按风格独立存储，一条风格一个词库）----
    private static String rulesKey(String styleName) {
        return "customRules_" + (styleName == null || styleName.isEmpty() ? "default" : styleName);
    }

    private static List<String[]> parseCustomRules(String json) {
        List<String[]> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String from = o.optString("from", "");
                String to = o.optString("to", "");
                String regex = o.optString("regex", "0");
                list.add(new String[]{from, to, regex});
            }
        } catch (Exception ignored) {}
        return list;
    }

    /** 读取指定风格的规则列表，每项为 [原文, 替换文, 是否正则("1"/"0")]。旧版全局数据一次性迁移到当前风格。 */
    public static List<String[]> customRulesFor(String styleName) {
        String key = rulesKey(styleName);
        if (sp().contains(key)) return parseCustomRules(sp().getString(key, "[]"));
        // 迁移：老版本全局 customRules 一次性归入首次读取的风格，之后各风格独立
        if (sp().contains("customRules")) {
            String old = sp().getString("customRules", "[]");
            if (old != null && !old.isEmpty() && !"[]".equals(old)) {
                sp().edit().putString(key, old).remove("customRules").apply();
                return parseCustomRules(old);
            }
        }
        return new ArrayList<>();
    }

    /** 保存指定风格的规则列表（每项 [原文, 替换文, 正则标记]） */
    public static void setCustomRulesFor(String styleName, List<String[]> rules) {
        JSONArray arr = new JSONArray();
        try {
            for (String[] r : rules) {
                JSONObject o = new JSONObject().put("from", r[0]).put("to", r[1]);
                o.put("regex", r.length > 2 ? r[2] : "0");
                arr.put(o);
            }
        } catch (Exception ignored) {}
        sp().edit().putString(rulesKey(styleName), arr.toString()).apply();
    }

    /** 词库示例是否已首次播种（用户删光后不再自动出现，可用「载入示例」手动恢复） */
    public static boolean rulesExampleSeeded() { return sp().getBoolean("rulesExampleSeeded", false); }
    public static void setRulesExampleSeeded(boolean v) { sp().edit().putBoolean("rulesExampleSeeded", v).apply(); }

    /** P0-5-2 翻译功能用户须知：是否已确认（本地存储，可在设置撤回重置） */
    public static boolean noticeShown() { return sp().getBoolean("noticeShown", false); }
    public static void setNoticeShown(boolean v) { sp().edit().putBoolean("noticeShown", v).apply(); }
    /** 撤回：重置后，下次进入翻译功能重新弹出须知 */
    public static void resetNotices() { sp().edit().putBoolean("noticeShown", false).apply(); }

    // ---- P1-2-8 R18 确认页（文件翻译叠加层，本地存储可撤回）----
    public static boolean r18Confirmed() { return sp().getBoolean("r18Confirmed", false); }
    public static void setR18Confirmed(boolean v) { sp().edit().putBoolean("r18Confirmed", v).apply(); }
    public static void resetR18Confirm() { sp().edit().putBoolean("r18Confirmed", false).apply(); }

    // ---- AI 扩展词库（API 预设词扩展，可选开关）----
    /** 是否启用 AI 扩展词库（默认关，需用户手动扩展后开启） */
    public static boolean apiLexiconEnabled() { return sp().getBoolean("apiLexiconEnabled", false); }
    public static void setApiLexiconEnabled(boolean v) { sp().edit().putBoolean("apiLexiconEnabled", v).apply(); }

    /**
     * AI 扩展词库数据，每行格式：原词=变体1|变体2|变体3
     * 由 ApiLexiconExpander 调用 API 结合当前人设生成，存储后供 MiaoifyEngine 随机替换。
     */
    public static String apiLexiconData() { return sp().getString("apiLexiconData", ""); }
    public static void setApiLexiconData(String v) { sp().edit().putString("apiLexiconData", v == null ? "" : v).apply(); }


    /** AI 扩展词库按人设隐藏编号存取（v5.0：编号隔离同名，避免词库互相覆盖）。
     *  读：先按 id key；空则回退按旧版人设名 key 读取（兼容历史数据）。
     *  写：只写 id key。 */
    public static String getApiLexiconDataForId(String id) {
        String key = "apiLexicon_" + (id == null || id.isEmpty() ? "default" : id);
        String v = sp().getString(key, "");
        if (v == null || v.isEmpty()) {
            String name = StyleManager.nameOfId(id);
            if (name != null && !name.isEmpty()) v = sp().getString("apiLexicon_" + name, "");
        }
        return v == null ? "" : v;
    }
    public static void setApiLexiconDataForId(String id, String v) {
        sp().edit().putString("apiLexicon_" + (id == null || id.isEmpty() ? "default" : id), v == null ? "" : v).apply();
    }

    /** 自定义人设隐藏内部编号：读取第 idx 个自定义人设的持久化 id（"c"+自增），
     *  无则惰性分配并回写 JSON，保证同名/增删后词库仍按稳定编号隔离。 */
    public static String customPersonaId(int idx) {
        try {
            JSONArray arr = new JSONArray(sp().getString("customPersonas", "[]"));
            if (idx < 0 || idx >= arr.length()) return "c" + (idx + 1);
            JSONObject o = arr.optJSONObject(idx);
            if (o == null) return "c" + (idx + 1);
            String id = o.optString("id", "");
            if (id.isEmpty()) {
                int seq = sp().getInt("customPersonaSeq", 0) + 1;
                id = "c" + seq;
                o.put("id", id);
                sp().edit().putInt("customPersonaSeq", seq).putString("customPersonas", arr.toString()).apply();
            }
            return id;
        } catch (Exception e) {
            return "c" + (idx + 1);
        }
    }

    /** 扩展词库对应的人设名（用于判断切换人设后是否需要重新扩展） */
    public static String apiLexiconStyle() { return sp().getString("apiLexiconStyle", ""); }
    public static void setApiLexiconStyle(String v) { sp().edit().putString("apiLexiconStyle", v == null ? "" : v).apply(); }

    /** 按人设独立存储的扩展词库（切换人设自动切换词库） */
    public static String getApiLexiconDataForStyle(String styleName) {
        String key = "apiLexicon_" + (styleName == null ? "default" : styleName);
        return sp().getString(key, "");
    }
    public static void setApiLexiconDataForStyle(String styleName, String v) {
        String key = "apiLexicon_" + (styleName == null ? "default" : styleName);
        sp().edit().putString(key, v == null ? "" : v).apply();
    }

    // ---- 按 App 自动切换风格 ----
    /** 预设可配置的 App 列表（包名, 显示名） */
    public static final String[][] PRESET_APPS = {
        {"com.tencent.mm", "微信"},
        {"com.tencent.mobileqq", "QQ"},
        {"com.tencent.tim", "TIM"},
        {"com.alibaba.android.rimet", "钉钉"},
        {"com.ss.android.lark", "飞书"},
        {"com.tencent.wework", "企业微信"},
        {"com.ss.android.ugc.aweme", "抖音"},
        {"com.sina.weibo", "微博"},
        {"com.zhihu.android", "知乎"},
        {"com.tencent.mtt", "QQ浏览器"},
    };

    /**
     * 读取某 App 的风格配置。
     * 返回值："-1"=跟随当前，"N"=人设索引（见 StyleManager.personaNames），"off"=关闭风格化
     */
    public static String appStyle(String pkg) {
        try {
            JSONObject obj = new JSONObject(sp().getString("appStyleRules", "{}"));
            return obj.optString(pkg, "-1");
        } catch (Exception e) {
            return "-1";
        }
    }

    /** 设置某 App 的风格配置 */
    public static void setAppStyle(String pkg, String style) {
        try {
            JSONObject obj = new JSONObject(sp().getString("appStyleRules", "{}"));
            obj.put(pkg, style);
            sp().edit().putString("appStyleRules", obj.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** 读取所有 App 风格配置（JSON 字符串） */
    public static String appStyleRules() {
        return sp().getString("appStyleRules", "{}");
    }

    // ---- 自定义人设（AI 生成或手动添加）----
    /** 读取自定义人设列表：每个元素 [name, prompt] */
    public static java.util.List<String[]> customPersonas() {
        java.util.List<String[]> list = new java.util.ArrayList<>();
        try {
            String raw = sp().getString("customPersonas", "[]");
            if (raw == null || raw.trim().isEmpty()) return list;   // P0-4-1 空串直接返回空列表，不抛异常吞数据
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String n = o.optString("name", "").trim();
                String pr = o.optString("prompt", "");
                if (n.isEmpty()) continue;                          // P0-4-1 跳过无名项，避免空人设占位导致索引漂移
                list.add(new String[]{n, pr});
            }
        } catch (Exception e) {
            AppLog.w("Prefs", "customPersonas 解析异常：" + e);
        }
        return list;
    }

    /** 添加一个自定义人设 */
    public static void addCustomPersona(String name, String prompt) {
        addCustomPersona(name, prompt, null);
    }

    /** 添加一个自定义人设（含本地规则 JSON，模板市场应用模板时派生存储；localJson 为 null 表示无本地规则） */
    public static void addCustomPersona(String name, String prompt, String localJson) {
        try {
            JSONArray arr = new JSONArray(sp().getString("customPersonas", "[]"));
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("prompt", prompt);
            if (localJson != null && !localJson.trim().isEmpty()) o.put("local", localJson);
            int seq = sp().getInt("customPersonaSeq", 0) + 1;
            o.put("id", "c" + seq);
            sp().edit().putInt("customPersonaSeq", seq).apply();
            arr.put(o);
            sp().edit().putString("customPersonas", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** 读取指定下标自定义人设的本地规则 JSON（模板市场应用时派生存储）；越界/无则返回 null */
    public static String customPersonaLocalJson(int idx) {
        try {
            String raw = sp().getString("customPersonas", "[]");
            if (raw == null || raw.trim().isEmpty()) return null;
            JSONArray arr = new JSONArray(raw);
            if (idx < 0 || idx >= arr.length()) return null;
            JSONObject o = arr.optJSONObject(idx);
            if (o == null) return null;
            return o.optString("local", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 全量覆盖自定义人设列表（导入备份用），每项 [name, prompt] */
    public static void setCustomPersonas(java.util.List<String[]> personas) {
        try {
            JSONArray arr = new JSONArray();
            for (String[] p : personas) {
                if (p == null || p.length < 2) continue;
                JSONObject o = new JSONObject().put("name", p[0]).put("prompt", p[1]);
                if (p.length >= 3 && p[2] != null && !p[2].isEmpty()) {
                    o.put("id", p[2]);
                } else {
                    int seq = sp().getInt("customPersonaSeq", 0) + 1;
                    o.put("id", "c" + seq);
                    sp().edit().putInt("customPersonaSeq", seq).apply();
                }
                arr.put(o);
            }
            sp().edit().putString("customPersonas", arr.toString()).apply();
            // 列表长度变化后收敛 styleIndex，防止越界导致选中漂移/失效
            int maxIdx = Math.max(StyleManager.personaCount() - 1, 0);
            int cur = sp().getInt("styleIndex", 0);
            if (cur < 0 || cur > maxIdx) {
                sp().edit().putInt("styleIndex", Math.min(Math.max(cur, 0), maxIdx)).apply();
            }
        } catch (Exception ignored) {}
    }

    /** 删除指定位置的自定义人设：移入回收站（保留 24 小时，期间可恢复），并从人设列表移除 */
    public static void removeCustomPersona(int index) {
        try {
            JSONArray arr = new JSONArray(sp().getString("customPersonas", "[]"));
            if (index >= 0 && index < arr.length()) {
                JSONObject removed = arr.optJSONObject(index);
                if (removed != null) {
                    // 移入回收站：记录被删人设 + 删除时间
                    JSONArray trash = new JSONArray(sp().getString("personaTrash", "[]"));
                    JSONObject t = new JSONObject();
                    t.put("name", removed.optString("name", ""));
                    t.put("prompt", removed.optString("prompt", ""));
                    t.put("id", removed.optString("id", ""));
                    t.put("ts", System.currentTimeMillis());
                    trash.put(t);
                    while (trash.length() > 100) trash.remove(0);   // 回收站限容防膨胀
                    sp().edit().putString("personaTrash", trash.toString()).apply();
                }
                // 删除前记录该自定义人设在全量人设列表（内置+自定义+自定义入口）中的索引
                int builtIn = StyleManager.personaBuiltinCount();
                int fullIdx = builtIn + index;
                arr.remove(index);
                sp().edit().putString("customPersonas", arr.toString()).apply();
                // 修正 styleIndex：删除导致后面的人设整体前移一位，当前选中索引需同步调整，防止越界/漂移
                int cur = sp().getInt("styleIndex", 0);
                if (cur == fullIdx) {
                    // 恰好删掉了当前选中的人设 → 重置为 0（内置第一个人设）
                    sp().edit().putInt("styleIndex", 0).apply();
                } else if (cur > fullIdx) {
                    // 删掉的是选中项之前的 → 选中项前移一位
                    sp().edit().putInt("styleIndex", cur - 1).apply();
                }
            }
        } catch (Exception ignored) {}
    }

    /** 回收站中的自定义人设（保留 24 小时）：返回 [name, prompt, ts]，过期项自动清理 */
    public static java.util.List<String[]> trashPersonas() {
        java.util.List<String[]> list = new java.util.ArrayList<>();
        try {
            long cutoff = System.currentTimeMillis() - 24L * 3600_000;
            JSONArray arr = new JSONArray(sp().getString("personaTrash", "[]"));
            JSONArray keep = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                long ts = o.optLong("ts", 0);
                if (ts >= cutoff) {
                    list.add(new String[]{o.optString("name", ""), o.optString("prompt", ""), String.valueOf(ts)});
                    keep.put(o);
                }
            }
            sp().edit().putString("personaTrash", keep.toString()).apply();
        } catch (Exception ignored) {}
        return list;
    }

    /** 恢复回收站中指定位置的自定义人设（24 小时内有效），成功返回 true */
    public static boolean restoreTrashPersona(int index) {
        try {
            long cutoff = System.currentTimeMillis() - 24L * 3600_000;
            JSONArray arr = new JSONArray(sp().getString("personaTrash", "[]"));
            if (index < 0 || index >= arr.length()) return false;
            JSONObject o = arr.optJSONObject(index);
            if (o == null || o.optLong("ts", 0) < cutoff) return false;
            JSONArray cur = new JSONArray(sp().getString("customPersonas", "[]"));
            JSONObject back = new JSONObject();
            back.put("name", o.optString("name", ""));
            back.put("prompt", o.optString("prompt", ""));
            if (!o.optString("id", "").isEmpty()) back.put("id", o.optString("id", ""));
            cur.put(back);
            arr.remove(index);
            sp().edit()
                    .putString("customPersonas", cur.toString())
                    .putString("personaTrash", arr.toString())
                    .apply();
            return true;
        } catch (Exception ignored) { return false; }
    }

    // ---- 翻译历史 ----
    public static String historyJson() {
        return sp().getString("translateHistory", "[]");
    }
    public static void setHistoryJson(String json) {
        sp().edit().putString("translateHistory", json).apply();
    }
public static void clearHistory() {
        sp().edit().remove("translateHistory").apply();
    }

    // ---- 常用风格收藏/置顶（key：pN=人设N）----
    /** 读取收藏的风格 key 列表（按收藏顺序） */
    public static java.util.List<String> starredStyles() {
        java.util.List<String> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp().getString("starredStyles", "[]"));
            for (int i = 0; i < arr.length(); i++) list.add(arr.optString(i));
        } catch (Exception ignored) {}
        return list;
    }
    public static boolean isStarred(String key) { return starredStyles().contains(key); }
    /** 收藏/取消收藏，返回切换后的状态 */
    public static boolean toggleStar(String key) {
        java.util.List<String> list = starredStyles();
        boolean now;
        if (list.contains(key)) { list.remove(key); now = false; }
        else { list.add(key); now = true; }
        JSONArray arr = new JSONArray();
        for (String k : list) arr.put(k);
        sp().edit().putString("starredStyles", arr.toString()).apply();
        return now;
    }
    public static void setStarredStyles(java.util.List<String> keys) {
        JSONArray arr = new JSONArray();
        for (String k : keys) arr.put(k);
        sp().edit().putString("starredStyles", arr.toString()).apply();
    }


}
