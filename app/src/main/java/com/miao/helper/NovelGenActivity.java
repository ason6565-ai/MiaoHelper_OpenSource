package com.miao.helper;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * v5.1 小说生成（完整工作流）。
 * 流程：输入故事大体 → 自动生成大纲 + 事件池 → 管理角色（自定义/AI生成）→
 *       按大纲推进 + 事件池联动生成故事；上下文自动携带（设定/大纲/角色口风/剧情摘要/最近正文/未引出事件池）。
 * 存储：NovelStore 单故事 JSON；当前故事 id 记在 Prefs.lastNovelId。
 */
public class NovelGenActivity extends AppCompatActivity {

    private EditText etTitle, etPremise;
    private TextView tvStatus, tvChars, tvOutline, tvEvents, tvResult, tvSummary;
    private ProgressBar progress;
    private TextView btnSetup, btnGen, btnCopy, btnAddChar, btnAiChar, btnAddEvent;

    private NovelStore.Story story;
    private String selectedEvent = "";   // 事件池中选中的事件（空=自动推进）
    private int selectedCharEdit = -1;

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
        tvTitle.setText(getString(R.string.nov_title));
        tvTitle.setTextSize(20);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(0xFF3E2723);
        root.addView(tvTitle);

        TextView tvHint = new TextView(this);
        tvHint.setText(getString(R.string.nov_hint));
        tvHint.setTextSize(12);
        tvHint.setTextColor(0xFF8D6E63);
        tvHint.setPadding(0, pad8, 0, pad8);
        root.addView(tvHint);

        // ---- 故事设定 ----
        etTitle = new EditText(this);
        etTitle.setHint(getString(R.string.nov_title_hint));
        etTitle.setTextSize(14);
        root.addView(etTitle);

        etPremise = new EditText(this);
        etPremise.setHint(getString(R.string.nov_premise_hint));
        etPremise.setTextSize(14);
        etPremise.setMinLines(3);
        etPremise.setGravity(Gravity.TOP);
        root.addView(etPremise);

        btnSetup = button(getString(R.string.nov_setup), 0xFFFB8C00, Color.WHITE);
        root.addView(btnSetup, matchWrap());

        tvStatus = new TextView(this);
        tvStatus.setTextSize(12);
        tvStatus.setTextColor(0xFF6D4C41);
        tvStatus.setPadding(0, pad8, 0, pad8);
        root.addView(tvStatus);

        // ---- 角色区 ----
        TextView secChars = section(getString(R.string.nov_sec_chars));
        root.addView(secChars);

        LinearLayout charBtns = new LinearLayout(this);
        charBtns.setOrientation(LinearLayout.HORIZONTAL);
        btnAddChar = button(getString(R.string.nov_add_char), 0xFFFFF3E0, 0xFFFB8C00);
        btnAiChar = button(getString(R.string.nov_ai_char), 0xFFFFF3E0, 0xFFFB8C00);
        charBtns.addView(btnAddChar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        charBtns.addView(btnAiChar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(charBtns);

        tvChars = new TextView(this);
        tvChars.setTextSize(13);
        tvChars.setTextColor(0xFF3E2723);
        tvChars.setPadding(0, pad8, 0, pad8);
        root.addView(tvChars);

        // ---- 大纲区 ----
        TextView secOutline = section(getString(R.string.nov_outline));
        root.addView(secOutline);
        tvOutline = new TextView(this);
        tvOutline.setTextSize(13);
        tvOutline.setTextColor(0xFF3E2723);
        tvOutline.setPadding(0, pad8, 0, pad8);
        root.addView(tvOutline);

        // ---- 事件池区 ----
        TextView secEvents = section(getString(R.string.nov_sec_events));
        root.addView(secEvents);
        btnAddEvent = button(getString(R.string.nov_add_event), 0xFFFFF3E0, 0xFFFB8C00);
        root.addView(btnAddEvent, matchWrap());
        tvEvents = new TextView(this);
        tvEvents.setTextSize(13);
        tvEvents.setTextColor(0xFF3E2723);
        tvEvents.setPadding(0, pad8, 0, pad8);
        root.addView(tvEvents);

        // ---- 生成区 ----
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        btnGen = button(getString(R.string.nov_gen), 0xFFFB8C00, Color.WHITE);
        root.addView(btnGen, matchWrap());

        tvResult = new TextView(this);
        tvResult.setTextSize(15);
        tvResult.setTextColor(0xFF3E2723);
        tvResult.setTextIsSelectable(true);
        tvResult.setLineSpacing(0, 1.25f);
        tvResult.setPadding(pad8, pad8, pad8, pad8);
        tvResult.setBackgroundColor(0xFFFFF8F0);
        root.addView(tvResult);

        btnCopy = button(getString(R.string.nov_copy), 0xFFFFF3E0, 0xFFFB8C00);
        root.addView(btnCopy, matchWrap());

        tvSummary = new TextView(this);
        tvSummary.setTextSize(12);
        tvSummary.setTextColor(0xFF8D6E63);
        tvSummary.setPadding(0, pad8, 0, 0);
        root.addView(tvSummary);

        setContentView(scroll);

        btnSetup.setOnClickListener(v -> doSetup());
        btnGen.setOnClickListener(v -> doGen());
        btnCopy.setOnClickListener(v -> copyResult());
        btnAddChar.setOnClickListener(v -> showCharDialog(-1));
        btnAiChar.setOnClickListener(v -> showAiCharDialog());
        btnAddEvent.setOnClickListener(v -> showAddEventDialog());

        // 恢复上次故事
        String lastId = Prefs.lastNovelId();
        if (!lastId.isEmpty()) {
            story = NovelStore.load(this, lastId);
            if (story != null) {
                etTitle.setText(story.title);
                etPremise.setText(story.premise);
            }
        }
        refresh();
    }

    // ================= UI 辅助 =================

    private TextView section(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(14);
        v.setTypeface(null, Typeface.BOLD);
        v.setTextColor(0xFF4E342E);
        v.setPadding(0, pad8() * 2, 0, pad8());
        return v;
    }

    private TextView button(String t, int bg, int fg) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(14);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(fg);
        v.setPadding(0, pad8(), 0, pad8());
        v.setBackgroundColor(bg);
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int pad8() { return (int) (8 * getResources().getDisplayMetrics().density); }

    private void refresh() {
        if (story == null) {
            tvStatus.setText(getString(R.string.nov_status_none));
            tvChars.setText(getString(R.string.nov_chars_none));
            tvOutline.setText(getString(R.string.nov_outline_none));
            tvEvents.setText(getString(R.string.nov_events_none));
            tvResult.setText("");
            tvSummary.setText("");
            return;
        }
        tvStatus.setText(getString(R.string.nov_status_fmt, story.title, story.id, story.chapters.size(), story.outline.size(), story.events.size(), countStatus("unused"), countStatus("introduced")));

        StringBuilder cs = new StringBuilder(getString(R.string.nov_chars_prefix));
        if (story.characters.isEmpty()) cs.append(getString(R.string.nov_chars_empty));
        for (int i = 0; i < story.characters.size(); i++) {
            NovelStore.Character c = story.characters.get(i);
            if (i > 0) cs.append("\n");
            cs.append("· ").append(c.name);
            if (c.voice != null && !c.voice.trim().isEmpty()) cs.append(getString(R.string.nov_voice_pre)).append(c.voice).append(getString(R.string.nov_voice_suf));
        }
        tvChars.setText(cs.toString());
        tvChars.setOnClickListener(v -> showCharListDialog());

        StringBuilder os = new StringBuilder(getString(R.string.nov_outline_prefix));
        if (story.outline.isEmpty()) os.append(getString(R.string.nov_outline_empty));
        for (int i = 0; i < story.outline.size(); i++) {
            os.append("\n").append(i + 1).append(". ").append(story.outline.get(i));
            if (i == story.chapters.size() && story.chapters.size() < story.outline.size()) {
                os.append(getString(R.string.nov_current));
            }
        }
        tvOutline.setText(os.toString());

        StringBuilder es = new StringBuilder(getString(R.string.nov_events_prefix));
        List<NovelStore.Event> unused = new ArrayList<>();
        for (NovelStore.Event e : story.events) {
            if ("unused".equals(e.status)) unused.add(e);
        }
        if (unused.isEmpty()) {
            es.append(getString(R.string.nov_events_empty));
        } else {
            for (int i = 0; i < unused.size(); i++) {
                NovelStore.Event e = unused.get(i);
                boolean sel = selectedEvent != null && selectedEvent.equals(e.text);
                es.append("\n").append(sel ? "▶ " : "  ").append(i + 1).append(". ")
                  .append(e.text).append(sel ? getString(R.string.nov_selected) : "");
            }
        }
        tvEvents.setText(es.toString());
        tvEvents.setOnClickListener(v -> showEventListDialog());

        if (!story.chapters.isEmpty()) {
            NovelStore.Chapter last = story.chapters.get(story.chapters.size() - 1);
            tvResult.setText("【" + last.title + "】\n" + last.content);
        } else {
            tvResult.setText("");
        }
        tvSummary.setText(story.summary.trim().isEmpty() ? "" : getString(R.string.nov_summary_fmt, story.summary.trim()));
    }

    private int countStatus(String st) {
        int n = 0;
        for (NovelStore.Event e : story.events) if (st.equals(e.status)) n++;
        return n;
    }

    private void busy(boolean b, String msg) {
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        btnSetup.setEnabled(!b);
        btnGen.setEnabled(!b);
        btnAddChar.setEnabled(!b);
        btnAiChar.setEnabled(!b);
        btnAddEvent.setEnabled(!b);
        if (b) tvResult.setText(msg);
    }

    // ================= 动作 =================

    private void doSetup() {
        String premise = etPremise.getText().toString().trim();
        if (premise.isEmpty()) {
            Toast.makeText(this, getString(R.string.nov_need_premise), Toast.LENGTH_SHORT).show();
            return;
        }
        String key = Prefs.apiKey();
        if (key == null || key.trim().isEmpty()) {
            Toast.makeText(this, getString(R.string.nov_need_key), Toast.LENGTH_LONG).show();
            return;
        }
        String title = etTitle.getText().toString().trim();
        if (title.isEmpty()) title = premise.length() > 12 ? premise.substring(0, 12) : premise;
        busy(true, getString(R.string.nov_setup_ing));
        final String ftitle = title;
        final String fpremise = premise;
        NovelEngine.generateSetup(this, fpremise, null, ftitle, new NovelEngine.Callback() {
            @Override public void onDone(String storyId) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    story = NovelStore.load(NovelGenActivity.this, storyId);
                    Prefs.setLastNovelId(storyId);
                    if (story != null) {
                        // 为大纲角色填充默认角色骨架：设定里提名的角色自动进角色表
                        ensureCharsFromPremise(fpremise);
                        NovelStore.save(NovelGenActivity.this, story);
                    }
                    busy(false, "");
                    refresh();
                    Toast.makeText(NovelGenActivity.this,
                            getString(R.string.nov_setup_done_fmt, (story == null ? 0 : story.outline.size()), (story == null ? 0 : story.events.size())),
                            Toast.LENGTH_LONG).show();
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    busy(false, "");
                    Toast.makeText(NovelGenActivity.this, getString(R.string.nov_fail_fmt, msg), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** 从设定文本简单提取「主角名」等候选角色名（按“名”/“/”/顿号切分，长度 1-8 的片段），避免空角色表 */
    private void ensureCharsFromPremise(String premise) {
        if (story == null || premise == null) return;
        if (!story.characters.isEmpty()) return;
        String[] parts = premise.split("[，,。！？、/\\s]+");
        for (String p : parts) {
            String n = p.trim();
            if (n.length() >= 1 && n.length() <= 8 && !n.matches(".*[的了吗呢是我你有他她它们和与在了一不].*")) {
                NovelStore.Character c = new NovelStore.Character();
                c.name = n;
                c.desc = "";
                story.characters.add(c);
                if (story.characters.size() >= 6) break;
            }
        }
    }

    private void doGen() {
        if (story == null) {
            Toast.makeText(this, getString(R.string.nov_need_setup), Toast.LENGTH_SHORT).show();
            return;
        }
        if (story.outline.isEmpty()) {
            Toast.makeText(this, getString(R.string.nov_outline_empty2), Toast.LENGTH_SHORT).show();
            return;
        }
        if (story.chapters.size() >= story.outline.size() && story.events.isEmpty()) {
            Toast.makeText(this, getString(R.string.nov_all_done), Toast.LENGTH_SHORT).show();
            return;
        }
        if (story.chapters.size() >= story.outline.size() && selectedEvent.isEmpty()) {
            Toast.makeText(this, getString(R.string.nov_pick_event), Toast.LENGTH_SHORT).show();
            return;
        }
        String key = Prefs.apiKey();
        if (key == null || key.trim().isEmpty()) {
            Toast.makeText(this, getString(R.string.nov_need_key2), Toast.LENGTH_LONG).show();
            return;
        }
        final String evt = selectedEvent;
        busy(true, evt.isEmpty() ? getString(R.string.nov_gen_ing) : getString(R.string.nov_evt_ing_fmt, evt));
        NovelEngine.generateChapter(this, story, evt, new NovelEngine.Callback() {
            @Override public void onDone(String chapterTitle) {
                runOnUiThread(() -> {
                    selectedEvent = "";   // 用完清空选中
                    busy(false, "");
                    refresh();
                    Toast.makeText(NovelGenActivity.this, getString(R.string.nov_gen_done_fmt, chapterTitle), Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    busy(false, "");
                    Toast.makeText(NovelGenActivity.this, getString(R.string.nov_fail_fmt, msg), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void copyResult() {
        String s = tvResult.getText().toString();
        if (s == null || s.trim().isEmpty()) {
            Toast.makeText(this, getString(R.string.nov_nothing_copy), Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("novel", s));
            Toast.makeText(this, getString(R.string.nov_copied), Toast.LENGTH_SHORT).show();
        }
    }

    // ================= 角色管理 =================

    private void showCharListDialog() {
        if (story == null) return;
        String[] names = new String[story.characters.size()];
        for (int i = 0; i < names.length; i++) names[i] = story.characters.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.nov_char_list))
                .setItems(names, (d, w) -> showCharDialog(w))
                .setNegativeButton(getString(R.string.api_close), null)
                .show();
    }

    /** 新增（idx=-1）或编辑角色（idx>=0） */
    private void showCharDialog(final int idx) {
        if (story == null) return;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 8, 24, 0);

        final EditText etName = new EditText(this);
        etName.setHint(getString(R.string.nov_char_name));
        etName.setTextSize(14);
        final EditText etDesc = new EditText(this);
        etDesc.setHint(getString(R.string.nov_char_desc));
        etDesc.setTextSize(14);
        etDesc.setMinLines(2);
        etDesc.setGravity(Gravity.TOP);
        final EditText etVoice = new EditText(this);
        etVoice.setHint(getString(R.string.nov_char_voice));
        etVoice.setTextSize(14);
        etVoice.setMinLines(2);
        etVoice.setGravity(Gravity.TOP);

        if (idx >= 0 && idx < story.characters.size()) {
            NovelStore.Character c = story.characters.get(idx);
            etName.setText(c.name);
            etDesc.setText(c.desc);
            etVoice.setText(c.voice);
        }
        root.addView(etName);
        root.addView(etDesc);
        root.addView(etVoice);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(idx < 0 ? getString(R.string.nov_add_char2) : getString(R.string.nov_edit_char))
                .setView(root)
                .setPositiveButton(getString(R.string.nov_save), (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) { Toast.makeText(this, getString(R.string.nov_char_name_empty), Toast.LENGTH_SHORT).show(); return; }
                    NovelStore.Character c;
                    if (idx >= 0 && idx < story.characters.size()) {
                        c = story.characters.get(idx);
                    } else {
                        c = new NovelStore.Character();
                        story.characters.add(c);
                    }
                    c.name = name;
                    c.desc = etDesc.getText().toString().trim();
                    c.voice = etVoice.getText().toString().trim();
                    NovelStore.save(this, story);
                    refresh();
                });
        if (idx >= 0) {
            b.setNegativeButton(getString(R.string.nov_del), (d, w) -> {
                story.characters.remove(idx);
                NovelStore.save(this, story);
                refresh();
            });
        } else {
            b.setNegativeButton(getString(R.string.cancel), null);
        }
        b.show();
    }

    /** AI 生成角色：输入描述 → 生成人设+口风 */
    private void showAiCharDialog() {
        if (story == null) {
            Toast.makeText(this, getString(R.string.nov_need_setup), Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 8, 24, 0);
        final EditText etDesc = new EditText(this);
        etDesc.setHint(getString(R.string.nov_ai_char_hint));
        etDesc.setTextSize(14);
        etDesc.setMinLines(2);
        etDesc.setGravity(Gravity.TOP);
        root.addView(etDesc);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.nov_ai_char_title))
                .setView(root)
                .setPositiveButton(getString(R.string.nov_gen_btn), (d, w) -> {
                    String desc = etDesc.getText().toString().trim();
                    if (desc.isEmpty()) { Toast.makeText(this, getString(R.string.nov_desc_empty), Toast.LENGTH_SHORT).show(); return; }
                    d.dismiss();
                    busy(true, getString(R.string.nov_ai_ing));
                    genCharByAi(desc);
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void genCharByAi(String desc) {
        final String fd = desc;
        String lang = L10n.effectiveTag();
        String system;
        String userMsg;
        if ("en".equals(lang)) {
            system = "You are a character design assistant. Based on the character description, produce a concise character profile.\n"
                    + "Format (output exactly three lines):\n"
                    + "Name: xxx\nPersona: one-sentence summary of personality, identity, background\nVoice: speaking style (self-reference, tone words, sentence habits)\n"
                    + "Requirement: English, fluent, no garbled text, do not self-identify.";
            userMsg = "Character description: " + desc;
        } else if ("ja".equals(lang)) {
            system = "あなたはキャラクターデザインアシスタントです。ユーザーのキャラクター説明に基づき、簡潔なキャラクター設定を出力してください。\n"
                    + "形式（以下の3行を厳守）：\n"
                    + "名前：xxx\n人設：性格・身分・背景を一文で\n口風：話し方（自称、語調、文型の癖）\n"
                    + "要件：日本語、自然、乱れなし、自分をアシスタントと名乗らない。";
            userMsg = "キャラクター説明：" + desc;
        } else if ("ko".equals(lang)) {
            system = "당신은 캐릭터 디자인 어시스턴트입니다. 사용자의 캐릭터 설명을 바탕으로 간결한 캐릭터 설정을 출력하세요.\n"
                    + "형식(정확히 아래 3줄로 출력):\n"
                    + "이름: xxx\n인물상: 성격·신분·배경을 한 문장으로\n말투: 말하는 방식(자칭, 어조, 문장 습관)\n"
                    + "요구: 한국어, 자연스럽게, 깨짐 없이, 스스로를 어시스턴트라고 밝히지 않기.";
            userMsg = "캐릭터 설명: " + desc;
        } else {
            system = "你是角色设计助手。根据用户给出的角色描述，产出一份简洁的角色设定。\n"
                    + "格式（严格按以下三行输出）：\n"
                    + "名字：xxx\n人设：一句话概括性格、身份、背景\n口风：说话风格描述（自称、语气词、句式习惯）\n"
                    + "要求：中文、通顺、无乱码、不自报身份。";
            userMsg = "角色描述：" + desc;
        }
        ApiMiaoifier.chat(system, userMsg, 600, new ApiMiaoifier.Callback() {
            @Override public void onSuccess(String text) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    busy(false, "");
                    NovelStore.Character c = new NovelStore.Character();
                    c.name = pick(text, charLabel(lang, "name"));
                    c.desc = pick(text, charLabel(lang, "desc"));
                    c.voice = pick(text, charLabel(lang, "voice"));
                    if (c.name.isEmpty()) c.name = getString(R.string.nov_default_char) + (story.characters.size() + 1);
                    story.characters.add(c);
                    NovelStore.save(NovelGenActivity.this, story);
                    refresh();
                    Toast.makeText(NovelGenActivity.this, getString(R.string.nov_char_done_fmt, c.name), Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    busy(false, "");
                    Toast.makeText(NovelGenActivity.this, getString(R.string.nov_fail_fmt, msg), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static String pick(String text, String label) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith(label) || line.startsWith("【" + label + "】")) {
                int i = line.indexOf('：');
                int j = line.indexOf(':');
                int k = (i < 0) ? j : ((j < 0) ? i : Math.min(i, j));
                if (k >= 0 && k < line.length() - 1) return line.substring(k + 1).trim();
            }
        }
        return "";
    }

    /** AI 生成角色的字段标记，随界面语言 zh/en/ja/ko */
    private static String charLabel(String lang, String which) {
        if ("en".equals(lang)) {
            if ("name".equals(which)) return "Name";
            if ("desc".equals(which)) return "Persona";
            return "Voice";
        }
        if ("ja".equals(lang)) {
            if ("name".equals(which)) return "名前";
            if ("desc".equals(which)) return "人設";
            return "口風";
        }
        if ("ko".equals(lang)) {
            if ("name".equals(which)) return "이름";
            if ("desc".equals(which)) return "인물상";
            return "말투";
        }
        if ("name".equals(which)) return "名字";
        if ("desc".equals(which)) return "人设";
        return "口风";
    }

    // ================= 事件管理 =================

    private void showAddEventDialog() {
        if (story == null) {
            Toast.makeText(this, getString(R.string.nov_need_setup), Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText et = new EditText(this);
        et.setHint(getString(R.string.nov_event_hint));
        et.setTextSize(14);
        et.setPadding(24, 8, 24, 0);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.nov_add_event_title))
                .setView(et)
                .setPositiveButton(getString(R.string.nov_add), (d, w) -> {
                    String t = et.getText().toString().trim();
                    if (t.isEmpty()) return;
                    NovelStore.Event e = new NovelStore.Event();
                    e.text = t;
                    e.status = "unused";
                    story.events.add(e);
                    NovelStore.save(this, story);
                    refresh();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showEventListDialog() {
        if (story == null) return;
        List<NovelStore.Event> unused = new ArrayList<>();
        for (NovelStore.Event e : story.events) if ("unused".equals(e.status)) unused.add(e);
        if (unused.isEmpty()) {
            Toast.makeText(this, getString(R.string.nov_no_unused), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[unused.size()];
        for (int i = 0; i < items.length; i++) items[i] = unused.get(i).text;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.nov_pick_evt_title))
                .setItems(items, (d, w) -> {
                    selectedEvent = unused.get(w).text;
                    refresh();
                    Toast.makeText(this, getString(R.string.nov_evt_picked), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(getString(R.string.nov_cancel_auto), (d, w) -> {
                    selectedEvent = "";
                    refresh();
                })
                .show();
    }
}
