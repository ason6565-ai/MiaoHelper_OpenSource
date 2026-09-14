package com.miao.helper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * v4.7.1 跨段术语表（P1-2-5 术语一致性增强，长文分段翻译用）。
 *
 * 背景：长文（txt/epub）分段翻译时，每段独立请求，模型在后续段落重新遇到
 * 人名/App名等专有名词时自由发挥，导致同一原文出现多个译名（如「マイ」被译成
 * 麦/真昼/麻衣，「ボディマ」被译成 BodyMa/身体商城/身体麻/身体妈妈）。
 *
 * 机制：
 * 1. 每段 system prompt 注入【已知术语表】，强制模型沿用已确定译名；
 * 2. 模型在译文末尾输出术语登记行（仅新出现的专有名词）：
 *        【术语】原文=译名
 * 3. App 侧 absorb() 解析登记行：首次出现的原文→登记为规范译名；
 *    同一原文再出现不同译名→记为漂移别名；
 * 4. normalize() 把漂移别名替换回规范译名，并剥离登记行，得到纯译文。
 *
 * 纯 JDK、同步安全、无外部依赖。
 */
public class TermGlossary {

    /** 原文术语 → 规范译名（按首次出现顺序，LinkedHashMap 保序） */
    private final LinkedHashMap<String, String> terms = new LinkedHashMap<>();
    /** 漂移译名（别名）→ 规范译名，用于输出归一 */
    private final LinkedHashMap<String, String> alias = new LinkedHashMap<>();

    private static final Pattern TERM_LINE =
            Pattern.compile("^\\s*【术语】\\s*(.+?)\\s*[=＝:：]\\s*(.+?)\\s*$");
    /** 登记行前缀匹配（剥离用） */
    private static final String TERM_PREFIX = "【术语】";

    /** v5.0 静态归一表：通用错误/漂移写法 → 规范译名（确定性兜底，先于别名表执行；不含任何特定作品的专名） */
    private static final String[][] STATIC_NORM = {
            {"充气娃娃", "性爱娃娃"},
            {"屄", "阴道"},
            {"白浊", "精液"},
            {"红桃色", "桃红色"}, {"赤桃色", "桃红色"},
            {"手镜", "手持镜"},
            {"达摩状态", "不倒翁状态"},
            {"写真偶像", "写真模特"},
            {"立食派对", "站立式自助派对"},
    };

    /**
     * 从一段译文里解析术语登记行并更新术语表。
     * 兼容多行登记；同一原文出现不同译名时，以首次译名为规范，后续译名记入别名。
     */
    public synchronized void absorb(String translation) {
        if (translation == null || translation.isEmpty()) return;
        for (String line : translation.split("\n")) {
            if (!line.contains(TERM_PREFIX)) continue;
            java.util.regex.Matcher m = TERM_LINE.matcher(line.trim());
            if (!m.matches()) continue;
            String src = m.group(1).trim();
            String dst = m.group(2).trim();
            if (src.isEmpty() || dst.isEmpty()) continue;
            String canonical = terms.get(src);
            if (canonical == null) {
                terms.put(src, dst);
            } else if (!canonical.equals(dst)) {
                alias.put(dst, canonical);
            }
        }
    }

    /** 生成传给下一段的【已知术语表】上下文；空表返回空串 */
    public synchronized String context() {
        if (terms.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : terms.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 归一 + 剥离：把漂移别名替换回规范译名，并删除【术语】登记行。
     * 返回可直接写回结果的纯译文；入参为 null 时返回 null。
     */
    public synchronized String normalize(String translation) {
        if (translation == null) return null;
        String out = translation;
        for (String[] pair : STATIC_NORM) {
            out = out.replace(pair[0], pair[1]);
        }
        for (Map.Entry<String, String> e : alias.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        // v5.0 P0-2 一致性本地校验（确定性、零 API 成本）：
        // 1) 日式引号统一为中文双引号；2) 术语表原文若被模型漏译直搬（日文假名残留），替换回规范译名
        out = out.replaceAll("「([^」]*)」", "“$1”").replaceAll("『([^』]*)』", "“$1”");
        for (Map.Entry<String, String> e : terms.entrySet()) {
            if (containsKana(e.getKey()) && out.contains(e.getKey())) {
                out = out.replace(e.getKey(), e.getValue());
            }
        }
        StringBuilder sb = new StringBuilder(out.length());
        String[] lines = out.split("\n", -1);
        for (String line : lines) {
            if (line.trim().startsWith(TERM_PREFIX)) continue;
            sb.append(line).append('\n');
        }
        return sb.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    /** v5.0 P0-2：判断字符串是否含日文假名（漏译直搬检测用，纯 JDK） */
    private static boolean containsKana(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '\u3040' && c <= '\u30FF') || (c >= '\u31F0' && c <= '\u31FF')) return true;
        }
        return false;
    }
}
