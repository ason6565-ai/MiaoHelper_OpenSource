package com.miao.helper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.SubMenu;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.PopupMenu;
import android.widget.Toast;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 无障碍核心：读取/替换当前输入框文字。
 * 悬浮球由本 Service 常驻持有（不依赖 Activity 存活），点击=风格化，长按=快捷菜单。
 * 实时模式按“增量句段”处理，避免对已风格化文本二次处理。
 */
public class MiaoService extends AccessibilityService {

    private static volatile MiaoService inst;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String lastSet = "";   // 自己写回的文本：防自触发 + 增量基准
    private volatile String lastSetFingerprint = "";  // lastSet 文本 + 当前风格/模式指纹：换风格后允许同文重翻
    private volatile boolean busy = false;
    private volatile long busySince = 0L;    // busy 起始时间，用于卡死自检
    private static final long BUSY_STALE_MS = 45000L; // 超过 45s 无回调即判定卡死并自愈
    private long realtimeApiLastMs = 0L;  // 实时 AI 自动翻译：上次触发时间戳（防抖）
    private static final long REALTIME_API_DEBOUNCE_MS = 1200L; // 实时 AI 两次触发最小间隔(ms)
    private final Runnable busyWatchdog = this::onBusyTimeout;
    private FloatBall floatBall;
    private PreviewBubble previewBubble;   // v3.3 结果预览-确认条（仅预览模式）
    private FloatPanel floatPanel;   // 新 UI：悬浮面板
    private String lastAutoPkg = "";  // 上一次自动切换风格的包名，防重复
    private String lastOffPkg = "";      // 因"按 App 关闭风格化"而禁用的包名：离开它之后需恢复全局启用
    private volatile String lastOriginal = "";  // 上一次风格化前的原文，用于撤销
    private volatile String lastFinishedOriginal = "";  // 上一次已完成翻译的原文：识别“重新生成”，同句再来时跳过 API 缓存
    private volatile String triggerUndoText = "";   // 触发词翻译前的输入框全文（撤销用）

    // 悬浮球快捷菜单 itemId 约定
    private static final int MENU_ENGINE_LOCAL = 200;
    private static final int MENU_ENGINE_API   = 201;
    private static final int MENU_ENGINE_REPLACE = 202;   // 彻底替换模式
    private static final int MENU_HIDE_FLOAT   = 300;
    private static final int MENU_UNDO         = 401;   // 撤销上次风格化，恢复原文
    private static final int MENU_GEN_REPLY    = 402;   // 输入对方的话，AI 生成回复
    private static final int MENU_JUDGE_STRENGTH = 403;  // AI 裁判严格度调节
    private static final int MENU_AI_FALLBACK   = 405;   // 4.4: AI 失败本地兜底开关
    private static final int MENU_TRIGGER_TOGGLE = 406;  // 4.4: 触发词模式开关
    private static final int MENU_TRIGGER_WORD   = 407;  // 4.4: 设置触发词
    private static final int MENU_TRIGGER_UNDO   = 408;  // 4.4: 撤销触发词翻译

    public static MiaoService get() { return inst; }


    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
        Prefs.init(this);   // 兜底初始化（正常由 MiaoApp 完成）
        AppLog.init(this);  // 兜底初始化日志
        // 动态补强无障碍配置：多窗口检索（getWindows）+ 焦点/内容变化事件，
        // 提升微信/QQ 等自定义输入框与多窗口场景下找到输入框的成功率
        try {
            AccessibilityServiceInfo si = getServiceInfo();
            if (si != null) {
                si.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
                si.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                        | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        | AccessibilityEvent.TYPE_VIEW_FOCUSED
                        | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
                setServiceInfo(si);
            }
        } catch (Throwable t) {
            AppLog.w("Service", "动态设置 serviceInfo 失败：" + t);
        }
        resetBusy();        // 服务（重新）连接时清掉可能残留的忙状态
        // 鉴复核 P0-2：线程池拒绝时通过 UiFeedback 统一出口提示，不静默丢请求
        ApiMiaoifier.setRejectNotifier(msg -> main.post(() -> toast(msg)));
        AppLog.i("Service", "无障碍服务已连接 onServiceConnected");
        refreshFloat();
    }

    // ============================================================
    // 无障碍事件：实时模式
    // ============================================================
    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        // 窗口变化：按 App 自动切换风格（放在 enabled 检查前，因为需要从"关闭"切回"开启"）
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = e.getPackageName();
            if (pkg != null) {
                String pkgName = pkg.toString();
                if (!pkgName.equals(lastAutoPkg)) {
                    String prevPkg = lastAutoPkg;
                    lastAutoPkg = pkgName;
                    String style = Prefs.appStyle(pkgName);
                    if (!style.equals("-1")) {
                        if (style.equals("off")) lastOffPkg = pkgName;   // 记录"因按 App 关闭而禁用"的包
                        applyAutoStyle(style);
                    } else if (!Prefs.enabled() && !lastOffPkg.isEmpty() && !lastOffPkg.equals(pkgName)) {
                        // 离开"按 App 关闭"的 App，且新 App 无规则 → 恢复全局启用（单向门修复）
                        Prefs.set("enabled", true);
                        lastOffPkg = "";
                        if (floatBall != null) floatBall.setStatus(statusText());
                        AppLog.i("Service", "离开按App关闭的 " + prevPkg + "，恢复风格化启用");
                    }
                }
            }
        }
        if (!Prefs.enabled()) return;
        // 触发词模式：前缀触发（独立于实时模式；默认关闭，防误伤）
        if (Prefs.triggerEnabled()
                && e.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            AccessibilityNodeInfo src = e.getSource();
            if (src == null) return;
            if (!src.isEditable()) { AccessibilityNodes.safeRecycle(src); return; }
            CharSequence cs = src.getText();
            if (cs == null) { AccessibilityNodes.safeRecycle(src); return; }
            String text = cs.toString();
            if (text.isEmpty() || text.equals(lastSet)) { AccessibilityNodes.safeRecycle(src); return; }
            String word = Prefs.triggerWord();
            if (word == null || word.isEmpty()) word = "?翻";
            // 转义：触发词首字符写两遍 = 字面输出（??翻 → ?翻，内容原样不翻译）
            if (text.startsWith(word.charAt(0) + word)) {
                String literal = text.substring(1);
                AppLog.i("Service", "触发词转义，输出字面：" + briefLog(literal));
                setText(src, literal);
                return;
            }
            // 正常触发：触发词 + 非空内容 → 翻译内容替换整段
            if (text.startsWith(word) && text.length() > word.length()) {
                AppLog.i("Service", "触发词翻译：" + briefLog(text));
                triggerTranslate(src, text, word);
                return;
            }
        }
        // 实时模式：句末标点触发。本地引擎走增量替换；AI 引擎走完整自动翻译（复用悬浮球主流程）
        if (Prefs.realtime()
                && e.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            AccessibilityNodeInfo src = e.getSource();
            if (src == null) return;
            if (!src.isEditable()) { AccessibilityNodes.safeRecycle(src); return; }
            CharSequence cs = src.getText();
            if (cs == null) { AccessibilityNodes.safeRecycle(src); return; }
            String text = cs.toString();
            if (text.isEmpty() || text.equals(lastSet)) { AccessibilityNodes.safeRecycle(src); return; } // 空 / 自己写回
            if (!endsWithPunct(text)) { AccessibilityNodes.safeRecycle(src); return; }
            if (Prefs.useApi()) {
                // 云端实时自动翻译：防抖 + busy 自检后复用主翻译流程（自带进度条/兜底）
                AccessibilityNodes.safeRecycle(src);
                long nowTs = System.currentTimeMillis();
                if (busy || nowTs - realtimeApiLastMs < REALTIME_API_DEBOUNCE_MS) return;
                realtimeApiLastMs = nowTs;
                AppLog.i("Service", "实时触发 AI 自动翻译：" + briefLog(text));
                miaoifyCurrentInput();
            } else {
                // 本地：只处理新增句段，保留已风格化部分（防二次风格化）
                String out;
                if (lastSet.length() > 0 && text.startsWith(lastSet)) {
                    out = lastSet + MiaoifyEngine.miaoify(text.substring(lastSet.length()));
                } else {
                    out = MiaoifyEngine.miaoify(text);
                }
                if (!out.equals(text)) {
                    lastSet = out;
                    lastSetFingerprint = fingerprintOf(out);
                    setText(src, out);
                } else {
                    AccessibilityNodes.safeRecycle(src);
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        AppLog.w("Service", "无障碍服务被系统中断 onInterrupt");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        AppLog.w("Service", "无障碍服务 onUnbind（可能被系统临时回收）");
        return super.onUnbind(intent);
    }

    /**
     * 按 App 配置自动切换风格。
     * style: 人设索引（"0"-"9"等），"off"=关闭风格化
     */
    private void applyAutoStyle(String style) {
        if (style.equals("off")) {
            if (!Prefs.enabled()) return;   // 已是关闭状态，跳过，避免重复写入
            Prefs.set("enabled", false);
            if (floatBall != null) floatBall.setStatus("停");
            return;
        }
        int idx;
        try {
            idx = Integer.parseInt(style);
        } catch (Exception e) {
            // 旧版遗留值（dN 方言等）：忽略，保持当前风格
            return;
        }
        // 已是目标人设风格且已启用：跳过，防止窗口变化时无谓覆盖用户刚切的风格
        if (Prefs.enabled() && Prefs.styleIndex() == idx) return;
        // 防御性 clamp：存量数据若含越界旧索引，不写入越界值（读取方虽 clamp，但 UI checked 会全灭）
        int maxIdx = Math.max(StyleManager.personaCount() - 1, 0);
        idx = Math.min(Math.max(idx, 0), maxIdx);
        Prefs.set("enabled", true);
        Prefs.set("styleIndex", idx);
        if (floatBall != null) floatBall.setStatus(statusText());
    }

    @Override
    public void onDestroy() {
        AppLog.w("Service", "无障碍服务 onDestroy");
        resetBusy();
        if (floatBall != null) {
            floatBall.hide();
            floatBall = null;
        }
        if (floatPanel != null) { floatPanel.hide(); floatPanel = null; }
        if (inst == this) inst = null;
        super.onDestroy();
    }

    // ============================================================
    // busy 状态管理：配合看门狗，杜绝回调丢失导致的永久卡死（长期挂后台后的“点了没反应”）
    // ============================================================
    private void markBusy() {
        busy = true;
        busySince = System.currentTimeMillis();
        main.removeCallbacks(busyWatchdog);
        main.postDelayed(busyWatchdog, BUSY_STALE_MS);
    }

    private void resetBusy() {
        busy = false;
        busySince = 0L;
        main.removeCallbacks(busyWatchdog);
    }

    /** 看门狗：45s 仍无回调，强制复位并提示，保证下次点击可用 */
    private void onBusyTimeout() {
        if (busy) {
            AppLog.e("Service", "AI 请求看门狗超时(" + BUSY_STALE_MS + "ms 无回调)，强制复位 busy");
            busy = false;
            busySince = 0L;
            if (floatBall != null) floatBall.cancelProgress();
            main.post(() -> Toast.makeText(this, "请求超时，已自动恢复，可重试", Toast.LENGTH_LONG).show());
        }
    }

    /** 日志用：把文本压成单行并截断，避免刷屏 */
    private static String briefLog(String s) {
        if (s == null) return "null";
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() > 40 ? one.substring(0, 40) + "…" : one;
    }

    private static boolean endsWithPunct(String s) {
        if (s == null || s.isEmpty()) return false;
        String tail = "。！？~～";
        return tail.indexOf(s.charAt(s.length() - 1)) >= 0;
    }

    // ============================================================
    // 悬浮球：生命周期 + 状态显示 + 快捷菜单
    // ============================================================

    /** 根据配置显示/隐藏悬浮球（Activity 也可调用刷新） */
    public void refreshFloat() {
        main.post(() -> {
            boolean show = Prefs.showFloat() && Settings.canDrawOverlays(this);
            if (show) {
                if (floatBall == null) {
                    floatBall = new FloatBall(this, this::miaoifyCurrentInput, () -> {});
                    floatPanel = new FloatPanel(this, panelListener);
                    floatBall.setPanel(floatPanel);
                    floatBall.setEngine(Prefs.useApi());
                    floatBall.setStatus(statusText());
                }
                floatBall.show();
            } else if (floatBall != null) {
                floatBall.hide();
                if (floatPanel != null) { floatPanel.hide(); floatPanel = null; }
                floatBall = null;
            }
        });
    }

    /** 切换风格/引擎后刷新悬浮球文字 */
    public void updateFloatStatus() {
        main.post(() -> {
            if (floatBall != null) {
                floatBall.setStatus(statusText());
                floatBall.setEngine(Prefs.useApi());
            }
            if (floatPanel != null) floatPanel.refreshSelection();
        });
    }

    /** 刷新悬浮球外观（大小/透明度/图标改动后实时生效） */
    public void refreshFloatBall() {
        main.post(() -> {
            if (floatBall != null) floatBall.refresh();
        });
    }

    /** 悬浮球状态文字：当前人设名 */
    private String statusText() {
        return StyleManager.currentName();
    }

    /** 悬浮面板回调：所有面板操作在此接入（同类内可直接调用私有方法） */
    private final FloatPanel.Listener panelListener = new FloatPanel.Listener() {
        @Override public void onMiaoify() { miaoifyCurrentInput(); }
        @Override public void onGenReply() { showReplyDialog(); }
        @Override public void onUndo() { undoLast(); }
        @Override public void onJudge() { showJudgeStrengthDialog(); }
        @Override public void onEngineToggle() {
            // 循环切换：本地词库 → AI翻译 → AI彻底替换 → 本地词库
            int m = Prefs.engineMode();
            if (m == Prefs.ENGINE_LOCAL_RULES) applyEngine(true, false);
            else if (!Prefs.replaceMode()) applyEngine(true, true);
            else applyEngineMode(Prefs.ENGINE_LOCAL_RULES);
        }
        @Override public void onStylePick(int index) {
            applyPersonaStyle(index);
            if (floatPanel != null) floatPanel.refreshSelection();
        }
        @Override public void onHideFloat() { hideFloat(); }
        @Override public void onOpenSettings() { openSettings(); }
        @Override public void onLocalPreToggle() {
            Prefs.set("localPreStyle", !Prefs.localPreStyle());
            if (floatPanel != null) floatPanel.refreshSelection();
            toast(Prefs.localPreStyle() ? "已开启：AI 前用扩展词打底" : "已关闭：不打底，直接交给 AI");
        }
    };

    /** 从悬浮面板打开 App 设置页 */
    private void openSettings() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            AppLog.e("Service", "打开设置失败", t);
            toast("无法打开设置");
        }
    }

    /** 长按悬浮球：弹出快捷菜单（人设风格 / 引擎 / 撤销 / 隐藏） */
    private void showFloatMenu() {
        if (floatBall == null) return;
        Context themed = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light);
        PopupMenu pm = new PopupMenu(themed, floatBall.getView());

        // 人设风格子菜单（group 1）
        String[] personaNames = StyleManager.personaNames();
        SubMenu personaSub = pm.getMenu().addSubMenu("人设风格");
        for (int i = 0; i < personaNames.length; i++) {
            boolean checked = Prefs.styleIndex() == i;
            personaSub.add(1, i, 0, personaNames[i]).setChecked(checked);
        }
        personaSub.setGroupCheckable(1, true, true);

        // 引擎子菜单（group 3）
        SubMenu engSub = pm.getMenu().addSubMenu("引擎");
        int engMode = Prefs.engineMode();
        engSub.add(3, MENU_ENGINE_LOCAL, 0, "本地词库").setChecked(engMode == Prefs.ENGINE_LOCAL_RULES);
        engSub.add(3, MENU_ENGINE_API, 0, "AI 翻译").setChecked(engMode == Prefs.ENGINE_CLOUD_API && !Prefs.replaceMode());
        engSub.add(3, MENU_ENGINE_REPLACE, 0, "AI 彻底替换").setChecked(engMode == Prefs.ENGINE_CLOUD_API && Prefs.replaceMode());
        engSub.setGroupCheckable(3, true, true);

        pm.getMenu().add(0, MENU_AI_FALLBACK, 0, "AI 失败本地兜底").setChecked(Prefs.aiFallbackLocal());
        pm.getMenu().add(0, MENU_GEN_REPLY, 0, "生成回复（输入对方的话）");
        pm.getMenu().add(0, MENU_JUDGE_STRENGTH, 0, "裁判严格度（当前 " + Prefs.judgeStrictness() + "/5）");
        pm.getMenu().add(0, MENU_UNDO, 0, "撤销上次风格化");
        pm.getMenu().add(0, MENU_HIDE_FLOAT, 0, "隐藏悬浮球");

        pm.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id >= 0 && id < StyleManager.personaCount()) {
                applyPersonaStyle(id);
            } else if (id == MENU_ENGINE_LOCAL) {
                applyEngine(false);
            } else if (id == MENU_ENGINE_API) {
                applyEngine(true, false);
            } else if (id == MENU_ENGINE_REPLACE) {
                applyEngine(true, true);
            } else if (id == MENU_JUDGE_STRENGTH) {
                showJudgeStrengthDialog();
            } else if (id == MENU_GEN_REPLY) {
                showReplyDialog();
            } else if (id == MENU_UNDO) {
                undoLast();
            } else if (id == MENU_AI_FALLBACK) {
                toggleAiFallback();
            } else if (id == MENU_HIDE_FLOAT) {
                hideFloat();
            }

            return true;
        });
        try {
            pm.show();
        } catch (Exception ignored) {
            // 极少数设备无法弹菜单，静默忽略
        }
    }

    private void applyPersonaStyle(int idx) {
        int __n = StyleManager.personaCount();
        if (__n <= 0) return;
        if (idx < 0 || idx >= __n) idx = Math.max(0, Math.min(idx, __n - 1));   // ★v4 修复：补 clamp（同文件 446 行有护栏，此处漏了）
        Prefs.set("styleIndex", idx);
        toast("人设：" + StyleManager.personaNames()[idx]);
        updateFloatStatus();
    }

    private void applyEngine(boolean api) { applyEngine(api, false); }
    private void applyEngine(boolean api, boolean replace) {
        if (DangerLock.isLocked()) return;   // 「千万别点」锁定期，悬浮球也不得切换引擎/替换
        Prefs.setEngineMode(api ? Prefs.ENGINE_CLOUD_API : Prefs.ENGINE_LOCAL_RULES);
        Prefs.set("replaceMode", api && replace);
        String label = !api ? "引擎：本地词库" : (replace ? "引擎：AI 彻底替换" : "引擎：AI 翻译");
        toast(label);
        updateFloatStatus();
        if (floatPanel != null) floatPanel.refreshSelection();
    }

    /** 切换到指定引擎模式（双态）；非云端时关闭彻底替换 */
    private void applyEngineMode(int mode) {
        if (DangerLock.isLocked()) return;
        Prefs.setEngineMode(mode);
        if (mode != Prefs.ENGINE_CLOUD_API) Prefs.set("replaceMode", false);
        String label = mode == Prefs.ENGINE_LOCAL_RULES ? "引擎：本地词库" : "引擎：AI 翻译";
        toast(label);
        updateFloatStatus();
        if (floatPanel != null) floatPanel.refreshSelection();
    }

    /** 4.4：AI 失败本地兜底开关——开：AI 输出异常时自动转本地词库；关：直接报错，绝不本地兜底 */
    private void toggleAiFallback() {
        boolean on = !Prefs.aiFallbackLocal();
        Prefs.set("aiFallbackLocal", on);
        toast(on ? "AI 失败本地兜底：开（异常时转本地词库）" : "AI 失败本地兜底：关（异常直接报错）");
        updateFloatStatus();
    }

    /** v4.7 本地前置打底的裁判豁免清单：打底产生的替换映射（如 我→本小姐）视为用户规则，裁判不得算作新增/偏离 */
    private String exemptOf(String styleKey, String orig) {
        if (!Prefs.localPreStyle() || styleKey == null || styleKey.isEmpty() || orig == null || orig.isEmpty()) return null;
        try {
            String pre = MiaoifyEngine.preStyleAIOnly(orig, styleKey);
            if (pre == null || pre.equals(orig)) return null;
            java.util.List<String[]> maps = TextDiff.extractMappings(orig, pre);
            if (maps == null || maps.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (String[] m : maps) {
                if (m != null && m.length >= 2 && m[0] != null && m[1] != null && !m[0].isEmpty() && !m[1].isEmpty()) {
                    if (sb.length() > 0) sb.append("；");
                    sb.append(m[0]).append("→").append(m[1]);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void hideFloat() {
        Prefs.set("showFloat", false);
        if (floatBall != null) {
            floatBall.hide();
            floatBall = null;
        }
        if (floatPanel != null) { floatPanel.hide(); floatPanel = null; }
        toast("悬浮球已隐藏（可在 App 设置重新开启）");
    }

    // ============================================================
    // 风格化入口
    // ============================================================

    /** 悬浮球点击：风格化当前焦点输入框 */
    public void miaoifyCurrentInput() {
        // 卡死自检：上次请求回调丢失会让 busy 长期为 true（长期挂后台最常见），到时自动恢复
        if (busy) {
            if (System.currentTimeMillis() - busySince > BUSY_STALE_MS) {
                AppLog.w("Service", "检测到 busy 卡死(>" + BUSY_STALE_MS + "ms)，自动复位");
                resetBusy();
                if (floatBall != null) floatBall.cancelProgress();
                toast("上次请求卡住，已自动恢复");
            } else {
                AppLog.d("Service", "正忙，忽略本次点击");
                toast("正在翻译中，请稍候…");
                return;
            }
        }
        AccessibilityNodeInfo edit = findInput();
        if (edit == null) {
            AppLog.w("Service", "未找到可编辑输入框");
            toast("没找到输入框，请先点进聊天输入框");
            return;
        }
        CharSequence cs = edit.getText();
        if (cs == null || cs.toString().trim().isEmpty()) {
            toast("输入框是空的");
            AccessibilityNodes.safeRecycle(edit);
            return;
        }
        String text = cs.toString();
        // 防重翻短路：仅当文本与上次写回相同、指纹（文本+风格+引擎模式）也相同、且不是用户对同句原文主动重新生成时才跳过。
        // 切换风格/引擎后指纹变化，允许同文重翻；输入框内容=上次翻译的原文时视为"重新生成"，也不短路（不吞用户的重翻意图）
        if (text.equals(lastSet) && lastSetFingerprint.equals(fingerprintOf(text))
                && !text.equals(lastFinishedOriginal)) {
            AppLog.d("Service", "防重翻短路：文本与上次写回相同，跳过。text=" + briefLog(text));
            UiFeedback.dedupSkip(this);
            AccessibilityNodes.safeRecycle(edit);
            return;
        }
        // 注意：不在发起前预更新 lastSetFingerprint——指纹只在真正写回结果时更新（setText），
        // 避免"本次未写回但指纹已变"导致下次同文同模式误判（v4.8-⑤）

        if (Prefs.engineMode() == Prefs.ENGINE_CLOUD_API) {
            // 身份诱导输入：本地兜底处理，不发给模型（防自报身份/跑偏成对话）
            if (isIdentityProbe(text)) {
                String out = MiaoifyEngine.miaoify(text);
                if (!guardWriteBack(out)) return;
                HistoryManager.add(text, out, StyleManager.currentName());
                lastOriginal = text; lastFinishedOriginal = text;
                setText(edit, out);
                return;
            }
            String key = Prefs.apiKey().trim();
            if (key.isEmpty()) {
                toast("API 模式需要先填写 Key");
                AccessibilityNodes.safeRecycle(edit);
                return;
            }
            // 极短文本(<=3字)直接走本地：API 对短文本容易加戏/被校验误判，且浪费一次请求
            if (text.length() <= 3) {
                String out = MiaoifyEngine.miaoify(text);
                if (!guardWriteBack(out)) return;
                HistoryManager.add(text, out, StyleManager.currentName());
                lastOriginal = text; lastFinishedOriginal = text;
                setText(edit, out);
                return;
            }
            markBusy();
            toast("处理中…");
            if (floatBall != null) floatBall.startProgress(text.length());
            AccessibilityNodes.safeRecycle(edit);   // 文本已读取，节点不再使用；回调里重新找输入框
            final String reqText = text;
            // 同一句原文在上次已完成后再次发起 = 用户主动重新生成：跳过 API 缓存，重新请求拿新译文
            final boolean regenerate = reqText.equals(lastFinishedOriginal);
            if (regenerate) AppLog.i("Service", "同句重新生成，跳过 API 缓存");
            final boolean replace = Prefs.replaceMode();
            if (replace) {
                AppLog.i("Service", "彻底替换开始 引擎=API 风格=" + StyleManager.currentName());
                // P0-4-3 快照发起时的风格 key：回调兜底必须用发起时的词库，不能用回调时当前风格
                final String styleKeyNow = StyleManager.currentKey();
                ApiMiaoifier.rework(reqText, key, styleKeyNow, StyleManager.currentPersonaBase(), new ApiMiaoifier.Callback() {
                    @Override public void onSuccess(String s) {
                        try {
                            final String out = ApiMiaoifier.sanitize(s);
                            // 1) 硬红线：自报身份/助手腔/视角翻转，不受裁判严格度影响，直接本地兜底
                            if (isDialogueResponse(s, reqText, true)) {
                                String local = MiaoifyEngine.miaoify(reqText, styleKeyNow);
                                AppLog.w("Service", "彻底替换命中对话回复红线，转本地兜底。API=" + briefLog(s)
                                        + " → 本地=" + briefLog(local));
                                finishTranslation(reqText, local, true);
                                return;
                            }
                            // 2) 关闭二次校验 → 直接输出（省一次裁判请求）
                            //    开启校验 → 4.6 多选一裁判每次都走（快筛只用于 L0 过滤候选，不短路主流程，
                            //    避免快筛漏判导致回答式翻译直接输出）
                            final int strict = Prefs.judgeStrictness();
                            if (!Prefs.aiVerify()) {
                                AppLog.i("Service", "彻底替换成功：" + briefLog(out));
                                finishTranslation(reqText, out, true);
                                return;
                            }
                            // 3) 4.6 多选一裁判——生成风格浓度梯度候选池，K 裁判选择题投票
                            //    初版输出 s 作为候选0 入池参与投票（方向错误会被 L0 剔除）
                            AppLog.i("Service", "彻底替换启动 4.6 多选一裁判 严格度=" + strict + "：原文=" + briefLog(reqText));
                            runReworkSelect(reqText, key, styleKeyNow, strict, s);
                        } catch (Throwable t) {
                            resetBusy();
                            if (floatBall != null) { floatBall.cancelProgress(); floatBall.flashError(); }
                            AppLog.e("Service", "彻底替换回调异常", t);
                        }
                    }
                    @Override public void onError(String m) {
                        resetBusy();
                        if (floatBall != null) floatBall.cancelProgress();
                        AppLog.w("Service", "彻底替换失败：" + m);
                        toastError("彻底替换失败：" + m);
                    }
                });
                return;
            }
            final String promptNow = StyleManager.currentPrompt();
            final String[][] shotsNow = StyleManager.fewShotExamples();
            final String styleKeyNow = StyleManager.currentKey();
            if (Prefs.streamEnabled()) {
                AppLog.i("Service", "AI 流式翻译开始 引擎=API(SSE) 风格=" + StyleManager.currentName() + (regenerate ? "（重新生成）" : ""));
                ApiMiaoifier.streamTranslate(reqText, key, promptNow, shotsNow, regenerate, new ApiMiaoifier.StreamCallback() {
                    @Override public void onDelta(String full, String delta, float p) {
                        main.post(() -> { if (floatBall != null) floatBall.updateStreamProgress(p); });
                    }
                    @Override public void onSuccess(String s) { handleApiTranslateSuccess(s, reqText, key, styleKeyNow); }
                    @Override public void onError(String m) {
                        AppLog.w("Service", "流式不可用，回退非流式：" + m);
                        ApiMiaoifier.miaoify(reqText, key, promptNow, shotsNow, regenerate, new ApiMiaoifier.Callback() {
                            @Override public void onSuccess(String s) { handleApiTranslateSuccess(s, reqText, key, styleKeyNow); }
                            @Override public void onError(String e) { handleApiTranslateError(e, reqText); }
                        });
                    }
                });
            } else {
                AppLog.i("Service", "AI 翻译开始 引擎=API 风格=" + StyleManager.currentName() + (regenerate ? "（重新生成）" : ""));
                ApiMiaoifier.miaoify(reqText, key, promptNow, shotsNow, regenerate, new ApiMiaoifier.Callback() {
                    @Override public void onSuccess(String s) { handleApiTranslateSuccess(s, reqText, key, styleKeyNow); }
                    @Override public void onError(String m) { handleApiTranslateError(m, reqText); }
                });
            }
        } else {
            try {
                long t0 = System.currentTimeMillis();
                String out = MiaoifyEngine.miaoify(text);
                AppLog.i("Local", "本地翻译 耗时=" + (System.currentTimeMillis() - t0)
                        + "ms 风格=" + StyleManager.currentName()
                        + " 原文=" + briefLog(text) + " 译文=" + briefLog(out));
                if (!guardWriteBack(out)) return;
                HistoryManager.add(text, out, StyleManager.currentName());
                lastOriginal = text; lastFinishedOriginal = text;
                setText(edit, out);
            } catch (Throwable t) {
                AppLog.e("Local", "本地翻译异常", t);
                toast("本地翻译出错，已记录日志");
                try { AccessibilityNodes.safeRecycle(edit); } catch (Throwable ignored) {}
            }
        }
    }

    /** 触发词模式：翻译触发词后的内容，替换整段（含触发词） */
    private void triggerTranslate(AccessibilityNodeInfo src, String fullText, String word) {
        String content = fullText.substring(word.length());
        triggerUndoText = fullText;   // 撤销：恢复触发前全文
        AccessibilityNodes.safeRecycle(src);
        if (content.trim().isEmpty()) return; // 空内容不触发（双保险）
        if (Prefs.engineMode() == Prefs.ENGINE_CLOUD_API) {
            String key = Prefs.apiKey().trim();
            if (key.isEmpty()) {
                main.post(() -> toast("触发词翻译需要 API Key，请先填写"));
                return;
            }
            AppLog.i("Service", "触发词 AI 翻译 内容=" + briefLog(content));
            final String promptNow = StyleManager.currentPrompt();
            final String[][] shotsNow = StyleManager.fewShotExamples();
            final String styleKeyNow = StyleManager.currentKey();
            ApiMiaoifier.miaoify(content, key, promptNow, shotsNow, false, new ApiMiaoifier.Callback() {
                @Override public void onSuccess(String s) { handleApiTranslateSuccess(s, content, key, styleKeyNow); }
                @Override public void onError(String m) { handleApiTranslateError(m, content); }
            });
        } else {
            String out = MiaoifyEngine.miaoify(content);
            AppLog.i("Local", "触发词本地翻译：" + briefLog(content) + " → " + briefLog(out));
            finishTranslation(content, out);
        }
    }

    /** 触发词模式开关（默认关闭；汉堡栏实验功能调用） */
    public void toggleTrigger() {
        boolean on = !Prefs.triggerEnabled();
        Prefs.set("triggerEnabled", on);
        toast(on ? "触发词模式已开启" : "触发词模式已关闭");
        AppLog.i("Service", "触发词模式 -> " + on);
    }

    /** 设置触发词（默认 ?翻；触发词首字符写两遍可输出字面，如 ??翻 → ?翻） */
    public void showTriggerWordDialog() {
        main.post(() -> {
            try {
                Context themed = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog);
                final EditText et = new EditText(themed);
                et.setText(Prefs.triggerWord());
                et.setHint("默认：?翻");
                int pad = dp(16);
                et.setPadding(pad, pad, pad, pad);
                AlertDialog.Builder b = new AlertDialog.Builder(themed);
                b.setTitle("设置触发词");
                b.setMessage("输入「触发词+内容」即翻译；触发词首字符写两遍可输出字面");
                b.setView(et);
                b.setPositiveButton("保存", (d, w) -> {
                    String v = et.getText().toString().trim();
                    if (v.isEmpty()) v = "?翻";
                    Prefs.set("triggerWord", v);
                    toast("触发词已设为：" + v);
                    d.dismiss();
                });
                b.setNegativeButton("取消", (d, w) -> d.dismiss());
                AlertDialog dlg = b.create();
                dlg.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                try { dlg.show(); } catch (Throwable t) {
                    AppLog.e("Service", "弹出触发词设置对话框失败", t);
                    toast("无法弹出对话框，请检查悬浮窗权限");
                }
            } catch (Throwable t) {
                AppLog.e("Service", "触发词设置异常", t);
            }
        });
    }

    /** 撤销最近一次触发词翻译：恢复触发前全文 */
    public void undoTrigger() {
        if (triggerUndoText == null || triggerUndoText.isEmpty()) {
            toast("没有可撤销的触发词翻译");
            return;
        }
        final String origin = triggerUndoText;
        AccessibilityNodeInfo edit = findInput();
        if (edit != null) {
            setText(edit, origin);
            triggerUndoText = "";
            AppLog.i("Service", "已撤销触发词翻译，恢复原文：" + briefLog(origin));
            toast("已恢复触发前原文");
        } else {
            triggerUndoText = "";
            if (copyToClipboard(origin)) {
                toast("未找到输入框，原文已复制到剪贴板");
            } else {
                toast("未找到输入框，复制失败，请手动复制");
            }
        }
    }
    /** 撤销上一次风格化：把缓存的原文写回当前输入框（只能撤销最近一次） */
    public void undoLast() {
        if (lastOriginal == null || lastOriginal.isEmpty()) {
            toast("没有可撤销的风格化记录");
            return;
        }
        final String origin = lastOriginal;
        AccessibilityNodeInfo edit = findInput();
        if (edit != null) {
            setText(edit, origin);
            lastOriginal = "";
            AppLog.i("Service", "已撤销上次风格化，恢复原文：" + briefLog(origin));
            toast("已恢复原文");
        } else {
            lastOriginal = "";
            if (copyToClipboard(origin)) {
                toast("未找到输入框，原文已复制到剪贴板");
            } else {
                toast("未找到输入框，复制失败，请手动复制");
            }
        }
    }
    // ============================================================
    // 生成回复：复制对方的话 → 长按悬浮球 → AI 以当前人设生成回复填入输入框
    // ============================================================
        // ============================================================
    // 生成回复：弹出输入框，用户粘贴/输入对方的话 → AI 以当前人设生成回复填入输入框
    // ============================================================
    /** 悬浮球菜单：AI 裁判严格度调节对话框（SeekBar 1-5，即时预览+确定保存） */
    private void showJudgeStrengthDialog() {
        main.post(() -> {
            Context themed = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog);
            AlertDialog.Builder b = new AlertDialog.Builder(themed);
            b.setTitle("AI 裁判严格度");
            final String[] levels = {"最宽松", "偏宽松", "平衡(默认)", "偏严格", "最严格"};
            final String[] descs = {
                "几乎不拦截，风格化扩写一律算忠实",
                "允许较大幅度风格化，只拦明确跑偏",
                "平衡：正常风格化放行，加戏/对话拦截",
                "偏严格：不鼓励扩写，添加情绪可能判跑偏",
                "最严格：只允许换自称+口癖，任何添加都可能跑偏"
            };
            LinearLayout box = new LinearLayout(themed);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(16);
            box.setPadding(pad, pad, pad, pad);
            final TextView tvLevel = new TextView(themed);
            tvLevel.setTextSize(16);
            tvLevel.setTypeface(null, android.graphics.Typeface.BOLD);
            int cur = Prefs.judgeStrictness();
            tvLevel.setText(levels[cur - 1] + "（" + cur + "/5）");
            box.addView(tvLevel);
            final TextView tvDesc = new TextView(themed);
            tvDesc.setTextSize(13);
            tvDesc.setTextColor(0xFF666666);
            tvDesc.setPadding(0, dp(4), 0, dp(10));
            tvDesc.setText(descs[cur - 1]);
            box.addView(tvDesc);
            final SeekBar sb = new SeekBar(themed);
            sb.setMax(4);
            sb.setProgress(cur - 1);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    tvLevel.setText(levels[progress] + "（" + (progress + 1) + "/5）");
                    tvDesc.setText(descs[progress]);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            box.addView(sb);
            b.setView(box);
            b.setPositiveButton("确定", (d, w) -> {
                int level = sb.getProgress() + 1;
                Prefs.set("judgeStrictness", level);
                toast("裁判严格度：" + levels[sb.getProgress()]);
                d.dismiss();
            });
            b.setNegativeButton("取消", (d, w) -> d.dismiss());
            AlertDialog dlg = b.create();
            dlg.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            try { dlg.show(); } catch (Throwable t) {
                AppLog.e("Service", "弹出裁判严格度对话框失败", t);
                toast("无法弹出对话框，请检查悬浮窗权限");
            }
        });
    }
    public void showReplyDialog() {
        // 复用卡死自检
        if (busy) {
            if (System.currentTimeMillis() - busySince > BUSY_STALE_MS) {
                AppLog.w("Service", "回复：检测到 busy 卡死，自动复位");
                resetBusy();
            } else {
                toast("正在处理中，请稍候…");
                return;
            }
        }
        final String key = Prefs.apiKey().trim();
        if (key.isEmpty()) {
            toast("生成回复需要调用 AI，请先在设置里填写 API Key");
            return;
        }

        main.post(() -> {
            Context themed = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog);
            AlertDialog.Builder b = new AlertDialog.Builder(themed);
            b.setTitle("生成回复");

            // 输入框
            final EditText input = new EditText(themed);
            input.setHint("长按这里粘贴对方发来的话，或手动输入…");
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setGravity(Gravity.TOP | Gravity.START);
            input.setMinLines(3);
            input.setMaxLines(6);
            int pad = dp(14);
            input.setPadding(pad, pad, pad, pad);
            input.setTextSize(15);

            LinearLayout box = new LinearLayout(themed);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(pad, 0, pad, 0);
            box.addView(input);
            b.setView(box);

            b.setPositiveButton("生成回复", null);  // 自定义点击，不自动关闭
            b.setNegativeButton("取消", (d, w) -> d.dismiss());

            final AlertDialog dlg = b.create();
            dlg.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            try {
                dlg.show();
            } catch (Throwable t) {
                AppLog.e("Service", "弹出生成回复对话框失败", t);
                toast("无法弹出对话框，请检查悬浮窗权限");
                return;
            }
            // 弹起键盘
            input.requestFocus();
            dlg.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

            // 自定义「生成回复」按钮：校验非空后生成，不自动关闭对话框
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String otherText = input.getText().toString().trim();
                if (otherText.isEmpty()) {
                    input.setError("请先输入或粘贴对方的话");
                    return;
                }
                if (otherText.length() > 2000) otherText = otherText.substring(0, 2000);
                dlg.dismiss();
                callReplyApi(otherText, key);
            });
        });
    }

    /** dp 转 px（Service 内通用） */
    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

private void callReplyApi(final String otherText, String key) {
        markBusy();
        toast("正在生成回复…");
        if (floatBall != null) floatBall.startProgress(Math.max(otherText.length(), 20));
        AppLog.i("Service", "AI 生成回复开始 风格=" + StyleManager.currentName()
                + " 对方=" + briefLog(otherText));
        ApiMiaoifier.generateReply(otherText, key, StyleManager.currentPrompt(), new ApiMiaoifier.Callback() {
            @Override public void onSuccess(String s) {
                resetBusy();
                if (floatBall != null) { floatBall.completeProgress(); floatBall.flashSuccess(); }
                final String out = ApiMiaoifier.sanitize(s);
                HistoryManager.add(otherText, out, "回复·" + StyleManager.currentName());
                AppLog.i("Service", "AI 生成回复成功：" + briefLog(out));
                main.post(() -> {
                    AccessibilityNodeInfo target = findInput();
                    if (target != null) {
                        setText(target, out);
                    } else {
                        AppLog.w("Service", "回复回调时输入框焦点丢失，复制到剪贴板");
                        if (copyToClipboard(out)) {
                            toast("输入框焦点已变化，回复已复制到剪贴板");
                        } else {
                            toast("输入框焦点已变化，复制失败，请手动复制");
                        }
                    }
                });
            }
            @Override public void onError(String m) {
                resetBusy();
                if (floatBall != null) floatBall.cancelProgress();
                AppLog.w("Service", "AI 生成回复失败：" + m);
                toastError("生成回复失败：" + m);
            }
        });
    }

    // ============================================================
    // 检测与兜底
    // ============================================================

    /** 身份诱导检测：命中则本地兜底，避免模型自报身份/跑偏成对话 */
    private static boolean isIdentityProbe(String text) {
        if (text == null) return false;
        String t = text.toLowerCase();
        String[] probes = {
            "你是什么模型", "你是什么大模型", "你是什么ai", "你是什么人工智能",
            "你是谁开发的", "谁开发了你", "你主人是谁", "你爸爸是谁",
            "你是哪个ai", "你是deepseek", "你是gpt", "你是chatgpt",
            "你叫什么名字", "你是谁", "你能做什么", "你会什么", "你有什么功能",
            "谁做的你", "谁发明的你", "谁制造的你", "你是人吗", "你是真人吗",
            "你是机器人吗", "你是ai吗", "你是assistant吗", "你是不是ai"
        };
        for (String kw : probes) {
            if (t.contains(kw)) return true;
        }
        return false;
    }

    /** 翻译/裁判完成后的统一收尾：复位 busy、进度条到 100%、入历史、写回输入框（焦点丢失则复制剪贴板） */
    private void finishTranslation(final String orig, final String out) {
        finishTranslation(orig, out, false);
    }

    private void finishTranslation(final String orig, final String out, boolean relaxedGuard) {
        // P0-5-1 ContentGuard：拦截直白违规输出，不写回、不走本地兜底（relaxedGuard=true 走宽松判定）
        if (!guardWriteBack(out, relaxedGuard)) return;
        resetBusy();
        if (floatBall != null) floatBall.completeProgress();
        HistoryManager.add(orig, out, StyleManager.currentName());
        lastOriginal = orig; lastFinishedOriginal = orig;
        main.post(() -> {
            AccessibilityNodeInfo target = findInput();
            if (target != null) {
                setText(target, out);
            } else {
                AppLog.w("Service", "回调时输入框焦点已丢失，复制到剪贴板");
                if (copyToClipboard(out)) {
                    toast("输入框焦点已变化，译文已复制到剪贴板");
                } else {
                    toast("输入框焦点已变化，复制失败，请手动复制");
                }
            }
        });
    }

    /** P0-5-1 写回前审核：通过返回 true（可写回）；拦截返回 false（不写回、不走本地兜底） */
    private boolean guardWriteBack(String out) {
        return guardWriteBack(out, false);
    }

    /** P0-5-1 写回前审核（标准/宽松）：relaxed=true 用于彻底替换（风格化输出），走宽松判定 */
    private boolean guardWriteBack(String out, boolean relaxed) {
        boolean blocked = relaxed ? ContentGuard.isBlockedRelaxed(out) : ContentGuard.isBlocked(out);
        if (!blocked) return true;
        AppLog.w("Service", "P0-5 ContentGuard 拦截输出（" + (relaxed ? "宽松" : "标准") + "），不写回、不走本地兜底");
        toast(ContentGuard.blockMessage());
        return false;
    }

    /**
     * 快筛：译文是否"可疑"到需要 AI 裁判二次确认。
     * 阈值随严格度变化：1=最宽松(几乎直接放行)，5=最严格(几乎所有添加都进裁判)。
     */
    // ============================================================
    // 4.6 多选一裁判核心流程（彻底替换 / 纯翻译共用）
    // 候选生成（风格浓度梯度，末位=零风格纯转述保底）→ L0 过滤 → K 裁判选择题投票
    // → 输出；全不合格→带理由重生成一轮→再不合格落保底候选（禁碎片）
    // ============================================================

    /**
     * L0 候选过滤：末位保底候选只过"对话红线"（零风格转述不会被误伤）；
     * 其余候选过红线+快筛。返回通过列表，fallbackRef[0] 记录可用保底候选。
     */
    private static java.util.List<String> filterCandidates(String[] cands, String orig,
                                                           boolean rework, int strict, String[] fallbackRef) {
        java.util.List<String> pass = new java.util.ArrayList<>();
        if (cands == null) return pass;
        int last = cands.length - 1;
        for (int i = 0; i < cands.length; i++) {
            String c = cands[i];
            if (c == null || c.trim().isEmpty()) continue;
            // ★v4.8：与原文相同的候选一律视为保底（双轨补齐可能产生多个原文候选），去重且只做方向检查
            if (c.equals(orig)) {
                if (fallbackRef[0] == null) fallbackRef[0] = c;
                if (!pass.contains(c)) pass.add(c);
                continue;
            }
            if (i == last) {   // 保底候选
                if (!isDialogueResponse(c, orig, rework)) {
                    pass.add(c);
                    fallbackRef[0] = c;
                }
            } else {
                if (isDialogueResponse(c, orig, rework)) continue;
                if (rework) { if (looksReworkSuspicious(c, orig, strict)) continue; }
                else        { if (looksSuspicious(c, orig, strict)) continue; }
                pass.add(c);
            }
        }
        return pass;
    }

    /** 落保底候选（零风格转述，最忠实）：优先池内末位保底，其次池内第一个通过 L0 的候选 */
    private static String pickFallback(java.util.List<String> pool, String[] fallbackRef) {
        if (fallbackRef != null && fallbackRef[0] != null) return fallbackRef[0];
        if (pool != null && !pool.isEmpty()) return pool.get(pool.size() - 1);
        return null;
    }

    /** v5.0 裁判联动：扩写等级 + 风格强度越高，候选数/裁判数下限越高（用户档位只增不减，封顶 16/3）。
     *  特例：用户档位为 1×1 一对一档时严格 1 候选 1 裁判，不联动抬升（尊重用户显式选择）。 */
    private static int effCandidateCount() {
        if (Prefs.candidateCount() == 1 && Prefs.judgeCount() == 1) return 1;
        int lvl = Prefs.expandLevel();
        int itn = Prefs.styleIntensity();
        int eff = Math.max(Prefs.candidateCount(), 3 + lvl + itn);
        return Math.min(eff, 16);
    }
    private static int effJudgeCount() {
        if (Prefs.judgeCount() == 1 && Prefs.candidateCount() == 1) return 1;
        int lvl = Prefs.expandLevel();
        int itn = Prefs.styleIntensity();
        int base = Prefs.judgeCount();
        int boost = (lvl >= 3 || itn >= 4) ? 1 : 0;
        boost += (lvl >= 5 || itn >= 5) ? 1 : 0;
        return Math.min(Math.max(base, 1 + boost), 6);
    }

    /** 彻底替换多选一：候选池 → L0 → K 裁判 → 输出；全不合格带理由重生成一轮，二次落保底；兜底本地 */
    private void runReworkSelect(final String reqText, final String key, final String styleKeyNow, final int strict,
                                 final String firstCandidate) {
        final int N = effCandidateCount();
        final int K = effJudgeCount();
        final String persona = StyleManager.currentPersonaBase();
        final String[][] shots = StyleManager.fewShotExamples();
        final boolean[] second = {false};
        final String[] fixHint = {""};
        selectRound(reqText, key, styleKeyNow, strict, N, K, persona, shots, true, second, fixHint, firstCandidate);
    }

    /** 纯翻译多选一：同上，但 P0-4-4 绝不本地兜底——保底候选是 API 零风格转述，允许；无保底则报错 */
    private void runTranslateSelect(final String reqText, final String key, final String styleKey,
                                    final String firstCandidate) {
        final int N = effCandidateCount();
        final int K = effJudgeCount();
        final String promptNow = StyleManager.currentPrompt();
        final String[][] shotsNow = StyleManager.fewShotExamples();
        final boolean[] second = {false};
        final String[] fixHint = {""};
        selectRound(reqText, key, styleKey, Prefs.judgeStrictness(), N, K, promptNow, shotsNow, false, second, fixHint, firstCandidate);
    }

    /** 多选一单轮（可递归第二轮）：生成候选 → 初版输出入池 → L0 过滤 → K 裁判投票 → 输出/修正 */
    private void selectRound(final String reqText, final String key, final String styleKey,
                             final int strict, final int N, final int K,
                             final String persona, final String[][] shots, final boolean rework,
                             final boolean[] second, final String[] fixHint, final String firstCandidate) {
        ApiMiaoifier.generateCandidates(reqText, key, styleKey, persona, shots, N, rework, second[0], fixHint[0],
                new ApiMiaoifier.CandidateCallback() {
            @Override public void onSuccess(String[] candidates) {
                try {
                    final String[] fallbackRef = new String[1];
                    final java.util.List<String> pool = filterCandidates(candidates, reqText, rework, strict, fallbackRef);
                    // 初版 API 输出入池（候选0）：用户看到的"最好的"不得被丢弃；
                    // 方向错误会被 L0 剔除（isDialogueResponse / 快筛），安全
                    if (firstCandidate != null && !firstCandidate.trim().isEmpty()) {
                        String fc = firstCandidate.trim();
                        if (!isDialogueResponse(fc, reqText, rework)) {
                            if (!rework || !looksReworkSuspicious(fc, reqText, strict)) {
                                pool.add(0, fc);
                            }
                        }
                    }
                    if (pool.isEmpty()) {
                        // 池空：带理由重生成一轮；二次仍空 → 彻底替换本地兜底 / 纯翻译报错
                        if (!second[0]) {
                            second[0] = true;
                            fixHint[0] = "上一次全部候选被判定为回答式/跑偏，请严格转述原文本身，禁止回答、反问或自报身份";
                            AppLog.w("Service", "4.6 候选池全被 L0 剔除，带修正提示重生成一轮");
                            selectRound(reqText, key, styleKey, strict, N, K, persona, shots, rework, second, fixHint, null);
                        } else {
                            AppLog.w("Service", "4.6 候选池两轮全空，彻底替换转本地兜底");
                            if (rework) {
                                finishTranslation(reqText, MiaoifyEngine.miaoify(reqText, styleKey), true);
                            } else {
                                resetBusy();
                                if (floatBall != null) floatBall.cancelProgress();
                                main.post(() -> toast("AI 输出跑偏，请重试"));
                            }
                        }
                        return;
                    }
                    // 只剩一个候选（通常是保底）：直接采用，不再浪费裁判
                    if (pool.size() == 1) {
                        AppLog.i("Service", "4.6 候选池过滤后仅剩 1 个，直接采用：" + briefLog(pool.get(0)));
                        if (rework) finishTranslation(reqText, pool.get(0), true);
                        else deliver(reqText, pool.get(0));
                        return;
                    }
                    AppLog.i("Service", "4.6 多选一裁判开始 N=" + pool.size() + " K=" + K + "（原始 N=" + N + "）");
                    ApiMiaoifier.verifySelect(reqText, pool.toArray(new String[0]), StyleManager.currentName(),
                            strict, rework, exemptOf(styleKey, reqText), key, K, (choice, reason) -> {
                        if (choice >= 0) {
                            String chosen = pool.get(choice);
                            AppLog.i("Service", "4.6 多选一选中候选#" + choice + "：" + briefLog(chosen));
                            if (rework) finishTranslation(reqText, chosen, true);
                            else deliver(reqText, chosen);
                        } else if (choice == -1) {
                            // 全不合格：带裁判理由重生成一轮；二次仍不合格 → 落保底候选
                            if (!second[0]) {
                                second[0] = true;
                                fixHint[0] = "裁判意见：" + (reason == null || reason.isEmpty() ? "候选是回答式而非转述" : reason)
                                        + "；请严格转述原文本身，禁止回答、反问或自报身份";
                                AppLog.w("Service", "4.6 裁判全不合格，带理由重生成一轮。理由=" + fixHint[0]);
                                selectRound(reqText, key, styleKey, strict, N, K, persona, shots, rework, second, fixHint, null);
                            } else {
                                String fb = pickFallback(pool, fallbackRef);
                                if (fb != null) {
                                    AppLog.w("Service", "4.6 二次仍不合格，落保底候选：" + briefLog(fb));
                                    if (rework) finishTranslation(reqText, fb, true);
                                    else deliver(reqText, fb);
                                } else if (rework) {
                                    AppLog.w("Service", "4.6 无保底候选，彻底替换转本地兜底");
                                    finishTranslation(reqText, MiaoifyEngine.miaoify(reqText, styleKey), true);
                                } else {
                                    resetBusy();
                                    if (floatBall != null) floatBall.cancelProgress();
                                    main.post(() -> toast("AI 输出跑偏，请重试"));
                                }
                            }
                        } else {
                            // 裁判全部失败（-2）：落保底候选，绝不停摆
                            String fb = pickFallback(pool, fallbackRef);
                            if (fb != null) {
                                AppLog.w("Service", "4.6 裁判全部失败，落保底候选：" + briefLog(fb));
                                if (rework) finishTranslation(reqText, fb, true);
                                else deliver(reqText, fb);
                            } else if (rework) {
                                AppLog.w("Service", "4.6 裁判失败且无保底，彻底替换转本地兜底");
                                finishTranslation(reqText, MiaoifyEngine.miaoify(reqText, styleKey), true);
                            } else {
                                resetBusy();
                                if (floatBall != null) floatBall.cancelProgress();
                                main.post(() -> toast("AI 输出异常，请重试"));
                            }
                        }
                    });
                } catch (Throwable t) {
                    resetBusy();
                    if (floatBall != null) floatBall.cancelProgress();
                    AppLog.e("Service", "4.6 多选一回调异常", t);
                }
            }
            @Override public void onError(String msg) {
                // 候选生成失败：彻底替换转本地兜底；纯翻译报错（P0-4-4）
                AppLog.w("Service", "4.6 候选生成失败：" + msg);
                if (rework) {
                    finishTranslation(reqText, MiaoifyEngine.miaoify(reqText, styleKey), true);
                } else {
                    resetBusy();
                    if (floatBall != null) floatBall.cancelProgress();
                    main.post(() -> toast("AI 输出异常，请重试"));
                }
            }
        });
    }

    // ===== v3.3：AI 翻译（非彻底替换）成功/失败的统一处理，流式与非流式共用 =====
    private void handleApiTranslateSuccess(String s, final String reqText, final String key, final String styleKey) {
        try {
            final String sanitized = ApiMiaoifier.sanitize(s);
            // P0-4-4 纯翻译绝不本地兜底：对话式回应视为 AI 输出异常，直接报错，不偷偷本地风格化
            if (isDialogueResponse(s, reqText)) {
                AppLog.w("Service", "规则判定对话式回应，纯翻译不本地兜底。API=" + briefLog(s));
                resetBusy();
                if (floatBall != null) floatBall.cancelProgress();
                main.post(() -> toast("AI 输出异常（对话式回应），请重试"));
                return;
            }
            // 关闭校验 → 直接输出；开启校验 → 4.6 多选一每次都走（快筛只用于 L0 过滤，不短路）
            if (!Prefs.aiVerify()) {
                deliver(reqText, sanitized);
                return;
            }
            AppLog.i("Service", "纯翻译启动 4.6 多选一裁判：原文=" + briefLog(reqText));
            runTranslateSelect(reqText, key, styleKey, sanitized);
        } catch (Throwable t) {
            resetBusy();
            if (floatBall != null) floatBall.cancelProgress();
            AppLog.e("Service", "成功回调处理异常", t);
        }
    }

    private void handleApiTranslateError(String m, final String reqText) {
        resetBusy();
        if (floatBall != null) floatBall.cancelProgress();
        AppLog.w("Service", "AI 翻译失败：" + m);
        // P0-4-4 纯翻译绝不本地兜底：直接报错，不偷偷用本地词库顶替
        toastError("风格化失败：" + m);
    }

    /** 结果投递：开启预览模式先弹确认条（点采用才真正写入），否则直接写入输入框 */
    private void deliver(final String orig, final String out) {
        if (Prefs.previewMode()) {
            main.post(() -> showPreviewBubble(orig, out));
        } else {
            finishTranslation(orig, out);
        }
    }

    private void showPreviewBubble(final String orig, final String out) {
        if (previewBubble == null) previewBubble = new PreviewBubble(this);
        previewBubble.hide();
        final int pct = TextDiff.changePercent(orig, out);
        previewBubble.show(out, pct, new PreviewBubble.Listener() {
            @Override public void onAccept() { finishTranslation(orig, out); }
            @Override public void onCancel() {
                resetBusy();
                if (floatBall != null) floatBall.cancelProgress();
                toast("已取消，输入框保持原文");
            }
        });
    }
    private static boolean looksSuspicious(String trans, String orig, int strictness) {
        if (trans == null || trans.trim().isEmpty()) return true;
        double lenMult; int sentDiff; boolean checkParen; boolean checkRhetoric;
        switch (strictness) {
            case 1:  lenMult = 3.0; sentDiff = 5; checkParen = false; checkRhetoric = false; break;
            case 2:  lenMult = 2.2; sentDiff = 3; checkParen = true;  checkRhetoric = false; break;
            case 4:  lenMult = 1.3; sentDiff = 1; checkParen = true;  checkRhetoric = true;  break;
            case 5:  lenMult = 1.1; sentDiff = 1; checkParen = true;  checkRhetoric = true;  break;
            default: lenMult = 1.6; sentDiff = 2; checkParen = true;  checkRhetoric = true;
        }
        if (checkParen && (trans.indexOf('(') >= 0 || trans.indexOf('（') >= 0)) return true;
        if (orig != null && orig.length() > 0 && trans.length() > orig.length() * lenMult + 6) return true;
        if (checkRhetoric) {
            boolean origQ = orig != null && (orig.indexOf('？') >= 0 || orig.indexOf('?') >= 0);
            String[] sus = {"对不对", "是不是", "你觉得", "主人觉得", "对吧", "是吧", "你说呢",
                    "怎么样呢", "好不好", "要不要", "？", "?"};
            for (String k : sus) {
                boolean isQ = k.equals("？") || k.equals("?");
                if (trans.contains(k)) {
                    if (isQ && origQ) continue;
                    return true;
                }
            }
        }
        if (strictness >= 5) {
            String[] emo = {"觉得", "认为", "开心", "难过", "生气", "伤心", "高兴", "兴奋", "害怕", "担心", "好奇", "委屈", "害羞"};
            for (String k : emo) {
                if (trans.contains(k) && (orig == null || !orig.contains(k))) return true;
            }
        }
        // 视角翻转信号（不受严格度影响，一定送裁判）：原文是问句、译文问号消失；或“主人问/你说…自称”转述结构
        boolean oq = orig != null && (orig.indexOf('？') >= 0 || orig.indexOf('?') >= 0
                || orig.matches(".*(吗|呢|怎么|什么|为什么|干嘛|干什么|做什么|如何|谁|哪|几|多少).*"));
        boolean tq = trans.indexOf('？') >= 0 || trans.indexOf('?') >= 0;
        if (oq && !tq) return true;
        if (trans.matches(".*(主人|你).{0,5}(问|说).{0,8}(本喵|人家|吾|本小姐|本大爷|本系统|我).*")) return true;
        if (countSentences(trans) >= countSentences(orig) + sentDiff) return true;
        return false;
    }

    /** 彻底替换（再创作）结果快筛：比翻译版 looksSuspicious 更宽松——允许大幅扩写/换措辞/情绪渲染，
     *  只抓"跑偏成对话"信号（反问用户、视角翻转、篇幅离谱、高严格度下句子暴增）。返回 true=可疑，送 AI 裁判 */
    private static boolean looksReworkSuspicious(String trans, String orig, int strictness) {
        if (trans == null || trans.trim().isEmpty()) return true;
        // 长度倍数：再创作允许扩写，阈值比翻译宽松，严格度越高越紧
        double lenMult;
        switch (strictness) {
            case 1:  lenMult = 6.0; break;   // 最宽松：几乎不限篇幅
            case 2:  lenMult = 4.0; break;
            case 4:  lenMult = 2.2; break;
            case 5:  lenMult = 1.6; break;
            default: lenMult = 3.0;
        }
        if (orig != null && orig.length() > 0 && trans.length() > orig.length() * lenMult + 10) return true;
        // 反问/求助用户：再创作也不允许把"要说出去的话"变成向用户提问或提供帮助
        String[] askUser = {"你觉得", "主人觉得", "你说呢", "怎么样呢", "要不要我", "需要我帮", "请问你", "我来帮你"};
        for (String k : askUser) if (trans.contains(k)) return true;
        // 视角翻转（不受严格度影响）：原文是问句、候选却无问号且以人设自称开头；或"主人问/你说…自称"转述自答
        boolean oq = orig != null && (orig.indexOf('？') >= 0 || orig.indexOf('?') >= 0
                || orig.matches(".*(吗|呢|怎么|什么|为什么|干嘛|干什么|做什么|如何|谁|哪|几|多少).*"));
        boolean tq = trans.indexOf('？') >= 0 || trans.indexOf('?') >= 0;
        if (oq && !tq && startsWithPersonaSelf(trans)) return true;
        if (trans.matches(".*(主人|你).{0,5}(问|说).{0,8}(本喵|人家|吾|本小姐|本大爷|本系统|我).*")) return true;
        // 高严格度：新增句子数过多视为可疑
        if (strictness >= 4 && countSentences(trans) >= countSentences(orig) + 3) return true;
        return false;
    }

    private static int countSentences(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
                    || c == '…' || c == '\n') n++;
        }
        return n;
    }

    /** 对话回应兜底检测：模型自报身份、助手腔或篇幅暴增时判为跑偏成对话，转本地兜底 */
    private static boolean isDialogueResponse(String resp, String orig) {
        return isDialogueResponse(resp, orig, false);
    }
    /** 对话回应兜底检测（可放宽）：allowExpand=true 用于彻底替换模式，允许大幅扩写，跳过篇幅暴增判据，只保留真正的对话回复红线 */
    private static boolean isDialogueResponse(String resp, String orig, boolean allowExpand) {
        if (resp == null || resp.trim().isEmpty()) return true;
        String r = resp.trim();
        // 一开口就是助手自我介绍
        if (r.matches(".*我是(DeepSeek|deepseek|AI|ai|人工智能|大模型|语言模型|模型|助手|机器人|豆包|ChatGPT|GPT|一个|一款|由|OpenAI|字节|深度求索).*"))
            return true;
        if (r.matches(".*(抱歉，?我是|很抱歉，?我是|你好！?我是|你好呀！?我是|我是你的|很高兴认识你，?我是).*"))
            return true;
        // 助手腔 / 拒答套话关键词（4.6：加 orig 守卫——原文本身含"以下是/很高兴为你"等词不得误杀忠实译文）
        String[] assistantTone = {
            "作为一个AI", "作为一个ai", "作为AI", "作为人工智能", "作为大模型", "作为语言模型", "作为模型", "作为助手",
            "我能帮你", "我可以帮您", "我可以帮你", "有什么可以帮你", "有什么可以为您", "请问有什么", "需要我帮忙", "有什么能帮",
            "很高兴为您", "很高兴为你", "为您服务", "为你服务", "我是一个语言模型", "我是一个大模型", "我是个人工智能",
            "我无法透露", "我不能透露", "我没有感情", "我没有实体", "根据我的训练", "训练数据",
            "以下是", "希望这能帮到", "希望可以帮到", "如果还有", "需要其他帮助", "请问还有什么", "还有什么问题", "随时为您",
            "我是虚拟助手", "作为一个虚拟"
        };
        for (String kw : assistantTone) {
            if (r.contains(kw) && (orig == null || !orig.contains(kw))) return true;
        }
        // 云端/运行状态自述：模型解释自己"没有在使用中/并不在云端/本地运行"等，明显对话跑偏 → 直接本地兜底
        String[] cloudTone = {
            "没有在使用", "不在使用", "未在使用", "没在使用",
            "不在运行", "未在运行", "没有在运行", "没在运行",
            "不在云端", "没有在云端", "未在云端", "没在云端", "并不在云端",
            "未连接云端", "没有连接云端", "没连上云端", "未连接到云端",
            "本地运行", "本地部署", "本地模型", "本地执行",
            "不需要云端", "无法在云端", "不能在云端", "未在云端使用"
        };
        for (String kw : cloudTone) {
            if (r.contains(kw) && (orig == null || !orig.contains(kw))) return true;
        }
        // 否定式自述 + 云端/使用中/运行中（如"我并没有在使用中"、"我现在没有在云端运行"）
        if (r.matches(".*(没有|不在|未在|没在|并未|并不|无法|不能)(在)?(云端|使用中|运行中|在线|被使用|被运行).*"))
            return true;
        // 篇幅暴增：正常风格化不会让长度翻数倍；超短原文放宽倍数，避免误伤风格化扩写
        if (orig != null && orig.length() > 0) {
            if (!allowExpand) {
                int mult = orig.length() <= 6 ? 15 : 6;
                if (r.length() > orig.length() * mult) return true;
            }
        }
        // ===== 视角翻转回答：原文是“说话人发出去的话”，译文却变成“人设角色在回答说话人” =====
        boolean origAsk = isQuestionText(orig);
        boolean transAsk = isQuestionText(r);
        // 1) 原文是问句、译文却不再是问句，且译文以人设自称开头 → 模型在回答而非改写
        if (origAsk && !transAsk && startsWithPersonaSelf(r)) return true;
        // 2) “主人问/主人说：…”式先转述再自答：转述动词后必须跟冒号/引号/道/过（“说话方式”的“说”不算转述）
        if (r.matches(".*(主人|你).{0,5}(问|说)([:：\"\"]|道|过).{0,8}(本喵|人家|吾|本小姐|本大爷|本系统|本姑娘|我).{0,12}(正在|在想|觉得|认为|这就|来告诉|当然|答案|可以|来帮|马上).*"))
            return true;   // ★v4 修复：原为悬空 if（上一行 if 的语句体是下一行 if），该判据永不生效
        if (r.matches(".*(主人|你).{0,5}问.{0,8}(本喵|人家|吾|本小姐|本大爷|本系统|本姑娘|我).{0,12}(正在|在想|觉得|认为|这就|来告诉|当然|答案|可以|来帮|马上).*"))
            return true;
        // 3) 原文是问句、译文无问号，且出现“自称+回答谓语”
        if (origAsk && !transAsk
                && r.matches(".*(本喵|人家|吾|本小姐|本大爷|本系统|本姑娘|我).{0,3}(正在|在想|觉得|认为|这就|来告诉你|当然是|答案是|可以帮|来帮你|马上).*"))
            return true;
        return false;
    }

    /** 是否为疑问句：含问号或疑问词 */
    private static boolean isQuestionText(String s) {
        if (s == null) return false;
        if (s.indexOf('?') >= 0 || s.indexOf('？') >= 0) return true;
        return s.matches(".*(吗|呢|怎么|什么|为什么|咋|如何|谁|哪|几|多少|是不是|有没有|干嘛|干什么|做什么|咋样).*");
    }

    /** 是否以人设第一人称自称开头 */
    private static boolean startsWithPersonaSelf(String s) {
        if (s == null) return false;
        String[] self = {"本喵", "人家", "吾", "本小姐", "本大爷", "本系统", "本姑娘", "咱", "阿拉", "本蹦", "在下", "鄙人", "我"};
        for (String z : self) if (s.startsWith(z)) return true;
        return false;
    }

    // ============================================================
    // 无障碍节点工具
    // ============================================================

    /**
     * 查找当前焦点输入框（多策略兜底，适配微信/QQ 等自定义输入框、多窗口场景）。
     * 策略1：当前活动窗口树；策略2：遍历所有 TYPE_APPLICATION 窗口树。
     * 每棵树内：输入焦点 → 可见 editable → 放宽可见性/按类名 EditText 匹配。
     */
    private AccessibilityNodeInfo findInput() {
        // 策略1：活动窗口（P1-3 修复：加 try/catch，窗口销毁并发时异常不穿透到 onAccessibilityEvent）
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo hit = findEditableInTree(root);
                AccessibilityNodes.safeRecycle(root);
                if (hit != null) {
                    AppLog.d("Service", "findInput 命中：活动窗口");
                    return hit;
                }
            } else {
                AppLog.w("Service", "findInput：getRootInActiveWindow 返回 null，尝试遍历窗口");
            }
        } catch (Throwable t) {
            AppLog.w("Service", "findInput 策略1异常，降级到遍历窗口：" + t);
        }
        // 策略2：遍历所有应用窗口（需要 FLAG_RETRIEVE_INTERACTIVE_WINDOWS）
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                // 先找当前活动/焦点窗口，再退回其它应用窗口
                for (int pass = 0; pass < 2; pass++) {
                    for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                        if (w == null) continue;
                        boolean isFocused = w.isActive() || w.isFocused();
                        if ((pass == 0) != isFocused) continue;   // pass0 只看焦点窗口，pass1 看其余
                        if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                        AccessibilityNodeInfo r = w.getRoot();
                        if (r == null) continue;
                        AccessibilityNodeInfo hit = findEditableInTree(r);
                        AccessibilityNodes.safeRecycle(r);
                        if (hit != null) {
                            AppLog.d("Service", "findInput 命中：遍历窗口 focused=" + isFocused);
                            return hit;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            AppLog.w("Service", "findInput 遍历窗口异常：" + t);
        }
        dumpWindowsForDiagnose();
        AppLog.w("Service", "未找到可编辑输入框（全部策略失败）");
        return null;
    }

    /** 诊断：枚举所有窗口及其节点树规模，定位“窗口拿不到”还是“树里没有输入框” */
    private void dumpWindowsForDiagnose() {
        try {
            AccessibilityNodeInfo ar = getRootInActiveWindow();
            AppLog.w("Diag", "活动窗口root=" + (ar == null ? "null"
                    : ("pkg=" + ar.getPackageName() + " 子节点=" + ar.getChildCount())));
            if (ar != null) AccessibilityNodes.safeRecycle(ar);
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) { AppLog.w("Diag", "getWindows()=null"); return; }
            AppLog.w("Diag", "窗口总数=" + wins.size());
            for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                if (w == null) continue;
                AccessibilityNodeInfo r = null;
                String tree = "root=null";
                try {
                    r = w.getRoot();
                    if (r != null) {
                        int[] stat = new int[2]; // [0]=节点总数 [1]=editable数
                        countNodes(r, stat, 0);
                        tree = "pkg=" + r.getPackageName() + " 节点=" + stat[0] + " editable=" + stat[1];
                    }
                } catch (Throwable t) { tree = "root读取异常:" + t.getMessage(); }
                finally { if (r != null) AccessibilityNodes.safeRecycle(r); }
                AppLog.w("Diag", "窗口 type=" + w.getType() + " layer=" + w.getLayer()
                        + " focused=" + w.isFocused() + " active=" + w.isActive() + " " + tree);
            }
        } catch (Throwable t) {
            AppLog.w("Diag", "诊断枚举异常：" + t);
        }
    }

    /** 递归统计节点总数与 editable 数（限深 12 层、上限 500，防超大树卡顿） */
    private void countNodes(AccessibilityNodeInfo node, int[] stat, int depth) {
        if (node == null || stat[0] > 500 || depth > 12) return;
        stat[0]++;
        if (node.isEditable()) stat[1]++;
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) continue;
            countNodes(c, stat, depth + 1);
            AccessibilityNodes.safeRecycle(c);
        }
    }

    /** 在一棵节点树中按优先级找输入框：输入焦点 → 可见 editable → 放宽可见性/类名 */
    private AccessibilityNodeInfo findEditableInTree(AccessibilityNodeInfo root) {
        if (root == null) return null;
        // 1) 输入焦点
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f != null) {
            if (f.isEditable()) return f;
            // 焦点落在容器上：在其子树里找
            AccessibilityNodeInfo sub = deepFind(f, true);
            if (sub != null) { AccessibilityNodes.safeRecycle(f); return sub; }
            AccessibilityNodes.safeRecycle(f);
        }
        // 2) 可见的 editable
        AccessibilityNodeInfo vis = deepFind(root, true);
        if (vis != null) return vis;
        // 3) 放宽：不要求可见，且类名含 EditText 也算（部分自定义框 isEditable 误报 false）
        return deepFind(root, false);
    }

    /** 深度遍历找可编辑节点；requireVisible=true 时要求对用户可见 */
    private AccessibilityNodeInfo deepFind(AccessibilityNodeInfo node, boolean requireVisible) {
        if (node == null) return null;
        java.util.List<AccessibilityNodeInfo> acquired = new java.util.ArrayList<>();
        AccessibilityNodeInfo hit = deepFindInner(node, requireVisible, acquired);
        // 统一回收遍历过程中获取的所有节点，仅保留返回的命中节点（由调用方回收），杜绝祖先/兄弟节点泄漏
        for (AccessibilityNodeInfo t : acquired) {
            if (t != null && t != hit) {
                try { AccessibilityNodes.safeRecycle(t); } catch (Throwable ignored) {}
            }
        }
        return hit;
    }

    /** 递归查找实现：把沿途 getChild 获取到的节点都记入 acquired，命中节点除外全部由上层统一回收 */
    private AccessibilityNodeInfo deepFindInner(AccessibilityNodeInfo node, boolean requireVisible,
                                               java.util.List<AccessibilityNodeInfo> acquired) {
        if (node == null) return null;
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) continue;
            acquired.add(c);
            try {
                boolean byEditable = c.isEditable();
                CharSequence cn = c.getClassName();
                boolean byClass = cn != null && cn.toString().toLowerCase().contains("edittext");
                boolean visibleOk = !requireVisible || c.isVisibleToUser();
                if ((byEditable || (!requireVisible && byClass)) && visibleOk) return c;
                AccessibilityNodeInfo r = deepFindInner(c, requireVisible, acquired);
                if (r != null) return r;
            } catch (Throwable t) {
                // 单个节点读取异常不影响继续遍历
            }
        }
        return null;
    }

    /** 原地替换文本（调用方传入节点，这里统一 recycle） */
    private void setText(AccessibilityNodeInfo edit, String text) {
        if (edit == null) return;
        try {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (ok) {
                lastSet = text;
                lastSetFingerprint = fingerprintOf(text);
            } else {
                // ★v4 修复：节点拒写时不再假装成功（原来先无条件置 lastSet，上层还无条件报 100%/记历史）
                // ★5.0 P0-2-1/2-2：剪贴板兜底也做回读校验，不承诺未验证的"已复制"
                AppLog.w("Service", "ACTION_SET_TEXT 被拒（节点不可写或已失效），改走剪贴板兜底");
                if (copyToClipboard(text)) {
                    toast("输入框写入被拒，已复制到剪贴板");
                } else {
                    toast("输入框写入失败，请手动复制");
                }
            }
        } finally {
            AccessibilityNodes.safeRecycle(edit);
        }
    }

    /** 指纹：文本 + 当前风格 + 引擎模式。用于“同文不同风格/模式允许重翻”的判断 */
    private static String fingerprintOf(String text) {
        int m = Prefs.engineMode();
        String tag = (m == Prefs.ENGINE_CLOUD_API) ? (Prefs.replaceMode() ? "R" : "A") : "L";
        return text + "|" + StyleManager.currentName() + "|" + tag;
    }

    /** 复制文本到系统剪贴板（焦点丢失时的兜底）；返回是否真正写入成功（Android 10+ 后台剪贴板受限，必须回读校验） */
    private boolean copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                AppLog.w("Service", "剪贴板服务不可用");
                return false;
            }
            cm.setPrimaryClip(android.content.ClipData.newPlainText("风格化译文", text));
            // 回读校验：Android 10+ 后台无法写入时 setPrimaryClip 可能静默失败
            android.content.ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                AppLog.w("Service", "剪贴板写入后回读为空，写入失败");
                return false;
            }
            CharSequence got = clip.getItemAt(0).coerceToText(this);
            if (got == null || !text.equals(got.toString())) {
                AppLog.w("Service", "剪贴板回读内容与原文不一致，写入失败");
                return false;
            }
            return true;
        } catch (Throwable t) {
            AppLog.e("Service", "复制到剪贴板失败", t);
            return false;
        }
    }

    private void toast(String msg) {
        // 鉴信息化模块一：所有提示统一走 UiFeedback 出口
        main.post(() -> UiFeedback.toast(this, msg));
    }

    /** 鉴信息化模块一：错误提示统一出口——Key 无效/网络错误走带操作建议的专用提示 */
    private void toastError(String msg) {
        if (msg != null && msg.contains("API Key 无效")) {
            main.post(() -> UiFeedback.apiKeyInvalid(this));
            return;
        }
        if (msg != null && (msg.contains("网络") || msg.contains("超时") || msg.contains("无法连接"))) {
            main.post(() -> UiFeedback.networkRetry(this));
            return;
        }
        main.post(() -> UiFeedback.error(this, msg == null ? "未知错误" : msg));
    }
}
