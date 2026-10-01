package com.miao.helper;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.card.MaterialCardView;

/** 第1页：开关页（2×4 方格 + 1 通栏）+ 翻译引擎分段（置于底部） */
public class SwitchPageFragment extends Fragment {
    private SegmentedSlider sgEngine;
    private MaterialCardView tileFloat, tileRealtime, tileTail, tileVerify, tileReplace;
    private MaterialCardView tilePreview, tileLocalPre;
    private TextView tvFloatState, tvRealtimeState, tvTailState, tvVerifyState, tvReplaceState;
    private TextView tvPreviewState, tvLocalPreState;
    private ImageView ivFloat, ivRealtime, ivTail, ivVerify, ivReplace, ivLocalPre, ivPreview;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_page_switch, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        sgEngine = v.findViewById(R.id.sgEngine);
        tileFloat = v.findViewById(R.id.tileFloat);
        tileRealtime = v.findViewById(R.id.tileRealtime);
        tileTail = v.findViewById(R.id.tileTail);
        tileVerify = v.findViewById(R.id.tileVerify);
        tileReplace = v.findViewById(R.id.tileReplace);
        tileLocalPre = v.findViewById(R.id.tileLocalPre);
        tvFloatState = v.findViewById(R.id.tvFloatState);
        tvRealtimeState = v.findViewById(R.id.tvRealtimeState);
        tvTailState = v.findViewById(R.id.tvTailState);
        tvVerifyState = v.findViewById(R.id.tvVerifyState);
        tvReplaceState = v.findViewById(R.id.tvReplaceState);
        tvLocalPreState = v.findViewById(R.id.tvLocalPreState);
        tilePreview = v.findViewById(R.id.tilePreview);
        tvPreviewState = v.findViewById(R.id.tvPreviewState);
        ivFloat = v.findViewById(R.id.ivFloat);
        ivRealtime = v.findViewById(R.id.ivRealtime);
        ivTail = v.findViewById(R.id.ivTail);
        ivVerify = v.findViewById(R.id.ivVerify);
        ivReplace = v.findViewById(R.id.ivReplace);
        ivLocalPre = v.findViewById(R.id.ivLocalPre);
        ivPreview = v.findViewById(R.id.ivPreview);
        // ---- 翻译引擎分段（本地翻译 / 彻底替换）v5.1: AI 翻译已隐藏，云端=彻底替换 ----
        sgEngine.setLabels(new String[]{getString(R.string.sw_local), getString(R.string.sw_full)});
        sgEngine.setSelected(Prefs.engineMode());
        // 「千万别点」锁定期吞掉全部触摸，引擎被固定在 AI 彻底替换
        sgEngine.setOnTouchListener((vv, ev) -> DangerLock.isLocked());
        sgEngine.setOnSelectionChangedListener(pos -> {
            if (DangerLock.isLocked()) { refreshTileStates(); return; }
            boolean api = pos == Prefs.ENGINE_CLOUD_API;
            Prefs.setEngineMode(api ? Prefs.ENGINE_CLOUD_API : Prefs.ENGINE_LOCAL_RULES);
            if (api) {
                // 云端入口 = 彻底替换（AI 翻译已隐藏），确保 replaceMode 打开
                Prefs.set("replaceMode", true);
            } else {
                // 本地引擎没有彻底替换 / AI 裁判，关闭之
                Prefs.set("replaceMode", false);
                Prefs.set("aiVerify", false);
            }
            MainActivity a = (MainActivity) getActivity();
            if (a != null) a.notifyEngineChanged();
            MiaoService s = MiaoService.get();
            if (s != null) s.updateFloatStatus();
            refreshTileStates();
        });

        tileFloat.setOnClickListener(vv -> {
            Prefs.set("showFloat", !Prefs.showFloat());
            refreshTileStates();
            MiaoService s = MiaoService.get();
            if (s != null) s.refreshFloat();
        });
        // 实时翻译：本地 / AI API 均可用（API 模式下为句末标点触发的自动翻译）
        tileRealtime.setOnClickListener(vv -> {
            if (DangerLock.isLocked()) return;   // 锁定期固定开启，不可关闭
            Prefs.set("realtime", !Prefs.realtime());
            refreshTileStates();
            MiaoService s = MiaoService.get();
            if (s != null) s.updateFloatStatus();
        });
        // 句尾口癖总开关（关闭后本地引擎与 AI 均不再追加句尾口癖）
        tileTail.setOnClickListener(vv -> {
            Prefs.set("tailEnabled", !Prefs.tailEnabled());
            refreshTileStates();
            MiaoService s = MiaoService.get();
            if (s != null) s.updateFloatStatus();
        });
        tileVerify.setOnClickListener(vv -> {
            if (!tileVerify.isEnabled()) return;
            Prefs.set("aiVerify", !Prefs.aiVerify());
            refreshTileStates();
        });
        tileReplace.setOnClickListener(vv -> {
            if (DangerLock.isLocked()) return;   // 锁定期固定彻底替换开，不可关闭
            if (!tileReplace.isEnabled()) return;
            Prefs.set("replaceMode", !Prefs.replaceMode());
            refreshTileStates();
            MainActivity a = (MainActivity) getActivity();
            if (a != null) a.refreshTempFromMode();
            MiaoService s = MiaoService.get();
            if (s != null) s.updateFloatStatus();
        });
        // AI 并联本地：开启后 AI 翻译/彻底替换前用当前人设扩展词打底（只读 AI 扩展词库，不跑内置预设词）
        tileLocalPre.setOnClickListener(vv -> {
            Prefs.set("localPreStyle", !Prefs.localPreStyle());
            refreshTileStates();
            android.widget.Toast.makeText(getContext(),
                    Prefs.localPreStyle() ? getString(R.string.style_lexicon_on) : getString(R.string.style_lexicon_off),
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        refreshTileStates();
        tilePreview.setOnClickListener(vv -> {
            if (!tilePreview.isEnabled()) return;
            Prefs.set("previewMode", !Prefs.previewMode());
            refreshTileStates();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshTileStates();
    }

    /** 刷新引擎分段选中态与全部开关状态/可用性；锁定期强制固定翻译相关项 */
    public void refreshTileStates() {
        if (tileFloat == null) return;
        boolean locked = DangerLock.isLocked();
        int mode = locked ? Prefs.ENGINE_CLOUD_API : Prefs.engineMode();
        boolean api = mode == Prefs.ENGINE_CLOUD_API;            // 仅云端 AI 启用彻底替换/裁判
        boolean realtime = locked || Prefs.realtime();          // 锁定期强制实时开
        boolean replace = locked || Prefs.replaceMode();        // 锁定期强制彻底替换开
        // v5.1: AI 翻译已隐藏；历史遗留的"云端+非彻底替换"态也映射为云端档（彻底替换）
        if (api && !replace) mode = Prefs.ENGINE_CLOUD_API;
        // setSelected 只重绘、不触发回调，外部同步安全
        if (sgEngine != null) sgEngine.setSelected(mode);
        tileReplace.setEnabled(api);
        tileVerify.setEnabled(api);
        paintTile(tileFloat, tvFloatState, ivFloat, Prefs.showFloat());
        paintTile(tileRealtime, tvRealtimeState, ivRealtime, realtime);
        paintTile(tileTail, tvTailState, ivTail, Prefs.tailEnabled());
        paintTile(tileVerify, tvVerifyState, ivVerify, Prefs.aiVerify());
        paintTile(tileReplace, tvReplaceState, ivReplace, replace);
        paintTile(tileLocalPre, tvLocalPreState, ivLocalPre, Prefs.localPreStyle());
        tilePreview.setEnabled(api);
        paintTile(tilePreview, tvPreviewState, ivPreview, Prefs.previewMode());
    }

    /** 控制中心式磁贴：开启=暖橙底深橙字+深橙图标，关闭=透明浅灰字+浅灰图标 */
    private void paintTile(MaterialCardView card, TextView state, ImageView icon, boolean on) {
        card.setCardBackgroundColor(on ? 0xFFFFB74D : 0x00FFFFFF);
        card.setStrokeColor(on ? 0xFFE65100 : 0x40E65100);
        state.setText(on ? getString(R.string.sw_on) : getString(R.string.sw_off));
        state.setTextColor(on ? 0xFFE65100 : 0xFFA1887F);
        if (icon != null) icon.setColorFilter(on ? 0xFFE65100 : 0xFFA1887F);
        card.setAlpha(on || card.isEnabled() ? 1f : 0.45f);
    }
}
