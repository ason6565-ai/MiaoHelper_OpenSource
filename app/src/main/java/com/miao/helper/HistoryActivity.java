package com.miao.helper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 翻译历史记录页面 */
public class HistoryActivity extends AppCompatActivity {
    private ListView listView;
    private TextView tvEmpty;
    private List<HistoryManager.Entry> data;          // 全量（最新在前）
    private List<HistoryManager.Entry> shown;         // 过滤后
    private HistoryAdapter adapter;
    private String query = "";
    private boolean favOnly = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        listView = findViewById(R.id.listView);
        tvEmpty = findViewById(R.id.tvEmpty);
        MaterialButton btnClear = findViewById(R.id.btnClear);
        MaterialButton btnExport = findViewById(R.id.btnExport);
        MaterialButton btnFavFilter = findViewById(R.id.btnFavFilter);
        EditText etSearch = findViewById(R.id.etSearch);

        // P2-4 搜索：实时过滤原文/译文/人设
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                query = e.toString().trim();
                applyFilter();
            }
        });

        // P2-4 只看收藏：切换过滤
        btnFavFilter.setOnClickListener(v -> {
            favOnly = !favOnly;
            btnFavFilter.setText(favOnly ? "全部记录" : "只看收藏");
            applyFilter();
        });

        // P2-4 导出：全部历史 → TXT → 下载目录
        btnExport.setOnClickListener(v -> {
            if (data == null || data.isEmpty()) {
                Toast.makeText(this, "没有可导出的记录", Toast.LENGTH_SHORT).show();
                return;
            }
            String msg = HistoryManager.exportToDownloads(this);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });

        btnClear.setOnClickListener(v -> {
            if (data == null || data.isEmpty()) {
                Toast.makeText(this, "没有历史记录", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("清空历史记录")
                    .setMessage("确定要清空全部翻译历史吗？此操作不可恢复。")
                    .setPositiveButton("清空", (d, w) -> {
                        HistoryManager.clear();
                        reload();
                        Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        data = HistoryManager.loadAll();
        applyFilter();
    }

    /** P2-4 按关键词 + 收藏筛选重建列表 */
    private void applyFilter() {
        shown = new java.util.ArrayList<>();
        if (data != null) {
            for (HistoryManager.Entry e : data) {
                if (favOnly && !e.favorite) continue;
                if (!query.isEmpty()) {
                    String q = query.toLowerCase(Locale.getDefault());
                    boolean hit = e.original.toLowerCase(Locale.getDefault()).contains(q)
                            || e.translated.toLowerCase(Locale.getDefault()).contains(q)
                            || e.style.toLowerCase(Locale.getDefault()).contains(q);
                    if (!hit) continue;
                }
                shown.add(e);
            }
        }
        if (adapter == null) {
            adapter = new HistoryAdapter();
            listView.setAdapter(adapter);
        } else {
            adapter.notifyDataSetChanged();
        }
        boolean empty = (shown == null || shown.isEmpty());
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        tvEmpty.setText(empty && data != null && !data.isEmpty()
                ? "没有匹配的记录\n换个关键词或取消收藏筛选试试"
                : "还没有翻译记录\n去别的 App 里输入文字试试吧");
    }

    /** P2-4 详情：完整原文+译文+人设+时间，可复制/收藏 */
    private void showDetailDialog(HistoryManager.Entry e) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(8), dp(18), dp(8));
        TextView st = new TextView(this);
        st.setText(e.style + (e.favorite ? "  ★" : "") + "  ·  " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(e.time)));
        st.setTextColor(0xFF8D6E63);
        st.setTextSize(12);
        col.addView(st);
        TextView o = new TextView(this);
        o.setText("原文：\n" + e.original);
        o.setTextSize(14);
        o.setTextColor(0xFF3E2723);
        o.setPadding(0, dp(8), 0, 0);
        col.addView(o);
        TextView t = new TextView(this);
        t.setText("译文：\n" + e.translated);
        t.setTextSize(15);
        t.setTextColor(0xFF6D4C41);
        t.setPadding(0, dp(8), 0, 0);
        col.addView(t);
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        new AlertDialog.Builder(this)
                .setTitle("记录详情")
                .setView(sv)
                .setPositiveButton("复制译文", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("风格化结果", e.translated));
                    Toast.makeText(this, "已复制译文", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(e.favorite ? "取消收藏" : "收藏", (d, w) -> {
                    HistoryManager.setFavorite(data.indexOf(e), !e.favorite);
                    reload();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 展示 AI 生成的词级替换映射，可编辑后保存为该风格的自定义规则 */
    private void showMappingsDialog(HistoryManager.Entry e) {
        java.util.List<String[]> map = TextDiff.extractMappings(e.original, e.translated);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(8));
        if (map.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("该次替换没有可提取的词级差异。");
            tv.setTextColor(0xFF8D6E63);
            tv.setTextSize(13);
            col.addView(tv);
        } else {
            TextView hint = new TextView(this);
            hint.setText("AI 把原文片段改成了这些词，可直接修改右侧替换词。保存后将写入「" + e.style + "」自定义词库，下次本地引擎按新词生效。");
            hint.setTextColor(0xFF795548);
            hint.setTextSize(12);
            col.addView(hint);
            for (String[] pair : map) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(4), 0, dp(4));
                TextView from = new TextView(this);
                from.setText(pair[0].isEmpty() ? "＋新增" : pair[0]);
                from.setTextColor(pair[0].isEmpty() ? 0xFF4CAF50 : 0xFF8D6E63);
                from.setTextSize(13);
                from.setMaxLines(2);
                LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(from, flp);
                TextView arrow = new TextView(this);
                arrow.setText("→");
                arrow.setTextColor(0xFFA1887F);
                row.addView(arrow);
                EditText to = new EditText(this);
                to.setText(pair[1]);
                to.setTextSize(13);
                to.setSingleLine(true);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tlp.leftMargin = dp(8);
                row.addView(to, tlp);
                col.addView(row);
            }
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        new AlertDialog.Builder(this)
                .setTitle("替换明细 · " + e.style)
                .setView(sv)
                .setPositiveButton("保存为自定义规则", (d, w) -> saveMappings(e.style, col))
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 把映射对话框里编辑后的替换对合并进该风格的自定义词库（同 from 覆盖，其余追加） */
    private void saveMappings(String styleName, LinearLayout col) {
        java.util.List<String[]> rules = new java.util.ArrayList<>();
        for (int i = 0; i < col.getChildCount(); i++) {
            View v = col.getChildAt(i);
            if (!(v instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) v;
            if (row.getChildCount() < 3) continue;
            View fv = row.getChildAt(0);
            View ev = row.getChildAt(2);
            if (!(fv instanceof TextView) || !(ev instanceof EditText)) continue;
            String from = ((TextView) fv).getText().toString().trim();
            String to = ((EditText) ev).getText().toString();
            if (from.isEmpty() || "＋新增".equals(from)) continue;   // 纯新增无原文锚点，不能做规则
            if (from.equals(to.trim())) continue;
            rules.add(new String[]{from, to, "0"});
        }
        if (rules.isEmpty()) {
            Toast.makeText(this, "没有可保存的替换项（纯新增内容无法作为规则保存）", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<String[]> existing = Prefs.customRulesFor(styleName);
        java.util.Map<String, String[]> byFrom = new java.util.LinkedHashMap<>();
        for (String[] r : existing) byFrom.put(r[0], r);
        for (String[] r : rules) byFrom.put(r[0], r);
        Prefs.setCustomRulesFor(styleName, new java.util.ArrayList<>(byFrom.values()));
        Toast.makeText(this, "已保存 " + rules.size() + " 条替换到「" + styleName + "」词库", Toast.LENGTH_LONG).show();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private String formatTime(long ms) {
        long diff = System.currentTimeMillis() - ms;
        if (diff < 60_000) return "刚刚";
        if (diff < 3600_000) return (diff / 60_000) + " 分钟前";
        if (diff < 86400_000) return (diff / 3600_000) + " 小时前";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    private class HistoryAdapter extends BaseAdapter {
        @Override public int getCount() { return shown == null ? 0 : shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(HistoryActivity.this)
                        .inflate(R.layout.item_history, parent, false);
            }
            final HistoryManager.Entry e = shown.get(position);
            ((TextView) convertView.findViewById(R.id.tvStyle)).setText(e.style);
            ((TextView) convertView.findViewById(R.id.tvTime)).setText(formatTime(e.time));
            ((TextView) convertView.findViewById(R.id.tvOriginal)).setText("原文：" + e.original);
            ((TextView) convertView.findViewById(R.id.tvTranslated)).setText("译文：" + e.translated);

            MaterialButton btnFav = convertView.findViewById(R.id.btnFav);
            MaterialButton btnMappings = convertView.findViewById(R.id.btnMappings);
            MaterialButton btnCopy = convertView.findViewById(R.id.btnCopy);
            MaterialButton btnDelete = convertView.findViewById(R.id.btnDelete);

            // P2-4 收藏切换（星标即时刷新）
            btnFav.setText(e.favorite ? "★" : "☆");
            btnFav.setOnClickListener(v -> {
                HistoryManager.setFavorite(data.indexOf(e), !e.favorite);
                applyFilter();
            });

            // P2-4 条目整体点击 → 详情
            convertView.setOnClickListener(v -> showDetailDialog(e));

            btnMappings.setOnClickListener(v -> showMappingsDialog(e));
            btnCopy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("风格化结果", e.translated));
                Toast.makeText(HistoryActivity.this, "已复制译文", Toast.LENGTH_SHORT).show();
            });
            btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(HistoryActivity.this)
                        .setTitle("删除这条记录？")
                        .setMessage("原文：" + e.original)
                        .setPositiveButton("删除", (d, w) -> {
                            HistoryManager.remove(data.indexOf(e));
                            reload();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
            return convertView;
        }
    }
}
