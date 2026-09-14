package com.miao.helper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多字脏话 / 辱骂词 DFA 过滤器（对标开源 sensitive-word 的 DFA 思路，零依赖自研）。
 *
 * 能力：全角/半角归一、大小写归一、忽略字间插入的空格与符号（“傻 逼”“s.b”）、
 *      拼音 / 字母变体识别（配合 TinyPinyin 生成长拼音索引）。
 *
 * 误伤红线（吸取“日期”被误改的教训）：
 *  1. 只处理多字词；单字脏词（日/操/靠）仍由 MiaoifyEngine 走 jieba 独立成词判断；
 *  2. 字母/拼音脏词强制左右词边界，避免 usb 含 sb、class 含 ass；
 *  3. 中文词自动生成的拼音索引要求长度 &gt;=5，避开 made 这类正常英文词；短脏词走显式字母表；
 *  4. 词表宁少勿误，不收存在正常义项的词（如“放屁/去死/几把”）。
 */
public final class BadWordFilter {

    private static final class Node {
        Map<Character, Node> next;
        boolean end;
        boolean ascii;
    }

    /** 归一化后的紧凑文本及其到原文的位置映射 */
    private static final class Compact {
        final char[] c;
        final int[] pos;
        Compact(char[] c, int[] pos) { this.c = c; this.pos = pos; }
    }

    /** 明确的中文多字脏词 / 辱骂词 */
    private static final String[] CN = {
            "他妈的", "她妈的", "特么的", "他娘的", "他妈", "特么",
            "你妈的", "去你妈", "操你妈", "艹你妈", "草泥马", "操你", "艹你",
            "日你妈", "日你", "狗日的", "狗娘养", "狗杂种", "王八蛋",
            "傻逼", "傻逼人", "煞笔", "煞痹", "傻比", "傻痹", "傻吊", "傻屌", "屌你妈",
            "你妹的", "妈卖批", "麻辣个逼", "妈了个逼", "妈的智障", "妈的逼",
            "贱骨头", "贱货", "贱人", "小贱人", "婊子", "臭婊子", "臭逼", "骚货", "荡妇",
            "鸡巴", "鸡吧", "鸡掰", "操蛋", "狗东西", "去死吧", "不得好死", "吃屎", "吃屎吧",
            "装你妈", "关你屁事", "关我屁事", "狗屁", "娘希匹", "龟儿子", "龟孙子",
            "兔崽子", "狗腿子", "不要脸", "下三滥", "人渣", "败类", "蠢货", "废物东西"
    };

    /** 显式字母 / 拼音脏词（缩写、短词，确定无歧义；仍受字母词边界保护） */
    private static final String[] ASCII = {
            "tmd", "nmsl", "cnm", "cnmb", "rnmb", "nmbd", "mmp", "mlgb",
            "sb", "jb", "fk", "wtf", "stfu",
            "fuck", "fucker", "fucking", "shit", "bitch", "bastard", "asshole",
            "caonima", "shabi", "shab", "caoni", "qunima", "gouride"
    };

    private static volatile Node root;
    private static volatile boolean inited = false;

    private BadWordFilter() {}

    /** 可在 App 启动后台线程预热，避免首次翻译时构建词表 */
    public static void warmUp() { ensure(); }

    private static synchronized void ensure() {
        if (inited) return;
        Node r = new Node();
        for (String w : CN) {
            insert(r, compact(w), false);
            String py = PinyinUtil.wordPinyin(w);
            if (py.length() >= 5) insert(r, py, true); // 长拼音几乎只可能是脏话拼音
        }
        for (String w : ASCII) insert(r, compact(w), true);
        root = r;
        inited = true;
        AppLog.i("BadWord", "DFA 脏词表构建完成");
    }

    private static void insert(Node r, String word, boolean ascii) {
        if (word == null || word.isEmpty()) return;
        Node cur = r;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (cur.next == null) cur.next = new HashMap<>();
            Node nx = cur.next.get(c);
            if (nx == null) { nx = new Node(); cur.next.put(c, nx); }
            cur = nx;
        }
        cur.end = true;
        cur.ascii = ascii;
    }

    /** 是否含脏词 */
    public static boolean hasBad(String text) {
        return !scan(build(text).c).isEmpty();
    }

    /**
     * 把命中的多字脏词整体替换为 replacement（通常是当前风格的可爱化脏词，如“大笨蛋！”）。
     * 未命中返回原文。
     */
    public static String replace(String text, String replacement) {
        if (text == null || text.isEmpty()) return text;
        ensure();
        Compact cp = build(text);
        List<int[]> hits = scan(cp.c);
        if (hits.isEmpty()) return text;
        String rep = (replacement == null || replacement.isEmpty()) ? "大笨蛋" : replacement;
        StringBuilder sb = new StringBuilder(text.length() + 8);
        int cursor = 0;
        for (int[] h : hits) {
            int origStart = cp.pos[h[0]];
            int origEnd = cp.pos[h[1] - 1] + 1;
            if (origStart < cursor) continue; // 保险：跳过重叠
            sb.append(text, cursor, origStart).append(rep);
            cursor = origEnd;
        }
        sb.append(text, cursor, text.length());
        return sb.toString();
    }

    // ------------------------------------------------------------
    // 内部：归一化 + DFA 扫描
    // ------------------------------------------------------------

    /** 在紧凑串上扫描命中区间 [start,end)，已做最长匹配、字母词边界、跳过重叠 */
    private static List<int[]> scan(char[] comp) {
        List<int[]> hits = new ArrayList<>();
        Node r = root;
        int n = comp.length;
        int i = 0;
        while (i < n) {
            Node cur = r;
            int j = i, lastEnd = -1;
            boolean lastAscii = false;
            while (j < n) {
                if (cur.next == null) break;
                Node nx = cur.next.get(comp[j]);
                if (nx == null) break;
                cur = nx;
                j++;
                if (cur.end) { lastEnd = j; lastAscii = cur.ascii; }
            }
            if (lastEnd > 0) {
                boolean boundaryOk = true;
                if (lastAscii) {
                    char left = i > 0 ? comp[i - 1] : 0;
                    char right = lastEnd < n ? comp[lastEnd] : 0;
                    if (isLetter(left) || isLetter(right)) boundaryOk = false;
                }
                if (boundaryOk) {
                    hits.add(new int[]{i, lastEnd});
                    i = lastEnd;
                    continue;
                }
            }
            i++;
        }
        return hits;
    }

    private static boolean isLetter(char c) { return c >= 'a' && c <= 'z'; }

    /** 原文 -> 紧凑串（全角转半角、小写、只留汉字/字母/数字）+ 到原文的位置映射 */
    private static Compact build(String s) {
        ensure();
        StringBuilder cb = new StringBuilder(s.length());
        List<Integer> posList = new ArrayList<>(s.length());
        for (int idx = 0; idx < s.length(); idx++) {
            char c = s.charAt(idx);
            if (c == 0x3000) continue;
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);
            if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
            boolean keep = (c >= 0x4E00 && c <= 0x9FFF) || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (keep) { cb.append(c); posList.add(idx); }
        }
        char[] cc = new char[cb.length()];
        cb.getChars(0, cb.length(), cc, 0);
        int[] pp = new int[posList.size()];
        for (int k = 0; k < posList.size(); k++) pp[k] = posList.get(k);
        return new Compact(cc, pp);
    }

    /** 构建词表用：归一化为紧凑小写汉字/字母数字串 */
    private static String compact(String w) {
        if (w == null) return "";
        StringBuilder sb = new StringBuilder(w.length());
        for (int idx = 0; idx < w.length(); idx++) {
            char c = w.charAt(idx);
            if (c == 0x3000) continue;
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);
            if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))
                sb.append(c);
        }
        return sb.toString();
    }
}
