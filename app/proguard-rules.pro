# ===== MiaoHelper R8 混淆规则 =====
# 审查 P2：项目无反射调用（Class.forName/getMethod 0 命中），四大组件由 Manifest 自动 keep，
# 库自带 consumer rules（OkHttp/ML Kit/TinyPinyin 等）。以下为补充显式 keep。

# 布局 XML 引用的自定义 View（混淆会改变类名导致 inflate 崩溃）
-keep class com.miao.helper.SegmentedSlider { *; }

# 无障碍服务配置 XML 仅引用 Service 自身（Manifest 自动 keep），无额外类需 keep。
# 若未来接入反射/动态类加载，必须在此补充对应 keep 规则。
