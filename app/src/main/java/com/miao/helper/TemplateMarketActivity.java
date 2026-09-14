/** 模板市场（暂时隐藏）：当前无服务器支持，已从 AndroidManifest 移除注册。
 *  保留源码以便后续接入服务器后恢复。勿在未恢复注册前直接 startActivity。 */
package com.miao.helper;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 人设模板市场（P1-1-2）：
 *  - 读取 assets/personas/*.json（mar 12 套模板）
 *  - 列表展示：模板名 + 描述 + 适用场景首条
 *  - 点击进详情：完整描述 + 3 句示例 + 场景
 *  - 「试译预览」：输入测试文本，走 API 翻译（无 Key 时本地兜底提示）
 *  - 「应用模板」：组装铁律+风格 prompt，保存为自定义人设并自动选中
 */
public class TemplateMarketActivity extends AppCompatActivity {

    private static final String TAG = "TemplateMarket";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Template> templates = new ArrayList<>();
    private ListView lv;
    private TemplateAdapter adapter;

    /** 模板数据（来自 persona json） */
    static final class Template {
        String id;
        String name;
        String description;
        String corePrompt;
        String[][] examples;
        String[] scenarios;
        /** v4.7 文体指纹：由模板 JSON 结构化字段（self_reference/address_other/speech_habit/vocabulary）拼成 */
        String fingerprint;
        /** v4.8-⑦ 本地翻译规则（方案B）：由结构化字段动态派生，应用模板后本地引擎可用，null=无本地规则 */
        String localJson;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_template_market);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        lv = findViewById(R.id.lvTemplates);
        loadTemplates();
        adapter = new TemplateAdapter();
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> showDetail(templates.get(pos)));
    }

    // ---------------- 数据加载 ----------------

    private void loadTemplates() {
        templates.clear();
        try {
            AssetManager am = getAssets();
            String[] files = am.list("personas");
            if (files != null) {
                for (String f : files) {
                    if (!f.endsWith(".json")) continue;
                    Template t = parseJson(am, f);
                    if (t != null) templates.add(t);
                }
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "模板加载失败：" + t);
        }
        // 按 id 排序，保证顺序稳定（撒|学|商|古|现|口|文|翻|温|毒|傲|娇）
        templates.sort((a, b) -> String.valueOf(a.name).compareTo(String.valueOf(b.name)));
    }

    private Template parseJson(AssetManager am, String file) {
        try (InputStream in = am.open("personas/" + file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            JSONObject o = new JSONObject(bos.toString("UTF-8"));
            Template t = new Template();
            t.id = o.optString("id", file);
            t.name = o.optString("name", "未命名");
            t.description = o.optString("description", "");
            t.corePrompt = o.optString("core_prompt", "");
            JSONArray ex = o.optJSONArray("examples");
            List<String[]> exList = new ArrayList<>();
            if (ex != null) {
                for (int i = 0; i < ex.length(); i++) {
                    JSONObject eo = ex.optJSONObject(i);
                    if (eo != null) {
                        exList.add(new String[]{eo.optString("s", ""), eo.optString("t", "")});
                    }
                }
            }
            t.examples = exList.toArray(new String[0][]);
            // 兼容 scenarios 为数组或单个字符串（mar 12 套模板实际为字符串形态，optJSONArray 会返回 null）
            JSONArray sc = o.optJSONArray("scenarios");
            List<String> scList = new ArrayList<>();
            if (sc != null) {
                for (int i = 0; i < sc.length(); i++) scList.add(sc.optString(i, ""));
            } else {
                String scText = o.optString("scenarios", "").trim();
                if (!scText.isEmpty()) scList.add(scText);
            }
            t.scenarios = scList.toArray(new String[0]);
            // v4.7 文体指纹：把结构化字段拼成「人设画像」段（stylometric fingerprint），组装进翻译 prompt
            StringBuilder fp = new StringBuilder();
            String selfRef = o.optString("self_reference", "我");
            if (!selfRef.isEmpty()) fp.append("自称：").append(selfRef);
            String addr = o.optString("address_other", "");
            if (!addr.isEmpty()) { if (fp.length() > 0) fp.append("\n"); fp.append("称呼对方：").append(addr); }
            JSONObject sh = o.optJSONObject("speech_habit");
            StringBuilder tails = new StringBuilder();
            if (sh != null) {
                JSONArray se = sh.optJSONArray("sentence_end");
                if (se != null) for (int i = 0; i < se.length(); i++) tails.append(se.optString(i, ""));
                JSONArray fw = sh.optJSONArray("filler_words");
                if (fw != null) for (int i = 0; i < fw.length(); i++) tails.append(fw.optString(i, ""));
            }
            if (tails.length() > 0) { if (fp.length() > 0) fp.append("\n"); fp.append("句尾口癖/语气词：").append(tails); }
            JSONObject vc = o.optJSONObject("vocabulary");
            if (vc != null) {
                JSONArray pre = vc.optJSONArray("preferred");
                if (pre != null && pre.length() > 0) {
                    StringBuilder ps = new StringBuilder();
                    for (int i = 0; i < pre.length(); i++) { if (i > 0) ps.append("、"); ps.append(pre.optString(i, "")); }
                    if (fp.length() > 0) fp.append("\n");
                    fp.append("常用词：").append(ps);
                }
                JSONArray av = vc.optJSONArray("avoid");
                if (av != null && av.length() > 0) {
                    StringBuilder avs = new StringBuilder();
                    for (int i = 0; i < av.length(); i++) { if (i > 0) avs.append("、"); avs.append(av.optString(i, "")); }
                    if (fp.length() > 0) fp.append("\n");
                    fp.append("禁用词/回避：").append(avs);
                }
            }
            t.fingerprint = fp.toString();
            // v4.8-⑦ 方案B：把结构化字段动态派生为本地翻译规则（me/you/tail/dirty/phrases），
            // 应用模板后本地引擎也能用这套人设，不再原样返回（本地引擎离线可用）
            try {
                JSONObject lr = new JSONObject();
                lr.put("me", selfRef == null || selfRef.isEmpty() ? "我" : selfRef);
                lr.put("you", addr == null || addr.isEmpty() ? "你" : addr);
                StringBuilder tailSpec = new StringBuilder();
                if (sh != null) {
                    JSONArray se = sh.optJSONArray("sentence_end");
                    if (se != null) {
                        for (int i = 0; i < se.length(); i++) {
                            String w = se.optString(i, "");
                            if (!w.isEmpty()) { if (tailSpec.length() > 0) tailSpec.append("|"); tailSpec.append(w); }
                        }
                    }
                    JSONArray fw = sh.optJSONArray("filler_words");
                    if (fw != null) {
                        for (int i = 0; i < fw.length(); i++) {
                            String w = fw.optString(i, "");
                            if (!w.isEmpty()) { if (tailSpec.length() > 0) tailSpec.append("|"); tailSpec.append(w); }
                        }
                    }
                }
                lr.put("tail", tailSpec.toString());
                String dirty = "";
                if (sh != null) {
                    JSONArray cp = sh.optJSONArray("catchphrase");
                    if (cp != null && cp.length() > 0) dirty = cp.optString(0, "");
                }
                lr.put("dirty", dirty);
                if (!exList.isEmpty()) {
                    JSONArray ph = new JSONArray();
                    for (String[] e : exList) {
                        if (e != null && e.length >= 2 && e[0] != null && !e[0].isEmpty()) {
                            ph.put(new JSONArray().put(e[0]).put(e[1] == null ? "" : e[1]));
                        }
                    }
                    if (ph.length() > 0) lr.put("phrases", ph);
                }
                t.localJson = lr.toString();
            } catch (Throwable ignored) {}
            return t;
        } catch (Throwable e) {
            AppLog.w(TAG, "模板解析失败 " + file + "：" + e);
            return null;
        }
    }

    // ---------------- 列表 ----------------

    private class TemplateAdapter extends BaseAdapter {
        @Override public int getCount() { return templates.size(); }
        @Override public Object getItem(int i) { return templates.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(TemplateMarketActivity.this)
                        .inflate(R.layout.item_template_market, parent, false);
            }
            Template t = templates.get(position);
            TextView tvName = convertView.findViewById(R.id.tvTplName);
            TextView tvDesc = convertView.findViewById(R.id.tvTplDesc);
            TextView tvScene = convertView.findViewById(R.id.tvTplScene);
            TextView tvApplied = convertView.findViewById(R.id.tvTplApplied);

            tvName.setText(t.name);
            tvDesc.setText(t.description);
            tvScene.setText(t.scenarios != null && t.scenarios.length > 0
                    ? "适合：" + t.scenarios[0] : "");
            boolean applied = alreadyApplied(t.name);
            tvApplied.setVisibility(applied ? View.VISIBLE : View.GONE);
            return convertView;
        }
    }

    /** 该模板名是否已存在于自定义人设（已应用过） */
    private boolean alreadyApplied(String name) {
        if (name == null) return false;
        for (String[] p : Prefs.customPersonas()) {
            if (name.equals(p[0])) return true;
        }
        return false;
    }

    // ---------------- 详情 ----------------

    private void showDetail(Template t) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(8), dp(18), dp(8));

        TextView tvDesc = new TextView(this);
        tvDesc.setText(t.description);
        tvDesc.setTextColor(0xFF3E2723);
        tvDesc.setTextSize(14);
        body.addView(tvDesc);

        if (t.examples != null && t.examples.length > 0) {
            TextView tvExTitle = sectionTitle("示例");
            body.addView(tvExTitle);
            for (String[] ex : t.examples) {
                if (ex == null || ex.length < 2) continue;
                TextView tvEx = new TextView(this);
                tvEx.setText("原文：" + ex[0] + "\n译文：" + ex[1]);
                tvEx.setTextColor(0xFF6D4C41);
                tvEx.setTextSize(13);
                tvEx.setPadding(0, 2, 0, 4);
                body.addView(tvEx);
            }
        }
        if (t.scenarios != null && t.scenarios.length > 0) {
            body.addView(sectionTitle("适用场景"));
            TextView tvSc = new TextView(this);
            tvSc.setText(TextUtils.join("\n", t.scenarios));
            tvSc.setTextColor(0xFF6D4C41);
            tvSc.setTextSize(13);
            body.addView(tvSc);
        }

        // 试译预览区
        body.addView(sectionTitle("试译预览"));
        EditText etTest = new EditText(this);
        etTest.setHint("输入一句话，看看这个模板的效果");
        etTest.setSingleLine(false);
        etTest.setMinLines(2);
        etTest.setTextSize(14);
        body.addView(etTest);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton btnPreview = new MaterialButton(this);
        btnPreview.setText("试译");
        btnPreview.setTextSize(12);
        btnRow.addView(btnPreview);
        MaterialButton btnApply = new MaterialButton(this);
        btnApply.setText(alreadyApplied(t.name) ? "已应用（可重复生成副本）" : "应用模板");
        btnApply.setTextSize(12);
        btnApply.setTextColor(0xFFFFFFFF);
        btnApply.setBackgroundColor(0xFFE65100);
        LinearLayout.LayoutParams apl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        apl.leftMargin = dp(10);
        btnApply.setLayoutParams(apl);
        btnRow.addView(btnApply);
        body.addView(btnRow);

        final TextView tvPreviewResult = new TextView(this);
        tvPreviewResult.setTextColor(0xFF3E2723);
        tvPreviewResult.setTextSize(14);
        tvPreviewResult.setPadding(0, dp(8), 0, 0);
        body.addView(tvPreviewResult);

        ScrollView sv = new ScrollView(this);
        sv.addView(body);

        new android.app.AlertDialog.Builder(this)
                .setTitle(t.name)
                .setView(sv)
                .setNegativeButton("关闭", null)
                .show();

        // 试译：优先 API（用模板完整 prompt），无 Key 给本地兜底提示
        btnPreview.setOnClickListener(v -> {
            String text = etTest.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, "先输入一句话", Toast.LENGTH_SHORT).show();
                return;
            }
            String key = Prefs.apiKey();
            if (key == null || key.trim().isEmpty()) {
                tvPreviewResult.setText("未配置 API Key：无法在线试译。\n请先在主界面配置 API Key 后重试。");
                return;
            }
            btnPreview.setEnabled(false);
            btnPreview.setText("试译中…");
            String prompt = StyleManager.buildTemplatePrompt(t.corePrompt, t.examples, t.fingerprint);
            ApiMiaoifier.miaoify(text, key, prompt, t.examples, false, new ApiMiaoifier.Callback() {
                @Override public void onSuccess(String out) {
                    main.post(() -> {
                        btnPreview.setEnabled(true);
                        btnPreview.setText("试译");
                        tvPreviewResult.setText("【" + t.name + "】\n" + out);
                    });
                }
                @Override public void onError(String msg) {
                    main.post(() -> {
                        btnPreview.setEnabled(true);
                        btnPreview.setText("试译");
                        tvPreviewResult.setText("试译失败：" + msg);
                    });
                }
            });
        });

        // 应用：保存为自定义人设 + 自动选中
        btnApply.setOnClickListener(v -> {
            String prompt = StyleManager.buildTemplatePrompt(t.corePrompt, t.examples, t.fingerprint);
            Prefs.addCustomPersona(t.name, prompt, t.localJson);
            // 选中新添加的人设：索引 = 内置数 + 自定义列表新位置 - 1
            int builtIn = StyleManager.personaBuiltinCount();
            int customIdx = Prefs.customPersonas().size() - 1;
            int newIdx = builtIn + customIdx;
            int maxIdx = Math.max(StyleManager.personaCount() - 1, 0);
            Prefs.set("styleIndex", Math.min(Math.max(newIdx, 0), maxIdx));
            Toast.makeText(this, "已应用「" + t.name + "」，可在风格设置中继续修改", Toast.LENGTH_LONG).show();
            adapter.notifyDataSetChanged();
        });
    }

    private TextView sectionTitle(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(0xFFE65100);
        tv.setTextSize(13);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(12), 0, dp(4));
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
