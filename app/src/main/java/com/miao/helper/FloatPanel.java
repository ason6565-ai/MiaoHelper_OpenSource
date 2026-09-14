package com.miao.helper;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.PopupMenu;
import android.widget.TextView;

/**
 * 悬浮面板（收纳式 UI）：
 *  从悬浮球长按展开的半透明玻璃面板。
 *  - 头部：当前风格名 + 引擎徽章 + 收起
 *  - 风格选择：人设收纳按钮，点开弹出菜单选择（不做横向滑动）
 *  - 操作区：风格化 / 生成回复 / 撤销 / 裁判 / 引擎 / 隐藏 / 设置，每行两键铺满
 *  - 点面板外或点「收起」自动隐藏，不阻塞下层 App 交互
 */
public class FloatPanel {

    /** 面板操作回调，由 MiaoService 实现 */
    public interface Listener {
        void onMiaoify();
        void onGenReply();
        void onUndo();
        void onJudge();
        void onEngineToggle();
        void onStylePick(int index);
        void onHideFloat();
        void onOpenSettings();
        void onLocalPreToggle();
    }

    private final WindowManager wm;
    private final View root;
    private final WindowManager.LayoutParams lp;
    private Listener listener;

    private final TextView styleName, engineBadge, closeBtn;
    private final TextView panelDesc, btnEngine, btnPickPersona, btnLocalPre;

    private boolean showing = false;
    private final int screenW, screenH;

    public FloatPanel(Context ctx, Listener l) {
        this.listener = l;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        root = LayoutInflater.from(ctx).inflate(R.layout.float_panel, null);

        styleName = root.findViewById(R.id.panelStyleName);
        engineBadge = root.findViewById(R.id.panelEngineBadge);
        closeBtn = root.findViewById(R.id.panelClose);
        panelDesc = root.findViewById(R.id.panelDesc);
        btnEngine = root.findViewById(R.id.btnEngine);
        btnPickPersona = root.findViewById(R.id.btnPickPersona);
        btnLocalPre = root.findViewById(R.id.btnLocalPre);

        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        bindActions();
        refreshSelection();
    }

    /** 按当前配置刷新头部状态 + 收纳按钮文字/选中态 + 引擎按钮 */
    public void refreshSelection() {
        styleName.setText(StyleManager.currentName());
        int eng = Prefs.engineMode();
        boolean api = eng == Prefs.ENGINE_CLOUD_API;
        boolean replace = api && Prefs.replaceMode();
        String badge = replace ? "替换" : (api ? "AI" : "本地");
        int engColor = replace ? 0xFFE65100 : (api ? 0xFF1E88E5 : 0xFF43A047);
        engineBadge.setText(badge);
        engineBadge.setTextColor(engColor);
        // 收纳按钮：显示当前所选人设
        btnPickPersona.setText("人设：" + StyleManager.currentName());
        btnPickPersona.setBackgroundResource(R.drawable.bg_chip_selected);
        btnPickPersona.setTextColor(0xFFFFFFFF);
        // 引擎按钮文字/颜色随当前引擎切换（本地词库 / AI 翻译 / 彻底替换）
        String engName = replace ? "彻底替换" : (api ? "AI 翻译" : "本地词库");
        btnEngine.setText(engName);
        btnEngine.setTextColor(engColor);
        // AI 并联本地：开=深橙强调 + “：开”后缀，关=普通橙底
        boolean localPre = Prefs.localPreStyle();
        btnLocalPre.setText(localPre ? "AI 并联本地：开" : "AI 并联本地");
        btnLocalPre.setBackgroundResource(localPre ? R.drawable.bg_chip_selected : R.drawable.bg_btn_orange);
        btnLocalPre.setTextColor(0xFFFFFFFF);
        int strict = Prefs.judgeStrictness();
        panelDesc.setText(engName + " · 裁判 " + strict + " 级");
    }

    private void bindActions() {
        root.findViewById(R.id.btnMiaoify).setOnClickListener(v -> {
            hide(); if (listener != null) listener.onMiaoify();
        });
        root.findViewById(R.id.btnGenReply).setOnClickListener(v -> {
            hide(); if (listener != null) listener.onGenReply();
        });
        root.findViewById(R.id.btnUndo).setOnClickListener(v -> {
            hide(); if (listener != null) listener.onUndo();
        });
        root.findViewById(R.id.btnJudge).setOnClickListener(v -> {
            if (listener != null) listener.onJudge();
        });
        btnEngine.setOnClickListener(v -> {
            if (listener != null) listener.onEngineToggle();
        });
        root.findViewById(R.id.btnHide).setOnClickListener(v -> {
            hide(); if (listener != null) listener.onHideFloat();
        });
        root.findViewById(R.id.btnSettings).setOnClickListener(v -> {
            hide(); if (listener != null) listener.onOpenSettings();
        });
        btnLocalPre.setOnClickListener(v -> {
            if (listener != null) listener.onLocalPreToggle();
        });
        closeBtn.setOnClickListener(v -> hide());

        // 收纳式风格选择：点开弹出人设菜单
        btnPickPersona.setOnClickListener(v -> showStyleMenu(btnPickPersona));

        // 点面板外（ACTION_OUTSIDE）收起
        root.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) {
                hide();
                return true;
            }
            return false;
        });
    }

    /** 弹出人设选择菜单（收纳式，替代横向滑动 chips） */
    private void showStyleMenu(View anchor) {
        Context themed = new ContextThemeWrapper(
                root.getContext(), android.R.style.Theme_DeviceDefault_Light);
        final PopupMenu pm = new PopupMenu(themed, anchor);
        String[] names = StyleManager.personaNames();
        for (int i = 0; i < names.length; i++) {
            pm.getMenu().add(0, i, 0, names[i]);
        }
        pm.setOnMenuItemClickListener(item -> {
            int idx = item.getItemId();
            if (listener != null) listener.onStylePick(idx);
            refreshSelection();
            return true;
        });
        try {
            pm.show();
        } catch (Exception ignored) {
            // 极少数设备无法从 overlay 弹菜单，静默忽略
        }
    }

    /** 在悬浮球旁展开面板，自动避让屏幕边缘；面板宽度固定更宽敞 */
    public void show(int ballX, int ballY, int ballSizePx) {
        if (showing) { hide(); return; }
        refreshSelection();
        // 面板宽度：铺满屏宽减边距（上限 360dp），不再收窄
        int width = Math.min(screenW - dp(24), dp(360));
        lp.width = width;
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST));
        int h = root.getMeasuredHeight();

        int x = ballX + ballSizePx / 2 - width / 2;
        int y = ballY + ballSizePx + dp(6);   // 默认在球下方
        if (y + h > screenH) {
            y = ballY - h - dp(6);            // 放不下则放球上方
            if (y < dp(4)) y = dp(4);         // 上方也放不下则贴屏幕顶部
        }
        if (x + width > screenW - dp(8)) {
            x = screenW - width - dp(8);
        }
        if (x < dp(4)) x = dp(4);

        lp.y = y;
        try {
            wm.addView(root, lp);
            showing = true;
        } catch (Exception ignored) {
            showing = false;
        }
    }

    public void hide() {
        if (!showing) return;
        try { wm.removeView(root); } catch (Exception ignored) {}
        showing = false;
    }

    public boolean isShowing() { return showing; }

    private int dp(int v) {
        return Math.round(root.getResources().getDisplayMetrics().density * v);
    }
}
