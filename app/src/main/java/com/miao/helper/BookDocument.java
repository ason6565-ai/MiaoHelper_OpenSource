package com.miao.helper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 书籍解析结果（P1-2-1）：标题 + 有序章节；每章含标题与已清洗的非空段落。
 * 纯 JDK、不可变，TXT/EPUB 解析器统一产出它，再交给分段翻译流程（P1-2-2）。
 */
public final class BookDocument {

    private final String title;
    private final List<Chapter> chapters;

    public BookDocument(String title, List<Chapter> chapters) {
        this.title = (title == null || title.trim().isEmpty()) ? "未命名" : title.trim();
        List<Chapter> copy = new ArrayList<>();
        if (chapters != null) for (Chapter c : chapters) if (c != null) copy.add(c);
        this.chapters = Collections.unmodifiableList(copy);
    }

    public String title() { return title; }
    public List<Chapter> chapters() { return chapters; }
    public int chapterCount() { return chapters.size(); }

    public int totalParagraphs() {
        int n = 0;
        for (Chapter c : chapters) n += c.paragraphCount();
        return n;
    }

    /** 全书纯文本（章标题换行 + 段落换行），供统计/兜底使用 */
    public String fullText() {
        StringBuilder sb = new StringBuilder();
        for (Chapter c : chapters) {
            if (c.title() != null && !c.title().isEmpty()) sb.append(c.title()).append('\n');
            for (String p : c.paragraphs()) sb.append(p).append('\n');
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** 单个章节：标题 + 有序非空段落（不可变） */
    public static final class Chapter {
        private final String title;
        private final List<String> paragraphs;

        public Chapter(String title, List<String> paragraphs) {
            this.title = (title == null) ? "" : title.trim();
            List<String> copy = new ArrayList<>();
            if (paragraphs != null) {
                for (String p : paragraphs) {
                    if (p != null && !p.trim().isEmpty()) copy.add(p.trim());
                }
            }
            this.paragraphs = Collections.unmodifiableList(copy);
        }

        public String title() { return title; }
        public List<String> paragraphs() { return paragraphs; }
        public int paragraphCount() { return paragraphs.size(); }

        public String joinText() {
            StringBuilder sb = new StringBuilder();
            for (String p : paragraphs) sb.append(p).append('\n');
            return sb.toString().trim();
        }
    }
}
