package com.miao.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 悬浮球自定义 View：
 *  - 粉色圆形背景
 *  - 中间显示风格名称文字
 *  - API 翻译时显示环形进度条（白色描边），卡在 90%，完成后到 100% 并淡出
 */
public class ProgressBallView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();

    private String text = "";
    private float progress = 0f;       // 0-1
    private boolean showRing = false;
    private float ringAlpha = 0f;      // 进度条透明度 0-1，用于淡入淡出
    private int bgColor = 0xFFE65100; // 深橙背景
    private int ringColor = 0xFFFFFFFF; // 白色进度环
    private float textSizeRatio = 0.30f;
    private int ringVersion = 0;       // 进度会话版本号：新 startProgress 递增，旧隐藏定时据此失效

    // 新 UI：橙→粉渐变背景 + 引擎角标（本地=绿点，AI=蓝点）
    private android.graphics.Shader bgShader;
    private boolean engineApi = false;
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ProgressBallView(Context context) {
        super(context);
        init();
    }

    public ProgressBallView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(bgColor);

        ringBgPaint.setStyle(Paint.Style.STROKE);
        ringBgPaint.setColor(0x33FFFFFF);
        ringBgPaint.setStrokeWidth(dp(3));
        ringBgPaint.setStrokeCap(Paint.Cap.ROUND);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(ringColor);
        ringPaint.setStrokeWidth(dp(3));
        ringPaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);

        dotRingPaint.setStyle(Paint.Style.STROKE);
        dotRingPaint.setColor(0xFFFFFFFF);
        dotRingPaint.setStrokeWidth(dp(1.6f));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) { bgShader = null; return; }
        bgShader = new android.graphics.LinearGradient(0, 0, w, h,
                0xFFFFB74D, 0xFFEC407A, android.graphics.Shader.TileMode.CLAMP);
    }

    /** 设置引擎角标：true=AI（蓝点），false=本地（绿点） */
    public void setEngine(boolean api) {
        this.engineApi = api;
        invalidate();
    }

    /** 5.0 状态闪烁：临时改变背景色，durationMs 后恢复 */
    private int savedBgColor = 0;
    private final Handler flashHandler = new Handler(Looper.getMainLooper());
    public void flashColor(final int color, long durationMs) {
        if (savedBgColor == 0) savedBgColor = bgPaint.getColor();
        bgPaint.setColor(color);
        invalidate();
        flashHandler.removeCallbacksAndMessages(null);
        flashHandler.postDelayed(new Runnable() {
            @Override public void run() {
                bgPaint.setColor(savedBgColor);
                savedBgColor = 0;
                invalidate();
            }
        }, durationMs);
    }

    public void setText(String t) {
        this.text = t == null ? "" : t;
        invalidate();
    }

    public void setBgColor(int color) {
        this.bgColor = color;
        bgPaint.setColor(color);
        invalidate();
    }

    public void setTextSizeRatio(float ratio) {
        this.textSizeRatio = ratio;
        invalidate();
    }

    /** 开始显示进度条（淡入）。开启新的进度会话，使旧的完成定时失效。 */
    public void startProgress() {
        ringVersion++;
        showRing = true;
        progress = 0f;
        ringAlpha = 0f;
        invalidate();
    }

    /** 设置进度 0-1 */
    public void setProgress(float p) {
        this.progress = Math.max(0f, Math.min(1f, p));
        this.ringAlpha = 1f;
        invalidate();
    }

    /** 完成：进度到 100%，然后淡出隐藏。仅当期间没有开启新进度会话时才真正隐藏。 */
    public void completeProgress() {
        final int v = ringVersion;
        this.progress = 1f;
        this.ringAlpha = 1f;
        invalidate();
        postDelayed(() -> {
            if (v != ringVersion) return;   // 期间开始了新进度，旧定时失效，不能误关
            showRing = false;
            ringAlpha = 0f;
            progress = 0f;
            invalidate();
        }, 400);
    }

    /** 取消进度条（出错时） */
    public void cancelProgress() {
        ringVersion++;
        showRing = false;
        ringAlpha = 0f;
        progress = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f - dp(2);

        // 背景圆（渐变）
        bgPaint.setShader(bgShader);
        canvas.drawCircle(cx, cy, radius, bgPaint);

        // 文字：自适应字号，且避开右下角引擎角标区域（文字横向收缩到球直径的 0.64 内）
        if (!text.isEmpty()) {
            float ratio = textSizeRatio;
            float dotR = Math.min(Math.max(dp(4), w * 0.10f), dp(6));
            float maxTextW = w - dotR * 2 - dp(6);
            // 长文本逐级缩小，保证不撞角标
            while (ratio > 0.12f) {
                textPaint.setTextSize(w * ratio);
                float tw = textPaint.measureText(text);
                if (tw <= maxTextW) break;
                ratio -= 0.03f;
            }
            textPaint.setTextSize(w * ratio);
            float y = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(text, cx, y, textPaint);
        }

        // 环形进度条（进度激活时隐藏引擎角标，避免圆点与进度环重叠打架）
        boolean ringActive = showRing && ringAlpha > 0;
        if (ringActive) {
            float padding = dp(4);
            ringRect.set(padding, padding, w - padding, h - padding);
            // 背景环
            ringBgPaint.setAlpha((int) (ringAlpha * 80));
            canvas.drawArc(ringRect, -90, 360, false, ringBgPaint);
            // 进度环
            ringPaint.setAlpha((int) (ringAlpha * 255));
            canvas.drawArc(ringRect, -90, 360 * progress, false, ringPaint);
        }

        // 引擎角标：仅在无进度环时显示，本地绿 / AI 蓝，白描边
        if (!ringActive) {
            float dotR = Math.min(Math.max(dp(4), w * 0.10f), dp(6));
            float dx = w - dotR - dp(2.5f);
            float dy = h - dotR - dp(2.5f);
            dotPaint.setColor(engineApi ? 0xFF2196F3 : 0xFF66BB6A);
            canvas.drawCircle(dx, dy, dotR, dotPaint);
            canvas.drawCircle(dx, dy, dotR, dotRingPaint);
        }
    }

    private float dp(float v) {
        return getResources().getDisplayMetrics().density * v;
    }
}
