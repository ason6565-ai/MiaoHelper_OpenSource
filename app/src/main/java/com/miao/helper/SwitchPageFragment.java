package com.miao.helper;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.card.MaterialCardView;

/** 第1页：开关页（2×4 方格 + 1 通栏）+ 翻译引擎分段（置于底部） */
public class SwitchPageFragment extends Fragment {
    private SegmentedSlider sgEngine;
    private MaterialCardView tileFloat, tileRealtime, tileTail, tileVerify, tileReplace;
    private MaterialCardView tileStream, tilePreview, tileLocalPre;
    private TextView tvFloatState, tvRealtimeState, tvTailState, tvVerifyState, tvReplaceState;
    private TextView tvStreamState, tvPreviewState, tvLocalPreState;

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
        tileStream = v.findViewById(R.id.tileStream);
        tvStreamState = v.findViewById(R.id.tvStreamState);
        tilePreview = v.findViewById(R.id.tilePreview);
        tvPreviewState = v.findViewById(R.id.tvPreviewState);
        // ---- 翻译引擎分段（本地词库 / AI API）----
        sgEngine.setLabels(new String[]{"本地词库", "AI API"});
        sgEngine.setSelected(Prefs.engineMode());
        // 「千万别点」锁定期吞掉全部触摸，引擎被固定在 AI API
        sgEngine.setOnTouchListener((vv, ev) -> DangerLock.isLocked());
        sgEngine.setOnSelectionChangedListener(pos -> {
            if (DangerLock.isLocked()) { refreshTileStates(); return; }
            Prefs.setEngineMode(pos);
            if (pos != Prefs.ENGINE_CLOUD_API) {
                // 非云端引擎没有彻底替换 / AI 裁判，关闭之
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
                    Prefs.localPreStyle() ? "已开启：AI 前用扩展词打底" : "已关闭：不打底，直接交给 AI",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        refreshTileStates();
        tileStream.setOnClickListener(vv -> {
            if (!tileStream.isEnabled()) return;
            Prefs.set("streamEnabled", !Prefs.streamEnabled());
            refreshTileStates();
        });
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
        boolean api = mode == Prefs.ENGINE_CLOUD_API;            // 仅云端 AI 启用彻底替换/裁判/流式
        boolean realtime = locked || Prefs.realtime();          // 锁定期强制实时开
        boolean replace = locked || Prefs.replaceMode();        // 锁定期强制彻底替换开
        // setSelected 只重绘、不触发回调，外部同步安全
        if (sgEngine != null) sgEngine.setSelected(mode);
        tileReplace.setEnabled(api);
        tileVerify.setEnabled(api);
        paintTile(tileFloat, tvFloatState, Prefs.showFloat());
        paintTile(tileRealtime, tvRealtimeState, realtime);
        paintTile(tileTail, tvTailState, Prefs.tailEnabled());
        paintTile(tileVerify, tvVerifyState, Prefs.aiVerify());
        paintTile(tileReplace, tvReplaceState, replace);
        paintTile(tileLocalPre, tvLocalPreState, Prefs.localPreStyle());
        tileStream.setEnabled(api);
        tilePreview.setEnabled(api);
        paintTile(tileStream, tvStreamState, Prefs.streamEnabled());
        paintTile(tilePreview, tvPreviewState, Prefs.previewMode());
    }

    /** 控制中心式磁贴：开启=暖橙底深橙字，关闭=透明浅灰字 */
    private void paintTile(MaterialCardView card, TextView state, boolean on) {
        card.setCardBackgroundColor(on ? 0xFFFFB74D : 0x00FFFFFF);
        card.setStrokeColor(on ? 0xFFE65100 : 0x22E65100);
        state.setText(on ? "开" : "关");
        state.setTextColor(on ? 0xFFE65100 : 0xFFA1887F);
        card.setAlpha(on || card.isEnabled() ? 1f : 0.45f);
    }
}
