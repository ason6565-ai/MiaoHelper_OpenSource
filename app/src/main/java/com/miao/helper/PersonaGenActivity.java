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
                Toast.makeText(this, "请先生成或填写人设 prompt", Toast.LENGTH_SHORT).show();
                return;
            }
            String name = etName.getText().toString().trim();
            PersonaPreviewDialog.showWithPrompt(this, name.isEmpty() ? "自定义人设" : name, prompt);
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
            Toast.makeText(this, "请先输入人设描述", Toast.LENGTH_SHORT).show();
            return;
        }
        String key = Prefs.apiKey();
        if (key == null || key.isEmpty()) {
            Toast.makeText(this, "请先在主界面配置 API Key", Toast.LENGTH_SHORT).show();
            return;
        }
        btnGenerate.setEnabled(false);
        btnGenerate.setText("生成中…");
        ApiMiaoifier.generatePersona(desc, key, new ApiMiaoifier.Callback() {
            @Override
            public void onSuccess(String text) {
                runOnUiThread(() -> {
                    btnGenerate.setEnabled(true);
                    btnGenerate.setText("生成人设");
                    // 解析生成结果：提取【人设名称】和【人设描述】
                    String name = "";
                    String prompt = text;
                    int nameIdx = text.indexOf("【人设名称】");
                    int descIdx = text.indexOf("【人设描述】");
                    if (nameIdx >= 0 && descIdx > nameIdx) {
                        name = text.substring(nameIdx + 6, descIdx).trim();
                        // 去掉可能的换行
                        name = name.replaceAll("[\\r\\n]+", " ").trim();
                        prompt = text.substring(descIdx + 6).trim();
                    }
                    etName.setText(name.isEmpty() ? "自定义人设" : name);
                    etPrompt.setText(prompt);
                    Toast.makeText(PersonaGenActivity.this, "生成成功！可以修改后保存", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    btnGenerate.setEnabled(true);
                    btnGenerate.setText("生成人设");
                    Toast.makeText(PersonaGenActivity.this, "生成失败：" + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void save() {
        String name = etName.getText().toString().trim();
        String prompt = etPrompt.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "请填写人设名称", Toast.LENGTH_SHORT).show();
            return;
        }
        if (prompt.isEmpty()) {
            Toast.makeText(this, "请先生成或填写人设 prompt", Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.addCustomPersona(name, prompt);
        Toast.makeText(this, "已保存「" + name + "」，返回主界面即可在风格列表中选择", Toast.LENGTH_LONG).show();
        refreshSavedList();
    }

    private void refreshSavedList() {
        List<String[]> list = Prefs.customPersonas();
        if (list.isEmpty()) {
            tvSavedList.setText("已保存的自定义人设：无");
        } else {
            StringBuilder sb = new StringBuilder("已保存的自定义人设：");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(list.get(i)[0]);
            }
            tvSavedList.setText(sb.toString());
        }
    }
}
