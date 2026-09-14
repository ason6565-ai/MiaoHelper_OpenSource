package com.miao.helper;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 风格设置单独页面：人设/自定义 Prompt + 自定义人设管理（删除进回收站，24 小时内可恢复） */
public class StyleSettingsActivity extends AppCompatActivity {
    private Spinner spStyle;
    private EditText etCustom;
    private LinearLayout customList, trashList;
    private TextView tvTrashTitle;
    private boolean syncing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_style_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        spStyle = findViewById(R.id.spStyle);
        etCustom = findViewById(R.id.etCustom);
        // v5.0 本地前置打底开关（默认关，用户自调；仅读 AI 扩展词库，不跑内置预设）
        androidx.appcompat.widget.SwitchCompat swLocal = findViewById(R.id.swLocalPreStyle);
        swLocal.setChecked(Prefs.localPreStyle());
        swLocal.setOnCheckedChangeListener((btn, isChecked) -> {
            Prefs.set("localPreStyle", isChecked);
            android.widget.Toast.makeText(this,
                    isChecked ? "已开启：AI 翻译前用 AI 扩展词打底" : "已关闭：不打底，直接交给 AI",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        com.google.android.material.button.MaterialButton btnPreviewStyle = findViewById(R.id.btnPreviewStyle);
        // P1-1-3 人设效果预览：可切换人设对比
        btnPreviewStyle.setOnClickListener(v -> {
            int idx = Math.max(spStyle.getSelectedItemPosition(), 0);
            PersonaPreviewDialog.showWithIndex(this, idx);
        });
        customList = findViewById(R.id.customPersonaList);
        trashList = findViewById(R.id.trashPersonaList);
        tvTrashTitle = findViewById(R.id.tvTrashTitle);

        // 人设下拉
        ArrayAdapter<String> styleAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, StyleManager.personaNames());
        spStyle.setAdapter(styleAdapter);

        // 载入当前设置
        syncing = true;
        spStyle.setSelection(Math.min(Prefs.styleIndex(), StyleManager.personaCount() - 1));
        etCustom.setText(Prefs.customPrompt());
        syncing = false;

        // 人设选择：选了人设就关方言
        spStyle.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                if (syncing) return;
                Prefs.set("styleIndex", pos);
                MiaoService s = MiaoService.get();
                if (s != null) s.updateFloatStatus();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        // 自定义 prompt
        etCustom.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                String cur = etCustom.getText().toString();
                if (!cur.equals(Prefs.customPrompt())) Prefs.set("customPrompt", cur);
            }
        });

        refreshPersonaMgmt();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次进入重新同步 Spinner 选中项：防止悬浮球菜单切换后此处显示未刷新，
        // 也防止外部窗口变化导致风格被覆盖后用户无感知
        syncing = true;
        spStyle.setSelection(Math.min(Prefs.styleIndex(), StyleManager.personaCount() - 1));
        syncing = false;
        refreshPersonaMgmt();
    }

    /** P0-4-1 人设列表增删后重建下拉 adapter 并同步选中：防止索引错位导致自定义风格失效 */
    private void refreshStyleSpinner() {
        try {
            ArrayAdapter<String> a = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, StyleManager.personaNames());
            spStyle.setAdapter(a);
            int idx = Math.min(Math.max(Prefs.styleIndex(), 0), StyleManager.personaCount() - 1);
            if (spStyle.getSelectedItemPosition() != idx) spStyle.setSelection(idx);
        } catch (Throwable ignored) {}
    }

    /** 重建自定义人设管理列表 + 回收站列表 */
    private void refreshPersonaMgmt() {
        // 自定义人设列表：每行 = 名称 + 删除按钮（删除进回收站）
        customList.removeAllViews();
        List<String[]> custom = Prefs.customPersonas();
        for (int i = 0; i < custom.size(); i++) {
            final int idx = i;
            final String name = (custom.get(i)[0] == null || custom.get(i)[0].isEmpty())
                    ? "未命名" : custom.get(i)[0];
            LinearLayout row = makeRow(name, "删除", v -> {
                Prefs.removeCustomPersona(idx);
                Toast.makeText(this, "已删除「" + name + "」，24 小时内可在下方恢复", Toast.LENGTH_SHORT).show();
                refreshPersonaMgmt();
                refreshStyleSpinner();
            });
            customList.addView(row);
        }
        if (custom.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无自定义人设（可在「AI 生成人设」或「词库管理」添加）");
            empty.setTextSize(12);
            empty.setTextColor(0xFFA1887F);
            empty.setPadding(0, 4, 0, 0);
            customList.addView(empty);
        }

        // 回收站列表：每行 = 名称 + 恢复按钮（仅保留 24 小时内删除的）
        trashList.removeAllViews();
        List<String[]> trash = Prefs.trashPersonas();
        tvTrashTitle.setVisibility(trash.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        for (int i = 0; i < trash.size(); i++) {
            final int idx = i;
            String[] e = trash.get(i);
            String name = (e[0] == null || e[0].isEmpty()) ? "未命名" : e[0];
            String when = "";
            try {
                long ts = Long.parseLong(e[2]);
                long diff = System.currentTimeMillis() - ts;
                if (diff < 3600_000) when = "（" + (diff / 60_000) + " 分钟前）";
                else when = "（" + (diff / 3600_000) + " 小时前）";
            } catch (Exception ignored) {}
            LinearLayout row = makeRow(name + when, "恢复", v -> {
                if (Prefs.restoreTrashPersona(idx)) {
                    Toast.makeText(this, "已恢复「" + name + "」", Toast.LENGTH_SHORT).show();
                    refreshPersonaMgmt();
                    refreshStyleSpinner();
                } else {
                    Toast.makeText(this, "已超过 24 小时无法恢复", Toast.LENGTH_SHORT).show();
                    refreshPersonaMgmt();
                }
            });
            trashList.addView(row);
        }
    }

    /** 构建一行：左名称(可伸缩) + 右按钮 */
    private LinearLayout makeRow(String text, String btnText, android.view.View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 4, 0, 4);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(0xFF3E2723);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(tl);
        row.addView(tv);

        MaterialButton btn = new MaterialButton(this);
        btn.setText(btnText);
        btn.setTextSize(12);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.leftMargin = 12;
        btn.setLayoutParams(bl);
        btn.setOnClickListener(onClick);
        row.addView(btn);
        return row;
    }
}
