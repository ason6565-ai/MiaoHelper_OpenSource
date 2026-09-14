package com.miao.helper;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.View;

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

        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            tvVersion.setText("拟言助手 v" + ver + " · 左右滑动切换");
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
        tvStatus.setText(on ? "服务已开启" : "服务未开启");
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

    /** 三条杠菜单：翻译历史 / 按App切换 / 错误日志 / 悬浮窗调节 / 文本翻译 */
    private void showMenu() {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, btnMenu);
        pm.getMenu().add(0, 1, 0, "翻译历史");
        pm.getMenu().add(0, 2, 0, "按 App 切换");
        pm.getMenu().add(0, 3, 0, "错误日志");
        pm.getMenu().add(0, 7, 0, "隐私政策");
        pm.getMenu().add(0, 4, 0, "悬浮窗调节");

        pm.getMenu().add(0, 6, 0, "文本翻译");
        pm.getMenu().add(0, 5, 0, "重新显示翻译须知");
        if (BuildConfig.DEBUG) {
            pm.getMenu().add(0, 9, 0, "小说生成");
        }

        android.view.SubMenu expSub = pm.getMenu().addSubMenu("实验功能");
        expSub.add(0, 51, 0, "触发词模式（前缀翻译）").setChecked(Prefs.triggerEnabled());
        expSub.add(0, 52, 0, "设置触发词（当前：" + (Prefs.triggerWord().isEmpty() ? "?翻" : Prefs.triggerWord()) + "）");
        expSub.add(0, 53, 0, "撤销触发词翻译");
        expSub.add(0, 54, 0, "千 万 别 点");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: startActivity(new Intent(this, HistoryActivity.class)); break;
                case 2: startActivity(new Intent(this, AppStyleActivity.class)); break;
                case 3: startActivity(new Intent(this, LogActivity.class)); break;
                case 7: startActivity(new Intent(this, PrivacyPolicyActivity.class)); break;
                case 4: showFloatSettingsDialog(); break;

                case 6: startActivity(new Intent(this, TextTranslatorActivity.class)); break;
                case 9: startActivity(new Intent(this, NovelGenActivity.class)); break;
                case 5:
                    Prefs.setNoticeShown(false);
                    android.widget.Toast.makeText(this, "下次进入翻译功能将重新显示须知", android.widget.Toast.LENGTH_SHORT).show();
                    break;
                case 51: { MiaoService s = MiaoService.get(); if (s != null) s.toggleTrigger(); break; }
                case 52: { MiaoService s = MiaoService.get(); if (s != null) s.showTriggerWordDialog(); break; }
                case 53: { MiaoService s = MiaoService.get(); if (s != null) s.undoTrigger(); break; }
                case 54: {
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
            }
            return true;
        });
        pm.show();
    }

    /** 悬浮窗调节对话框：大小 + 透明度 */
    private void showFloatSettingsDialog() {
        android.content.Context themed = new androidx.appcompat.view.ContextThemeWrapper(this,
                android.R.style.Theme_DeviceDefault_Light_Dialog);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(themed);
        b.setTitle("悬浮窗调节");

        android.widget.LinearLayout box = new android.widget.LinearLayout(themed);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        TextView tvSizeLabel = new TextView(themed);
        tvSizeLabel.setText("大小：" + Prefs.floatSize() + " dp");
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
                tvSizeLabel.setText("大小：" + size + " dp");
                refreshFloatBall();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        TextView tvOpLabel = new TextView(themed);
        tvOpLabel.setText("透明度：" + Prefs.floatOpacity() + "%");
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
                tvOpLabel.setText("透明度：" + val + "%");
                refreshFloatBall();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        b.setView(box);
        b.setPositiveButton("完成", null);
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
