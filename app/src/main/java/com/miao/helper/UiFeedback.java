package com.miao.helper;

import android.content.Context;
import android.widget.Toast;

/**
 * 5.0 信息化·模块一：统一交互反馈出口（UiFeedback）。
 * 目的：任何操作必须有明确反馈，杜绝"点不动、卡死、没反应"。
 * 所有 Toast / 拦截提示 / 成功 / 错误走同一个出口，便于后续统一加悬浮球变色、震动等。
 */
public final class UiFeedback {

    private UiFeedback() {}

    /** 普通短提示 */
    public static void toast(Context ctx, String msg) {
        if (ctx == null || msg == null) return;
        try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    /** 长提示（用于需要用户仔细看的拦截/错误） */
    public static void toastLong(Context ctx, String msg) {
        if (ctx == null || msg == null) return;
        try { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }

    /**
     * 拦截类提示：必须带可操作建议。
     * 例：blocked(ctx, "原文包含不适合风格化的词汇", "修改措辞后重试")
     */
    public static void blocked(Context ctx, String reason, String suggestion) {
        String msg = reason == null ? "已拦截" : reason;
        if (suggestion != null && !suggestion.isEmpty()) msg += "（" + suggestion + "）";
        toastLong(ctx, msg);
    }

    /** 成功提示（后续可扩展悬浮球变绿） */
    public static void success(Context ctx, String msg) { toast(ctx, msg); }

    /** 错误提示 */
    public static void error(Context ctx, String msg) { toastLong(ctx, msg); }

    /** API Key 无效：提示 + 可操作建议 */
    public static void apiKeyInvalid(Context ctx) {
        toastLong(ctx, "API Key 无效，请在设置里检查或重新填写");
    }

    /** 网络错误：自动重试提示 */
    public static void networkRetry(Context ctx) {
        toast(ctx, "网络超时，已自动重试，请稍后再试");
    }

    /** 防重翻短路：原文没变化时提示，避免用户以为没反应 */
    public static void dedupSkip(Context ctx) {
        toast(ctx, "原文没变化，已跳过重复翻译");
    }
}
