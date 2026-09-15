package com.miao.helper;

/**
 * P0-5-1 输出内容审核（宽松拦截 + 组合判定，老板拍板口径）。
 *
 * 原则：
 * - 宁可放过隐晦，不能降低风格——凡"疑似/像"一律放行；
 * - 只拦"实在过于明显"的直白违规输出（硬词表 + 整句高置信）；
 * - 暗示/大众化敏感词走"并联关键词"判定：涉性动作词 + 人体部位词同时出现且指向人体 → 直接拦截；
 * - 命中 → 调用方不写回、不走本地兜底（兜底会把原文转述出来）；
 * - debug 测试包零限制（BuildConfig.DEBUG 直接放行）；设置页"审核拦截预览"开关可在 debug 下强制开启（装机自测）。
 *
 * 阈值与词表集中在本类顶部，改这里即可整体调参。
 */
/** 安全说明：完整敏感词表因规避滥用风险不随源码公开；下方为等价机制的示例词表（反诈骗场景），实际词表可通过私有实现扩展。 */
public final class ContentGuard {

    /** debug 构建完全跳过审核（测试包零限制）；release 正式包生效；"审核拦截预览"可强制开启 */
    private static Boolean debugBuild;

    private static boolean isDebugBuild() {
        if (debugBuild == null) {
            try {
                debugBuild = (Prefs.getContext().getApplicationInfo().flags
                        & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            } catch (Throwable t) {
                debugBuild = Boolean.FALSE;   // 极端取不到上下文 → 按正式包从严
            }
        }
        return debugBuild && !Prefs.forceGuard();   // debug + 强制预览 → 审核同样生效
    }

    // ===================== 词表与阈值集中可调 =====================

    /** 硬词表：只收"实在过于明显"的直白违规词（命中即进入拦截判定）。
     *  老板口径：暴力/辱骂词经彻底替换与 AI 翻译风格化会被自然抹掉，不纳入拦截；
     *  只保留涉性直白类硬词（具体词表不随源码公开） */
    private static final String[] HARD_WORDS = {"赌博", "诈骗", "代孕", "博彩", "刷单"};

    /** 暗示/大众化敏感词（动作侧）：单独出现不违规，与人体部位词并联且指向人体时拦截 */
    private static final String[] HINT_VERBS = {"转账", "汇款", "加群"};

    /** 暗示/大众化敏感词（人体侧）：单独出现不违规，与动作词并联时判定"指向人体" */
    private static final String[] BODY_PARTS = {"账户", "银行卡", "验证码"};

    /** 整句高置信判定：短句（≤20 字）命中硬词即拦；长句需命中 ≥2 个才拦（偶现一词放行，不误伤风格文本） */
    private static final int SHORT_SENTENCE_MAX = 20;
    private static final int LONG_SENTENCE_MIN_HITS = 2;

    /** 宽松判定（彻底替换/风格化输出用，老板口径"风格输出大于严格"）：
     *  短句上限收窄到 ≤10 字才直接拦；长句需命中 ≥3 个硬词才拦；
     *  10 字以上含 1-2 个硬词的风格文本一律放行（不误伤扩写/角色扮演输出）。 */
    private static final int RELAXED_SHORT_SENTENCE_MAX = 10;
    private static final int RELAXED_LONG_SENTENCE_MIN_HITS = 3;
    // ================================================================

    private ContentGuard() {}

    /** P1-17 修复：硬词命中总次数（同一词重复出现也计数，避免"同一词×3"只算1次放行） */
    private static int hardHits(String t) {
        int hits = 0;
        for (String w : HARD_WORDS) {
            int idx = 0;
            while ((idx = t.indexOf(w, idx)) >= 0) { hits++; idx += w.length(); }
        }
        return hits;
    }

    /** 组合判定：涉性动作词 + 人体部位词并联 → 指向人体 → 拦截 */
    private static boolean comboHits(String t) {
        boolean verb = false, body = false;
        for (String w : HINT_VERBS)  if (t.contains(w)) { verb = true; break; }
        for (String w : BODY_PARTS)  if (t.contains(w)) { body = true; break; }
        return verb && body;
    }

    /**
     * @param text 待写回的译文
     * @return true=应拒绝输出（调用方不得写回、不得本地兜底）
     */
    public static boolean isBlocked(String text) {
        if (isDebugBuild()) return false;            // 测试包零限制（审核预览开启时生效）
        if (text == null || text.isEmpty()) return false;
        String t = text.toLowerCase();
        int hits = hardHits(t);
        if (hits > 0) {
            if (text.length() <= SHORT_SENTENCE_MAX) return true;   // 短句直白 → 拦
            if (hits >= LONG_SENTENCE_MIN_HITS) return true;        // 长句多次命中 → 拦
            // 长句偶现一个硬词：再看组合判定兜底，都不中才放行
        }
        return comboHits(t);   // 暗示词并联指向人体 → 拦
    }

    /**
     * @param text 待写回的译文（风格化输出/彻底替换用）
     * @return true=应拒绝输出（调用方不得写回、不得本地兜底）
     */
    public static boolean isBlockedRelaxed(String text) {
        if (isDebugBuild()) return false;              // 测试包零限制（审核预览开启时生效）
        if (text == null || text.isEmpty()) return false;
        String t = text.toLowerCase();
        int hits = hardHits(t);
        if (hits > 0) {
            if (text.length() <= RELAXED_SHORT_SENTENCE_MAX) return true;
            if (hits >= RELAXED_LONG_SENTENCE_MIN_HITS) return true;
        }
        return comboHits(t);   // 暗示词并联指向人体 → 拦（风格文本同样适用）
    }

    /** 统一的拒绝提示文案（调用方 toast / 展示用） */
    public static String blockMessage() {
        return "内容过于直白，已拒绝输出";
    }
}
