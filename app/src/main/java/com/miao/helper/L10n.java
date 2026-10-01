package com.miao.helper;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

public final class L10n {

    private L10n() { }

    /* 当前应用内语言对应的 Locale；中文（默认 values）或未设置时返回 null。 */
    public static Locale current() {
        String tag = Prefs.language();
        if (tag == null) return null;
        switch (tag) {
            case "en": return Locale.ENGLISH;
            case "ja": return Locale.JAPANESE;
            case "ko": return Locale.KOREAN;
            default:   return null;
        }
    }

    /* 返回按应用内语言包裹过的 Context；中文时原样返回。 */
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
