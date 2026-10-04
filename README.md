# MiaoHelper

> English below / 中文在下方

Android accessibility input enhancement: a floating ball that triggers text style transformation in any input field — local rule replacement + multi-vendor AI translation, with built-in content safety filter.

[![Download](https://img.shields.io/badge/Download-v5.2.0-4F8CFF?style=for-the-badge&logo=android&logoColor=white)](https://github.com/ason6565-ai/MiaoHelper_OpenSource/releases/latest)

> Open-source public build. Source code for study and technical research.

## Keywords

android · accessibility service · floating window · input enhancement · ai translation · text style transfer · multilingual i18n

## Features

- **Floating ball**: global overlay via AccessibilityService, captures and replaces text in any input field
- **Follows system language**: auto-switches UI language (中文 / English / 日本語 / 한국어)
- **Dual engine**: local rule replacement (offline, sub-millisecond) + AI style translation (multi-vendor)
- **Multi-vendor API**: DeepSeek / Qwen / GLM / Kimi / Doubao / Hunyuan / SiliconFlow / OpenRouter / OpenAI — all OpenAI Chat Completions compatible
- **AI judge voting**: generate multiple candidates, multiple AI judges vote on the best one (candidates and judges independently adjustable)
- **Auto failover**: primary API fails → switch to backup; offline → fall back to local wordbook
- **Content safety**: built-in sensitive-word filter blocks unsafe output before writing back
- **Translation cache**: local disk LRU cache with configurable TTL and size
- **Local wordbook**: visual editor for custom style rules and persona templates
- **Transparency**: user-facing logs, token/cost estimates, translation stats

## Architecture

```
AccessibilityService (captures input text)
        │
        ▼
┌─────────────────────────────┐
│   Local replacement engine  │ ← wordbook / persona templates
└─────────────────────────────┘
        │
        ▼
┌─────────────────────────────┐
│  AI engine (OpenAI-compat)  │
│   ├─ Multi-candidate gen    │
│   ├─ AI judge voting         │
│   └─ Content safety filter   │
└─────────────────────────────┘
        │
        ▼
Write back to input field
```

## Build

Requires JDK 17+ and Android SDK (compileSdk 34+).

```bash
./gradlew assembleDebug          # debug build
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleRelease        # release (needs your own keystore)
```

## Disclaimer

- For study and technical research only.
- Users are responsible for their own content and usage.
- Not affiliated with any AI vendor.

## License

MIT — see [LICENSE](LICENSE).

---

# 拟言助手 MiaoHelper

Android 无障碍输入文本增强工具：在任意 App 的输入框中，通过悬浮球一键触发文本风格化转换（本地替换引擎 + 多厂商 AI 风格化翻译），内置内容安全审核。

> 本仓库为开源公开版，源码仅供学习交流与技术研究。

## 特色亮点

### 1. AI 裁判投票：移动端的多模型仲裁

生成阶段采用双轨策略（忠实原文轨 + 风格轨 + 保底候选），随后由多个 AI 裁判独立投票，选中最优候选；生成数量与裁判数量可独立调节（1-16 / 1-3）。

### 2. 翻译场景专用的人设模板体系

内置 12 套结构化人设模板（傲娇 / 翻译腔 / 雌小鬼 / 学术 / 温柔 / 撒娇系等），每套包含自称锁定、说话习惯、核心词汇、禁忌行为、示例翻译对。

### 3. 三层术语体系：长文本一致性

静态归一表 → 每本书术语档 → AI 扩展词库，配合别名表与冲突处理，解决长篇翻译人名专名前后不一致的问题。

## 功能特性

- **无障碍悬浮球**：基于 AccessibilityService 的全局悬浮球
- **跟随系统语言**：中文 / English / 日本語 / 한국어 自动切换
- **双引擎**：本地规则替换 + AI 风格化翻译
- **多厂商 API**：DeepSeek / 通义千问 / 智谱 GLM / Kimi / 字节豆包 / 腾讯混元 / SiliconFlow / OpenRouter / OpenAI
- **AI 裁判机制**：多候选生成 + 多 AI 裁判投票
- **多引擎自动降级**：主 API 失效自动切换备用
- **内容安全审核**：内置敏感词拦截
- **翻译缓存**：本地 LRU 缓存
- **本地词库**：可视化编辑与自定义人设模板

## 免责声明

- 本工具仅用于学习交流与技术研究，请勿用于任何违法违规用途。
- 翻译/风格化内容由用户发起，用户对内容及其使用承担全部责任。
- 本项目与任何 AI 厂商无隶属关系。

## License

MIT License — 详见 [LICENSE](LICENSE)。
