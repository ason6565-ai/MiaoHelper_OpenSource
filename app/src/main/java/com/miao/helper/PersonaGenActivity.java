package com.miao.helper;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.List;

/** AI 自动生成人设页面 */
public class PersonaGenActivity extends AppCompatActivity {
    private EditText etDesc, etName, etPrompt;
    private MaterialButton btnGenerate, btnSave;
    private TextView tvSavedList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_persona_gen);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        etDesc = findViewById(R.id.etDesc);
        etName = findViewById(R.id.etName);
        etPrompt = findViewById(R.id.etPrompt);
        btnGenerate = findViewById(R.id.btnGenerate);
        btnSave = findViewById(R.id.btnSave);
        MaterialButton btnPreview = findViewById(R.id.btnPreview);
        tvSavedList = findViewById(R.id.tvSavedList);

        btnGenerate.setOnClickListener(v -> generate());
        btnSave.setOnClickListener(v -> save());
        // P1-1-3 人设效果预览：用当前编辑的 prompt 试译
        btnPreview.setOnClickListener(v -> {
            String prompt = etPrompt.getText().toString().trim();
            if (prompt.isEmpty()) {
                Toast.makeText(this, getString(R.string.pg_need_prompt), Toast.LENGTH_SHORT).show();
                return;
            }
            String name = etName.getText().toString().trim();
            PersonaPreviewDialog.showWithPrompt(this, name.isEmpty() ? getString(R.string.pg_custom) : name, prompt);
        });

        refreshSavedList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSavedList();
    }

    private void generate() {
        String desc = etDesc.getText().toString().trim();
        if (desc.isEmpty()) {
            Toast.makeText(this, getString(R.string.pg_need_desc), Toast.LENGTH_SHORT).show();
            return;
        }
        String key = Prefs.apiKey();
        if (key == null || key.isEmpty()) {
            Toast.makeText(this, getString(R.string.pg_need_key), Toast.LENGTH_SHORT).show();
            return;
        }
        btnGenerate.setEnabled(false);
        btnGenerate.setText(getString(R.string.pg_generating));
        ApiMiaoifier.generatePersona(desc, key, new ApiMiaoifier.Callback() {
            @Override
            public void onSuccess(String text) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    btnGenerate.setEnabled(true);
                    btnGenerate.setText(getString(R.string.pg_generate));
                    // 解析生成结果：提取人设名称和人设描述（标记随界面语言 zh/en/ja/ko）
                    String name = "";
                    String prompt = text;
                    String lang = Prefs.language();
                    String nameMarker = ApiMiaoifier.personaNameMarker(lang);
                    String descMarker = ApiMiaoifier.personaDescMarker(lang);
                    int nameIdx = text.indexOf("【" + nameMarker + "】");
                    int descIdx = text.indexOf("【" + descMarker + "】");
                    if (nameIdx < 0) nameIdx = text.indexOf(nameMarker + ":");
                    if (descIdx < 0) descIdx = text.indexOf(descMarker + ":");
                    if (nameIdx >= 0 && descIdx > nameIdx) {
                        name = text.substring(nameIdx + nameMarker.length() + 2, descIdx).trim();
                        // 去掉可能的换行
                        name = name.replaceAll("[\\r\\n]+", " ").trim();
                        prompt = text.substring(descIdx + descMarker.length() + 2).trim();
                    }
                    etName.setText(name.isEmpty() ? getString(R.string.pg_custom) : name);
                    etPrompt.setText(prompt);
                    Toast.makeText(PersonaGenActivity.this, getString(R.string.pg_done), Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    btnGenerate.setEnabled(true);
                    btnGenerate.setText(getString(R.string.pg_generate));
                    Toast.makeText(PersonaGenActivity.this, getString(R.string.pg_fail_fmt, msg), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void save() {
        String name = etName.getText().toString().trim();
        String prompt = etPrompt.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, getString(R.string.pg_need_name), Toast.LENGTH_SHORT).show();
            return;
        }
        if (prompt.isEmpty()) {
            Toast.makeText(this, getString(R.string.pg_need_prompt), Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.addCustomPersona(name, prompt);
        Toast.makeText(this, getString(R.string.pg_saved_fmt, name), Toast.LENGTH_LONG).show();
        refreshSavedList();
    }

    private void refreshSavedList() {
        List<String[]> list = Prefs.customPersonas();
        if (list.isEmpty()) {
            tvSavedList.setText(getString(R.string.pg_none));
        } else {
            StringBuilder sb = new StringBuilder(getString(R.string.pg_list_prefix));
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(list.get(i)[0]);
            }
            tvSavedList.setText(sb.toString());
        }
    }
}
