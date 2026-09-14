package com.miao.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 文本/文件翻译（P1-2 文件翻译全链）。
 * - P1-2-1 接入：TXT 编码探测（BOM/UTF-8→GBK，TxtBookParser）+ EPUB 解析（EpubBookParser）
 * - P1-2-2 分段：段落优先、超长硬切，每段 ≤ 1200 字
 * - P1-2-3 断点续传：翻译进度实时写私有草稿，重进可恢复
 * - P1-2-4 失败重试：单段失败自动重试 1 次，仍失败跳过并计数
 * - P1-2-5 术语一致性：translateDoc 铁律要求术语准确；词库按人设独立（ApiLexiconExpander）
 * - P1-2-6 上下文窗口：相邻段落合并入块，块间保持语义连贯
 * - P1-2-7 进度：ProgressBar + 段数 + 完成/失败汇总
 * - P1-2-8 R18 确认页：文件翻译在须知之后叠加 R18 确认（本地存储可撤回）
 * - P1-2-9 导出：TXT + 极简 EPUB（zip 结构）
 * - P1-2-10 结果可编辑：译文区为可编辑 EditText，编辑后保存导出
 */
public class TextTranslatorActivity extends Activity {

    private static final int SEGMENT_SIZE = 1200;
    private static final int REQUEST_IMPORT = 1001;

    private EditText etInput;
    private TextView tvOutput;
    private TextView tvProgress;
    private ProgressBar progressBar;
    private Button btnTranslate;
    private Button btnImport;
    private Button btnClear;
    private Button btnCopy;
    private Button btnSave;
    private Button btnSaveEpub;
    private Button btnBack;
    private EditText etBookName;
    private Button btnGlossary;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean translating = false;
    private volatile boolean cancelled = false;

    /** 断点续传草稿 */
    private File draftFile;
    // P1-8 修复：草稿异步单线程写（避免每段全量序列化+写文件导致 O(n²) IO 和主线程阻塞）
    private static final java.util.concurrent.ExecutorService DRAFT_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "miao-draft-writer");
                t.setDaemon(true);
                return t;
            });
    private volatile int draftWriteCounter = 0;
    private List<String> draftSegs = new ArrayList<>();
    private int draftTotal = 0;
    private int draftDone = 0;
    private int draftFail = 0;
    private StringBuilder draftResult = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_translator);

        etInput = findViewById(R.id.etInput);
        tvOutput = findViewById(R.id.tvOutput);
        tvProgress = findViewById(R.id.tvProgress);
        progressBar = findViewById(R.id.progressBar);
        btnTranslate = findViewById(R.id.btnTranslate);
        btnImport = findViewById(R.id.btnImport);
        btnClear = findViewById(R.id.btnClear);
        btnCopy = findViewById(R.id.btnCopy);
        btnSave = findViewById(R.id.btnSave);
        btnSaveEpub = findViewById(R.id.btnSaveEpub);
        btnBack = findViewById(R.id.btnBack);
        etBookName = findViewById(R.id.etBookName);
        btnGlossary = findViewById(R.id.btnGlossary);
        btnGlossary.setOnClickListener(v -> showGlossaryDialog());

        btnBack.setOnClickListener(v -> finish());

        btnImport.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            String[] mimeTypes = {"text/plain", "text/*", "application/octet-stream", "application/epub+zip"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            startActivityForResult(Intent.createChooser(intent, "选择文本/EPUB 文件"), REQUEST_IMPORT);
        });

        btnClear.setOnClickListener(v -> {
            if (translating) { Toast.makeText(this, "翻译中，请先等待完成", Toast.LENGTH_SHORT).show(); return; }
            etInput.setText("");
            tvOutput.setText("");
            deleteDraft();
        });

        btnTranslate.setOnClickListener(v -> startTranslate());

        btnCopy.setOnClickListener(v -> {
            String text = tvOutput.getText().toString();
            if (text.isEmpty()) {
                Toast.makeText(this, "没有可复制的译文", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("译文", text));
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });

        btnSave.setOnClickListener(v -> saveAsTxt());
        btnSaveEpub.setOnClickListener(v -> saveAsEpub());

        // P0-5-2 翻译功能用户须知：首次使用弹一次性免责弹窗
        maybeShowTranslateNotice();
        // P1-2-3 断点续传：检测未完成草稿
        checkDraftAndOfferResume();
    }

    // ================= v5.0 每本书术语档 =================

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 术语表管理弹窗：查看/添加/删除当前书术语条目（原文=译名） */
    private void showGlossaryDialog() {
        final String book = etBookName.getText().toString().trim();
        if (book.isEmpty()) {
            Toast.makeText(this, "请先在上方输入术语档书名", Toast.LENGTH_SHORT).show();
            return;
        }
        final Map<String, String> map = new LinkedHashMap<>(BookGlossaryStore.load(this, book));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), dp(4));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);

        final EditText etNew = new EditText(this);
        etNew.setHint("原文=译名");
        etNew.setTextSize(14);
        etNew.setSingleLine(true);
        Button btnAdd = new Button(this);
        btnAdd.setText("添加");
        btnAdd.setTextSize(13);
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        addRow.setPadding(dp(12), 0, dp(12), dp(8));
        addRow.addView(etNew, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        addRow.addView(btnAdd, new LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT));

        final Runnable[] rebuildHolder = new Runnable[1];
        rebuildHolder[0] = () -> {
            list.removeAllViews();
            if (map.isEmpty()) {
                TextView empty = new TextView(TextTranslatorActivity.this);
                empty.setText("暂无条目，输入「原文=译名」添加");
                empty.setTextSize(13);
                empty.setTextColor(0xFF8D6E63);
                empty.setPadding(0, dp(8), 0, dp(8));
                list.addView(empty);
            }
            for (final Map.Entry<String, String> e : map.entrySet()) {
                TextView tv = new TextView(TextTranslatorActivity.this);
                tv.setText(e.getKey() + " = " + e.getValue());
                tv.setTextSize(14);
                tv.setTextColor(0xFF1A1A1A);
                tv.setPadding(0, dp(6), 0, dp(6));
                tv.setOnClickListener(v -> new AlertDialog.Builder(TextTranslatorActivity.this)
                        .setTitle("删除术语")
                        .setMessage("删除「" + e.getKey() + " = " + e.getValue() + "」？")
                        .setPositiveButton("删除", (d, w) -> {
                            map.remove(e.getKey());
                            rebuildHolder[0].run();
                            BookGlossaryStore.save(TextTranslatorActivity.this, book, map);
                            AppLog.i("Glossary", "删除术语：" + e.getKey());
                        })
                        .setNegativeButton("取消", null)
                        .show());
                list.addView(tv);
            }
        };
        rebuildHolder[0].run();

        btnAdd.setOnClickListener(v -> {
            String line = etNew.getText().toString().trim();
            int eq = line.indexOf('=');
            if (eq <= 0 || eq == line.length() - 1) {
                Toast.makeText(this, "格式：原文=译名", Toast.LENGTH_SHORT).show();
                return;
            }
            map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            etNew.setText("");
            rebuildHolder[0].run();
            BookGlossaryStore.save(this, book, map);
            AppLog.i("Glossary", "添加术语：" + line);
        });

        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(addRow);

        new AlertDialog.Builder(this)
                .setTitle("术语表：" + book)
                .setView(root)
                .setPositiveButton("完成", null)
                .show();
    }

    // ================= P0-5-2 用户须知 =================

    private void maybeShowTranslateNotice() {
        if (Prefs.noticeShown()) return;
        new AlertDialog.Builder(this)
                .setTitle("翻译功能使用须知")
                .setMessage("本应用仅提供翻译功能框架。\n\n翻译内容由你发起并仅由你使用，请确保使用行为合法合规，并对翻译内容及其使用负全部责任。\n\n请勿用于违法用途。")
                .setPositiveButton("我知道了，继续使用", (d, w) -> {
                    Prefs.setNoticeShown(true);
                    d.dismiss();
                })
                .setNegativeButton("暂不使用", (d, w) -> {
                    Prefs.setNoticeShown(true);
                    d.dismiss();
                })
                .setCancelable(false)
                .show();
    }

    // ================= P1-2-8 R18 确认页（文件翻译叠加层） =================

    private void maybeShowR18Confirm(Runnable onOk) {
        if (Prefs.r18Confirmed()) { onOk.run(); return; }
        new AlertDialog.Builder(this)
                .setTitle("内容确认")
                .setMessage("文件翻译可能涉及成人内容。\n\n本应用不做云端留存、不做社区分享，确认状态仅保存在本地，可随时撤回。")
                .setPositiveButton("确认并继续", (d, w) -> {
                    Prefs.setR18Confirmed(true);
                    d.dismiss();
                    onOk.run();
                })
                .setNegativeButton("暂不翻译", (d, w) -> d.dismiss())
                .setCancelable(false)
                .show();
    }

    // ================= 文件导入（P1-2-1） =================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) loadFile(uri);
        }
    }

    private void loadFile(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) {
                Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show();
                return;
            }
            byte[] bytes = readAll(is);
            is.close();
            String name = uri.getLastPathSegment();
            String content = null;
            String how = "";
            boolean isEpub = (name != null && name.toLowerCase().endsWith(".epub")) || isEpubBytes(bytes);
            if (isEpub) {
                BookDocument doc = EpubBookParser.parse(bytes);
                content = doc.fullText();
                how = "EPUB " + doc.chapterCount() + " 章";
            } else {
                // P1-2-1 编码探测：BOM / UTF-8 严格 / GBK 兜底（解决老 TXT 乱码）
                content = TxtBookParser.decode(bytes, null);
                how = "TXT " + (bytes.length / 1024) + " KB";
            }
            if (content == null || content.trim().isEmpty()) {
                Toast.makeText(this, "文件内容为空或无法解析", Toast.LENGTH_LONG).show();
                return;
            }
            etInput.setText(content);
            if (name != null && etBookName.getText().toString().trim().isEmpty()) {
                String bookName = name.replaceFirst("(?i)\\.[a-z0-9]+$", "");
                if (!bookName.isEmpty()) etBookName.setText(bookName);
            }
            Toast.makeText(this, "已导入：" + how + "，" + content.length() + " 字", Toast.LENGTH_LONG).show();
            AppLog.i("TextTranslate", "导入成功 " + how + " 字=" + content.length());
        } catch (Throwable e) {
            // P1-13 修复：OutOfMemoryError 不是 Exception 子类，原 catch(Exception) 接不住大文件 OOM 导致崩溃
            AppLog.e("TextTranslate", "读取文件失败", e);
            Toast.makeText(this, "读取文件失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static boolean isEpubBytes(byte[] b) {
        // EPUB 是 zip：PK 头
        return b != null && b.length > 4 && (b[0] & 0xFF) == 'P' && (b[1] & 0xFF) == 'K';
    }

    /** P2-28：读完即关流，避免每次进入页面泄漏 1 个文件描述符。 */
    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    // ================= 翻译主流程（P1-2-2/3/4/5/6/7） =================

    private void startTranslate() {
        if (translating) {
            Toast.makeText(this, "正在翻译中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = smartDeWrap(etInput.getText().toString()).trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "请输入或导入文本", Toast.LENGTH_SHORT).show();
            return;
        }
        String key = Prefs.apiKey().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "请先在 API 设置中配置 Key", Toast.LENGTH_LONG).show();
            return;
        }

        // 断点：优先恢复草稿，否则全新开始
        if (draftTotal > 0 && draftDone < draftTotal) {
            resumeFromDraft();
            return;
        }

        final List<String> segments = splitText(text, SEGMENT_SIZE);
        final int total = segments.size();
        final int[] failCount = {0};
        final StringBuilder result = new StringBuilder();
        cancelled = false;

        translating = true;
        btnTranslate.setEnabled(false);
        btnClear.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvProgress.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        tvOutput.setText("");

        // P1-2-8 文件翻译：须知之后叠加 R18 确认页
        Runnable run = () -> new Thread(() -> translateLoop(segments, total, 0, result, failCount)).start();
        maybeShowR18Confirm(run);
    }

    /** 断点续传恢复入口（P1-2-3） */
    private void resumeFromDraft() {
        if (translating) return;
        final List<String> segs = new ArrayList<>(draftSegs);
        final int total = draftTotal;
        final int from = draftDone;
        final int[] failCount = {draftFail};
        final StringBuilder result = new StringBuilder(draftResult);
        cancelled = false;

        translating = true;
        btnTranslate.setEnabled(false);
        btnClear.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvProgress.setVisibility(View.VISIBLE);
        progressBar.setProgress((int) (from / (float) total * 100));
        tvOutput.setText(result.toString());

        new Thread(() -> translateLoop(segs, total, from, result, failCount)).start();
    }

    /** 分段翻译主循环：断点可续、失败重试、进度实时持久化 */
    private void translateLoop(List<String> segments, int total, int startIdx,
                               StringBuilder result, int[] failCount) {
        final String key = Prefs.apiKey().trim();
        final TermGlossary glossary = new TermGlossary();   // v4.7.1 跨段术语表：解决长文名称偏移
        // v5.0 每本书术语档：用户预设术语先于动态学习登记（用户译名优先，模型漂移自动归一）
        final String book = etBookName.getText().toString().trim();
        if (!book.isEmpty()) {
            Map<String, String> userTerms = BookGlossaryStore.load(TextTranslatorActivity.this, book);
            for (Map.Entry<String, String> e : userTerms.entrySet()) {
                glossary.absorb("【术语】" + e.getKey() + "=" + e.getValue());
            }
            AppLog.i("TextTranslate", "术语档已加载 book=" + book + " 条数=" + userTerms.size());
        }
        for (int i = startIdx; i < total; i++) {
            if (cancelled || !translating) break;
            final int idx = i;
            final String seg = segments.get(i);
            main.post(() -> {
                int pct = (int) ((idx / (float) total) * 100);
                progressBar.setProgress(pct);
                tvProgress.setText("翻译中... " + (idx + 1) + "/" + total + " 段");
            });

            final String[] translated = new String[1];
            final boolean[] done = {false};
            final String[] err = {null};

            ApiMiaoifier.translateDoc(seg, key, glossary.context(), new ApiMiaoifier.Callback() {
                @Override public void onSuccess(String t) { translated[0] = t; done[0] = true; }
                @Override public void onError(String msg) { err[0] = msg; done[0] = true; }
            });
            // P1-7 修复：忙等加超时(90s)和取消检查，原代码无超时且不检查cancelled，用户点取消后当前段仍死等
            long ws1 = System.currentTimeMillis();
            while (!done[0] && !cancelled && System.currentTimeMillis() - ws1 < 90000) { sleep(50); }
            if (cancelled) break;
            if (!done[0]) { err[0] = "单段翻译超时(90s)"; }

            // 4.6.4 单段失败自动重试 1 次；仍失败跳过
            if (err[0] != null) {
                err[0] = null; done[0] = false;
                AppLog.w("TextTranslate", "第 " + (idx + 1) + " 段翻译失败，自动重试一次");
                // v5.0 网络瞬时波动退避：重试前等 2s（UnknownHostException/连接闪断窗口通常 <2s），
                // 避免在断网窗口内立即重试必败；退避期间仍响应取消
                long backoffStart = System.currentTimeMillis();
                while (!cancelled && System.currentTimeMillis() - backoffStart < 2000) { sleep(50); }
                if (cancelled) break;
                ApiMiaoifier.translateDoc(seg, key, glossary.context(), new ApiMiaoifier.Callback() {
                    @Override public void onSuccess(String t) { translated[0] = t; done[0] = true; }
                    @Override public void onError(String msg) { err[0] = msg; done[0] = true; }
                });
                // P1-7 修复：重试路径同样加超时和取消检查
                long ws2 = System.currentTimeMillis();
                while (!done[0] && !cancelled && System.currentTimeMillis() - ws2 < 90000) { sleep(50); }
                if (cancelled) break;
                if (!done[0]) { err[0] = "重试超时(90s)"; }
            }
            if (err[0] != null) {
                failCount[0]++;
                final String emsg = err[0];
                main.post(() -> tvProgress.setText("第 " + (idx + 1) + " 段翻译失败已跳过，继续翻译后续段落..."));
                AppLog.w("TextTranslate", "第 " + (idx + 1) + " 段重试仍失败，跳过：" + emsg);
                result.append("〔第 " + (idx + 1) + " 段翻译失败，已跳过〕\n\n");
                updateDraft(segments, total, idx + 1, failCount[0], result);
                continue;
            }

            // v4.7.1 术语一致性：吸收模型登记的术语行，归一漂移译名后再写回
            glossary.absorb(translated[0]);
            translated[0] = glossary.normalize(translated[0]);

            // P0-5-1 + 鉴审阅（09-13 第1轮）：段落级 ContentGuard 命中 → 该段不写回、
            // 标记"未翻译"并继续后续段；不弹窗不打断（文件翻译段落级拦截不应阻塞流程，也无重试死循环）
            if (ContentGuard.isBlocked(translated[0])) {
                failCount[0]++;
                AppLog.w("TextTranslate", "第 " + (idx + 1) + " 段被内容审核拦截，标记未翻译跳过");
                result.append("〔第 " + (idx + 1) + " 段未翻译（内容审核）〕\n\n");
                updateDraft(segments, total, idx + 1, failCount[0], result);
                final String partialB = result.toString();
                main.post(() -> {
                    tvOutput.setText(partialB);
                    tvProgress.setText("第 " + (idx + 1) + " 段被拦截，已标记未翻译，继续...");
                });
                continue;
            }

            result.append(translated[0]);
            if (idx < total - 1) result.append("\n\n");
            updateDraft(segments, total, idx + 1, failCount[0], result);

            final String partial = result.toString();
            main.post(() -> tvOutput.setText(partial));
        }

        main.post(() -> {
            progressBar.setProgress(100);
            tvProgress.setText("翻译完成，共 " + total + " 段" + (failCount[0] > 0 ? "，" + failCount[0] + " 段失败/拦截已跳过" : ""));
            btnTranslate.setEnabled(true);
            btnClear.setEnabled(true);
            translating = false;
            deleteDraft();
            main.postDelayed(() -> {
                progressBar.setVisibility(View.GONE);
                tvProgress.setVisibility(View.GONE);
            }, 2500);
        });
    }

    /** P2-27：页面销毁时清掉延迟隐藏进度条等回调，避免操作已销毁的 View。 */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ================= 断点续传草稿（P1-2-3） =================

    private File draftFile() {
        return new File(getFilesDir(), "translate_draft.json");
    }

    private void updateDraft(List<String> segs, int total, int doneCount, int fail, StringBuilder result) {
        // P1-8 修复：异步写 + 降频（每3段写一次，最后一段必写），避免每段全量序列化导致 O(n²) IO
        draftWriteCounter++;
        final boolean isLast = (doneCount >= total);
        if (!isLast && draftWriteCounter % 3 != 0) return;
        final List<String> segsCopy = new ArrayList<>(segs);
        final String resultCopy = result.toString();
        DRAFT_POOL.execute(() -> {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("v", 1);
                o.put("total", total);
                o.put("done", doneCount);
                o.put("fail", fail);
                org.json.JSONArray ja = new org.json.JSONArray();
                for (String s : segsCopy) ja.put(s);
                o.put("segs", ja);
                o.put("result", resultCopy);
                File f = draftFile();
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(o.toString().getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
            } catch (Exception e) {
                AppLog.w("TextTranslate", "草稿写入失败：" + e);
            }
        });
    }

    private void checkDraftAndOfferResume() {
        File f = draftFile();
        if (!f.exists()) return;
        try {
            String json = new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8);
            org.json.JSONObject o = new org.json.JSONObject(json);
            int total = o.optInt("total", 0);
            int done = o.optInt("done", 0);
            int fail = o.optInt("fail", 0);
            if (total <= 0 || done >= total) { f.delete(); return; }
            draftTotal = total; draftDone = done; draftFail = fail;
            draftSegs.clear();
            org.json.JSONArray ja = o.optJSONArray("segs");
            if (ja != null) for (int i = 0; i < ja.length(); i++) draftSegs.add(ja.optString(i, ""));
            draftResult.setLength(0);
            draftResult.append(o.optString("result", ""));
            new AlertDialog.Builder(this)
                    .setTitle("检测到未完成的翻译草稿")
                    .setMessage("上次翻译进行到第 " + done + "/" + total + " 段，是否继续？")
                    .setPositiveButton("继续翻译", (d, w) -> resumeFromDraft())
                    .setNegativeButton("放弃草稿", (d, w) -> { f.delete(); draftTotal = 0; draftDone = 0; })
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            AppLog.w("TextTranslate", "草稿读取失败，清理：" + e);
            f.delete();
        }
    }

    private void deleteDraft() {
        draftTotal = 0; draftDone = 0; draftFail = 0; draftSegs.clear(); draftResult.setLength(0);
        File f = draftFile();
        if (f.exists()) f.delete();
    }

    // ================= 分段（P1-2-2/6） =================

    /**
     * 4.6.4 分段加固 + P1-2-6 上下文窗口：优先按段落切（保留语义边界，相邻段落并入同块形成上下文），
     * 单段超过 maxLen 时按字符硬切，保证任何一段都不超过 maxLen。
     */
    private List<String> splitText(String text, int maxLen) {
        List<String> segments = new ArrayList<>();
        if (text.length() <= maxLen) {
            segments.add(text);
            return segments;
        }
        String[] paragraphs = text.split("\n", -1);
        StringBuilder current = new StringBuilder();
        for (String p : paragraphs) {
            if (p.length() > maxLen) {
                if (current.length() > 0) { segments.add(current.toString()); current = new StringBuilder(); }
                for (int i = 0; i < p.length(); i += maxLen) {
                    segments.add(p.substring(i, Math.min(i + maxLen, p.length())));
                }
                continue;
            }
            if (current.length() + p.length() + 1 > maxLen && current.length() > 0) {
                segments.add(current.toString());
                current = new StringBuilder();
            }
            current.append(p).append("\n");
        }
        if (current.length() > 0) {
            segments.add(current.toString());
        }
        return segments;
    }

    /**
     * 4.4 智能去换行：PDF/网页复制文本常有断行。
     * 规则：段落（空行）保留；行尾连字符断词合并；行尾为终止标点保留换行；
     * 否则按相邻字符判断合并（英文补空格、中文直接拼接）。
     */
    private String smartDeWrap(String text) {
        if (text == null || text.isEmpty()) return text;
        text = text.replaceAll("(?m)-\\h*\\R(?!\\s)", "");
        String[] paras = text.split("\\n\\s*\\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paras.length; i++) {
            sb.append(mergeLines(paras[i]));
            if (i < paras.length - 1) sb.append("\n\n");
        }
        return sb.toString();
    }

    /** 合并段内断行：行尾终止标点保留换行，其余按相邻字符拼接 */
    private String mergeLines(String para) {
        String[] lines = para.split("\\n");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (i == 0) { out.append(line); continue; }
            boolean endPunct = line.length() > 0 && "。！？!?；;：:”’\"…、，,）】》".indexOf(line.charAt(0)) >= 0;
            if (endPunct) {
                out.append('\n').append(line);
            } else {
                char prev = out.length() > 0 ? out.charAt(out.length() - 1) : 0;
                char cur = line.charAt(0);
                boolean bothWord = (Character.isLetterOrDigit(prev) && Character.isLetterOrDigit(cur));
                boolean cnPrev = prev >= 0x4E00 && prev <= 0x9FA5;
                boolean cnCur = cur >= 0x4E00 && cur <= 0x9FA5;
                if (bothWord && !(cnPrev && cnCur)) out.append(' ');
                out.append(line);
            }
        }
        return out.toString();
    }

    // ================= 导出（P1-2-9） =================

    /** 保存译文为 txt 到下载目录（Android 10+：MediaStore.Downloads，无需存储权限） */
    private void saveAsTxt() {
        String text = tvOutput.getText().toString();
        if (text.isEmpty()) {
            Toast.makeText(this, "没有可保存的译文", Toast.LENGTH_SHORT).show();
            return;
        }
        // v5.0：默认输出名=导入 TXT 文件名（etBookName 导入时自动回填文件名去扩展名）；未导入则回退 译文_时间戳
        String baseName = etBookName.getText().toString().trim();
        if (baseName.isEmpty()) {
            baseName = "译文_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.getDefault()).format(new java.util.Date());
        }
        String fileName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt";
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("创建文件失败");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                verifySaved(uri);
            } else {
                // P0-5 修复：API 26-28 无 MediaStore.Downloads，用传统公共下载目录
                java.io.File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                java.io.File file = new java.io.File(dir, fileName);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    fos.write(text.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
            }
            Toast.makeText(this, "已保存到下载：" + fileName, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** P1-2-9 导出 EPUB（极简 EPUB2：mimetype + container.xml + content.opf + content.xhtml） */
    private void saveAsEpub() {
        String text = tvOutput.getText().toString();
        if (text.isEmpty()) {
            Toast.makeText(this, "没有可保存的译文", Toast.LENGTH_SHORT).show();
            return;
        }
        String fileName = "译文_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                java.util.Locale.getDefault()).format(new java.util.Date()) + ".epub";
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/epub+zip");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("创建文件失败");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    writeEpub(os, text);
                    os.flush();
                }
                verifySaved(uri);
            } else {
                // P0-5 修复：API 26-28 传统方式保存 EPUB
                java.io.File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                java.io.File file = new java.io.File(dir, fileName);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    writeEpub(fos, text);
                    fos.flush();
                }
            }
            Toast.makeText(this, "已保存 EPUB 到下载：" + fileName, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void writeEpub(OutputStream os, String text) throws Exception {
        String[] paras = text.split("\\n\\s*\\n");
        StringBuilder body = new StringBuilder();
        for (String p : paras) {
            if (p.trim().isEmpty()) continue;
            body.append("<p>").append(escapeHtml(p.trim())).append("</p>\n");
        }
        String xhtml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n<head>\n"
                + "<title>译文</title>\n"
                + "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"/>\n"
                + "</head>\n<body>\n" + body + "</body>\n</html>\n";
        String container = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
                + "<rootfiles>\n"
                + "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n"
                + "</rootfiles>\n</container>\n";
        String opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"uid\">\n"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
                + "<dc:title>译文</dc:title>\n"
                + "<dc:language>zh</dc:language>\n"
                + "<dc:identifier id=\"uid\">miao-helper-translation</dc:identifier>\n"
                + "</metadata>\n"
                + "<manifest>\n"
                + "<item id=\"content\" href=\"content.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
                + "</manifest>\n"
                + "<spine toc=\"\"><itemref idref=\"content\"/></spine>\n"
                + "</package>\n";
        ZipOutputStream zos = new ZipOutputStream(os);
        // EPUB 规范：mimetype 必须为第一个条目且不压缩
        zos.setLevel(0);
        zos.putNextEntry(new ZipEntry("mimetype"));
        zos.write("application/epub+zip".getBytes(StandardCharsets.US_ASCII));
        zos.closeEntry();
        zos.setLevel(6);
        zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
        zos.write(container.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
        zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
        zos.write(opf.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
        zos.putNextEntry(new ZipEntry("OEBPS/content.xhtml"));
        zos.write(xhtml.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
        zos.finish();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** P0-2-5 回读校验：不承诺未验证的"已保存" */
    private void verifySaved(Uri uri) throws Exception {
        int total = 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("回读失败：无法打开文件");
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) total += n;
        }
        if (total == 0) throw new Exception("回读校验失败：文件为空");
    }
}
