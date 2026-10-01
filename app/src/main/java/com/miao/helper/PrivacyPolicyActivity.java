package com.miao.helper;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 隐私政策页：展示 assets/privacy_policy.txt（上架合规入口） */
public class PrivacyPolicyActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.privacy_title));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        TextView tv = new TextView(this);
        tv.setPadding(dp(18), dp(14), dp(18), dp(28));
        tv.setTextSize(14f);
        tv.setLineSpacing(0f, 1.3f);
        tv.setTextColor(Color.parseColor("#222222"));
        tv.setTypeface(Typeface.DEFAULT);
        tv.setText(loadPolicy());
        sv.addView(tv, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private String loadPolicy() {
        // 按界面语言加载：英文界面显示英文隐私政策，其余显示中文
        String asset = "en".equals(Prefs.language()) ? "privacy_policy_en.txt" : "privacy_policy.txt";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                getAssets().open(asset), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            return getString(R.string.privacy_fail);
        }
        return TextUtils.isEmpty(sb) ? "" : sb.toString();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
