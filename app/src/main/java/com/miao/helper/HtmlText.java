package com.miao.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 XHTML/HTML 文本抽取（P1-2-1，EPUB 章节正文用）。纯 JDK、不引第三方库。
 * 块级标签处断行 → 删除所有标签 → 解码实体 → 按行清洗，得到有序非空段落。
 */
final class HtmlText {
    private HtmlText() {}

    /** 块级/换行标签：在这些位置断行（开标签与闭标签都算） */
    private static final Pattern BLOCK = Pattern.compile(
            "(?i)</?(?:p|div|h[1-6]|li|tr|br|blockquote|section|article|header|footer|hr)(?:\\s[^>]*)?/?>");
    private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t\\u00A0\\f]+");
    private static final Pattern TITLE_TAG = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern HEADING_TAG = Pattern.compile("(?is)<h([1-3])[^>]*>(.*?)</h\\1>");
    private static final Pattern NAMED_ENTITY = Pattern.compile("&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);");

    /** 把一段 XHTML/HTML 转成有序的非空文本段落 */
    static List<String> toBlocks(String html) {
        List<String> out = new ArrayList<>();
        if (html == null) return out;
        String s = BLOCK.matcher(html).replaceAll("\n"); // 块级处换行
        s = ANY_TAG.matcher(s).replaceAll("");           // 删剩余标签
        s = decodeEntities(s);
        for (String raw : s.split("\n")) {
            String line = MULTI_SPACE.matcher(raw).replaceAll(" ").trim();
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    /** 取文档标题：优先 <title>，否则第一个 h1-h3，都没有返回空串 */
    static String extractTitle(String html) {
        if (html == null) return "";
        Matcher m = TITLE_TAG.matcher(html);
        if (m.find()) {
            String t = decodeEntities(ANY_TAG.matcher(m.group(1)).replaceAll("")).trim();
            if (!t.isEmpty()) return t;
        }
        Matcher h = HEADING_TAG.matcher(html);
        if (h.find()) {
            return decodeEntities(ANY_TAG.matcher(h.group(2)).replaceAll("")).trim();
        }
        return "";
    }

    /** 解码常见命名实体与数字实体（&#NNN; / &#xHH;） */
    static String decodeEntities(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        Matcher m = NAMED_ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer(s.length());
        while (m.find()) {
            String e = m.group(1);
            String rep;
            if (e.charAt(0) == '#') {
                int code;
                try {
                    code = (e.charAt(1) == 'x' || e.charAt(1) == 'X')
                            ? Integer.parseInt(e.substring(2), 16)
                            : Integer.parseInt(e.substring(1));
                    rep = new String(Character.toChars(code));
                } catch (Exception ex) {
                    rep = m.group(0); // 非法数字实体，原样保留
                }
            } else {
                rep = named(e);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String named(String name) {
        switch (name) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return " ";
            case "mdash": return "—";
            case "ndash": return "–";
            case "hellip": return "…";
            case "lsquo": return "‘";
            case "rsquo": return "’";
            case "ldquo": return "“";
            case "rdquo": return "”";
            case "middot": return "·";
            default: return "&" + name + ";"; // 未知实体原样保留
        }
    }
}
