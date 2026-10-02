# 关键决策（ADR）

每条决策一旦被新决策取代，不要删除，追加 `Superseded by: Dxxx` 标记，保留历史。

---

## D1 · 地图方案：`flutter_map` + Carto dark_all

**Status**: Superseded by: D5 · 2026-10-02

**Decision**: Flutter 端使用社区包 `flutter_map`，瓦片源沿用 h5 中的
`https://{s}.basemaps.cartocdn.com/rastertiles/dark_all/{z}/{x}/{y}.png`，
与 h5 视觉保持 1:1。

**Consequences**:
- 可直接复用 h5 的地图配色基调 `#151619` 背景 + `opacity:0.9` 瓦片。
- **合规提示**：Carto 在中国大陆无 CDN 节点，网络访问稳定性一般；后续如出海以外还要服务国内用户，需要准备 MapView 抽象层切到高德/腾讯 SDK，但**当前版本不做**。
- Polyline 颜色锁死 `#E2FF3B`，`weight: 5`，`opacity: 0.9`。

---

## D2 · AI 调用路径：前台原生代理（无后端）

**Status**: Superseded by: D5 · 2026-10-02

**Decision**: 不引入独立后端。Gemini API Key 存储在原生端安全存储
（Android: EncryptedSharedPreferences / Keystore；iOS: Keychain）。
Flutter 仅通过 `VeloNativeApi.analyzeRide(rideId)` 触发分析，原生端负责：

1. 从本地 DB 读取 Ride 数据
2. 组装 prompt（见 `ui-spec.md` § AI Prompt）
3. HTTPS 调用 Gemini，返回文本
4. Flutter 只拿 `String` 结果

**Consequences**:
- 免后端运维；key 不会随 apk 反编译泄漏。
- **Key 下发**：首次打包前通过构建脚本从本地 `.env` 或 CI secret 写入资源，运行时首次启动落到安全存储，原资源清零。
- 限速/用量只能在客户端做软限制（例如：同一 ride 一次分析 + 手动触发刷新冷却 60s）。
- 若未来需要统一用量、审计或切换模型，需再起一次决策会（潜在升级到 D2-next：后端代理）。

---

## D3 · 后台定位：不支持

**Status**: Superseded by: D5 · 2026-10-02

**Decision**: 应用回到后台或锁屏即**自动暂停**定位采集，前台回来需用户手动恢复。

**Consequences**:
- Android 不需要 Foreground Service / 持续通知，不触发电池优化白名单提示。
- iOS 不需要 `Background Modes: location`，上架审核门槛降低。
- **Flutter 侧行为**：监听 `AppLifecycleState`：
  - `paused` / `inactive` → 调用 `VeloNativeApi.pauseTracking()`
  - `resumed` → **不**自动恢复（用户手动按钮恢复，符合 h5 暂停语义）
- WakeLock 仅在前台生效；`AppLifecycleState.paused` 时原生自动释放。
- **用户提示**：在首次录制前的权限引导页注明"请在骑行途中保持应用前台"。

---

## D4 · 历史数据迁移：不做

**Status**: Superseded by: D5 · 2026-10-02

**Decision**: Flutter App 首发即空库。不提供从 h5 IndexedDB 的 `.gpx` 或 JSON 导入。

**Consequences**:
- `VeloTrack-h5` 的用户被视作原型测试用户，不承诺数据延续。
- Flutter 端无需任何导入 UI / 文件选择器 / 解析代码。
- 若未来用户要求，可通过"导出 JSON 粘贴导入"的轻量方式补做，不阻塞首发。

---

## 决策依赖图

```
D1 flutter_map ──┐
D2 原生代理 ─────┼──→ 不引入后端、不引入地图 SDK，工程复杂度保持在单仓 App 级别
D3 仅前台 ───────┘
D4 不迁移 ───────→ 首发数据库空，安装即新用户
```


## D5 · 原生 Android 现行实现

**Status**: Accepted · 2026-10-02

以当前 Kotlin 源码为准，替代 D1–D4 对本仓库的约束；旧文保留用于理解历史方案。

- 界面：Jetpack Compose；国内高德、海外 Google Maps。定位引擎失败时可回退到系统 GPS。
- 录制：前台定位服务支持后台和锁屏；进程重建后以暂停状态恢复草稿，由用户继续。
- 存储：Room v6 双表，串行增量写入、确认后推进游标、活动时长检查点、统计版本与迁移测试。
- 坐标：WGS-84 入库；高德 GCJ-02 定位归一化，地图边界按 SDK 适用区域转换。
- AI：Release 仅走 HTTPS 服务端代理。Debug 可用显式模型配置直连；本地安全存储不能让打包密钥对 APK 持有者保密。
- 数据延续：支持原生 Room 旧版本升级；跨 H5/Flutter 导入、用户自主备份仍需单独实现，不能与 Room 升级混为一谈。
- 验证：单测、lint、Debug/Release 编译，模拟器实际执行迁移与持久化测试。发布前额外检查正式配置和实际安装行为。
