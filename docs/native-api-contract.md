# 原生接口契约（Pigeon）

三端通过 **Pigeon**（Flutter 官方 type-safe channel codegen）统一接口。
**本文档是 Flutter / Android / iOS 三端唯一的对齐入口**，任何能力诉求必须先改本文档。

## 1. 单元与约定

| 字段 | 类型 | 单位 |
|---|---|---|
| `timestamp` | `int64` | epoch milliseconds |
| `speed` | `double?` | **m/s**（与 h5 一致，显示层乘 3.6 转 km/h） |
| `altitude` | `double?` | meters |
| `accuracy` | `double?` | meters（新增字段，用于原生内做 `>40m` 丢点） |
| `totalDistance` / `avgSpeed` / `maxSpeed` | `double` | 同上 |
| `id` | `String` | 形如 `Date.now().toString()`，即 epoch ms 字符串 |

> 所有可空字段使用 `nullable`，禁止用 `0` / `-1` 表示缺失。

## 2. DTO

```dart
// pigeons/velotrack_api.dart

class GpsPointDto {
  double lat;
  double lng;
  int timestamp;
  double? speed;
  double? altitude;
  double? accuracy;
}

class RideDto {
  String id;
  String title;
  int startTime;
  int? endTime;
  List<GpsPointDto?> points;
  double totalDistance;
  double avgSpeed;
  double maxSpeed;
}

enum TrackingState {
  idle,
  tracking,
  paused,
}
```

## 3. HostApi（Flutter → Native）

```dart
@HostApi()
abstract class VeloNativeApi {
  /// 开始录制。原生：申请权限 → 启动 Fused/CL → 拉起 WakeLock → 启动 1s 计时器。
  /// 若权限未授予，抛 FlutterError(code = 'GPS_PERMISSION_DENIED')
  @async
  void startTracking(String rideId, String title);

  /// 暂停：仅停计时增量与轨迹累加，不释放 WakeLock，不停 Fused（继续推但 Flutter 侧忽略）
  @async
  void pauseTracking();

  @async
  void resumeTracking();

  /// 停止：落库 + 计算 avgSpeed/endTime + 释放 WakeLock + 关闭 Fused + 返回终稿
  @async
  RideDto stopTracking();

  /// 返回当前状态，用于 App 恢复前台时做同步（避免状态错位）
  @async
  TrackingState getState();

  /// 持久化：按 startTime DESC 返回
  @async
  List<RideDto> listRides();

  @async
  RideDto? getRide(String id);

  @async
  void deleteRide(String id);

  /// AI 代理：原生读库、组 prompt、调 Gemini、返回纯文本。
  /// 失败抛 FlutterError(code = 'AI_PROXY_FAILED', message)
  @async
  String analyzeRide(String id);
}
```

## 4. FlutterApi（Native → Flutter）

```dart
@FlutterApi()
abstract class VeloFlutterApi {
  /// 每个合法 GPS 点推一次；原生已做 accuracy > 40m 丢点
  void onLocation(GpsPointDto point);

  /// 1Hz 计时；elapsedMs 为已排除暂停时长
  void onElapsed(int elapsedMs);

  /// 结构化错误上报
  void onError(String code, String message);
}
```

### 错误码集

| code | 含义 | Flutter 处理 |
|---|---|---|
| `GPS_PERMISSION_DENIED` | 权限被拒 | 弹引导跳设置 |
| `GPS_SIGNAL_LOST` | 连续 >10s 无定位 | 顶部状态卡切 SIGNAL LOST |
| `DB_WRITE_FAILED` | 落库失败 | Toast，保留 currentRide 在内存 |
| `AI_PROXY_FAILED` | Gemini 返回非 200 或网络异常 | 详情页 AI 卡替换文案 |
| `UNKNOWN` | 兜底 | Toast |

## 5. 生命周期规则（与决策 D3 一致）

Flutter 侧监听 `AppLifecycleState`：

| 状态变化 | 调用 |
|---|---|
| `resumed` 且之前在 tracking | 调 `getState()` 对齐；不自动 `resumeTracking` |
| `paused` / `inactive` | 调 `pauseTracking()` |
| `detached` | 调 `stopTracking()`（兜底；按钮触发的正常停止优先） |

原生侧在 App 被系统杀掉前，若处于 `tracking` 状态，必须把当前进度 flush 到 DB（落一条 `endTime = now` 的残局记录，`title` 尾加 `(interrupted)` 标识）。

## 6. 权限清单

### Android (`VeloTrack-android/app/src/main/AndroidManifest.xml`)
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`
- `INTERNET`
- **不声明** `ACCESS_BACKGROUND_LOCATION`
- **不声明** `FOREGROUND_SERVICE`
- WakeLock 通过 `FLAG_KEEP_SCREEN_ON` 挂在 Activity Window，不申请 `WAKE_LOCK` 权限

### iOS (`Info.plist`)
- `NSLocationWhenInUseUsageDescription`：`骑行途中需要定位以记录轨迹`
- **不加** `NSLocationAlwaysAndWhenInUseUsageDescription`
- **不加** `UIBackgroundModes: location`

## 7. 版本演进

| 版本 | 变更 |
|---|---|
| 1.0 | 本文初版，对齐 h5 现状 |

> 破坏性改动（字段删减 / 语义变更）必须 bump major；Flutter 与原生同时升级。新增可空字段视为兼容变更。

## 8. Pigeon 生成命令（供实现者参考）

```bash
# 在 VeloTrack-flutter 根目录（Kotlin 输出路径写在 pigeons/velotrack_api.dart 的 @ConfigurePigeon 里）
dart run pigeon --input pigeons/velotrack_api.dart
```

当前 `kotlinOut` 指向 `../VeloTrack-android/app/src/main/kotlin/com/velotrack/pigeon/VelotrackApi.g.kt`（与 **qiqixue-reading-maniac** 一致：Pigeon 桩落在 **Android 宿主工程**，Flutter 侧为 add-to-app **module**）。
