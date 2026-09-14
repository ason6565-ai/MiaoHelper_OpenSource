package com.miao.helper;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

/**
 * P1-1-3 人设效果预览：
 *  - 输入测试文本 → 用指定人设 prompt 走 API 翻译（无 Key 时给本地提示）
 *  - 结果显示在对话框内，可反复改文本/换人设重试
 *  - 对比模式：下拉切换人设，译文随之刷新，方便对比不同人设效果
 */
public final class PersonaPreviewDialog {

    private PersonaPreviewDialog() {}

    /** 用完整 prompt 预览（人设生成页：prompt 由用户/AI 现场填写） */
    public static void showWithPrompt(Context ctx, String personaName, String prompt) {
        show(ctx, personaName, prompt, null);
    }

    /** 用现有人设索引预览（模板/风格设置页：选下拉人设，切索引对比） */
    public static void showWithIndex(Context ctx, int styleIndex) {
        String name = StyleManager.personaName(styleIndex);
        String prompt = StyleManager.buildPrompt(styleIndex);
        show(ctx, name, prompt, styleIndex);
    }

    private static void show(Context ctx, final String personaName, final String basePrompt, final Integer fixedIndex) {
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(ctx, 18), dp(ctx, 8), dp(ctx, 18), dp(ctx, 8));

        // 人设选择：固定索引则不显示下拉（生成页场景），否则下拉可切换对比
        final android.widget.Spinner sp = new android.widget.Spinner(ctx);
        final String[] names = StyleManager.personaNames();
        final int[] selIdx = {fixedIndex == null ? Math.min(Math.max(Prefs.styleIndex(), 0), names.length - 1) : fixedIndex};
        if (fixedIndex == null) {
            sp.setAdapter(new android.widget.ArrayAdapter<>(ctx,
                    android.R.layout.simple_spinner_dropdown_item, names));
            sp.setSelection(selIdx[0]);
            body.addView(sp);
        }

        EditText etTest = new EditText(ctx);
        etTest.setHint("输入一句话，看看效果");
        etTest.setSingleLine(false);
        etTest.setMinLines(2);
        etTest.setTextSize(14);
        body.addView(etTest);

        MaterialButton btnGo = new MaterialButton(ctx);
        btnGo.setText("试译");
        btnGo.setTextSize(12);
        body.addView(btnGo);

        final TextView tvResult = new TextView(ctx);
        tvResult.setTextColor(0xFF3E2723);
        tvResult.setTextSize(14);
        tvResult.setPadding(0, dp(ctx, 10), 0, 0);
        body.addView(tvResult);

        ScrollView sv = new ScrollView(ctx);
        sv.addView(body);

        final Handler main = new Handler(Looper.getMainLooper());
        final AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle(personaName + " · 效果预览")
                .setView(sv)
                .setNegativeButton("关闭", null)
                .show();

        btnGo.setOnClickListener(v -> {
            final String text = etTest.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(ctx, "先输入一句话", Toast.LENGTH_SHORT).show();
                return;
            }
            final String key = Prefs.apiKey();
            if (key == null || key.trim().isEmpty()) {
                tvResult.setText("未配置 API Key：无法预览。\n请先在主界面配置 API Key 后重试。");
                return;
            }
            btnGo.setEnabled(false);
            btnGo.setText("试译中…");
            // 确定实际使用的 prompt：下拉切换时用所选人设的 buildPrompt；生成页固定用传入的 prompt
            final int useIdx = fixedIndex == null ? Math.max(sp.getSelectedItemPosition(), 0) : fixedIndex;
            final String prompt = fixedIndex == null ? StyleManager.buildPrompt(useIdx) : basePrompt;
            final String displayName = fixedIndex == null ? names[useIdx] : personaName;
            ApiMiaoifier.miaoify(text, key, prompt, StyleManager.fewShotExamples(), false, new ApiMiaoifier.Callback() {
                @Override public void onSuccess(String out) {
                    main.post(() -> {
                        btnGo.setEnabled(true);
                        btnGo.setText("试译");
                        tvResult.setText("【" + displayName + "】\n" + out);
                    });
                }
                @Override public void onError(String msg) {
                    main.post(() -> {
                        btnGo.setEnabled(true);
                        btnGo.setText("试译");
                        tvResult.setText("试译失败：" + msg);
                    });
                }
            });
        });
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
