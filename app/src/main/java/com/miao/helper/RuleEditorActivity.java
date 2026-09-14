package com.miao.helper;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 本地词库管理：添加/删除自定义替换规则；首次进入播种示例规则用于教学。 */
public class RuleEditorActivity extends AppCompatActivity {

    /** P2-30：导入备份（读全文 + JSON 解析 + 逐风格写 SP）放后台线程，避免大文件在主线程 ANR。 */
    private static final ExecutorService BACKUP_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "miao-backup");
        t.setDaemon(true);
        return t;
    });
    /**
     * 内置示例规则，教用户两种用法：
     *  - 普通替换：输入里出现「原文」就整体替换为「替换为」；
     *  - 正则替换：添加时勾选「正则」后按正则匹配。
     * 注意：SafeRegex 对替换文做了 quoteReplacement，不支持 $1 反向引用，故正则示例的替换文均为纯字面。
     */
    private static final String[][] SAMPLE_RULES = {
            // —— 普通替换 ——
            {"哈哈哈", "嘿嘿嘿", "0"},
            {"好的", "好哒", "0"},
            {"拜拜", "拜拜啦", "0"},
            {"哇", "哇呜", "0"},
            {"没问题", "没问题哒", "0"},
            {"早上好", "早上好呀", "0"},
            {"666", "好厉害呀", "0"},
            {"绝绝子", "好厉害", "0"},
            // —— 正则替换（勾选「正则」）——
            {"\\d{4,}", "一大串数字", "1"},   // 4 位及以上连续数字
            {" {2,}", " ", "1"},             // 多个连续空格压成一个
            {"!{3,}|！{3,}", "！", "1"},     // 3 个以上连续感叹号收敛为一个
    };

    private List<String[]> rules;
    private String currentStyle;
    private String currentStyleId;
    private RuleAdapter adapter;
    private TextView tvEmpty;
    private com.google.android.material.switchmaterial.SwitchMaterial swLexicon;
    private TextView tvLexiconInfo;
    private com.google.android.material.button.MaterialButton btnLexiconExpand;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_rule_editor);

        Intent it = getIntent();
        String target = (it != null) ? it.getStringExtra("style") : null;
        currentStyle = (target == null || target.trim().isEmpty()) ? StyleManager.currentName() : target.trim();
        currentStyleId = StyleManager.idOfName(currentStyle);
        if (currentStyleId == null || currentStyleId.isEmpty()) currentStyleId = StyleManager.currentId();
        rules = new ArrayList<>(Prefs.customRulesFor(currentStyle));
        TextView tvStyle = findViewById(R.id.tvStyleLabel);
        tvStyle.setText("当前人设：" + currentStyle + "（点击切换）");
        tvStyle.setOnClickListener(v -> showPersonaPicker());
        seedSamplesIfFirst();

        adapter = new RuleAdapter();
        tvEmpty = findViewById(R.id.tvEmpty);
        ListView lv = findViewById(R.id.lvRules);
        lv.setAdapter(adapter);

        findViewById(R.id.btnAdd).setOnClickListener(v -> showAddDialog());
        findViewById(R.id.btnSample).setOnClickListener(v -> loadSamples());
        findViewById(R.id.btnClear).setOnClickListener(v -> confirmClear());

        // AI 扩展词库
        swLexicon = findViewById(R.id.swLexicon);
        tvLexiconInfo = findViewById(R.id.tvLexiconInfo);
        btnLexiconExpand = findViewById(R.id.btnLexiconExpand);
        swLexicon.setChecked(Prefs.apiLexiconEnabled());
        swLexicon.setOnCheckedChangeListener((v, on) -> {
            Prefs.setApiLexiconEnabled(on);
            refreshLexicon();
        });
        btnLexiconExpand.setOnClickListener(v -> expandLexicon());
        findViewById(R.id.btnLexiconClear).setOnClickListener(v -> clearLexicon());

        // 备份导出 / 导入
        findViewById(R.id.btnBackupExport).setOnClickListener(v -> exportBackup());
        findViewById(R.id.btnBackupImport).setOnClickListener(v -> importBackup());

        // v4.8-⑥ 词库查看入口：点击词库信息行弹出完整词条列表（原文→变体），可检查 AI 扩展了哪些词
        tvLexiconInfo.setOnClickListener(v -> showLexiconDialog());
        refreshLexicon();
        refreshEmpty();
    }

    /** 人设列表：列出全部人设（内置 + 自定义 + AI 生成），点击进入该人设的自定义词库界面 */
    private void showPersonaPicker() {
        final String[] names = StyleManager.personaNames();
        new AlertDialog.Builder(this)
                .setTitle("选择人设（词库按人设独立）")
                .setItems(names, (d, w) -> {
                    String picked = names[w];
                    if (picked.equals(currentStyle)) return;
                    currentStyle = picked;
                    currentStyleId = StyleManager.personaId(w);
                    rules = new ArrayList<>(Prefs.customRulesFor(currentStyle));
                    TextView tvStyle = findViewById(R.id.tvStyleLabel);
                    tvStyle.setText("当前人设：" + currentStyle + "（点击切换）");
                    adapter.notifyDataSetChanged();
                    refreshEmpty();
                    refreshLexicon();
                    Toast.makeText(RuleEditorActivity.this, "已切换人设：" + currentStyle, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 导出备份：规则 + AI 扩展词库 + 自定义人设 → 系统保存对话框（txt） */
    private void exportBackup() {
        String json = BackupManager.buildBackup();
        if (json == null) {
            Toast.makeText(this, "生成备份失败", Toast.LENGTH_SHORT).show();
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date());
        createDocLauncher.launch("拟言助手备份_" + stamp + ".txt");
    }

    private final ActivityResultLauncher<String> createDocLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
                if (uri == null) {
                    Toast.makeText(this, "已取消导出", Toast.LENGTH_SHORT).show();
                    return;
                }
                String json = BackupManager.buildBackup();
                if (json == null) {
                    Toast.makeText(this, "生成备份失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        os.write(json.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        Toast.makeText(this, "✅ 备份已导出（规则/扩展词库/人设）", Toast.LENGTH_LONG).show();
                        return;
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, "导出失败：无法写入", Toast.LENGTH_SHORT).show();
            });

    /** 导入备份：系统文件选择器 → 解析并全量覆盖 */
    private void importBackup() {
        openDocLauncher.launch(new String[]{"text/plain", "application/json", "text/*", "application/octet-stream"});
    }

    private final ActivityResultLauncher<String[]> openDocLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                // 弹确认：导入会覆盖现有规则/词库/人设
                new AlertDialog.Builder(this)
                        .setTitle("导入备份")
                        .setMessage("导入将覆盖当前的：\n• 自定义替换规则\n• AI 扩展词库\n• 自定义人设\n\n确认继续？")
                        .setPositiveButton("导入", (d, w) -> {
                            Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show();
                            // P2-30：readUri + applyBackup 全部移到后台线程，完成后回主线程更新 UI
                            BACKUP_POOL.execute(() -> {
                                String content = readUri(uri);
                                final String err = content == null ? "读取文件失败" : BackupManager.applyBackup(content);
                                runOnUiThread(() -> {
                                    if (err != null) {
                                        Toast.makeText(this, "导入失败：" + err, Toast.LENGTH_LONG).show();
                                        return;
                                    }
                                    rules = new ArrayList<>(Prefs.customRulesFor(currentStyle));
                                    TextView tvStyle = findViewById(R.id.tvStyleLabel);
                                    tvStyle.setText("当前人设：" + currentStyle + "（点击切换）");
                                    adapter.notifyDataSetChanged();
                                    refreshEmpty();
                                    refreshLexicon();
                                    Toast.makeText(this, "✅ 备份已导入", Toast.LENGTH_LONG).show();
                                });
                            });
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });

    /** 读取 Uri 内容为 UTF-8 字符串 */
    private String readUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 刷新扩展词库信息显示（按人设独立存储） */
    private void refreshLexicon() {
        if (swLexicon == null || tvLexiconInfo == null) return;
        boolean enabled = Prefs.apiLexiconEnabled();
        swLexicon.setChecked(enabled);
        int[] stats = ApiLexiconExpander.statsFor(currentStyleId);
        int wordCount = stats[0];
        int variantCount = stats[1];
        if (wordCount == 0) {
            tvLexiconInfo.setText("未扩展：点击下方按钮，用 API 结合当前人设为 100+ 高频词生成多个替换变体");
            btnLexiconExpand.setText("AI 扩展当前人设");
            return;
        }
        if (enabled) {
            tvLexiconInfo.setText("已启用：" + wordCount + " 词 / " + variantCount + " 变体（人设：" + currentStyle + "）");
        } else {
            tvLexiconInfo.setText("已扩展但未启用：" + wordCount + " 词 / " + variantCount + " 变体（人设：" + currentStyle + "），打开开关后生效");
        }
        btnLexiconExpand.setText("增量扩展当前人设（新词不覆盖已有）");
    }

    /** v4.8-⑥ 词库查看：弹出当前人设 AI 扩展词条（原文→变体）Dialog，便于核对与检查 */
    private void showLexiconDialog() {
        java.util.Map<String, String[]> lex = ApiLexiconExpander.getLexiconFor(currentStyleId);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        if (lex != null && !lex.isEmpty()) {
            int total = lex.size();
            int limit = Math.min(total, 200);   // 词条过多时只展示前 200 条，避免 Dialog 卡顿
            for (java.util.Map.Entry<String, String[]> e : lex.entrySet()) {
                if (shown >= limit) break;
                String[] vars = e.getValue();
                sb.append(e.getKey()).append(" → ");
                if (vars != null && vars.length > 0) {
                    for (int i = 0; i < Math.min(vars.length, 4); i++) {  // 每个词最多展示 4 个变体
                        if (i > 0) sb.append(" / ");
                        sb.append(vars[i]);
                    }
                    if (vars.length > 4) sb.append(" …共").append(vars.length).append("变体");
                }
                sb.append("\n");
                shown++;
            }
            if (shown < total) sb.append("\n…共 ").append(total).append(" 词，仅展示前 ").append(shown).append(" 条");
        } else {
            sb.append("该人设还没有 AI 扩展词条。\n\n点击「AI 扩展当前人设」生成后，可再次点击此处查看。");
        }
        new AlertDialog.Builder(this)
                .setTitle("扩展词库（" + currentStyle + "）")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 调用 API 扩展当前人设词库 */
    private void expandLexicon() {
        btnLexiconExpand.setEnabled(false);
        btnLexiconExpand.setText("扩展中…");
        ApiLexiconExpander.expandFor(currentStyleId, new ApiLexiconExpander.ExpandCallback() {
            @Override
            public void onProgress(int batch, int totalBatches, int wordCount) {
                runOnUiThread(() -> btnLexiconExpand.setText("扩展中 " + batch + "/" + totalBatches + "（" + wordCount + "词）"));
            }
            @Override
            public void onSuccess(int wordCount, int variantCount, String styleName) {
                runOnUiThread(() -> {
                    btnLexiconExpand.setEnabled(true);
                    btnLexiconExpand.setText("AI 扩展词库");
                    refreshLexicon();
                    Toast.makeText(RuleEditorActivity.this,
                            "扩展完成：" + wordCount + " 词 / " + variantCount + " 变体（人设：" + styleName + "），已自动启用",
                            Toast.LENGTH_LONG).show();
                });
            }
            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    btnLexiconExpand.setEnabled(true);
                    btnLexiconExpand.setText("AI 扩展词库");
                    refreshLexicon();
                    Toast.makeText(RuleEditorActivity.this, "扩展失败：" + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** 清除当前人设的扩展词库 */
    private void clearLexicon() {
        if (!ApiLexiconExpander.hasFor(currentStyleId)) {
            Toast.makeText(this, "该人设的扩展词库已是空的", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清除 AI 扩展词库")
                .setMessage("确定清除所有 AI 扩展词吗？清除后需重新扩展。")
                .setPositiveButton("清除", (d, w) -> {
                    ApiLexiconExpander.clearFor(currentStyleId);
                    refreshLexicon();
                    Toast.makeText(this, "已清除扩展词库", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 首次进入：用户还没有任何规则时自动填入示例用于教学，只播种一次。 */
    private void seedSamplesIfFirst() {
        if (Prefs.rulesExampleSeeded()) return;
        if (rules.isEmpty()) {
            for (String[] s : SAMPLE_RULES) rules.add(new String[]{s[0], s[1], s[2]});
            Prefs.setCustomRulesFor(currentStyle, rules);
            Toast.makeText(this, "已填入示例规则", Toast.LENGTH_LONG).show();
        }
        Prefs.setRulesExampleSeeded(true);
    }

    /** 手动载入示例：把尚不存在的示例追加进去（按「原文」去重）。 */
    private void loadSamples() {
        Set<String> exist = new HashSet<>();
        for (String[] r : rules) exist.add(r[0].trim());
        int add = 0;
        for (String[] s : SAMPLE_RULES) {
            if (!exist.contains(s[0])) {
                rules.add(new String[]{s[0], s[1], s[2]});
                add++;
            }
        }
        if (add == 0) {
            Toast.makeText(this, "示例规则都已在列表中", Toast.LENGTH_SHORT).show();
            return;
        }
        saveAndRefresh();
        Toast.makeText(this, "已追加 " + add + " 条示例规则", Toast.LENGTH_SHORT).show();
    }

    private void confirmClear() {
        if (rules.isEmpty()) {
            Toast.makeText(this, "列表已经是空的", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空全部规则")
                .setMessage("确定删除全部自定义规则吗？可随时点「载入示例」恢复教学规则。")
                .setPositiveButton("清空", (d, w) -> {
                    rules.clear();
                    saveAndRefresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 弹出添加规则对话框 */
    private void showAddDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 0);
        final EditText etFrom = new EditText(this);
        etFrom.setHint("原文（要被替换的词）");
        final EditText etTo = new EditText(this);
        etTo.setHint("替换为");
        final CheckBox cbRegex = new CheckBox(this);
        cbRegex.setText("正则表达式（如 \\d+ 匹配数字；替换文不支持 $1）");
        layout.addView(etFrom);
        layout.addView(etTo);
        layout.addView(cbRegex);

        new AlertDialog.Builder(this)
                .setTitle("添加替换规则")
                .setView(layout)
                .setPositiveButton("添加", (d, w) -> {
                    String from = etFrom.getText().toString().trim();
                    String to = etTo.getText().toString();
                    if (from.isEmpty()) {
                        Toast.makeText(this, "原文不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean regex = cbRegex.isChecked();
                    if (regex) {
                        String err = SafeRegex.validate(from);
                        if (err != null) {
                            Toast.makeText(this, "正则语法错误：" + err, Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                    rules.add(new String[]{from, to, regex ? "1" : "0"});
                    saveAndRefresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteRule(int pos) {
        rules.remove(pos);
        saveAndRefresh();
    }

    private void saveAndRefresh() {
        Prefs.setCustomRulesFor(currentStyle, rules);
        adapter.notifyDataSetChanged();
        refreshEmpty();
    }

    private void refreshEmpty() {
        tvEmpty.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---- 列表适配器 ----
    private class RuleAdapter extends BaseAdapter {
        @Override public int getCount() { return rules.size(); }
        @Override public Object getItem(int i) { return rules.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(RuleEditorActivity.this)
                        .inflate(R.layout.item_rule, parent, false);
            }
            String[] r = rules.get(position);
            TextView tv = convertView.findViewById(R.id.tvRule);
            String tag = r.length > 2 && "1".equals(r[2]) ? "  [正则]" : "";
            tv.setText("「" + r[0] + "」  →  「" + r[1] + "」" + tag);
            convertView.findViewById(R.id.btnDelete).setOnClickListener(v -> deleteRule(position));
            return convertView;
        }
    }
}
