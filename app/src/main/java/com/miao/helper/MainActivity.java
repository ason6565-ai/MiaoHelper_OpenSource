package com.miao.helper;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.View;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

public class MainActivity extends AppCompatActivity {
    private TextView tvStatus, tvVersion;
    private android.widget.ImageButton btnMenu;
    private ViewPager2 viewPager;
    private View dot0, dot1;
    private com.google.android.material.button.MaterialButton btnA11yGuide;
    private PagerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvVersion = findViewById(R.id.tvVersion);
        btnMenu = findViewById(R.id.btnMenu);
        viewPager = findViewById(R.id.viewPager);
        dot0 = findViewById(R.id.dot0);
        dot1 = findViewById(R.id.dot1);
        btnA11yGuide = findViewById(R.id.btnA11yGuide);

        // 状态栏染色：暖白背景 + 深色图标
        getWindow().setStatusBarColor(0xFFFFF8F2);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        // 无障碍引导按钮：点击直达无障碍设置
        btnA11yGuide.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        // 隐私政策首次启动确认：未接受则弹窗；拒绝退出应用
        if (!Prefs.privacyAccepted()) {
            showPrivacyDialog();
        }

        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            tvVersion.setText(getString(R.string.main_version_fmt, ver));
        } catch (Exception ignored) {}

        // 分页
        adapter = new PagerAdapter(this);
        viewPager.setAdapter(adapter);
        viewPager.setOffscreenPageLimit(2);
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) { updateDots(position); }
        });
        updateDots(0);

        btnMenu.setOnClickListener(v -> showMenu());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (adapter != null) adapter.sw.refreshTileStates();
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::refreshStatus, 500);
    }

    private void refreshStatus() {
        boolean on = isAccessibilityOn();
        tvStatus.setText(on ? getString(R.string.status_on) : getString(R.string.status_off));
        if (btnA11yGuide != null) btnA11yGuide.setVisibility(on ? View.GONE : View.VISIBLE);
        MiaoService s = MiaoService.get();
        if (s != null) s.refreshFloat();
    }

    /** 底部圆点指示器：选中=暖橙，未选=浅灰 */
    private void updateDots(int position) {
        dot0.setBackground(ovalDot(position == 0));
        dot1.setBackground(ovalDot(position == 1));
    }
    private android.graphics.drawable.GradientDrawable ovalDot(boolean active) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(active ? 0xFFE65100 : 0xFFD7CCC8);
        return d;
    }

    /** 隐私政策首次启动确认弹窗：展示全文 + 同意/拒绝。拒绝 → 退出应用；同意 → 记录，下次不再弹 */
    private void showPrivacyDialog() {
        final androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
        androidx.appcompat.app.AlertDialog.Builder b = new androidx.appcompat.app.AlertDialog.Builder(this);
        b.setTitle(getString(R.string.privacy_dialog_title));
        b.setCancelable(false);

        // 内容区：提示语 + 可滚动全文
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, 0);

        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText(getString(R.string.privacy_dialog_hint));
        hint.setTextSize(13f);
        hint.setTextColor(0xFF666666);
        root.addView(hint, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setPadding(0, dp(10), 0, dp(10));
        tv.setTextSize(14f);
        tv.setLineSpacing(0f, 1.3f);
        tv.setTextColor(0xFF222222);
        tv.setText(loadPrivacyText());
        sv.addView(tv, new ScrollView.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));

        b.setView(root);
        b.setPositiveButton(getString(R.string.privacy_dialog_accept), (d, w) -> {
            Prefs.setPrivacyAccepted(true);
        });
        b.setNegativeButton(getString(R.string.privacy_dialog_reject), (d, w) -> {
            // 拒绝：直接退出应用（下次进入重新弹窗）
            if (holder[0] != null) holder[0].dismiss();
            finishAffinity();
            System.exit(0);
        });
        holder[0] = b.show();
    }

    /** 按界面语言加载隐私政策全文（与 PrivacyPolicyActivity 同一来源） */
    private String loadPrivacyText() {
        String asset = "en".equals(Prefs.language()) ? "privacy_policy_en.txt" : "privacy_policy.txt";
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                getAssets().open(asset), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (java.io.IOException e) {
            return getString(R.string.privacy_fail);
        }
        return android.text.TextUtils.isEmpty(sb) ? "" : sb.toString();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    /** 引擎切换回调：通知开关页刷新可用性，并刷新温度滑块（按新引擎读取对应温度） */
    public void notifyEngineChanged() {
        if (adapter == null) return;
        adapter.sw.refreshTileStates();
        adapter.nav.refreshTempFromMode();
    }
    /** 彻底替换开关切换回调：刷新温度滑块（读取彻底替换温度字段） */
    public void refreshTempFromMode() {
        if (adapter == null) return;
        adapter.nav.refreshTempFromMode();
    }

    private static class PagerAdapter extends FragmentStateAdapter {
        final SwitchPageFragment sw = new SwitchPageFragment();
        final NavPageFragment nav = new NavPageFragment();
        PagerAdapter(@NonNull FragmentActivity fa) { super(fa); }
        @NonNull @Override
        public androidx.fragment.app.Fragment createFragment(int position) {
            return position == 0 ? sw : nav;
        }
        @Override public int getItemCount() { return 2; }
    }

    /** 菜单：MaterialAlertDialog 列表（主菜单 + 实验功能 + 语言选择） */
    private void showMenu() {
        final String[] menuItems = {
            getString(R.string.menu_history),
            getString(R.string.menu_error_log),
            getString(R.string.menu_privacy),
            getString(R.string.menu_support),
            getString(R.string.menu_float),
            getString(R.string.menu_text_translate),
            getString(R.string.menu_notice),
            getString(R.string.menu_experimental),
            getString(R.string.menu_language),
        };
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ui_menu))
            .setItems(menuItems, (d, which) -> {
                switch (which) {
                    case 0: startActivity(new Intent(this, HistoryActivity.class)); break;
                    case 1: startActivity(new Intent(this, LogActivity.class)); break;
                    case 2: startActivity(new Intent(this, PrivacyPolicyActivity.class)); break;
                    case 3: openSupportPage(); break;
                    case 4: showFloatSettingsDialog(); break;
                    case 5: startActivity(new Intent(this, TextTranslatorActivity.class)); break;
                    case 6:
                        Prefs.setNoticeShown(false);
                        android.widget.Toast.makeText(this, getString(R.string.ma_notice_again), android.widget.Toast.LENGTH_SHORT).show();
                        break;
                    case 7: showExperimentalMenu(); break;
                    case 8: showLanguageMenu(); break;
                }
            })
            .show();
    }

    /** 赞助入口：浏览器打开 Ko-fi 主页 */
    private void openSupportPage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://ko-fi.com/ason6565")));
        } catch (Exception e) {
            AppLog.e("Main", "打开赞助页失败", e);
            android.widget.Toast.makeText(this, getString(R.string.support_open_fail), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 实验功能子菜单 */
    private void showExperimentalMenu() {
        final String[] items = {
            getString(R.string.ma_exp_trigger) + (Prefs.triggerEnabled() ? " ✓" : ""),
            getString(R.string.ma_trigger_current_fmt, (Prefs.triggerWord().isEmpty() ? getString(R.string.ma_trigger_default) : Prefs.triggerWord())),
            getString(R.string.ma_exp_undo_trigger),
            getString(R.string.ma_danger),
        };
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.menu_experimental))
            .setItems(items, (d, which) -> {
                MiaoService s = MiaoService.get();
                switch (which) {
                    case 0: if (s != null) s.toggleTrigger(); break;
                    case 1: if (s != null) s.showTriggerWordDialog(); break;
                    case 2: if (s != null) s.undoTrigger(); break;
                    case 3:
                        DangerLock.engage();
                        Prefs.set("enabled", true);
                        Prefs.set("realtime", true);
                        Prefs.setEngineMode(Prefs.ENGINE_CLOUD_API);
                        Prefs.set("replaceMode", true);
                        notifyEngineChanged();
                        MiaoService s2 = MiaoService.get();
                        if (s2 != null) { s2.updateFloatStatus(); s2.refreshFloat(); }
                        break;
                }
            })
            .show();
    }

    /** 语言选择子菜单 */
    private void showLanguageMenu() {
        final String[] langs = {getString(R.string.lang_chinese), getString(R.string.lang_english),
                getString(R.string.lang_japanese), getString(R.string.lang_korean)};
        String cur = Prefs.language();
        int checked;
        if ("ja".equals(cur)) checked = 2;
        else if ("ko".equals(cur)) checked = 3;
        else checked = "en".equals(cur) ? 1 : 0;
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.menu_language))
            .setSingleChoiceItems(langs, checked, (d, which) -> {
                String tag;
                switch (which) {
                    case 1: tag = "en"; break;
                    case 2: tag = "ja"; break;
                    case 3: tag = "ko"; break;
                    default: tag = "zh";
                }
                applyLanguage(tag);
                d.dismiss();
            })
            .show();
    }

    /** 切换界面语言（zh/en/ja/ko）：AppCompat 官方 API，自动应用并重建当前界面 */
    private void applyLanguage(String tag) {
        if (tag.equals(Prefs.language())) return;
        Prefs.setLanguage(tag);
        androidx.core.os.LocaleListCompat ll = androidx.core.os.LocaleListCompat.forLanguageTags(tag);
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(ll);
        // 浮窗是 Service 常驻 View，语言只在创建时固定：切语言后重建，让按钮/菜单文案跟随
        MiaoService s = MiaoService.get();
        if (s != null) s.recreateFloatForLanguage();
    }

    /** 悬浮窗调节对话框：大小 + 透明度 */
    private void showFloatSettingsDialog() {
        android.content.Context themed = new androidx.appcompat.view.ContextThemeWrapper(this,
                android.R.style.Theme_DeviceDefault_Light_Dialog);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(themed);
        b.setTitle(getString(R.string.ma_float_title));

        android.widget.LinearLayout box = new android.widget.LinearLayout(themed);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        TextView tvSizeLabel = new TextView(themed);
        tvSizeLabel.setText(getString(R.string.ma_size_fmt, Prefs.floatSize()));
        tvSizeLabel.setTextColor(0xFF8D6E63);
        box.addView(tvSizeLabel);
        SeekBar sbSize = new SeekBar(themed);
        sbSize.setMax(40);
        sbSize.setProgress(Prefs.floatSize() - 20);
        box.addView(sbSize);
        sbSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int val, boolean fromUser) {
                int size = val + 20;
                Prefs.set("floatSize", size);
                tvSizeLabel.setText(getString(R.string.ma_size_fmt, size));
                refreshFloatBall();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        TextView tvOpLabel = new TextView(themed);
        tvOpLabel.setText(getString(R.string.ma_opacity_fmt, Prefs.floatOpacity()));
        tvOpLabel.setTextColor(0xFF8D6E63);
        tvOpLabel.setPadding(0, pad, 0, 0);
        box.addView(tvOpLabel);
        SeekBar sbOp = new SeekBar(themed);
        sbOp.setMax(100);
        sbOp.setProgress(Prefs.floatOpacity());
        box.addView(sbOp);
        sbOp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int val, boolean fromUser) {
                Prefs.set("floatOpacity", val);
                tvOpLabel.setText(getString(R.string.ma_opacity_fmt, val));
                refreshFloatBall();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        b.setView(box);
        b.setPositiveButton(getString(R.string.ma_done), null);
        b.show();
    }
    private void refreshFloatBall() {
        MiaoService s = MiaoService.get();
        if (s != null) s.refreshFloatBall();
    }

    private boolean isAccessibilityOn() {
        int ok = 0;
        try {
            ok = android.provider.Settings.Secure.getInt(getContentResolver(),
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Exception ignored) {}
        if (ok != 1) return false;
        String services = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return services != null && services.toLowerCase().contains(getPackageName().toLowerCase());
    }
}
