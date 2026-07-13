# VeloTrack Android — Agent 指南

面向 Cursor / AI 助手的项目说明。修改代码前请先读本文与 `docs/` 中的设计规格。

## 项目是什么

**VeloTrack** 是一款骑行轨迹记录 App 的 **纯原生 Android** 实现（Kotlin + Jetpack Compose）。功能包括：前台 GPS 录制、轨迹地图、历史列表、详情与 Gemini 骑行分析。

> **重要**：本仓库已从「Flutter UI + Pigeon 原生桥」改为 **全栈原生**。`docs/architecture.md`、`docs/native-api-contract.md`、`docs/roadmap.md` 仍描述三端/Flutter 方案，作跨仓设计参考；**以本仓库实际 Kotlin 代码为准**。

## 技术栈

| 层级 | 技术 |
|------|------|
| UI | Jetpack Compose (Material3)、`VeloTheme` / `VeloColors` |
| 状态 | `TrackViewModel` + `StateFlow<TrackUiState>` |
| 定位 | `LocationTracker`（国内高德定位 / 海外 GMS Fused） |
| 地图 | `MapPane`：国内 `AMAP`，海外 `Google Maps`（`MapProviderSelector`） |
| 持久化 | Room（`rides` + `gps_points`） |
| AI | `GeminiClient`（OkHttp，Key 来自 `BuildConfig`） |
| 构建 | AGP 8.10、Kotlin 2.1、minSdk 29、compileSdk 36 |

## 目录结构

```
app/src/main/kotlin/com/velotrack/velotrack/
├── MainActivity.kt          # 入口：权限、定位订阅、保屏、Compose 根
├── MainScreen.kt            # 三视图壳 + 转场动画
├── TrackViewModel.kt        # 录制/暂停/计时/历史/删除/AI 状态机
├── TrackModels.kt           # GpsPoint、Ride、AppView
├── LocationTracker.kt       # 定位采集与丢点策略
├── MapPane.kt               # 双地图实现与轨迹 Polyline
├── MapProviderSelector.kt   # CN → 高德，否则 Google
├── CoordinateTransform.kt   # WGS-84 ↔ GCJ-02（仅地图渲染）
├── RideRepository.kt        # Room 读写
├── GeminiClient.kt          # Gemini API
├── recording/               # 前台服务、录制会话、通知
├── VeloApp.kt               # Application + RecordingSessionManager
├── db/                      # Room Entity / Dao / Database
└── ui/
    ├── VeloTheme.kt         # design-tokens 对齐色板与圆角
    ├── recording|history|detail/  # 三主屏
    └── components/          # BottomNav、长按停止、删除 Modal 等

docs/                        # 设计 Source of Truth（与 VeloTrack-h5 对齐）
scripts/build_flutter_aar.sh # 历史脚本，当前工程无 Flutter 依赖
```

## 核心数据流

1. **录制**：`RecordingScreen` → `onStartRecording` → 权限 → `beginStartCountdown` → `startRecording` → `LocationTracker` 推点 → `TrackViewModel.onLocation` → `livePoints` + 距离/速度统计。
2. **停止**：长按 1.5s（`HoldProgressOverlay`）→ `stopRecording` → `RideRepository` 落库 → 切 `AppView.DETAIL`。
3. **后台录制**：`RecordingForegroundService`（`foregroundServiceType=location`）+ `RecordingSessionManager`；切后台仍记轨迹，通知栏可暂停/停止。倒计时预热仍由 Activity 的 `prewarmLocationTracker` 负责。
4. **坐标**：存储与统计一律 **WGS-84**；高德底图仅在 `MapPane` / `CoordinateTransform` 渲染时转 GCJ-02。

## 三视图

| `AppView` | 屏幕 | 文件 |
|-----------|------|------|
| `RECORDING` | 录制 HUD + 地图 | `ui/recording/RecordingScreen.kt` |
| `HISTORY` | 骑行列表 | `ui/history/HistoryScreen.kt` |
| `DETAIL` | 单次详情 + AI | `ui/detail/DetailScreen.kt` |

## 配置与构建

```bash
./gradlew :app:assembleDebug
```

在 `local.properties` 或 `gradle.properties`（勿提交密钥）配置：

```properties
GOOGLE_MAPS_API_KEY=...
AMAP_API_KEY=...
GEMINI_API_KEY=...          # 可选
MAP_PROVIDER=AMAP|GOOGLE    # 可选，覆盖区域自动选择
```

- **debug** 包名：`com.velotrack.velotrack.debug`（高德 Key 需单独绑定 debug 签名 SHA1）。
- **release** 包名：`com.velotrack.velotrack`。
- 高德隐私合规：`VeloApplication` 必须在任何 `MapView` 之前调用 `MapsInitializer.updatePrivacyShow/Agree`。

## 设计对齐

- 视觉规格：`docs/ui-spec.md`、`docs/design-tokens.md`、`docs/design-tokens.json`。
- Compose 常量：`ui/VeloTheme.kt` 中的 `VeloColors` / `VeloDimens` 应与 token 一致；改色先改 `design-tokens.json` 再同步 Kotlin。
- 产品交互以 **VeloTrack-h5** 为 Design Source（本仓不内含 h5）。

## 修改时的约定

1. **最小改动**：只改与任务相关的文件；不恢复 Flutter/Pigeon 除非明确要求。
2. **单位**：速度内部 **m/s**，UI 显示 km/h（×3.6）；距离米。
3. **地图**：动 `MapPane` / `MapProviderSelector` 时同时考虑国内/海外两套 SDK。
4. **Room**：schema 变更需 bump `AppDatabase` version 并保留 `app/schemas/` 导出。
5. **ProGuard**：release 已开启混淆；新增反射/序列化类需更新 `proguard-rules.pro`。
6. **语言**：用户可见文案与注释可用中文；包名与 API 保持英文。

## 常用命令

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:lint
```

## 相关文档索引

| 文档 | 用途 |
|------|------|
| `README.md` | 构建、地图 Key、高德白屏排查 |
| `docs/ui-spec.md` | 交互、动效、长按停止等 |
| `docs/decisions.md` | ADR（前台录制、AI 代理、不迁历史等） |
| `docs/android-optimization-opportunities.md` | 性能优化备忘 |
| `docs/native-api-contract.md` | 历史 Pigeon 契约（跨仓参考） |

## 禁止事项（仍适用）

- 不要在 Kotlin 层返回 UI 无关的「业务以外」副作用到错误的设计（原 Native 边界：不向外暴露 dp/颜色字符串给别的端——本仓已合一，但保持分层：ViewModel 不直接操作 Map SDK）。
- 录制中勿绕过 `RecordingSessionManager` 在 Activity 内单独启停定位（倒计时预热除外）。
- 不要把 API Key 硬编码进源码或提交到 git。
