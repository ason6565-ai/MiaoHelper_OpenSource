package com.miao.helper;

import com.github.promeg.pinyinhelper.Pinyin;

/**
 * TinyPinyin 薄封装（io.github.biezhi:TinyPinyin，原 promeg 代码，包名仍为
 * com.github.promeg.pinyinhelper）。无声调、无第三方词典依赖、纯 Java、Android 原生兼容。
 * 用途：①给 DfaFilter 生成脏词拼音索引，识别拼音 / 谐音打字的脏话；②预留按拼音处理能力。
 */
public final class PinyinUtil {

    private PinyinUtil() {}

    public static boolean isChinese(char c) {
        try { return Pinyin.isChinese(c); } catch (Throwable t) { return false; }
    }

    /** 单字无声调大写全拼；非汉字返回字符本身 */
    public static String of(char c) {
        try { return Pinyin.toPinyin(c); } catch (Throwable t) { return String.valueOf(c); }
    }

    /**
     * 整句压成连续小写串：汉字转无声调拼音、英文字母小写、数字保留、其余符号忽略。
     * 用于脏词的拼音 / 谐音变体匹配。
     */
    public static String compactPinyin(CharSequence s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() * 3);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isChinese(c)) {
                sb.append(Pinyin.toPinyin(c).toLowerCase());
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) (c + 32));
            }
        }
        return sb.toString();
    }

    /** 单个词的小写拼音串（构建脏词拼音索引用） */
    public static String wordPinyin(String w) {
        return w == null ? "" : compactPinyin(w);
    }
}
