package com.miao.helper;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 结果预览-确认悬浮条（对标开源 gestrow 的“先预览再写入”范式，配合 diff-match-patch 显示改动率）。
 * 仅当用户在设置里开启“预览模式”时使用：AI/本地结果先显示在条上、不写入输入框，
 * 点“采用”才由 MiaoService 写入，点“取消”则输入框保持原文。纯文字、无 Emoji（悬浮窗 UI 约束），
 * FLAG_NOT_FOCUSABLE 不抢输入框焦点；所有 View 操作强制主线程；30 秒无操作自动消失。
 */
public class PreviewBubble {

    public interface Listener {
        void onAccept();
        void onCancel();
    }

    private static final long AUTO_HIDE_MS = 30000;

    private final WindowManager wm;
    private final View root;
    private final TextView title;
    private final TextView body;
    private final WindowManager.LayoutParams lp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean showing = false;
    private Listener listener;
    private final Runnable autoHide = this::hide;

    public PreviewBubble(Context ctx) {
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        float d = ctx.getResources().getDisplayMetrics().density;
        int pad = dp(d, 12);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(d, 14));
        bg.setColor(0xFFFFF3E0);
        bg.setStroke(dp(d, 1), 0xFFFFCC80);
        col.setBackground(bg);

        title = new TextView(ctx);
        title.setTextColor(0xFFE65100);
        title.setTextSize(12);

        body = new TextView(ctx);
        body.setTextColor(0xFF3E2723);
        body.setTextSize(15);
        body.setMaxLines(4);
        body.setEllipsize(TextUtils.TruncateAt.END);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);

        Button cancel = mkButton(ctx, false, "取消", d);
        Button accept = mkButton(ctx, true, "采用", d);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = dp(d, 10);
        row.addView(cancel, clp);
        row.addView(accept);

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(d, 6);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(d, 10);
        col.addView(title, tlp);
        col.addView(body, blp);
        col.addView(row, rlp);

        accept.setOnClickListener(v -> {
            Listener l = this.listener;
            hide();
            if (l != null) l.onAccept();
        });
        cancel.setOnClickListener(v -> {
            Listener l = this.listener;
            hide();
            if (l != null) l.onCancel();
        });

        root = col;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.horizontalMargin = 0.03f;
        lp.y = dp(d, 120);
    }

    private Button mkButton(Context ctx, boolean primary, String text, float d) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setPadding(dp(d, 18), dp(d, 6), dp(d, 18), dp(d, 6));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(d, 10));
        if (primary) {
            g.setColor(0xFFFB8C00);
            b.setTextColor(0xFFFFFFFF);
        } else {
            g.setColor(0x00000000);
            g.setStroke(dp(d, 1), 0xFFFFCC80);
            b.setTextColor(0xFF8D6E63);
        }
        b.setBackground(g);
        return b;
    }

    public void show(String translated, int changePercent, Listener l) {
        main.post(() -> {
            this.listener = l;
            title.setText(changePercent >= 0 ? "译文预览 · 改动 " + changePercent + "%" : "译文预览");
            body.setText(translated == null ? "" : translated);
            try {
                if (showing) wm.updateViewLayout(root, lp);
                else { wm.addView(root, lp); showing = true; }
            } catch (Exception ignored) {}
            main.removeCallbacks(autoHide);
            main.postDelayed(autoHide, AUTO_HIDE_MS);
        });
    }

    /** 流式过程中实时刷新预览文本 */
    public void updateText(String translated) {
        main.post(() -> body.setText(translated == null ? "" : translated));
    }

    public void hide() {
        main.post(() -> {
            try {
                if (showing) { wm.removeView(root); showing = false; }
            } catch (Exception ignored) {}
            main.removeCallbacks(autoHide);
            this.listener = null;
        });
    }

    public boolean isShowing() { return showing; }

    private static int dp(float d, int v) { return Math.round(v * d); }
}
