# 架构总览

## 分层

```
┌──────────────────────────────────────────────────────────┐
│  VeloTrack-h5   (Design Source of Truth, read-only)     │
│  React 19 + TS + Vite + Tailwind v4                      │
│  - 视觉 / 交互 / 动效规格                                │
│  - 不再承载发布功能；保留一个可跑的参考版本              │
└──────────────────────────────────────────────────────────┘
                       ▲ 设计对齐（tokens + ui-spec）
┌──────────────────────────────────────────────────────────┐
│  VeloTrack-flutter   (纯 UI 层)                          │
│  - 三视图状态机：recording / history / detail            │
│  - 仅持有：ViewState、currentRide、history               │
│  - 依赖：flutter_map / fl_chart / flutter_animate /      │
│          google_fonts / lucide_icons_flutter / riverpod  │
│  - 无文件 IO / 无网络 / 无传感器直调                     │
└──────────────────────────────────────────────────────────┘
                       ▲ Pigeon (type-safe MethodChannel)
┌──────────────────────────────────────────────────────────┐
│  VeloTrack-android (Kotlin)  /  未来 iOS (Swift)         │
│  - FusedLocationProvider / CLLocationManager             │
│  - WakeLock / IdleTimerDisabled                          │
│  - Room / CoreData                                       │
│  - AI 代理（Gemini key 保存在原生 Keystore/Keychain）    │
└──────────────────────────────────────────────────────────┘
```

## 状态与数据流

Flutter 侧以 Riverpod 管理三个 provider：

```
viewProvider:        StateProvider<ViewState>              // recording | history | detail
rideProvider:        StateNotifierProvider<RideState>      // 含 currentRide、isRecording、isPaused、elapsedMs
historyProvider:     FutureProvider<List<Ride>>            // 从原生 listRides() 拉
```

录制流程：

```
UI: onTap Play
  → Riverpod rideProvider.start()
  → Pigeon VeloNativeApi.startTracking(rideId)
      ↓（原生启动定位 + WakeLock + 计时器）
Native → Flutter (FlutterApi)
  - onLocation(GpsPointDto)   每 1-2s 推
  - onElapsed(elapsedMs)      每 1s 推（原生跑计时器，避免 UI jank 漂移）
  → rideProvider 合并到 currentRide.points、累加 distance、更新 maxSpeed
  → UI 重绘 HUD / Polyline / SpeedGauge
```

停止流程：

```
UI: onLongPressEnd (1.5s)
  → Pigeon VeloNativeApi.stopTracking() → RideDto（终稿，含 avgSpeed、endTime，已落库）
  → rideProvider.clearCurrent()
  → historyProvider.refresh()
  → 切换 view 到 detail(selectedId = rideId)
```

## 模块边界（禁止事项）

| 模块 | 禁止 |
|---|---|
| Flutter | 禁止调用 `geolocator` / `shared_preferences` / `sqflite` / `http` 访问 Gemini |
| Native | 禁止返回任何 UI 概念（dp、颜色字符串、文案） |
| h5 | 禁止在方案固化后承担任何真实用户业务（无登录、无账号、无上传） |

## 错误传递

原生端所有异常统一走 `VeloFlutterApi.onError(code, message)`，`code` 来自固定集合：

- `GPS_PERMISSION_DENIED`
- `GPS_SIGNAL_LOST`
- `DB_WRITE_FAILED`
- `AI_PROXY_FAILED`
- `UNKNOWN`

Flutter 侧按 code 映射到 UI 提示（具体文案见 `ui-spec.md`）。

## 与 h5 的差异清单

| 项 | h5 现状 | 三端方案后 |
|---|---|---|
| 定位 | `watchPosition` + accuracy>40 丢点 | 原生 Fused/CL，丢点策略同原生（默认 `accuracy > 40m` 丢） |
| 保活 | Wake Lock Web API | Android `FLAG_KEEP_SCREEN_ON`；iOS `isIdleTimerDisabled = true`；**仅前台** |
| 持久化 | IndexedDB (idb) | Android Room；iOS CoreData；相同 Schema |
| AI | 前端直调 Gemini | 原生代理（见 decisions D2） |
| 地图 | leaflet + Carto dark_all | flutter_map + 相同 tile URL（见 decisions D1） |
| PWA | 是 | 否（原生 App） |
| 历史迁移 | — | 不迁移（见 decisions D4） |
| 后台录制 | — | 不支持（见 decisions D3） |
