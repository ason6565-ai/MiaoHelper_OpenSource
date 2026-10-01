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
            btnFavFilter.setText(favOnly ? getString(R.string.his_all) : getString(R.string.his_fav_only));
            applyFilter();
        });

        // P2-4 导出：全部历史 → TXT → 下载目录
        btnExport.setOnClickListener(v -> {
            if (data == null || data.isEmpty()) {
                Toast.makeText(this, getString(R.string.his_no_export), Toast.LENGTH_SHORT).show();
                return;
            }
            String msg = HistoryManager.exportToDownloads(this);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });

        btnClear.setOnClickListener(v -> {
            if (data == null || data.isEmpty()) {
                Toast.makeText(this, getString(R.string.his_empty), Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.his_clear_title))
                    .setMessage(getString(R.string.his_clear_msg))
                    .setPositiveButton(getString(R.string.his_clear), (d, w) -> {
                        HistoryManager.clear();
                        reload();
                        Toast.makeText(this, getString(R.string.his_cleared), Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(getString(R.string.cancel), null)
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
                ? getString(R.string.his_nomatch)
                : getString(R.string.his_no_history));
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
        o.setText(getString(R.string.his_orig_fmt, e.original));
        o.setTextSize(14);
        o.setTextColor(0xFF3E2723);
        o.setPadding(0, dp(8), 0, 0);
        col.addView(o);
        TextView t = new TextView(this);
        t.setText(getString(R.string.his_trans_fmt, e.translated));
        t.setTextSize(15);
        t.setTextColor(0xFF6D4C41);
        t.setPadding(0, dp(8), 0, 0);
        col.addView(t);
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.his_detail))
                .setView(sv)
                .setPositiveButton(getString(R.string.his_copy_trans), (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.tt_clip_label), e.translated));
                    Toast.makeText(this, getString(R.string.his_copied), Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(e.favorite ? getString(R.string.his_unfav) : getString(R.string.his_fav), (d, w) -> {
                    HistoryManager.setFavorite(data.indexOf(e), !e.favorite);
                    reload();
                })
                .setNegativeButton(getString(R.string.api_close), null)
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
            tv.setText(getString(R.string.his_no_diff));
            tv.setTextColor(0xFF8D6E63);
            tv.setTextSize(13);
            col.addView(tv);
        } else {
            TextView hint = new TextView(this);
            hint.setText(getString(R.string.his_diff_hint_fmt, e.style));
            hint.setTextColor(0xFF795548);
            hint.setTextSize(12);
            col.addView(hint);
            for (String[] pair : map) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(4), 0, dp(4));
                TextView from = new TextView(this);
                from.setText(pair[0].isEmpty() ? getString(R.string.his_new) : pair[0]);
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
                .setTitle(getString(R.string.his_diff_title_fmt, e.style))
                .setView(sv)
                .setPositiveButton(getString(R.string.his_save_rule), (d, w) -> saveMappings(e.style, col))
                .setNegativeButton(getString(R.string.api_close), null)
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
            if (from.isEmpty() || getString(R.string.his_new).equals(from)) continue;   // 纯新增无原文锚点，不能做规则
            if (from.equals(to.trim())) continue;
            rules.add(new String[]{from, to, "0"});
        }
        if (rules.isEmpty()) {
            Toast.makeText(this, getString(R.string.his_no_saveable), Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<String[]> existing = Prefs.customRulesFor(styleName);
        java.util.Map<String, String[]> byFrom = new java.util.LinkedHashMap<>();
        for (String[] r : existing) byFrom.put(r[0], r);
        for (String[] r : rules) byFrom.put(r[0], r);
        Prefs.setCustomRulesFor(styleName, new java.util.ArrayList<>(byFrom.values()));
        Toast.makeText(this, getString(R.string.his_saved_rules_fmt, rules.size(), styleName), Toast.LENGTH_LONG).show();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private String formatTime(long ms) {
        long diff = System.currentTimeMillis() - ms;
        if (diff < 60_000) return getString(R.string.his_just_now);
        if (diff < 3600_000) return getString(R.string.his_min_ago_fmt, (diff / 60_000));
        if (diff < 86400_000) return getString(R.string.his_hr_ago_fmt, (diff / 3600_000));
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
            ((TextView) convertView.findViewById(R.id.tvOriginal)).setText(getString(R.string.his_orig_fmt, e.original));
            ((TextView) convertView.findViewById(R.id.tvTranslated)).setText(getString(R.string.his_trans_fmt, e.translated));

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
                cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.tt_clip_label), e.translated));
                Toast.makeText(HistoryActivity.this, getString(R.string.his_copied), Toast.LENGTH_SHORT).show();
            });
            btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(HistoryActivity.this)
                        .setTitle(getString(R.string.his_del_title))
                        .setMessage(getString(R.string.his_orig_fmt, e.original))
                        .setPositiveButton(getString(R.string.his_delete), (d, w) -> {
                            HistoryManager.remove(data.indexOf(e));
                            reload();
                        })
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show();
            });
            return convertView;
        }
    }
}
