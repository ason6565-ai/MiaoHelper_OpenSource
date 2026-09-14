package com.miao.helper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * EPUB 解析（P1-2-1）。纯 JDK（java.util.zip + 轻量正则），不引第三方库：
 * META-INF/container.xml → OPF（manifest/spine/metadata）→ 按 spine 顺序抽各 XHTML 正文为章节。
 * 输入为 EPUB 字节/流，不碰 Android；结构缺失时逐级容错，尽量返回可用结果而非抛异常。
 */
public final class EpubBookParser {
    private EpubBookParser() {}

    private static final Pattern CONTAINER_OPF = Pattern.compile("(?is)full-path\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern DC_TITLE = Pattern.compile("(?is)<dc:title[^>]*>(.*?)</dc:title>");
    private static final Pattern ITEM = Pattern.compile("(?is)<item\\b[^>]*>");
    private static final Pattern ITEMREF = Pattern.compile("(?is)<itemref\\b[^>]*>");
    private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]+>");

    public static BookDocument parse(InputStream in) throws Exception {
        if (in == null) throw new IllegalArgumentException("epub stream is null");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return parse(bos.toByteArray());
    }

    public static BookDocument parse(byte[] epub) throws Exception {
        if (epub == null || epub.length == 0) throw new IllegalArgumentException("epub empty");

        // 1) 读全部条目（保留原名 + 小写索引，容错大小写）
        Map<String, byte[]> entries = readEntries(epub);
        TreeMap<String, String> lower = new TreeMap<>();
        for (String name : entries.keySet()) lower.put(norm(name), name);

        // 2) 定位 OPF
        String opfPath = locateOpf(entries, lower);
        String opf = opfPath == null ? "" : utf8(entries.get(lower.get(norm(opfPath))));

        // 3) 标题
        String title = "EPUB 文档";
        Matcher tm = DC_TITLE.matcher(opf);
        if (tm.find()) {
            String t = ANY_TAG.matcher(tm.group(1)).replaceAll("").trim();
            if (!t.isEmpty()) title = t;
        }

        // 4) manifest：id -> href；并记录文档型 item 的出现顺序作兜底
        Map<String, String> idToHref = new LinkedHashMap<>();
        List<String> docHrefOrder = new ArrayList<>();
        Matcher im = ITEM.matcher(opf);
        while (im.find()) {
            String tag = im.group();
            String id = attr(tag, "id");
            String href = attr(tag, "href");
            String media = attr(tag, "media-type");
            if (id != null && href != null) idToHref.put(id, href);
            if (media != null && (media.contains("xml") || media.contains("html")) && href != null) {
                docHrefOrder.add(href);
            }
        }

        // 5) spine 顺序
        List<String> order = new ArrayList<>();
        Matcher rm = ITEMREF.matcher(opf);
        while (rm.find()) {
            String idref = attr(rm.group(), "idref");
            if (idref != null && idToHref.containsKey(idref)) order.add(idToHref.get(idref));
        }
        if (order.isEmpty()) order.addAll(docHrefOrder); // spine 缺失：按文档出现顺序兜底

        // 6) 逐文档抽正文
        String opfDir = dirOf(opfPath == null ? "" : opfPath);
        List<BookDocument.Chapter> chapters = new ArrayList<>();
        int idx = 0;
        for (String href : order) {
            String path = resolvePath(opfDir, href);
            String real = lower.get(norm(path));
            if (real == null) continue;
            byte[] data = entries.get(real);
            if (data == null) continue;
            String xhtml = utf8(data);
            List<String> blocks = HtmlText.toBlocks(xhtml);
            if (blocks.isEmpty()) continue;
            String cTitle = HtmlText.extractTitle(xhtml);
            if (cTitle.isEmpty()) cTitle = "第" + (idx + 1) + "节";
            chapters.add(new BookDocument.Chapter(cTitle, blocks));
            idx++;
        }
        return new BookDocument(title, chapters);
    }

    // ---------------- 内部 ----------------

    private static Map<String, byte[]> readEntries(byte[] epub) throws Exception {
        Map<String, byte[]> map = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(epub))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) != -1) bos.write(buf, 0, n);
                map.put(e.getName(), bos.toByteArray());
            }
        }
        return map;
    }

    private static String locateOpf(Map<String, byte[]> entries, TreeMap<String, String> lower) {
        byte[] container = null;
        String ck = lower.get(norm("META-INF/container.xml"));
        if (ck != null) container = entries.get(ck);
        if (container != null) {
            Matcher m = CONTAINER_OPF.matcher(utf8(container));
            if (m.find()) return decodePct(m.group(1));
        }
        // 兜底：直接找 .opf
        for (String name : entries.keySet()) {
            if (name.toLowerCase().endsWith(".opf")) return name;
        }
        return null;
    }

    /** 从标签片段取属性值（兼容单/双引号） */
    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("(?is)\\b" + Pattern.quote(name) + "\\s*=\\s*[\"']([^\"']*)[\"']").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String resolvePath(String baseDir, String href) {
        String h = decodePct(href);
        h = h.replace('\\', '/');
        List<String> segs = new ArrayList<>();
        if (!h.startsWith("/")) {
            for (String s : baseDir.split("/")) if (!s.isEmpty()) segs.add(s);
        }
        for (String s : h.split("/")) {
            if (s.isEmpty() || ".".equals(s)) continue;
            if ("..".equals(s)) { if (!segs.isEmpty()) segs.remove(segs.size() - 1); }
            else segs.add(s);
        }
        return String.join("/", segs);
    }

    private static String dirOf(String path) {
        String p = path.replace('\\', '/');
        int i = p.lastIndexOf('/');
        return i < 0 ? "" : p.substring(0, i);
    }

    private static String norm(String p) {
        if (p == null) return "";
        return p.replace('\\', '/').replaceFirst("^/+", "").toLowerCase();
    }

    private static String decodePct(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static String utf8(byte[] data) {
        return data == null ? "" : new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }
}
