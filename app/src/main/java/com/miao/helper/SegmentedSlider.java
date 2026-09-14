package com.miao.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 分段控制器：选中段橙色填充+白字，未选中段透明+灰字，段间细分隔线。
 * 默认二段（左明右暗），也支持 setLabels(String[]) 扩为 N 段。
 */
public class SegmentedSlider extends View {
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path activePath = new Path();

    private String[] labels = {"选项A", "选项B"};
    private int segments = 2;
    private int selected = 0;

    private static final int ACTIVE_COLOR  = 0xFFE65100;
    private static final int TEXT_COLOR    = 0xFF8D6E63;
    private static final int ACTIVE_TEXT   = 0xFFFFFFFF;
    private static final int DIVIDER_COLOR = 0x1A000000;

    private OnSelectionChangedListener listener;

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int position);
    }

    public SegmentedSlider(Context context) { super(context); init(); }
    public SegmentedSlider(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public SegmentedSlider(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); init(); }

    private void init() {
        activePaint.setColor(ACTIVE_COLOR);
        activePaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(TEXT_COLOR);
        textPaint.setTextAlign(Paint.Align.CENTER);
        activeTextPaint.setColor(ACTIVE_TEXT);
        activeTextPaint.setTextAlign(Paint.Align.CENTER);
        dividerPaint.setColor(DIVIDER_COLOR);
        dividerPaint.setStyle(Paint.Style.FILL);
    }

    /** 二段标签（兼容旧调用） */
    public void setLabels(String left, String right) { setLabels(new String[]{left, right}); }

    /** N 段标签 */
    public void setLabels(String[] arr) {
        if (arr == null || arr.length < 2) return;
        labels = arr;
        segments = arr.length;
        selected = Math.max(0, Math.min(segments - 1, selected));
        invalidate();
    }

    public void setSelected(int pos) {
        selected = Math.max(0, Math.min(segments - 1, pos));
        invalidate();
    }

    public int getSelected() { return selected; }
    public int getSegmentCount() { return segments; }
    public void setOnSelectionChangedListener(OnSelectionChangedListener l) { listener = l; }

    private float h()   { return getHeight() * 0.78f; }
    private float top() { return (getHeight() - h()) / 2; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float L = getPaddingLeft();
        float R = getWidth() - getPaddingRight();
        float T = top();
        float B = T + h();
        float w = R - L;
        float segW = w / segments;
        float rad = h() / 2;

        // 段间分隔线（先画，选中填充随后覆盖，保证选中段内无杂线）
        for (int i = 1; i < segments; i++) {
            float x = L + segW * i;
            canvas.drawRect(x - 0.5f, T + h() * 0.22f, x + 0.5f, B - h() * 0.22f, dividerPaint);
        }

        // 选中段：首段左半圆角、末段右半圆角、中间段直角
        float x1 = L + segW * selected;
        float x2 = x1 + segW;
        boolean first = selected == 0;
        boolean last = selected == segments - 1;
        activePath.reset();
        activePath.moveTo(first ? L + rad : x1, T);
        activePath.lineTo(last ? R - rad : x2, T);
        if (last) {
            activePath.arcTo(new RectF(R - 2 * rad, T, R, T + 2 * rad), 270, 90);
            activePath.lineTo(R, B - rad);
            activePath.arcTo(new RectF(R - 2 * rad, B - 2 * rad, R, B), 0, 90);
        } else {
            activePath.lineTo(x2, B);
        }
        activePath.lineTo(first ? L + rad : x1, B);
        if (first) {
            activePath.arcTo(new RectF(L, B - 2 * rad, L + 2 * rad, B), 90, 90);
            activePath.lineTo(L, T + rad);
            activePath.arcTo(new RectF(L, T, L + 2 * rad, T + 2 * rad), 180, 90);
        } else {
            activePath.lineTo(x1, T);
        }
        activePath.close();
        canvas.drawPath(activePath, activePaint);

        // 统一字号：取能让所有标签放进各自段宽的最小值
        float textSize = h() * 0.34f;
        textPaint.setTextSize(textSize);
        for (String s : labels) {
            float need = textPaint.measureText(s);
            if (need > segW * 0.88f) textSize = Math.min(textSize, textSize * (segW * 0.88f) / need);
        }
        textPaint.setTextSize(textSize);
        activeTextPaint.setTextSize(textSize);
        float cy = T + h() / 2 - (textPaint.descent() + textPaint.ascent()) / 2;
        for (int i = 0; i < segments; i++) {
            float cx = L + segW * (i + 0.5f);
            canvas.drawText(labels[i], cx, cy, i == selected ? activeTextPaint : textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float L = getPaddingLeft();
            float w = getWidth() - getPaddingRight() - L;
            int pos = (int) ((event.getX() - L) / (w / segments));
            pos = Math.max(0, Math.min(segments - 1, pos));
            if (pos != selected) {
                selected = pos;
                invalidate();
                if (listener != null) listener.onSelectionChanged(selected);
            }
            return true;
        }
        return true;
    }
}
