package com.miao.helper;

import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 无障碍节点安全回收（鉴 P0-1，老板线复核 2026-09-14）。
 *
 * 三层防护：
 * 1) null 安全：空节点直接跳过；
 * 2) API 兼容：Android 13+（API 33）起节点由系统自动回收，手动 recycle() 反而可能抛
 *    Already recycled，因此 SDK≥33 一律跳过；
 * 3) 重复回收防护：低版本机型对同一节点二次 recycle() 会崩。用弱引用集合标记已回收节点
 *    （WeakHashMap 不阻止 GC，不造成泄漏），重复回收时跳过并记日志。
 * 所有异常均 try/catch 吞掉并写 AppLog，绝不把 Binder 异常抛回主流程。
 */
public final class AccessibilityNodes {

    private AccessibilityNodes() {}

    /** 已显式回收的节点集合（弱引用，不阻止 GC；仅用于老机型重复回收防护） */
    private static final Set<AccessibilityNodeInfo> RECYCLED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<AccessibilityNodeInfo, Boolean>()));

    /**
     * 安全回收节点：null 安全、API 33+ 跳过、重复回收跳过、异常记日志。
     */
    public static void safeRecycle(AccessibilityNodeInfo node) {
        if (node == null) {
            // 空节点：无需回收，静默返回
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ 系统自动回收，手动 recycle() 会抛 Already recycled
            return;
        }
        if (!RECYCLED.add(node)) {
            // 同一节点已回收过（引用计数/状态标记）：跳过并记日志
            AppLog.w("Nodes", "重复回收防护：节点已被回收，跳过二次 recycle");
            return;
        }
        try {
            node.recycle();
        } catch (Throwable t) {
            AppLog.w("Nodes", "节点回收异常（已吞掉，不影响主流程）：" + t);
        }
    }
}
