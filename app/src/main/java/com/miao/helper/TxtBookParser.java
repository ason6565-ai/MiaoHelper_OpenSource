package com.miao.helper;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * TXT 解析（P1-2-1）。纯 JDK：BOM/编码探测（UTF-8 优先，失败回退 GBK）+ 章节切分 + 段落清洗。
 * 不碰 Android 文件/Uri，输入为已读到的字节或文本，便于 JVM 单测；Android 接入层只负责把文件读成 byte[]。
 */
public final class TxtBookParser {
    private TxtBookParser() {}

    /** 独占一行的章节标题（中文章/节/回/卷 + Chapter N + 序跋类），并限制长度避免误切正文 */
    private static final Pattern HEADING = Pattern.compile(
            "^\\s*(第\\s*[0-9零一二三四五六七八九十百千万两]+\\s*[章节回卷部集篇][^\\n]{0,30}"
            + "|卷\\s*[0-9零一二三四五六七八九十百千万两]+[^\\n]{0,30}"
            + "|Chapter\\s+\\d+[^\\n]{0,40}"
            + "|序\\s*章|序\\s*言|前\\s*言|楔\\s*子|尾\\s*声|后\\s*记|番\\s*外[^\\n]{0,20})\\s*$");

    /** 纯文本（已解码）→ BookDocument，默认书名 */
    public static BookDocument parse(String text) {
        return parse("文本文档", text);
    }

    /** 纯文本（已解码）→ BookDocument */
    public static BookDocument parse(String title, String text) {
        List<BookDocument.Chapter> chapters = new ArrayList<>();
        if (text == null) return new BookDocument(title, chapters);

        String curTitle = "";
        List<String> curParas = new ArrayList<>();
        String[] lines = text.split("\r\n|\r|\n", -1);
        for (String raw : lines) {
            String line = raw.trim();
            if (HEADING.matcher(line).matches()) {
                flush(chapters, curTitle, curParas);
                curTitle = line;
                curParas = new ArrayList<>();
            } else if (!line.isEmpty()) {
                curParas.add(line);
            }
        }
        flush(chapters, curTitle, curParas);

        // 一个章节标题都没识别到：整书为一章
        if (chapters.isEmpty()) chapters.add(new BookDocument.Chapter("", toParas(text)));
        return new BookDocument(title, chapters);
    }

    /** 字节流 → BookDocument，自动探测编码 */
    public static BookDocument parse(byte[] bytes) {
        return parse("文本文档", bytes, null);
    }

    /** 字节流 → BookDocument；declaredCharset 传 null/"auto"/"" 表示自动探测 */
    public static BookDocument parse(String title, byte[] bytes, String declaredCharset) {
        return parse(title, decode(bytes, declaredCharset));
    }

    /** 解码：BOM 优先；指定编码优先；否则严格 UTF-8，失败回退 GBK */
    public static String decode(byte[] bytes, String declared) {
        if (bytes == null) return "";
        // UTF-8 BOM
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        // UTF-16 BOM（交给 JDK 自动识别端序）
        if (bytes.length >= 2 && ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF
                || (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE)) {
            return new String(bytes, Charset.forName("UTF-16"));
        }
        if (declared != null && !declared.trim().isEmpty()
                && !declared.equalsIgnoreCase("auto")) {
            try { return new String(bytes, Charset.forName(declared.trim())); }
            catch (Exception ignore) {}
        }
        if (strictlyDecodable(bytes, StandardCharsets.UTF_8)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            return new String(bytes, Charset.forName("GBK")); // 中文老 TXT 兜底
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** 严格解码：出现非法/不可映射序列即判 false */
    static boolean strictlyDecodable(byte[] bytes, Charset cs) {
        try {
            cs.newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void flush(List<BookDocument.Chapter> out, String title, List<String> paras) {
        boolean hasContent = false;
        for (String p : paras) if (p != null && !p.trim().isEmpty()) { hasContent = true; break; }
        // 标题前若没有任何正文，则不产生空的"开头"章
        if (hasContent || (!title.isEmpty() && !paras.isEmpty())) {
            out.add(new BookDocument.Chapter(title, paras));
        }
    }

    private static List<String> toParas(String text) {
        List<String> ps = new ArrayList<>();
        for (String raw : text.split("\r\n|\r|\n")) {
            String l = raw.trim();
            if (!l.isEmpty()) ps.add(l);
        }
        return ps;
    }
}
