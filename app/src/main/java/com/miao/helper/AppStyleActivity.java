package com.miao.helper;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 按 App 自动切换风格：为预设的聊天/社交 App 设置默认风格，
 * 无障碍服务检测到该 App 窗口时自动切换。
 * 人设列表直接读取 StyleManager.personaNames()，与 PERSONA 顺序保持一致。
 */
public class AppStyleActivity extends AppCompatActivity {

    // 风格选项（显示名），人设部分运行时从 StyleManager 动态生成
    private static String[] buildStyleNames() {
        String[] p = StyleManager.personaNames();
        String[] names = new String[p.length + 2];
        int i = 0;
        names[i++] = "跟随当前";
        for (String n : p) names[i++] = n;
        names[i] = "关闭风格化";
        return names;
    }

    // 风格选项（存储值）：-1=跟随，N=人设索引，off=关闭
    private static String[] buildStyleValues() {
        int n = StyleManager.personaCount();
        String[] vals = new String[n + 2];
        int i = 0;
        vals[i++] = "-1";
        for (int k = 0; k < n; k++) vals[i++] = String.valueOf(k);
        vals[i] = "off";
        return vals;
    }

    private final String[] STYLE_NAMES = buildStyleNames();
    private final String[] STYLE_VALUES = buildStyleValues();

    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_app_style);

        adapter = new AppAdapter();
        ListView lv = findViewById(R.id.lvApps);
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((parent, view, position, id) -> showStyleDialog(position));
    }

    /** 弹出风格选择对话框 */
    private void showStyleDialog(int pos) {
        String pkg = Prefs.PRESET_APPS[pos][0];
        String current = Prefs.appStyle(pkg);
        int checked = 0;
        for (int i = 0; i < STYLE_VALUES.length; i++) {
            if (STYLE_VALUES[i].equals(current)) { checked = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle(Prefs.PRESET_APPS[pos][1] + " 的默认风格")
                .setSingleChoiceItems(STYLE_NAMES, checked, (d, w) -> {
                    Prefs.setAppStyle(pkg, STYLE_VALUES[w]);
                    adapter.notifyDataSetChanged();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把存储值转成显示名 */
    private String styleDisplay(String val) {
        for (int i = 0; i < STYLE_VALUES.length; i++) {
            if (STYLE_VALUES[i].equals(val)) return STYLE_NAMES[i];
        }
        return "跟随当前";
    }

    // ---- 列表适配器 ----
    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return Prefs.PRESET_APPS.length; }
        @Override public Object getItem(int i) { return Prefs.PRESET_APPS[i]; }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(AppStyleActivity.this)
                        .inflate(R.layout.item_app_style, parent, false);
            }
            String[] app = Prefs.PRESET_APPS[position];
            TextView tvName = convertView.findViewById(R.id.tvAppName);
            TextView tvStyle = convertView.findViewById(R.id.tvAppStyle);
            tvName.setText(app[1]);
            String val = Prefs.appStyle(app[0]);
            tvStyle.setText("当前：" + styleDisplay(val));
            return convertView;
        }
    }
}
