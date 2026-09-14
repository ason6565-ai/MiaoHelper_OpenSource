package com.miao.helper;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * v5.0 小说生成测试模块（仅 debug 包开放）。
 * 最小闭环：选角色（角色池）→ 选事件（事件表）→ 以角色人设生成一段反应文本。
 * 角色用内置结构化人设（18 字段简化版），事件写死，排版不管，能读即可。
 */
public class NovelGenActivity extends AppCompatActivity {

    /** 内置角色池：名称 + 人设 prompt */
    private static final String[][] ROLES = {
        {"白雀（落魄画师）",
         "你叫白雀，三十出头，落魄画师，在旧城租了间漏雨画室。说话慢、带点自嘲，习惯把情绪藏进" +
         "笔下的颜色里。自称『我』，偶尔叹气。哪怕心里翻江倒海，嘴上也是轻描淡写。"},
        {"阿七（夜市摊主）",
         "你叫阿七，四十岁，夜市炒粉摊主，嗓门大、热心肠，爱管闲事。说话带市井烟火气，自称" +
         "『你七哥』，爱用『嘿』『得嘞』。嘴上损人，手上从不含糊。"},
        {"小满（大学新生）",
         "你叫小满，十九岁，刚上大学，有点怂又有点冲。说话带括号和省略号，紧张时会结巴，自称" +
         "『我』，爱用『那个……』『怎么说呢』。心里戏多，嘴上漏一半。"}
    };

    /** 内置事件表 */
    private static final String[] EVENTS = {
        "凌晨两点，你听到画室外传来窸窸窣窣的敲门声，透过门缝看见一双湿透的布鞋。",
        "收摊时，一个陌生女孩递给你一张皱巴巴的纸条，上面只有一个地址和一句「明晚见」。",
        "开学第一周的社团招新会上，你被一个自称学姐的人强行拉进了话剧社。",
        "巷口那家关了十年的旧书店，今晚突然亮起了灯，门缝里传出翻书声。"
    };

    private Spinner spRole, spEvent;
    private TextView tvResult;
    private ProgressBar progress;
    private EditText etEvent;          // v5.0 放宽：自定义事件（空则用内置事件表）
    private String roleState = "";     // v5.0 角色状态追踪（参考开源 ZenStory/AI_xiaoshuo）
    private boolean retried = false;   // v5.0 乱码/对话化自动重试一次

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        int pad8 = (int) (8 * getResources().getDisplayMetrics().density);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFFBF4F0);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("小说生成（测试）");
        tvTitle.setTextSize(20);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(0xFF3E2723);
        root.addView(tvTitle);

        TextView tvHint = new TextView(this);
        tvHint.setText("测试模块：选角色 + 选事件，生成一段角色反应文本。");
        tvHint.setTextSize(13);
        tvHint.setTextColor(0xFF8D6E63);
        tvHint.setPadding(0, pad8, 0, pad8);
        root.addView(tvHint);

        String[] roleNames = new String[ROLES.length];
        for (int i = 0; i < ROLES.length; i++) roleNames[i] = ROLES[i][0];
        spRole = new Spinner(this);
        spRole.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, roleNames));
        root.addView(spRole);

        spEvent = new Spinner(this);
        spEvent.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, EVENTS));
        root.addView(spEvent);

        TextView tvCustom = new TextView(this);
        tvCustom.setText("或输入自定义事件（留空用上方内置事件）");
        tvCustom.setTextSize(13);
        tvCustom.setTextColor(0xFF8D6E63);
        tvCustom.setPadding(0, pad8, 0, pad8);
        root.addView(tvCustom);
        etEvent = new EditText(this);
        etEvent.setHint("例如：半夜有人敲你的画室门，说找你有急事");
        etEvent.setTextSize(14);
        root.addView(etEvent);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        TextView btnGo = new TextView(this);
        btnGo.setText("生成一段");
        btnGo.setTextSize(15);
        btnGo.setGravity(Gravity.CENTER);
        btnGo.setTextColor(Color.WHITE);
        btnGo.setPadding(pad8, pad8, pad8, pad8);
        btnGo.setBackgroundColor(0xFFFB8C00);
        root.addView(btnGo, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        tvResult = new TextView(this);
        tvResult.setTextSize(15);
        tvResult.setTextColor(0xFF3E2723);
        tvResult.setTextIsSelectable(true);
        tvResult.setLineSpacing(0, 1.2f);
        tvResult.setPadding(pad8, pad8, pad8, pad8);
        tvResult.setBackgroundColor(0xFFFFF8F0);
        root.addView(tvResult);

        TextView btnCopy = new TextView(this);
        btnCopy.setText("复制结果");
        btnCopy.setTextSize(14);
        btnCopy.setGravity(Gravity.CENTER);
        btnCopy.setTextColor(0xFFFB8C00);
        btnCopy.setPadding(pad8, pad8, pad8, pad8);
        btnCopy.setBackgroundColor(0xFFFFF3E0);
        root.addView(btnCopy, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        btnGo.setOnClickListener(v -> run());
        btnCopy.setOnClickListener(v -> {
            String s = tvResult.getText().toString();
            if (s == null || s.trim().isEmpty() || "生成中…".equals(s)) {
                Toast.makeText(this, "还没有结果可复制", Toast.LENGTH_SHORT).show();
                return;
            }
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("novel", s));
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            }
        });

        setContentView(scroll);
    }

    private void run() {
        String key = Prefs.apiKey();
        if (key == null || key.trim().isEmpty()) {
            Toast.makeText(this, "未配置 API Key，请先在 API 设置中填写", Toast.LENGTH_LONG).show();
            return;
        }
        int ri = Math.max(spRole.getSelectedItemPosition(), 0);
        String custom = etEvent == null ? "" : etEvent.getText().toString().trim();
        String event = (custom.isEmpty())
                ? EVENTS[Math.max(spEvent.getSelectedItemPosition(), 0)]
                : custom;
        AppLog.i("Api", "小说生成开始 角色=" + ROLES[ri][0] + " 事件=" + brief(event));
        final String rolePrompt = ROLES[ri][1];
        progress.setVisibility(View.VISIBLE);
        tvResult.setText("生成中…");
        String persona = rolePrompt
                + "\n【任务】你正在经历下面这件事。请用你的口吻，写一段此刻的内心反应或自言自语，"
                + "约 100-200 字。要像真人当下的反应，不要总结、不要喊口号。";
        if (roleState != null && !roleState.isEmpty()) {
            persona += "\n【你当前的状态】" + roleState
                    + "（以上是你此前刚刚经历的事与心情，请顺着这个状态自然地继续反应，不要重复复述它）";
        }
        if (retried) {
            persona += "\n【上次输出不合格】上次生成的文本出现乱码、碎片化或变成了对话腔，"
                    + "请重新写一遍：必须是通顺连贯的中文角色内心反应，禁止乱码与碎片堆叠，禁止反问用户。";
        }
        final String eventFinal = event;
        ApiMiaoifier.rework(event, key.trim(), null, persona, new ApiMiaoifier.Callback() {
            @Override public void onSuccess(String out) {
                String safe = (out == null) ? "（空结果）" : out.trim();
                if (looksBad(safe) && !retried) {
                    retried = true;
                    AppLog.w("Api", "小说生成输出异常（乱码/对话化），自动重试一轮：" + brief(safe));
                    // P1-12 修复：run() 内直接操作 View，必须切主线程调用，否则池线程跨线程操作 View 导致重试永久失效
                    runOnUiThread(() -> {
                        progress.setVisibility(View.VISIBLE);
                        tvResult.setText("生成中…（上次输出异常，重试中）");
                        run();
                    });
                    return;
                }
                if (!looksBad(safe)) {
                    roleState = summary(safe) + "（来自事件：" + brief(eventFinal) + "）";
                }
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    tvResult.setText(safe);
                    retried = false;
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    tvResult.setText("出错了：" + msg);
                    retried = false;
                });
            }
        });
    }

    /** v5.0 生成结果审计（参考开源 InkOS 审计-修订闭环）：乱码/对话化/自报身份检测，保守启发式，宁可放过不误伤 */
    private static boolean looksBad(String s) {
        if (s == null || s.trim().isEmpty()) return true;
        String t = s.trim();
        String low = t.toLowerCase();
        if (low.contains("我是ai") || low.contains("我是人工智能") || low.contains("作为ai")
                || low.contains("作为助手") || low.contains("你觉得呢") || low.contains("你说呢")
                || low.contains("对不对")) return true;
        int cn = 0, letters = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (c >= '\u4e00' && c <= '\u9fff') cn++;
            }
        }
        if (letters > 0 && cn * 100 / letters < 40) return true;
        for (int i = 2; i < t.length(); i++) {
            if (t.charAt(i) == t.charAt(i - 1) && t.charAt(i) == t.charAt(i - 2)) return true;
        }
        return false;
    }

    private static String summary(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() > 45 ? t.substring(0, 45) + "……" : t;
    }

    private static String brief(String s) {
        if (s == null) return "";
        return s.length() > 20 ? s.substring(0, 20) + "…" : s;
    }
}
