# Android 优化清单

本文档整理 `VeloTrack-android` 当前可优化点，按优先级和模块分类。用于后续排期、PR 拆分和回归检查。

## P0：安全与发布风险

### 1. 密钥管理

**现状**
- `app/build.gradle.kts` 会把 `GEMINI_API_KEY`、`GOOGLE_MAPS_API_KEY`、`AMAP_API_KEY` 注入到 `BuildConfig` 或 Manifest。
- 若密钥直接放在 `gradle.properties` 或打入 APK，反编译后可被读取。

**影响**
- 第三方服务密钥可能被盗用，带来配额、账单和合规风险。

**建议**
- 本地开发使用 `local.properties` 或未入库的环境变量。
- CI 使用 Secret 注入。
- Gemini 等服务端密钥建议通过后端代理，不直接放入客户端。
- Google / 高德 Key 需在控制台限制包名、SHA1 和调用范围。

**涉及文件**
- `app/build.gradle.kts`
- `gradle.properties`

### 2. Release 构建配置

**现状**
- `release` 仍使用 debug 签名。
- `isMinifyEnabled = false`。

**影响**
- 不适合正式发布。
- APK 更易被逆向，包体也更大。

**建议**
- 配置独立 release 签名。
- 开启 R8 / ProGuard，并按需补充混淆规则。
- 评估开启资源压缩。

**涉及文件**
- `app/build.gradle.kts`

### 3. 依赖版本不可复现

**现状**
- 高德依赖使用 `latest.integration`：

```kotlin
implementation("com.amap.api:3dmap-location-search:latest.integration")
```

**影响**
- 不同时间或不同机器可能解析到不同版本。
- 线上问题难以复现。

**建议**
- 固定为明确版本号。
- 后续通过单独 PR 升级依赖并回归地图、定位、打包。

**涉及文件**
- `app/build.gradle.kts`

## P1：功能正确性与稳定性

### 4. 通知权限声明不一致

**现状**
- `MainActivity` 在 Android 13+ 会请求 `POST_NOTIFICATIONS`。
- `AndroidManifest.xml` 当前未声明该权限。

**影响**
- 权限请求行为不稳定。
- 后续如果接入前台服务通知，会出现权限链路不完整。

**建议**
- 如果短期不使用通知，先移除请求。
- 如果要支持后台骑行记录，补充 Manifest 权限并设计通知权限引导。

**涉及文件**
- `app/src/main/AndroidManifest.xml`
- `app/src/main/kotlin/com/velotrack/velotrack/MainActivity.kt`

### 5. 后台与锁屏录制

**现状**
- 录制定位订阅由 `MainActivity` 控制。
- `onPause()` 会停止定位更新。
- 当前没有前台服务。

**影响**
- App 切后台或息屏后，骑行轨迹可能中断或变稀疏。

**建议**
- 如果产品目标包含真实骑行记录，应引入 Foreground Service。
- Manifest 声明 location foreground service type。
- 配套常驻通知、权限说明和系统限制适配。

**涉及文件**
- `MainActivity.kt`
- `AndroidManifest.xml`

### 6. 高德轨迹绘制全量重建

**现状**
- `AmapPane` 每次点变化会 `map.clear()`，再重画轨迹线和描边线。

**影响**
- 长时间骑行点数增多后，地图更新成本持续上升。
- 可能导致掉帧、耗电增加。

**建议**
- 保存 Polyline 引用。
- 后续点更新时使用 `setPoints(...)` 或等效增量更新。
- 对超长轨迹做降采样或分段绘制。

**涉及文件**
- `MapPane.kt`

### 7. 相机跟随节流

**现状**
- 已支持手动移动地图后延迟 3 秒平滑回正。
- 正常跟随状态下，定位点变化仍可能频繁触发相机动画。

**影响**
- 高频动画会增加耗电。
- 低端设备可能出现地图轻微卡顿。

**建议**
- 增加距离阈值，例如位置变化超过一定米数才移动相机。
- 或增加时间节流，例如每 2-3 秒最多动画一次。
- 录制中连续跟随可考虑 `moveCamera`，回正时才使用 `animateCamera`。

**涉及文件**
- `MapPane.kt`

## P2：架构与维护性

### 8. 定位逻辑集中在 Activity

**现状**
- `MainActivity` 同时负责权限、定位订阅、定位缓存、地图 provider 选择和 UI 绑定。

**影响**
- 难测试，难复用。
- 后续接入前台服务或多种定位源会让 Activity 继续膨胀。

**建议**
- 抽象 `LocationEngine`。
- 用 `StateFlow` 或 `callbackFlow` 输出定位点。
- Activity 只负责权限和生命周期桥接。

**涉及文件**
- `MainActivity.kt`
- `TrackViewModel.kt`

### 9. `MainScreen.kt` 体量过大

**现状**
- 单文件承载 Recording、Log、Detail、BottomNav、Modal、Chart 等 UI。

**影响**
- 修改一个页面时容易影响其他页面。
- 文件导航和代码 review 成本增加。

**建议**
- 拆分为：
  - `ui/recording/RecordingScreen.kt`
  - `ui/history/HistoryScreen.kt`
  - `ui/detail/DetailScreen.kt`
  - `ui/components/BottomNavBar.kt`
  - `ui/components/DeleteRideModal.kt`

**涉及文件**
- `MainScreen.kt`

### 10. Room 轨迹点存储方式

**现状**
- 轨迹点作为 JSON 字符串保存在单列。

**影响**
- 长骑行记录会导致单条数据很大。
- 历史详情读取时需要整体解析。
- 后续查询、分页和统计不方便。

**建议**
- 短期可保持现状，但需要记录上限和压测。
- 中长期可拆为 `Ride` 表 + `GpsPoint` 表。
- 或按 segment 分块存储轨迹点。

**涉及文件**
- `RideRepository.kt`
- `db/RideEntity.kt`
- `db/RideDao.kt`
- `db/AppDatabase.kt`

### 11. AI 请求错误处理

**现状**
- Gemini 请求失败时统一显示“分析失败，请稍后重试”。
- 网络请求缺少明确 timeout、重试和错误分类。

**影响**
- 用户无法区分无网络、Key 无效、配额限制或服务异常。

**建议**
- 给请求加 `withTimeout`。
- 增加有限重试和退避。
- 根据错误类型显示更准确的文案。

**涉及文件**
- `GeminiClient.kt`
- `TrackViewModel.kt`

### 12. 地图 Provider 选择过于简单

**现状**
- 当前按系统地区选择：
  - `CN` 使用高德
  - 其他地区使用 Google Maps

**影响**
- 用户实际所在地、系统地区和网络环境不一定一致。
- 不方便 debug 和手动切换。

**建议**
- 增加用户设置或 debug override。
- 将选择结果持久化。

**涉及文件**
- `MapProviderSelector.kt`
- `MainActivity.kt`

## P3：清理项

### 13. Debug/Profile Manifest 模板残留

**现状**
- `app/src/debug/AndroidManifest.xml` 和 `app/src/profile/AndroidManifest.xml` 仍可能包含模板式注释或无关内容。

**影响**
- 不影响功能，但会误导后续维护者。

**建议**
- 精简为项目真实需要的 debug/profile 配置。

**涉及文件**
- `app/src/debug/AndroidManifest.xml`
- `app/src/profile/AndroidManifest.xml`

## 建议执行顺序

1. 固定高德依赖版本，确保构建可复现。
2. 修正通知权限请求与 Manifest 声明。
3. 优化高德 Polyline 增量更新。
4. 评估并设计后台骑行记录前台服务。
5. 拆分 `MainScreen.kt`，降低后续 UI 调整成本。
6. 梳理密钥注入方式，尤其是 Gemini Key 的客户端暴露问题。
