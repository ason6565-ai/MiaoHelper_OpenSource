package com.miao.helper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 错误日志查看页：展示 AppLog 内存日志 + 落盘日志，支持复制/分享/清空 */
public class LogActivity extends AppCompatActivity {
    private final Handler autoHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            reload();
            autoHandler.postDelayed(this, 60000);
        }
    };

    private TextView tvLog;
    private ScrollView scroll;
    private boolean showAll = false;  // 默认显示最近50条（用户级视图）
    private MaterialButton btnToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        tvLog = findViewById(R.id.tvLog);
        scroll = findViewById(R.id.scroll);
        MaterialButton btnRefresh = findViewById(R.id.btnRefresh);
        MaterialButton btnCopy = findViewById(R.id.btnCopy);
        MaterialButton btnShare = findViewById(R.id.btnShare);
        MaterialButton btnClear = findViewById(R.id.btnClear);
        MaterialButton btnSave = findViewById(R.id.btnSave);
        btnToggle = findViewById(R.id.btnToggle);

        btnRefresh.setOnClickListener(v -> reload());
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            String copyText = showAll ? tvLog.getText().toString() : recentLogsText(20);
            cm.setPrimaryClip(ClipData.newPlainText("MiaoHelper Log", copyText));
            Toast.makeText(this, getString(R.string.log_copied), Toast.LENGTH_SHORT).show();
        });
        btnShare.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_subject));
            i.putExtra(Intent.EXTRA_TEXT, tvLog.getText().toString());
            startActivity(Intent.createChooser(i, getString(R.string.log_share)));
        });
        btnClear.setOnClickListener(v -> {
            AppLog.clear();
            reload();
            Toast.makeText(this, getString(R.string.log_cleared), Toast.LENGTH_SHORT).show();
        });

        btnSave.setOnClickListener(v -> saveAsTxt());
        btnToggle.setOnClickListener(v -> {
            showAll = !showAll;
            btnToggle.setText(showAll ? getString(R.string.log_recent) : getString(R.string.log_all));
            reload();
        });
        reload();
        autoHandler.postDelayed(autoRefresh, 60000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        autoHandler.removeCallbacks(autoRefresh);
    }

    private void reload() {
        if (showAll) {
            // 全部日志视图（文件+内存，原有逻辑）
            StringBuilder sb = new StringBuilder();
            File f = AppLog.file();
            String fileText = readFile(f);
            String mem = AppLog.dump();
            if (!fileText.isEmpty()) {
                sb.append(getString(R.string.log_file_section)).append('\n').append(fileText);
                java.util.HashSet<String> inFile = new java.util.HashSet<>();
                for (String l : fileText.split("\n")) inFile.add(l.trim());
                StringBuilder fresh = new StringBuilder();
                for (String l : mem.split("\n")) {
                    if (l.trim().isEmpty()) continue;
                    if (!inFile.contains(l.trim())) fresh.append(l).append('\n');
                }
                if (fresh.length() > 0) {
                    sb.append('\n').append(getString(R.string.log_fresh_section)).append('\n').append(fresh);
                }
            } else {
                sb.append(mem.isEmpty() ? getString(R.string.log_none) : mem);
            }
            tvLog.setText(sb.toString());
        } else {
            // 用户级视图：最近50条，颜色分级（绿=成功/INFO，黄=WARN，红=ERROR，灰=DEBUG）
            java.util.List<String> logs = AppLog.getRecentLogs(50);
            if (logs.isEmpty()) {
                tvLog.setText(getString(R.string.log_none));
            } else {
                SpannableStringBuilder ssb = new SpannableStringBuilder();
                for (String line : logs) {
                    int color = 0xFF9E9E9E;  // 默认灰（DEBUG）
                    if (line.contains(" I/") || line.contains("成功") || line.contains("已")) color = 0xFF4CAF50;  // 绿
                    else if (line.contains(" W/") || line.contains("偏慢") || line.contains("跳过")) color = 0xFFFF9800;  // 橙
                    else if (line.contains(" E/") || line.contains("失败") || line.contains("错误") || line.contains("异常")) color = 0xFFF44336;  // 红
                    SpannableString ss = new SpannableString(line + "\n");
                    ss.setSpan(new ForegroundColorSpan(color), 0, ss.length(), 0);
                    ssb.append(ss);
                }
                tvLog.setText(ssb);
            }
        }
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    /** 取最近 N 条日志的纯文本（用于复制） */
    private String recentLogsText(int n) {
        java.util.List<String> logs = AppLog.getRecentLogs(n);
        StringBuilder sb = new StringBuilder();
        for (String l : logs) sb.append(l).append('\n');
        return sb.toString();
    }


    /** 保存日志为 txt 文件到下载目录 */
    private void saveAsTxt() {
        String content = tvLog.getText().toString();
        if (content.isEmpty() || content.equals(getString(R.string.log_none))) {
            Toast.makeText(this, getString(R.string.log_nothing_save), Toast.LENGTH_SHORT).show();
            return;
        }
        String fileName = "miao_log_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                java.util.Locale.getDefault()).format(new java.util.Date()) + ".txt";
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception(getString(R.string.err_file_create));
                java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(content.getBytes(StandardCharsets.UTF_8));
                os.close();
            } else {
                java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                java.io.File f = new java.io.File(dir, fileName);
                java.io.FileWriter fw = new java.io.FileWriter(f);
                fw.write(content);
                fw.close();
            }
            Toast.makeText(this, getString(R.string.log_saved_fmt, fileName), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            // 降级：保存到 app 外部文件目录
            try {
                java.io.File f = new java.io.File(getExternalFilesDir(null), fileName);
                java.io.FileWriter fw = new java.io.FileWriter(f);
                fw.write(content);
                fw.close();
                Toast.makeText(this, getString(R.string.log_saved_path_fmt, f.getAbsolutePath()), Toast.LENGTH_LONG).show();
            } catch (Exception e2) {
                Toast.makeText(this, getString(R.string.log_save_fail_fmt, e2.getMessage()), Toast.LENGTH_LONG).show();
            }
        }
    }
    private String readFile(File f) {
        if (f == null || !f.exists()) return "";
        try {
            byte[] b = Files.readAllBytes(f.toPath());
            return new String(b, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return getString(R.string.log_read_fail_fmt, t);
        }
    }
}
