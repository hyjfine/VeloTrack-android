# VeloTrack Android（宿主）

与 **`qiqixue-reading-maniac-android`** 相同的 Flutter 接入方式：

- **`settings.gradle`**：`useFlutterSource` + `dependencyResolutionManagement` + 条件执行 `../VeloTrack-flutter/.android/include_flutter.groovy`
- **`gradle.properties`**：`useFlutterSource=true|false`
- **源码依赖**：`implementation(project(":flutter"))`
- **AAR 依赖**：`implementation("com.velotrack.flutter_module:flutter_release:1.0")`（由 `flutter build aar` 生成，见下）

Gradle 插件与部分库版本与 `qiqixue-reading-maniac-android` 对齐（AGP 8.10.0、Kotlin 2.1.0、KSP、阿里云镜像等）。

## 前置条件

与 reading 工程一样，本目录与 Flutter 模块为**兄弟目录**：

```
VeloTrack/
  VeloTrack-android/    ← 本仓库（当前目录）
  VeloTrack-flutter/    ← Flutter module
```

`local.properties` 中需有 `sdk.dir` 与 **`flutter.sdk`**（Flutter 模块的 `.android/local.properties` 由在 `VeloTrack-flutter` 下执行 `flutter pub get` 生成）。

## 源码模式（默认）

`gradle.properties`：

```properties
useFlutterSource=true
```

编译：

```bash
./gradlew :app:assembleDebug
```

## AAR 模式（与 reading 脚本一致）

1. 在 **`VeloTrack-flutter`** 已配置 `pubspec.yaml` 的 `flutter: module:` 的前提下执行：

   ```bash
   ./scripts/build_flutter_aar.sh
   ```

   等价于参考仓库的 `scripts/build_flutter_aar.sh`：在 Flutter 目录执行 `flutter build aar`，输出到本仓库的 **`flutter_aar/`**。

2. 将 `gradle.properties` 改为：

   ```properties
   useFlutterSource=false
   ```

3. 再执行 `./gradlew :app:assembleRelease`（或 `assembleDebug`）。`settings.gradle` 会在 `flutter_aar/host/outputs/repo` 存在时挂上本地 Maven 仓库。

## Pigeon / 原生代码

Kotlin 生成物与业务实现位于 `app/src/main/kotlin/`（由 `VeloTrack-flutter` 根目录执行 `dart run pigeon` 写入 `com/velotrack/pigeon/`）。

## 可选：Gemini

在 `gradle.properties` 中增加 `GEMINI_API_KEY=...` 供 `BuildConfig` 使用。
