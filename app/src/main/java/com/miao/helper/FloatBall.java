package com.miao.helper;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 全局悬浮球：
 *  - 显示当前人格名称
 *  - 单击 = 风格化当前输入框
 *  - 长按 = 弹出快捷菜单
 *  - 拖动后记住位置
 *  - API 翻译时显示环形进度条，卡在 90%，完成后到 100% 淡出
 */
public class FloatBall {

    private final WindowManager wm;
    private final ProgressBallView ball;
    private final WindowManager.LayoutParams lp;
    private float sx, sy, ox, oy;
    private boolean moved;
    private final int touchSlop;   // 系统标准拖动阈值，避免手指轻微抖动被误判成拖动导致点击失效
    private final Runnable onTap;
    private final Runnable onLongTap;
    private String currentText = "";
    private FloatPanel panel;   // 新 UI：悬浮面板（长按展开）

    // 进度条动画
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private float currentProgress = 0f;
    private long progressStartMs = 0L;
    private long progressDurationMs = 3000L;
    private static final float PROGRESS_CAP = 0.90f; // 卡在 90%
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            long elapsed = System.currentTimeMillis() - progressStartMs;
            float ratio = Math.min(1f, (float) elapsed / progressDurationMs);
            // 缓动：先快后慢，接近 90% 时越来越慢
            float eased = 1f - (float) Math.pow(1f - ratio, 3);
            currentProgress = eased * PROGRESS_CAP;
            ball.setProgress(currentProgress);
            if (ratio < 1f) {
                progressHandler.postDelayed(this, 30);
            }
        }
    };

    public FloatBall(Context ctx, Runnable tap, Runnable longTap) {
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        ball = new ProgressBallView(ctx);
        touchSlop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
        int size = Prefs.floatSize();
        lp = new WindowManager.LayoutParams(
                dp(size), dp(size),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Prefs.floatX();
        lp.y = Prefs.floatY();
        onTap = tap;
        onLongTap = longTap;
        applyAppearance();
        bindTouch();
    }

    private void applyAppearance() {
        int size = Prefs.floatSize();
        ball.setAlpha(Math.max(0.2f, Math.min(1f, Prefs.floatOpacity() / 100f)));
        int len = currentText == null ? 0 : currentText.length();
        float ratio = len <= 2 ? 0.38f : (len == 3 ? 0.30f : 0.24f);
        ball.setTextSizeRatio(ratio);
        ball.setText(currentText);
    }

    public void refresh() {
        applyAppearance();
        ball.setEngine(Prefs.useApi());
        int size = Prefs.floatSize();
        lp.width = dp(size);
        lp.height = dp(size);
        try { wm.updateViewLayout(ball, lp); } catch (Exception ignored) {}
    }

    public void setStatus(String text) {
        currentText = text == null ? "" : text;
        applyAppearance();
    }

    /** 绑定悬浮面板（新 UI：长按展开）。未绑定则回退到旧 longTap 回调。 */
    public void setPanel(FloatPanel p) {
        this.panel = p;
    }

    /** 刷新引擎角标（本地绿点 / AI 蓝点） */
    public void setEngine(boolean api) {
        ball.setEngine(api);
    }

    public View getView() {
        return ball;
    }

    // ============================================================
    // 进度条控制
    // ============================================================

    /**
     * 开始进度动画。根据原文长度决定动画时长，最终卡在 90%。
     * 线程安全：所有碰 View 的操作统一 post 到主线程（可能从 API 线程被调用）。
     * @param textLength 原文长度，用于估算时长
     */
    public void startProgress(int textLength) {
        // 时长估算：短文本 1.5s，中文本 3s，长文本 6s，超长 10s（纯计算，无线程问题）
        if (textLength <= 15) progressDurationMs = 1500;
        else if (textLength <= 40) progressDurationMs = 3000;
        else if (textLength <= 100) progressDurationMs = 6000;
        else progressDurationMs = 10000;
        startProgressInternal();
    }

    /** 自定义时长的进度条（长文本/慢接口用此重载） */
    public void startProgress(int textLength, long durationMs) {
        progressDurationMs = Math.max(1500L, durationMs);
        startProgressInternal();
    }

    private void startProgressInternal() {
        currentProgress = 0f;
        progressStartMs = System.currentTimeMillis();
        progressHandler.post(() -> {
            progressHandler.removeCallbacksAndMessages(null);
            ball.cancelProgress();
            ball.startProgress();
            progressHandler.post(progressTick);
        });
    }

    /** 完成：直接补到 100%，短暂停留后淡出隐藏。线程安全（可能从 API 回调线程调用） */
    public void completeProgress() {
        progressHandler.post(() -> {
            progressHandler.removeCallbacksAndMessages(null);
            ball.setProgress(1f);
            progressHandler.postDelayed(ball::completeProgress, 250);
        });
    }

    /** v3.3 真流式进度：用 SSE 估算进度覆盖假爬动画并停掉自动 tick。线程安全（可从 API 线程调用） */
    public void updateStreamProgress(float p) {
        final float v = Math.max(0.05f, Math.min(0.92f, p));
        progressHandler.post(() -> {
            progressHandler.removeCallbacks(progressTick);
            currentProgress = v;
            ball.setProgress(v);
        });
    }

    /** 流式过程中同步刷新球内提示文字（如显示已生成字数）。线程安全 */
    public void updateStreamText(String text) {
        progressHandler.post(() -> ball.setText(text == null ? "" : text));
    }
    /** 取消进度条（出错时）。线程安全 */
    public void cancelProgress() {
        currentProgress = 0f;
        progressHandler.post(() -> {
            progressHandler.removeCallbacksAndMessages(null);
            ball.cancelProgress();
        });
    }

    /** 5.0 状态指示：成功绿闪 1.2 秒 */
    public void flashSuccess() {
        progressHandler.post(() -> ball.flashColor(0xFF66BB6A, 1200));
    }

    /** 5.0 状态指示：失败红闪 1.2 秒 */
    public void flashError() {
        progressHandler.post(() -> ball.flashColor(0xFFEF5350, 1200));
    }

    private int dp(int v) {
        return Math.round(ball.getResources().getDisplayMetrics().density * v);
    }

    private void bindTouch() {
        ball.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sx = e.getRawX(); sy = e.getRawY();
                    ox = lp.x; oy = lp.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                    // 超过系统拖动阈值才算拖动；阈值内的轻微抖动不移动球，保证松手仍触发点击
                    if (Math.abs(dx) + Math.abs(dy) > touchSlop) moved = true;
                    if (moved) {
                        if (panel != null && panel.isShowing()) panel.hide();
                        lp.x = (int) (ox + dx);
                        lp.y = (int) (oy + dy);
                        wm.updateViewLayout(ball, lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moved) {
                        Prefs.set("floatX", lp.x);
                        Prefs.set("floatY", lp.y);
                        return true;
                    }
                    long dt = e.getEventTime() - e.getDownTime();
                    if (dt > 500) {
                        // 长按 = 展开/收起悬浮面板（新 UI）
                        if (panel != null) {
                            if (panel.isShowing()) panel.hide();
                            else panel.show(lp.x, lp.y, dp(Prefs.floatSize()));
                        } else {
                            onLongTap.run();
                        }
                    } else if (panel != null && panel.isShowing()) {
                        // 面板展开时单击悬浮球：收起面板 + 继续执行风格化（入框替换），
                        // 防止面板状态残留把单击吞掉导致"点了没反应"
                        panel.hide();
                        onTap.run();
                    } else {
                        onTap.run();
                    }
                    return true;
            }
            return false;
        });
    }

    public void show() {
        try { wm.addView(ball, lp); } catch (Exception ignored) {}
    }

    public void hide() {
        try { wm.removeView(ball); } catch (Exception ignored) {}
    }
}
