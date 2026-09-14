package com.miao.helper;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** P1-2-1 文件解析核心单测：TXT 章节/编码、HtmlText、EPUB（内存构造），纯 JVM。 */
public class BookParserTest {

    // ---------------- TXT ----------------

    @Test
    public void testTxtNoHeadingIsOneChapter() {
        BookDocument doc = TxtBookParser.parse("只是几段普通正文。\n第二段，没有任何章节标题。");
        assertEquals(1, doc.chapterCount());
        assertEquals(2, doc.chapters().get(0).paragraphCount());
    }

    @Test
    public void testTxtSplitByHeading() {
        String text = "序前引导段。\n" +
                "第一章 开端\n开端正文一。\n开端正文二。\n" +
                "第二章 发展\n发展正文。";
        BookDocument doc = TxtBookParser.parse("样例", text);
        assertEquals(3, doc.chapterCount());           // 开头 + 两章
        assertEquals("", doc.chapters().get(0).title());
        assertEquals("第一章 开端", doc.chapters().get(1).title());
        assertEquals(2, doc.chapters().get(1).paragraphCount());
        assertEquals("第二章 发展", doc.chapters().get(2).title());
        assertEquals(1, doc.chapters().get(2).paragraphCount());
        assertEquals(4, doc.totalParagraphs());
    }

    @Test
    public void testTxtUtf8Bom() {
        String cn = "中文内容，BOM 前缀应被正确去除。";
        byte[] body = cn.getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[3 + body.length];
        bom[0] = (byte) 0xEF; bom[1] = (byte) 0xBB; bom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bom, 3, body.length);
        assertEquals(cn, TxtBookParser.decode(bom, null));
    }

    @Test
    public void testTxtGbkFallback() throws Exception {
        String cn = "这是一段传统GBK编码的中文文本用于验证回退解码逻辑稳定可靠";
        byte[] gbk = cn.getBytes("GBK");
        assertEquals(cn, TxtBookParser.decode(gbk, null));
    }

    // ---------------- HtmlText ----------------

    @Test
    public void testHtmlToBlocksAndEntities() {
        String html = "<html><body><p>第一段&amp;内容</p>" +
                "<p>第二&nbsp;段 &lt;标记&gt;</p><h2>标题块</h2></body></html>";
        java.util.List<String> blocks = HtmlText.toBlocks(html);
        assertTrue(blocks.contains("第一段&内容"));
        assertTrue(blocks.contains("第二 段 <标记>"));
        assertTrue(blocks.contains("标题块"));
        assertEquals("标题", HtmlText.extractTitle("<title>标题</title>"));
    }

    // ---------------- EPUB（内存构造最小合法 EPUB） ----------------

    @Test
    public void testEpubSpineOrderAndContent() throws Exception {
        byte[] epub = buildMiniEpub();
        BookDocument doc = EpubBookParser.parse(epub);

        assertEquals("测试之书", doc.title());
        assertEquals(2, doc.chapterCount());

        BookDocument.Chapter c1 = doc.chapters().get(0);
        assertEquals("第一章 启程", c1.title());
        assertTrue(c1.paragraphs().contains("第一段内容。"));
        assertTrue("nbsp 应解码为空格", c1.paragraphs().contains("第二段 内容。"));
        assertTrue(c1.paragraphs().contains("小节标题"));

        BookDocument.Chapter c2 = doc.chapters().get(1);
        assertEquals(2, c2.paragraphCount());
        assertEquals("第二章正文A", c2.paragraphs().get(0));
    }

    /** 构造一个含 container/opf/两章 xhtml 的最小 EPUB（相对路径 OEBPS/Text/） */
    private static byte[] buildMiniEpub() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            put(zos, "META-INF/container.xml",
                "<?xml version=\"1.0\"?><container><rootfiles><rootfile " +
                "full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>" +
                "</rootfiles></container>");
            put(zos, "OEBPS/content.opf",
                "<package xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                "<metadata><dc:title>测试之书</dc:title></metadata>" +
                "<manifest>" +
                "<item id=\"c1\" href=\"Text/c1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"c2\" href=\"Text/c2.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "</manifest>" +
                "<spine><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine></package>");
            put(zos, "OEBPS/Text/c1.xhtml",
                "<html><head><title>第一章 启程</title></head><body>" +
                "<p>第一段内容。</p><p>第二段&nbsp;内容。</p>" +
                "<h2>小节标题</h2><p>第三段。</p></body></html>");
            put(zos, "OEBPS/Text/c2.xhtml",
                "<html><body><p>第二章正文A</p><p>第二章正文B</p></body></html>");
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
