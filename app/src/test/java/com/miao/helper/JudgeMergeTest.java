package com.miao.helper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 4.6 多选一裁判：投票合并 6 分支 JVM 桩测（Clean 验收项）。 */
public class JudgeMergeTest {

    private static int merge(int K, int N, int[] choices, int[] confs) {
        return ApiMiaoifier.mergeVotes(K, N, choices, confs, new String[K]);
    }

    @Test
    public void allThreeSame_picksIt() {
        // K=3, N=6：3 个裁判都选视角 1（原下标 = (0+1-1)%6=0）
        assertEquals(0, merge(3, 6, new int[]{1, 1, 1}, new int[]{90, 85, 88}));
    }

    @Test
    public void twoToOne_picksMajority() {
        // 裁判0(k=0)选视角1→原0；裁判1(k=1)选视角2→原2；裁判2(k=2)选视角1→原2 → 原2 得2票
        assertEquals(2, merge(3, 6, new int[]{1, 2, 1}, new int[]{70, 90, 75}));
    }

    @Test
    public void allDifferent_picksHighestConfidence() {
        // 裁判0选视角1(原0,conf60)，裁判1选视角3(原(1+3-1)%6=3,conf95)，裁判2选视角2(原(2+2-1)%6=3,conf90)
        // → 原3 得2票(conf95) 胜出
        assertEquals(3, merge(3, 6, new int[]{1, 3, 2}, new int[]{60, 95, 90}));
    }

    @Test
    public void allDifferent_tieConfidence_picksFirst() {
        // 原0 / 原2 各1票且置信相同 → 取更小下标（遍历顺序）
        assertEquals(0, merge(2, 6, new int[]{1, 3}, new int[]{80, 80}));
    }

    @Test
    public void majorityZero_allRejected() {
        // K=3：2 个裁判判全不合格（choice=0）→ -1 触发修正路径
        assertEquals(-1, merge(3, 6, new int[]{0, 1, 0}, new int[]{0, 80, 0}));
    }

    @Test
    public void singleZero_picksConsensus() {
        // 1 个弃权(0) + 裁判0选视角3→原2(conf85) + 裁判2选视角3→原4(conf90) → 平票比置信 → 原4
        assertEquals(4, merge(3, 6, new int[]{3, 0, 3}, new int[]{85, 0, 90}));
    }

    @Test
    public void allFailed_fallbackPath() {
        // 全部裁判失败 → -2 落保底
        assertEquals(-2, merge(3, 6, new int[]{-1, -1, -1}, new int[]{0, 0, 0}));
    }

    @Test
    public void noValidVotes_fallbackPath() {
        // 有弃权但无有效票（K=2 一个失败一个0，zeros=1 < (2+1)/2=1？(2+1)/2=1 → zeros>=1 触发 -1）
        // 此处构造 K=2：choice=0 + choice=-1 → zeros=1 >= 1 → -1（修正路径）
        assertEquals(-1, merge(2, 6, new int[]{0, -1}, new int[]{0, 0}));
    }

    @Test
    public void singleJudge_acceptsItsChoice() {
        // K=1：直接采用裁判选择（视角1 → 原0）
        assertEquals(0, merge(1, 6, new int[]{1}, new int[]{88}));
    }

    @Test
    public void singleJudge_rejectsAll() {
        // K=1 判全不合格 → -1
        assertEquals(-1, merge(1, 6, new int[]{0}, new int[]{0}));
    }

    @Test
    public void positionRotation_mapsToOriginalIndex() {
        // 位置轮换映射：k=1 的裁判选视角3 → 原下标 (1+3-1)%6=3（裁判0失败票不参与）
        assertEquals(3, merge(2, 6, new int[]{-1, 3}, new int[]{0, 77}));
    }

    @Test
    public void reasonFor_highCandidateIndex_noOutOfBounds() {
        // 4.6.2 实锤崩溃：K=1 裁判选中原下标4，reasons 数组长度1，旧代码 reasons[4] 越界
        // reasonFor 应返回投给该候选的裁判理由而非按候选下标索引
        String reason = ApiMiaoifier.reasonFor(4, 1, 6, new int[]{5}, new String[]{"风格浓度最高"});
        assertEquals("风格浓度最高", reason);
    }

    @Test
    public void reasonFor_noVoter_returnsEmpty() {
        // 候选1 无人投票（裁判全投候选5）→ 空理由，不越界
        String reason = ApiMiaoifier.reasonFor(1, 2, 6, new int[]{5, 5}, new String[]{"a", "b"});
        assertEquals("", reason);
    }

    @Test
    public void reasonFor_negativeMerged_returnsEmpty() {
        assertEquals("", ApiMiaoifier.reasonFor(-1, 1, 6, new int[]{5}, new String[]{"x"}));
    }
}
