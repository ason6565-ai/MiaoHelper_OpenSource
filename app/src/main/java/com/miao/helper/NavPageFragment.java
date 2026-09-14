package com.miao.helper;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/** 第2页：跳转列表 + 翻译调节 + 权限 + 「千万别点」彩蛋锁定 */
public class NavPageFragment extends Fragment {
    private TextView tvJudgeLevel, tvTemperature, tvIntensityLevel, tvExpandLevel;
    private TextView tvCandidateCount, tvJudgeCount;
    private SeekBar sbCandidateCount, sbJudgeCount;
    private SeekBar sbTemperature, sbJudge, sbIntensity, sbExpand;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_page_nav, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        // ---- 5 条跳转 ----
        v.findViewById(R.id.navStyle).setOnClickListener(vv ->
                startActivity(new Intent(requireContext(), StyleSettingsActivity.class)));
        v.findViewById(R.id.navApi).setOnClickListener(vv ->
                startActivity(new Intent(requireContext(), ApiSettingsActivity.class)));
        v.findViewById(R.id.navRule).setOnClickListener(vv ->
                startActivity(new Intent(requireContext(), RuleEditorActivity.class)));
        v.findViewById(R.id.navPersona).setOnClickListener(vv ->
                startActivity(new Intent(requireContext(), PersonaGenActivity.class)));
        // ---- 意见反馈（无服务器：弹窗展示反馈邮箱，可复制 / 拉起邮件客户端） ----
        v.findViewById(R.id.navFeedback).setOnClickListener(vv -> showFeedbackDialog());


        // ---- 权限 ----
        v.findViewById(R.id.btnService).setOnClickListener(vv ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        v.findViewById(R.id.btnOverlay).setOnClickListener(vv ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + requireContext().getPackageName()))));

        // ---- 翻译调节 4 拖动条 ----
        tvJudgeLevel = v.findViewById(R.id.tvJudgeLevel);
        tvTemperature = v.findViewById(R.id.tvTemperature);
        tvIntensityLevel = v.findViewById(R.id.tvIntensityLevel);
        tvExpandLevel = v.findViewById(R.id.tvExpandLevel);
        sbJudge = v.findViewById(R.id.sbJudge);
        tvCandidateCount = v.findViewById(R.id.tvCandidateCount);
        sbCandidateCount = v.findViewById(R.id.sbCandidateCount);
        tvJudgeCount = v.findViewById(R.id.tvJudgeCount);
        sbJudgeCount = v.findViewById(R.id.sbJudgeCount);
        sbTemperature = v.findViewById(R.id.sbTemperature);
        sbIntensity = v.findViewById(R.id.sbIntensity);
        sbExpand = v.findViewById(R.id.sbExpand);

        updateJudgeSeek();
        updateCandidateSeek();
        updateJudgeCountSeek();
        sbCandidateCount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                Prefs.set("candidateCount", progress + 1);
                updateCandidateSeek();
                AppLog.i("Nav", "生成数量：" + Prefs.candidateCount());
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        sbJudgeCount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                Prefs.set("judgeCount", progress + 1);
                updateJudgeCountSeek();
                AppLog.i("Nav", "裁判数量：" + Prefs.judgeCount());
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        updateIntensitySeek();
        updateExpandSeek();
        refreshTempFromMode();

        sbJudge.setProgress(Prefs.judgeStrictness() - 1);
        sbJudge.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                Prefs.set("judgeStrictness", progress + 1);
                updateJudgeSeek();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        sbTemperature.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                double val = progress / 100.0;
                tvTemperature.setText(String.format(java.util.Locale.US, "%.2f", val));
                writeCurrentTemp(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        sbIntensity.setProgress(Prefs.styleIntensity() - 1);
        sbIntensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                Prefs.set("styleIntensity", progress + 1);
                updateIntensitySeek();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        sbExpand.setProgress(Prefs.expandLevel());
        sbExpand.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                Prefs.set("expandLevel", progress);
                updateExpandSeek();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        updateJudgeSeek();
        updateIntensitySeek();
        updateExpandSeek();
        refreshTempFromMode();
    }

    /** 按当前引擎模式读写温度字段：本地词库=tempLocal，AI翻译=tempTranslate，AI彻底替换=tempReplace */
    private double currentTemp() {
        int m = Prefs.engineMode();
        if (m == Prefs.ENGINE_CLOUD_API) return Prefs.replaceMode() ? Prefs.tempReplace() : Prefs.tempTranslate();
        return Prefs.tempLocal();
    }
    private void writeCurrentTemp(int progress) {
        int m = Prefs.engineMode();
        if (m == Prefs.ENGINE_CLOUD_API) {
            if (Prefs.replaceMode()) Prefs.set("tempReplace", progress);
            else Prefs.set("tempTranslate", progress);
        } else Prefs.set("tempLocal", progress);
    }
    /** 引擎/模式切换时刷新温度滑块显示（读写对应引擎字段） */
    public void refreshTempFromMode() {
        if (sbTemperature == null) return;
        double cur = currentTemp();
        int tp = (int) Math.round(cur * 100);
        if (sbTemperature.getProgress() != tp) sbTemperature.setProgress(tp);
        tvTemperature.setText(String.format(java.util.Locale.US, "%.2f", cur));
    }

    /** 生成数量滑条：1-16（progress+1 为实际值），与裁判数量独立 */
    private void updateCandidateSeek() {
        int cur = Prefs.candidateCount();
        if (sbCandidateCount.getProgress() != cur - 1) sbCandidateCount.setProgress(cur - 1);
        tvCandidateCount.setText(String.valueOf(cur));
    }

    /** 裁判数量滑条：1-3（progress+1 为实际值），与生成数量独立 */
    private void updateJudgeCountSeek() {
        int cur = Prefs.judgeCount();
        if (sbJudgeCount.getProgress() != cur - 1) sbJudgeCount.setProgress(cur - 1);
        tvJudgeCount.setText(String.valueOf(cur));
    }

    private void updateJudgeSeek() {
        int cur = Prefs.judgeStrictness();
        if (sbJudge.getProgress() != cur - 1) sbJudge.setProgress(cur - 1);
        // 5.0 决议：档位只给数字，不给"平衡/标准"等文字档名
        tvJudgeLevel.setText(cur + "/5");
    }

    private void updateIntensitySeek() {
        int cur = Prefs.styleIntensity();
        if (sbIntensity.getProgress() != cur - 1) sbIntensity.setProgress(cur - 1);
        tvIntensityLevel.setText(cur + "/5");
    }

    private void updateExpandSeek() {
        int cur = Prefs.expandLevel();
        if (sbExpand.getProgress() != cur) sbExpand.setProgress(cur);
        tvExpandLevel.setText(cur + "/5");
    }

    /** 意见反馈弹窗：展示反馈邮箱，支持复制与拉起邮件客户端 */
    private void showFeedbackDialog() {
        final String email = "cn228590550@gmail.com";
        new AlertDialog.Builder(requireContext())
                .setTitle("意见反馈")
                .setMessage("使用中遇到问题或想提建议，欢迎发邮件告诉我们：\n\n" + email)
                .setPositiveButton("写邮件", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_SENDTO,
                                Uri.parse("mailto:" + email)));
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), "未找到邮件客户端，请复制邮箱后自行发送", Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("复制邮箱", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) requireContext()
                            .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("feedback_email", email));
                        Toast.makeText(requireContext(), "邮箱已复制：" + email, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

}
