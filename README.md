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

在 `gradle.properties` 中配置地图 Key：

```properties
GOOGLE_MAPS_API_KEY=...
AMAP_API_KEY=...
```

### 国内高德地图不显示 / 白屏排查

1. **隐私合规**：已在 `VeloApplication` 中调用 `MapsInitializer.updatePrivacyShow` / `updatePrivacyAgree`，必须在任何 `MapView` 创建之前执行（当前已满足）。
2. **Key 与包名、签名一致**：`debug` 构建带 `applicationIdSuffix`，实际包名为 **`com.velotrack.velotrack.debug`**，请在[高德开放平台](https://lbs.amap.com/)为该包名 + **debug keystore SHA1** 单独配置 Key；`release` 使用 **`com.velotrack.velotrack`** + 发布签名。
3. **网络权限**：已声明 `INTERNET` 与 `ACCESS_NETWORK_STATE`。

## 可选：Gemini

在 `gradle.properties` 中增加 `GEMINI_API_KEY=...` 供 `BuildConfig` 使用。
