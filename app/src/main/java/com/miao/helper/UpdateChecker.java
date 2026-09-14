package com.miao.helper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 轻量应用内更新检查（对标开源 AppUpdater 的“检查更新”能力，零 UI 框架依赖）。
 * 更新源 URL 由用户在设置里配置，返回 JSON：
 *   {"versionCode":整数,"versionName":"x.y","url":"下载页或apk地址","notes":"更新说明"}
 * 仅做“检查 + 提示 + 外部浏览器打开下载链接”，不做静默下载/安装（无固定更新服务器，
 * 也避免申请安装未知应用权限）；更新源留空时优雅提示，绝不打扰主流程。
 */
public final class UpdateChecker {

    public static final class Info {
        public final int versionCode;
        public final String versionName;
        public final String url;
        public final String notes;
        Info(int vc, String vn, String url, String notes) {
            this.versionCode = vc; this.versionName = vn; this.url = url; this.notes = notes;
        }
    }

    public interface Callback {
        void onUpdate(Info info);
        void onUpToDate();
        void onFail(String msg);
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "miao-update");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile OkHttpClient client;

    private UpdateChecker() {}

    private static OkHttpClient http() {
        if (client == null) {
            synchronized (UpdateChecker.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(8, TimeUnit.SECONDS)
                            .readTimeout(8, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return client;
    }

    public static void check(final Context ctx, final Callback cb) {
        final String url = Prefs.updateUrl();
        if (url == null || url.trim().isEmpty()) {
            MAIN.post(() -> cb.onFail("尚未配置更新源"));
            return;
        }
        final String trimmed = url.trim();
        if (!isAllowedScheme(trimmed)) {
            MAIN.post(() -> cb.onFail("更新源必须使用 HTTPS（自建内网更新源可配置局域网 HTTP 地址）"));
            return;
        }
        POOL.execute(() -> {
            try {
                Request req = new Request.Builder().url(trimmed).get().build();
                try (Response resp = http().newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        postFail(cb, "更新服务器返回 " + resp.code());
                        return;
                    }
                    String body = resp.body().string();
                    JSONObject j = new JSONObject(body);
                    int vc = j.optInt("versionCode", 0);
                    Info info = new Info(vc, j.optString("versionName", ""),
                            j.optString("url", ""), j.optString("notes", ""));
                    int cur;
                    try {
                        cur = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
                    } catch (Exception pm) { cur = 0; }
                    if (vc > cur) MAIN.post(() -> cb.onUpdate(info));
                    else MAIN.post(cb::onUpToDate);
                }
            } catch (Throwable t) {
                String m = t.getMessage() == null ? "网络错误" : t.getMessage();
                postFail(cb, "更新检查失败：" + m);
            }
        });
    }

    /** P2-25：更新源强制 HTTPS；仅回环与局域网（10./192.168./172.16-31.）允许 HTTP，公网必须 https 防中间人篡改。 */
    private static boolean isAllowedScheme(String url) {
        String s = url.toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("https://")) return true;
        if (!s.startsWith("http://")) return false;
        String host = s.substring("http://".length());
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        if (host.equals("localhost") || host.equals("127.0.0.1")) return true;
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true;
        if (host.startsWith("172.")) {
            try {
                int dot = host.indexOf('.', 4);
                int seg = Integer.parseInt(host.substring(4, dot < 0 ? host.length() : dot));
                return seg >= 16 && seg <= 31;
            } catch (Throwable t) { return false; }
        }
        return false;
    }

    private static void postFail(Callback cb, String m) {
        MAIN.post(() -> cb.onFail(m));
    }
}
