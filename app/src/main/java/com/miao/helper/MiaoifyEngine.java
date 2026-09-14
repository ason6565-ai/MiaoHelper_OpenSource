package com.miao.helper;

import com.miao.helper.jieba.JiebaSegmenter;
import com.miao.helper.jieba.SegToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * 本地词库引擎：纯本地规则替换，离线秒回。
 * 规则统一从 StyleManager.currentLocal() 读取（根据当前选中人设）。
 * 无本地规则的风格（如自定义）返回原文本。
 * 句尾口癖为「多候选随机池」：tail 支持用 | 分隔多个候选随机出现；
 * 受全局「句尾口癖」开关（Prefs.tailEnabled）控制，关闭时不追加任何尾缀。
 * 颜文字体系已移除。
 */
public class MiaoifyEngine {

    // 多词脏话（长词在前，替换为“风格脏话词+感叹号”）
    private static final String[][] DIRTY_PHRASES = {
        {"我他妈的"}, {"他妈的"}, {"你妈的"}, {"我操"}, {"我靠"}, {"我日"}, {"我草"},
        {"妈的"}, {"特么"}, {"卧槽"}, {"我去"}
    };

    // 独立成词的脏话（避免“日期”被误伤）
    private static final Pattern[] DIRTY = {
        Pattern.compile("(?<![\\u4e00-\\u9fa5])日(?![\\u4e00-\\u9fa5])"),
        Pattern.compile("(?<![\\u4e00-\\u9fa5])操(?![\\u4e00-\\u9fa5])"),
        Pattern.compile("(?<![\\u4e00-\\u9fa5])靠(?![\\u4e00-\\u9fa5])")
    };

    private static String pick(String[] arr, Random rnd) {
        return arr[rnd.nextInt(arr.length)];
    }

    /** 从「a|b|c」形式的尾缀规格里随机挑一个；不含 | 时原样返回；空规格返回 "" */
    private static String pickTail(String spec, Random rnd) {
        if (spec == null || spec.isEmpty()) return "";
        if (spec.indexOf('|') < 0) return spec;
        List<String> ok = new ArrayList<>();
        for (String p : spec.split("\\|")) if (p != null && !p.isEmpty()) ok.add(p);
        if (ok.isEmpty()) return "";
        return ok.get(rnd.nextInt(ok.size()));
    }

    /** 句子是否已以尾缀池里任一候选结尾（避免重复追加） */
    private static boolean endsWithAnyTail(String s, String spec) {
        if (spec == null || spec.isEmpty()) return false;
        for (String p : spec.split("\\|")) if (p != null && !p.isEmpty() && s.endsWith(p)) return true;
        return false;
    }

    public static String miaoify(String s) {
        if (s == null || s.trim().isEmpty()) return s;
        StyleManager.LocalRule cur = StyleManager.currentLocal();
        if (cur == null) return s;   // 自定义等无本地规则：原样返回
        return miaoify(s, cur, StyleManager.currentTailChance(), StyleManager.currentName());
    }

    /** 风格试验台：按指定风格 key（pN=人设N）做本地翻译，不改变全局选中风格 */
    public static String miaoify(String s, String styleKey) {
        if (s == null || s.trim().isEmpty()) return s;
        StyleManager.LocalRule cur = StyleManager.localOfKey(styleKey);
        if (cur == null) return s;
        return miaoify(s, cur, StyleManager.tailChanceOf(StyleManager.nameOfKey(styleKey)), StyleManager.nameOfKey(styleKey));
    }

    /**
     * 仅叠加用户自定义替换规则（字面量/正则），不跑内置人称、短语、口癖、脏话、AI 扩展词库。
     * 供「AI 彻底替换」候选生成/兜底使用：AI 已经生成了完整风格化文本，内置词库二次叠加会污染输出，
     * 只有用户亲手添加的规则值得补一刀。
     */
    public static String miaoifyCustomOnly(String s, String styleName) {
        if (s == null || s.trim().isEmpty()) return s;
        for (String[] cr : Prefs.customRulesFor(styleName == null || styleName.isEmpty() ? "default" : styleName)) {
            if (cr[0] == null || cr[0].isEmpty()) continue;
            String to = cr.length > 1 && cr[1] != null ? cr[1] : "";
            boolean isRegex = cr.length > 2 && "1".equals(cr[2]);
            try {
                if (isRegex) s = SafeRegex.replaceAll(s, cr[0], to);
                else s = s.replace(cr[0], to);
            } catch (Throwable t) {
                AppLog.w("Engine", "自定义规则执行异常，跳过该条：" + t);
            }
        }
        return s;
    }

    /**
     * v4.7 本地前置打底：只做「人称替换 + 用户自定义规则」，不跑口癖/短语/脏话/AI 扩展词库。
     * 产物作为 AI 再创作的【风格参考】输入；调用方必须把【原文】一起给 AI（对照输入），
     * 让原文做语义锚、打底做风格方向——即使本地打底走偏，AI 也能以原文纠偏，不会一路错到底。
     */
    public static String preStyleBase(String s, String styleKey) {
        if (s == null || s.trim().isEmpty()) return s;
        StyleManager.LocalRule r = (styleKey == null || styleKey.isEmpty())
                ? StyleManager.currentLocal() : StyleManager.localOfKey(styleKey);
        if (r == null) return s;
        String me = r.me, you = r.you;
        String seg = segmentRewrite(s, me, you, true, null);
        if (seg != null) {
            s = seg;
        } else {
            String[] PROTECTED = {"我国","我家","自我","忘我","你我","唯我","无我","迷你","予你","佑你"};
            String[] PH = new String[PROTECTED.length];
            for (int i = 0; i < PROTECTED.length; i++) {
                PH[i] = "\uE000" + i + "\uE001";
                s = s.replace(PROTECTED[i], PH[i]);
            }
            s = s.replace("咱们", me + "们");
            s = s.replace("我们", me + "们");
            s = s.replace("你们", you + "们");
            s = s.replace("我", me);
            s = s.replace("你", you);
            for (int i = 0; i < PROTECTED.length; i++) s = s.replace(PH[i], PROTECTED[i]);
        }
        String name = (styleKey == null || styleKey.isEmpty()) ? "default" : StyleManager.nameOfKey(styleKey);
        for (String[] cr : Prefs.customRulesFor(name)) {
            if (cr[0] == null || cr[0].isEmpty()) continue;
            String to = cr.length > 1 && cr[1] != null ? cr[1] : "";
            boolean isRegex = cr.length > 2 && "1".equals(cr[2]);
            try {
                if (isRegex) s = SafeRegex.replaceAll(s, cr[0], to);
                else s = s.replace(cr[0], to);
            } catch (Throwable t) {
                AppLog.w("Engine", "自定义规则执行异常，跳过该条：" + t);
            }
        }
        return s;
    }

    /**
     * v5.0 本地前置打底（AI 扩展版）：只读取当前/指定人设的 AI 扩展词库做打底，
     * 不跑任何内置预设（人称/短语/口癖/脏话/内置规则）。产物仅作 AI 再创作的风格参考，
     * 调用方必须同时把【原文】给 AI 做语义锚。
     */
    public static String preStyleAIOnly(String s, String styleKey) {
        if (s == null || s.trim().isEmpty()) return s;
        String sid = (styleKey == null || styleKey.isEmpty())
                ? StyleManager.currentId() : StyleManager.idOfKey(styleKey);
        java.util.Map<String, String[]> lex = ApiLexiconExpander.lexiconOfStyle(sid);
        if (lex == null || lex.isEmpty()) return s;
        return ApiLexiconExpander.randomReplace(s, lex, new java.util.Random());
    }

    /**
     * 基于 jieba 分词的按词替换：只替换“独立成词”的代词与单字脏词，
     * 从根上避免“我国→本国、日期→日期（示例），避免整串替换误伤、迷你→迷主人”等整串替换误伤。
     * 分词器未就绪或任何异常时返回 null，由调用方回退到整串替换，保证永不罢工。
     */
    private static String segmentRewrite(String s, String me, String you, boolean doPronouns, String dirty) {
        List<SegToken> toks;
        try {
            toks = JiebaSegmenter.tokenize(s);
        } catch (Throwable t) {
            return null;
        }
        if (toks == null || toks.isEmpty()) return null;
        StringBuilder out = new StringBuilder(s.length() + 8);
        int cursor = 0;
        for (SegToken tk : toks) {
            if (tk.startOffset > cursor) out.append(s, cursor, tk.startOffset);
            String w = tk.word;
            String repl = null;
            if (doPronouns) {
                if (w.equals("我们") || w.equals("咱们")) repl = me + "们";
                else if (w.equals("你们")) repl = you + "们";
                else if (w.equals("我")) repl = me;
                else if (w.equals("你")) repl = you;
            }
            if (repl == null && dirty != null && !dirty.isEmpty()
                    && (w.equals("日") || w.equals("操") || w.equals("靠"))) {
                repl = dirty;
            }
            if (repl != null) out.append(repl);
            else out.append(s, tk.startOffset, tk.endOffset); // 取原文，保留大小写/全角/标点
            cursor = tk.endOffset;
        }
        if (cursor < s.length()) out.append(s.substring(cursor));
        return out.toString();
    }

    private static String miaoify(String s, StyleManager.LocalRule r, int tailChance, String styleName) {
        if (s == null || s.trim().isEmpty()) return s;
        String me = r.me;
        String you = r.you;
        String tail = r.tail;
        String dirty = r.dirty;
        // 风格强度 1-5：1=只句尾，2=句尾+代词，3=全部（默认），4-5=全部+高频口癖
        int intensity = Prefs.styleIntensity();
        // 短语句（≤10字）强制短语替换+人称替换，保证短文本也有明显效果
        // 长句（>15字）克制：词表变体多为完整短句（如"喜欢"→"哦，我真是喜欢极了"），嵌入长句必破坏句子结构，只留代词+口癖
        boolean isShort = s.trim().length() <= 10;
        boolean isLong = s.trim().length() > 15;
        boolean doPronouns = intensity >= 2 || isShort;
        double phraseProb = isShort ? 1.0 : (isLong ? 0.0 : (intensity >= 3 ? 1.0 : (intensity == 2 ? 0.4 : 0.0)));
        Random rnd = new Random();

        // 1. 多词脏话：固定短语（带感叹号）+ v3.3 DFA 词库（覆盖插符号/拼音变体，约 70 词）
        if (dirty != null && !dirty.isEmpty()) {
            for (String[] d : DIRTY_PHRASES)
                s = s.replace(d[0], dirty + "！");
            s = BadWordFilter.replace(s, dirty);
        }
        // 2.单字脏词 + 3.人称：优先 jieba 按词替换（只动独立成词的 token，根治复合词误伤）；分词器未就绪则回退旧方案
        String segResult = segmentRewrite(s, me, you, doPronouns, dirty);
        if (segResult != null) {
            s = segResult;
        } else {
            if (dirty != null && !dirty.isEmpty()) {
                for (Pattern pp : DIRTY)
                    s = pp.matcher(s).replaceAll(dirty);
            }
            if (doPronouns) {
                // 回退方案：先保护含“我/你”的常见复合词（我国/迷你等），再整串替换
                String[] PROTECTED = {"我国","我家","自我","忘我","你我","唯我","无我","迷你","予你","佑你"};
                String[] PH = new String[PROTECTED.length];
                for (int i = 0; i < PROTECTED.length; i++) {
                    PH[i] = "" + i + "";
                    s = s.replace(PROTECTED[i], PH[i]);
                }
                s = s.replace("咱们", me + "们");
                s = s.replace("我们", me + "们");
                s = s.replace("你们", you + "们");
                s = s.replace("我", me);
                s = s.replace("你", you);
                for (int i = 0; i < PROTECTED.length; i++) {
                    s = s.replace(PH[i], PROTECTED[i]);
                }
            }
        }
        // 3.2 风格特有短语替换（该风格定义的常用语替换）
        if (r.phrases != null) {
            for (String[] p : r.phrases) {
                if (p[0] != null && !p[0].isEmpty()) {
                    if (phraseProb >= 1.0 || rnd.nextDouble() < phraseProb) {
                        s = s.replace(p[0], p[1] == null ? "" : p[1]);
                    }
                }
            }
        }
        // 3.3 AI 扩展词库（可选开关：按人设独立存储，全量分批扩展，增量合并，毫秒级随机替换）
        if (Prefs.apiLexiconEnabled()) {
            java.util.Map<String, String[]> lex = ApiLexiconExpander.getCurrentLexicon();
            if (lex != null && !lex.isEmpty()) {
                s = ApiLexiconExpander.randomReplace(s, lex, rnd);
            }
        }
        // 3.5 自定义替换规则（用户在词库管理里添加的，优先级高于内置）
        // 正则模式走 SafeRegex：带 50ms 超时，防 ReDoS 卡死主线程
        // P1-16 修复：加 try/catch，单条规则异常不中断整个风格化流程
        for (String[] cr : Prefs.customRulesFor(styleName)) {
            if (cr[0] != null && !cr[0].isEmpty()) {
                String to = cr.length > 1 && cr[1] != null ? cr[1] : "";
                boolean isRegex = cr.length > 2 && "1".equals(cr[2]);
                try {
                    if (isRegex) {
                        s = SafeRegex.replaceAll(s, cr[0], to);
                    } else {
                        s = s.replace(cr[0], to);
                    }
                } catch (Throwable t) {
                    AppLog.w("Engine", "自定义规则执行异常，跳过该条：" + t);
                }
            }
        }
        s = s.trim();
        // 4. 句尾口癖（多候选随机池；受全局开关控制，关闭时不追加任何尾缀）
        if (Prefs.tailEnabled() && !s.isEmpty()) {
            char last = s.charAt(s.length() - 1);
            String punc = "。！？、，；：….!,?;:)]）】》~～";
            String add = pickTail(tail, rnd);
            if (punc.indexOf(last) < 0 && !add.isEmpty() && !endsWithAnyTail(s, tail)
                    && (tailChance >= 100 || rnd.nextInt(100) < tailChance)) {
                s += add;
            }
        }
        return s;
    }
}
