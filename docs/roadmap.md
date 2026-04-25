# 路线图

基于 4 个已决策（见 `decisions.md`）：`flutter_map` / 原生 AI 代理 / 仅前台 / 不迁历史数据。

## 阶段划分

### P0 · 契约冻结（0.5 周）
**目标**：把所有跨端对齐点写死，后续并行开发不再回头扯皮。

- [x] 从 `VeloTrack-h5` 反抽 UI / token / 接口 → 形成本 `docs/` 目录
- [ ] `VeloTrack-flutter` 建仓：`flutter create` + 基础目录结构
- [ ] 写 `pigeons/velotrack_api.dart`（与 `native-api-contract.md` 一致）
- [ ] 跑一次 `dart run pigeon` 生成 Dart / Kotlin 桩，提交进各仓

**交付**：Flutter 空壳能编译；Pigeon 生成物存在但未实现。

---

### P1 · Flutter UI 骨架（1 周）
**目标**：mock 数据驱动三视图，动效与长按可交互。

- [ ] 主题层：从 `design-tokens.json` 生成 `VeloTokens` ThemeExtension
- [ ] 视图：`RecordingScreen` / `HistoryScreen` / `DetailScreen` + `AppShell`
- [ ] 组件：`StatusCard` / `DistanceCard` / `GaugeCard`（含长按进度条）/ `RideListItem` / `StatsGrid` / `PerfChart` / `AICoachCard` / `DeleteModal` / `BottomNav`
- [ ] 地图：`flutter_map` + Carto dark_all，Polyline 视觉对齐
- [ ] 动画：`flutter_animate` 级联 / `AnimatedSwitcher` 视图切换
- [ ] 状态：Riverpod 三个 provider + 假数据工厂
- [ ] 与 h5 并排跑截图对比（至少 5 张：recording/history/detail/删除 modal/空态）

**交付**：Flutter App 全 UI 跑通，使用内存 mock 数据；Pigeon 接口被 `FakeVeloNativeApi` 填充。

---

### P2 · Android 原生接入（1 周）
**目标**：Kotlin 实现 Pigeon 全部接口，真机录制/回放打通。

- [ ] Kotlin 实现 `VeloNativeApi`
  - [ ] `FusedLocationProviderClient`（`PRIORITY_HIGH_ACCURACY`, `interval=1000ms`）
  - [ ] 原生侧 1Hz 计时器 → `onElapsed`
  - [ ] accuracy > 40m 丢点
  - [ ] WakeLock：Activity `FLAG_KEEP_SCREEN_ON`
  - [ ] Room：`rides` 表（`startTime DESC` 索引）
  - [ ] `AppLifecycleObserver` 失焦自动 pause（对齐 D3）
- [ ] AI 代理：`VeloAiProxy` 读库 → 组 prompt → Gemini HTTPS → 返文本
- [ ] Key 管理：首次启动从 `BuildConfig` 写入 EncryptedSharedPreferences，源清零
- [ ] 错误码映射（见 `native-api-contract.md` §4）

**交付**：Android 设备完成一次真实骑行记录 → 出现在历史 → 详情 AI 分析可用。

---

### P3 · iOS 原生接入（0.5~1 周）
**目标**：Swift 对称实现。

- [ ] `CLLocationManager` + `requestWhenInUseAuthorization`
- [ ] `UIApplication.isIdleTimerDisabled = true`（仅在 tracking 时）
- [ ] CoreData（与 Room 同字段）
- [ ] AI 代理：URLSession + Keychain 存 key
- [ ] `UIApplication.didEnterBackgroundNotification` → `pauseTracking`

**交付**：iOS 真机与 Android 端到端对齐。

---

### P4 · 打磨与发布（0.5 周）
- [ ] 权限引导页（首启）
- [ ] 错误态 UI 回归（见 `ui-spec.md` §10）
- [ ] 图标、启动图、应用名国际化
- [ ] CI：token 哈希校验 + Pigeon 漂移检查
- [ ] 打签名包、内测分发

---

## 依赖矩阵

| 能力 | Flutter 端包 | Android 原生 | iOS 原生 |
|---|---|---|---|
| 地图 | `flutter_map` | — | — |
| 图表 | `fl_chart` | — | — |
| 图标 | `lucide_icons_flutter` | — | — |
| 字体 | `google_fonts` | — | — |
| 动画 | `flutter_animate` | — | — |
| 状态 | `flutter_riverpod` | — | — |
| 跨端 | `pigeon` (dev) | 对应生成文件 | 对应生成文件 |
| 定位 | — | Google Play Services Location | CoreLocation |
| 存储 | — | Room + Kotlinx Coroutines | CoreData |
| 加密存储 | — | `security-crypto` EncryptedSharedPreferences | Keychain (Apple) |
| 网络 | — | OkHttp | URLSession |

## 里程碑验收 checklist（每阶段末必做）

1. h5 与 Flutter 并排截图逐屏比对（差异点登记，不能默默不修）
2. Pigeon 接口变更触发三端同步升级 commit（同一 PR / 同一 commit 前后缀）
3. `design-tokens.json` 任何字段改动需同时更新 `ui-spec.md` 引用位置

## 非目标（明确不做）

- 用户账号、登录、云同步
- 后台录制、锁屏继续
- 从 h5 导入历史数据
- 独立后端服务
- 国内合规地图 SDK（v1 海外 only）
- 中英文切换（首版简中 + 既有英文标签混合，与 h5 保持一致）
