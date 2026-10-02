# VeloTrack Android（纯原生）

当前工程已移除 Flutter 依赖，采用纯原生 Android 路线（Kotlin）。

## 构建

```bash
./gradlew :app:assembleDebug
```

## 地图双版本策略（全球）

- 中国大陆：`Amap (CN)`
- 海外默认：`Google Maps (Global)`

当前已在 `MapProviderSelector` 中按区域自动切换地图实现，并接入了对应 SDK 的轨迹渲染。

在被 Git 忽略的 `local.properties` 中配置地图 Key，CI 使用 Secret 注入；不要写入受 Git 跟踪的文件：

```properties
GOOGLE_MAPS_API_KEY=...
AMAP_API_KEY=...
```

### 国内高德地图不显示 / 白屏排查

1. **隐私合规**：已在 `VeloApplication` 中调用 `MapsInitializer.updatePrivacyShow` / `updatePrivacyAgree`，必须在任何 `MapView` 创建之前执行（当前已满足）。
2. **Key 与包名、签名一致**：`debug` 构建带 `applicationIdSuffix`，实际包名为 **`com.velotrack.velotrack.debug`**，请在[高德开放平台](https://lbs.amap.com/)为该包名 + **debug keystore SHA1** 单独配置 Key；`release` 使用 **`com.velotrack.velotrack`** + 发布签名。
3. **网络权限**：已声明 `INTERNET` 与 `ACCESS_NETWORK_STATE`。

## 可选：Gemini

Debug 直连需在 `local.properties` 中同时配置 `GEMINI_API_KEY=...` 和 `GEMINI_MODEL=当前项目可用的模型名称`。
模型名称由开发者在对应服务账户确认，客户端不再回退到已停用模型。Release 不会把该密钥打进 APK，
必须配置 `AI_PROXY_URL=https://...`，代理接收 `{requestId, prompt}` 并返回 `{text}`。

正式签名通过 CI Secret 或未提交的 `local.properties` 配置：

```properties
RELEASE_STORE_FILE=/absolute/path/to/release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

## 验证与发布

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest  # 需要已启动的设备或模拟器
./gradlew assembleRelease           # 编译与 R8 检查，未配置签名时产出 unsigned APK
./gradlew verifyReleaseReady -PPREVIOUS_VERSION_CODE=1
```

发布检查会验证签名、地图、HTTPS AI 代理与递增版本号，再生成 Release Bundle。版本号可通过 `-PVERSION_CODE=数字` 设置。
`PREVIOUS_VERSION_CODE` 应填写商店中已用的最高值；构建检查不能代替商店记录核对及正式包安装验证。

录制使用 Room v6，保存活动时长检查点和统计算法版本。旧库升级保留轨迹；旧草稿没有活动时长时仅估算同段连续时间。
当前原生架构见 [Android 现行决策](docs/decisions.md#d5--原生-android-现行实现)，审查实施进度见 [spec](spec/)。
