package com.miao.helper;

import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch;

import java.util.LinkedList;

/**
 * 原文/译文差异工具（封装 Google diff-match-patch，Apache-2.0）。
 * 量化“AI 到底改了多少”，供预览条展示改动率、日志与裁判参考。每次新建实例以保证线程安全。
 */
public final class TextDiff {

    private TextDiff() {}

    /** 改动率 0-100：Levenshtein 编辑距离 ÷ 较长文本长度；计算失败返回 -1 */
    public static int changePercent(String orig, String out) {
        try {
            if (orig == null) orig = "";
            if (out == null) out = "";
            int denom = Math.max(orig.length(), out.length());
            if (denom == 0) return 0;
            DiffMatchPatch dmp = new DiffMatchPatch();
            LinkedList<DiffMatchPatch.Diff> diffs = dmp.diffMain(orig, out);
            dmp.diffCleanupSemantic(diffs);
            int lev = dmp.diffLevenshtein(diffs);
            return Math.min(100, Math.round(lev * 100f / denom));
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 改动率是否低于阈值（基本没改） */
    public static boolean looksUnchanged(String orig, String out, int belowPercent) {
        int p = changePercent(orig, out);
        return p >= 0 && p < belowPercent;
    }

    /**
     * 提取「原文片段 → 译文片段」替换映射（供查看/编辑 AI 生成的词汇替换）。
     * 基于 diff：相邻 DELETE+INSERT 配对为一次替换；单独 INSERT 为 from=""（新增）；单独 DELETE 为 to=""（删除）。
     * 返回 [from, to] 列表；纯空白或 from==to 的对被过滤。
     */
    public static java.util.List<String[]> extractMappings(String orig, String out) {
        java.util.List<String[]> map = new java.util.ArrayList<>();
        try {
            if (orig == null) orig = "";
            if (out == null) out = "";
            DiffMatchPatch dmp = new DiffMatchPatch();
            LinkedList<DiffMatchPatch.Diff> diffs = dmp.diffMain(orig, out);
            dmp.diffCleanupSemantic(diffs);
            String pendingDel = null;
            for (DiffMatchPatch.Diff d : diffs) {
                if (d.operation == DiffMatchPatch.Operation.DELETE) {
                    pendingDel = d.text;
                } else if (d.operation == DiffMatchPatch.Operation.INSERT) {
                    map.add(new String[]{pendingDel == null ? "" : pendingDel, d.text});
                    pendingDel = null;
                } else { // EQUAL
                    pendingDel = null;
                }
            }
            if (pendingDel != null) map.add(new String[]{pendingDel, ""});
            java.util.List<String[]> cleaned = new java.util.ArrayList<>();
            for (String[] pair : map) {
                String f = pair[0] == null ? "" : pair[0].trim();
                String t = pair[1] == null ? "" : pair[1].trim();
                if (f.isEmpty() && t.isEmpty()) continue;
                if (f.equals(t)) continue;
                cleaned.add(new String[]{f, t});
            }
            return cleaned;
        } catch (Throwable t) {
            return map;
        }
    }
}
