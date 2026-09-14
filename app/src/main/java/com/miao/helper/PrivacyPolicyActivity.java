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
        setTitle("隐私政策");
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
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                getAssets().open("privacy_policy.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            return "隐私政策加载失败，请稍后重试。";
        }
        return TextUtils.isEmpty(sb) ? "" : sb.toString();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
