package com.miao.helper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

/**
 * 合成台：输入两种语言的内容（如中文+英文、中文+日语、普通话+方言），
 * 以所选人设的语气合成为一句融合两种语言的话。
 * 默认先给本地词库合并结果（秒出），配置了 API Key 则自动再走一次在线合成覆盖为更自然的结果。
 */
public class StyleLabActivity extends AppCompatActivity {

    private EditText etA, etB;
    private Spinner spPersona;
    private LinearLayout resultBox;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String[] personaNames;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_style_lab);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        etA = findViewById(R.id.etA);
        etB = findViewById(R.id.etB);
        spPersona = findViewById(R.id.spPersona);
        resultBox = findViewById(R.id.resultBox);
        MaterialButton btnSynthesize = findViewById(R.id.btnSynthesize);

        personaNames = StyleManager.personaNames();
        spPersona.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, personaNames));
        // 默认选中当前全局人设
        spPersona.setSelection(Math.min(Math.max(Prefs.styleIndex(), 0), personaNames.length - 1));

        btnSynthesize.setOnClickListener(v -> synthesize());
    }

    private void synthesize() {
        final String a = etA.getText().toString().trim();
        final String b = etB.getText().toString().trim();
        if (a.isEmpty() || b.isEmpty()) {
            Toast.makeText(this, "请填写两种语言的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        int idx = Math.max(spPersona.getSelectedItemPosition(), 0);
        final String name = personaNames[idx];
        final String styleKey = "p" + idx;

        // 1) 本地词库先出（秒出、不耗 token）：两段拼一起用该人设规则转换
        String local;
        try {
            local = MiaoifyEngine.miaoify(a + " " + b, styleKey);
        } catch (Throwable t) {
            local = a + " " + b;
        }
        final ResultCard card = addResultCard(name, local);

        // 2) 配置了 API 则走在线合成，覆盖为更自然的结果
        final String key = Prefs.apiKey();
        if (key == null || key.trim().isEmpty()) {
            card.setNote("本地合并结果（未配置 API Key）");
            return;
        }
        card.setNote("本地合并 · 在线合成中…");
        card.setBusy(true);
        String prompt = StyleManager.buildPrompt(idx);
        ApiMiaoifier.synthesize(a, b, key, prompt, new ApiMiaoifier.Callback() {
            @Override public void onSuccess(String out) {
                main.post(() -> {
                    card.setResult(out);
                    card.setNote("API 合成");
                    card.setBusy(false);
                    AppLog.i("Lab", "合成成功 人设=" + name + " 结果=" + out);
                });
            }
            @Override public void onError(String msg) {
                main.post(() -> {
                    card.setNote("本地合并结果（API 失败：" + msg + "）");
                    card.setBusy(false);
                    Toast.makeText(StyleLabActivity.this, "在线合成失败，保留本地结果", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** 一张结果卡片：人设名 + 复制 + 重新合成 + 正文 + 说明 */
    private ResultCard addResultCard(String personaName, String localResult) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(0xFFFFF3E0);
        card.setStrokeColor(0xFFFFCC80);
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(10);
        card.setLayoutParams(clp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvName = new TextView(this);
        tvName.setText(personaName);
        tvName.setTextColor(0xFFE65100);
        tvName.setTextSize(14);
        tvName.setTypeface(tvName.getTypeface(), android.graphics.Typeface.BOLD);
        head.addView(tvName, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton btnCopy = smallButton("复制");
        MaterialButton btnAgain = smallButton("重新合成");
        head.addView(btnCopy);
        head.addView(btnAgain);

        TextView tvResult = new TextView(this);
        tvResult.setText(localResult);
        tvResult.setTextColor(0xFF3E2723);
        tvResult.setTextSize(15);
        tvResult.setPadding(0, dp(8), 0, 0);

        TextView tvNote = new TextView(this);
        tvNote.setTextColor(0xFFA1887F);
        tvNote.setTextSize(11);
        tvNote.setPadding(0, dp(4), 0, 0);

        box.addView(head);
        box.addView(tvResult);
        box.addView(tvNote);
        card.addView(box);
        resultBox.addView(card);

        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("miao", tvResult.getText().toString()));
            Toast.makeText(this, "已复制「" + personaName + "」合成结果", Toast.LENGTH_SHORT).show();
        });
        btnAgain.setOnClickListener(v -> synthesize());

        return new ResultCard(tvResult, tvNote);
    }

    private MaterialButton smallButton(String text) {
        MaterialButton b = new MaterialButton(this);
        b.setText(text);
        b.setTextSize(11);
        b.setMinWidth(dp(52));
        b.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    /** 结果卡片的轻量句柄 */
    private static final class ResultCard {
        final TextView result, note;
        ResultCard(TextView result, TextView note) { this.result = result; this.note = note; }
        void setResult(String s) { result.setText(s); }
        void setNote(String s) { note.setText(s); }
        void setBusy(boolean busy) { result.setAlpha(busy ? 0.5f : 1f); }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
