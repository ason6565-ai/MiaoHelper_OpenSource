package com.miao.helper;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

public final class L10n {

    private L10n() { }

    /** 跟随系统时按系统当前语言解析出实际生效的语言 tag（zh/en/ja/ko，其他语言系统回退中文）。 */
    public static String effectiveTag() {
        String tag = Prefs.language();
        if (!"system".equals(tag)) return tag;
        String sys = java.util.Locale.getDefault().getLanguage();
        if ("en".equals(sys)) return "en";
        if ("ja".equals(sys)) return "ja";
        if ("ko".equals(sys)) return "ko";
        return "zh"; // 含 zh 与所有其他语言
    }

    /* 当前应用内语言对应的 Locale；跟随系统或中文时返回 null（不包裹，走系统资源解析）。 */
    public static Locale current() {
        String tag = Prefs.language();
        if (tag == null || "system".equals(tag) || "zh".equals(tag)) return null;
        switch (tag) {
            case "en": return Locale.ENGLISH;
            case "ja": return Locale.JAPANESE;
            case "ko": return Locale.KOREAN;
            default:   return null;
        }
    }

    /* 返回按应用内语言包裹过的 Context；跟随系统或中文时原样返回。 */
    public static Context wrap(Context base) {
        if (base == null) return null;
        Locale loc = current();
        if (loc == null) return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(loc);
        cfg.setLocales(new LocaleList(loc));
        return base.createConfigurationContext(cfg);
    }
}
