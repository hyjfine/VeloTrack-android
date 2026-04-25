# VeloTrack 架构文档

本目录是 VeloTrack 三端协作的 **Source of Truth**。`VeloTrack-h5` 本身不再以"发布产品"为目标，而是作为 **Design Source**：它既是设计稿，又是可运行的交互原型，所有视觉与交互细节以此为准。

## 三端职责

| 仓库 | 角色 | 承担内容 |
|---|---|---|
| `VeloTrack-h5` | Design Source（活 Figma） | 视觉/交互/色板/动效/文案；用于比对与回归 |
| `VeloTrack-flutter` | 纯 UI 渲染层 | Widget 树 1:1 还原 h5；只做布局、动画、图表、地图贴图、状态机 |
| `VeloTrack-android` / 未来 iOS | 系统能力层 | 定位、WakeLock、持久化、AI 代理；通过 Pigeon 暴露接口 |

Flutter 侧**不直接调用**任何传感器 / 存储 / 网络副作用能力，一切经 Pigeon 接口下沉到原生。

## 文档索引

| 文档 | 面向读者 | 说明 |
|---|---|---|
| [`architecture.md`](./architecture.md) | 全员 | 三端分层、数据流、模块边界 |
| [`decisions.md`](./decisions.md) | 全员 | 已拍板的关键决策（ADR） |
| [`ui-spec.md`](./ui-spec.md) | Flutter / 设计 | 从 h5 反抽的视图、交互、动效规格 |
| [`native-api-contract.md`](./native-api-contract.md) | Flutter / Android / iOS | Pigeon 接口契约，跨端唯一入口 |
| [`design-tokens.md`](./design-tokens.md) | 全员 | 色板、圆角、字体、动效常量说明 |
| [`design-tokens.json`](./design-tokens.json) | 工具 / CI | 机器可读 token，Flutter / h5 / 原生均从此生成 |
| [`roadmap.md`](./roadmap.md) | PM / 全员 | 阶段划分、交付物、里程碑 |

## 变更约定

1. **设计变更**：改 `VeloTrack-h5` 源码 → 同步更新 `design-tokens.json` 与 `ui-spec.md` → Flutter 侧重跑 codegen。
2. **接口变更**：改 `native-api-contract.md` → 改 Pigeon `.dart` 定义 → 生成 Kotlin/Swift 桩 → 双端实现同步。
3. **决策变更**：在 `decisions.md` 新增 ADR 条目（不要删旧条目，只追加 "Superseded by" 标注）。

> 任何端的修改若与本目录文档冲突，以本目录为准；文档本身的修改必须通过 PR，被三端 owner 至少一位 review。
