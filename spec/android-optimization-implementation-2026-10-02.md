# Android 审查优化实施记录

日期：2026-10-02。起点：`ea4f2fc`。对应 [审查报告](android-project-review-2026-10-02.md)。

本轮依次实施 F01–F17，并处理与这些修复相连的地图初始化、定位回退、后台服务、详情性能、发布检查和文档问题。下表的“已实现”表示代码已修改；设备、外部服务和发布条件的验收边界另列，不能理解为所有真机场景均已通过。

## 问题修复

| 编号 | 实施结果 | 验证重点 |
| --- | --- | --- |
| F01 | 新增 DraftWriteBuffer；数据库确认后推进游标；失败保留完整点列，下次继续补写；草稿创建用 IGNORE，避免重试时级联删除已有点；向 UI 报告存储错误 | 连续追加失败、创建失败、恢复补写、重复创建及最终保存 |
| F02 | 信号质量恢复与位置重锚独立计数；恢复后开启新 segment | 好点恢复到新区域、不跨断线累计距离 |
| F03 | 低速位移相对稳定锚点累计，限制在连续时间窗口内；实时和保存后共用统计规则；位移估速增加短距离窗口兜底 | 120 米低速轨迹、多种精度、静止抖动、尖峰及实时/离线一致性 |
| F04 | 地图先推断真实分段再降采样；抽样点用独立类型保留分段语义；保留每段首尾 | 20,001 点恢复、6,001 点实时输入、真实暂停边界 |
| F05 | 移除错误港澳矩形；正向区域由高德 SDK 判断，反向转换只应用于 SDK 标识为 GCJ-02 的定位；逆变换迭代提高精度 | 深圳、上海往返转换；台湾与境外样本不转换 |
| F06 | Room v6 增加 activeDurationMs；点和活动时长检查点在同一事务写入；RecordingClock 排除暂停和壁钟变化 | 暂停 10 分钟不回算；检查点恢复；旧草稿使用同段连续时间下界估计 |
| F07 | 数据库、列表、详情不再把合法零移动时间替换为壁钟总时长；AI 明确区分移动与活动时长 | 静止轨迹保存重开后仍为零 |
| F08 | 所有进入活动录制态的路径统一启动定位、计时和周期落盘 | 恢复和保存失败后继续录制共用同一路径；设备长时间回归仍需完成 |
| F09 | 统一错误暂停：冻结时钟、取消 ticker、停止定位；通知刷新不会自行重启失败服务 | 时钟暂停幂等；权限或服务失败分支审查 |
| F10 | 极差精度也参与信号劣化；无回调超过阈值标记信号丢失；首次等待定位有原因说明 | 连续 accuracy=300 输入、健康检查调用链 |
| F11 | 无 fix 单调时间时校验本次活动段和位置年龄；段壁钟边界结合当前壁钟与单调时间换算 | 拒绝暂停期间生成的高德旧点；保留系统定位单调时间分支 |
| F12 | 详情读取在 IO 执行，回主线程后检查取消和请求序号；切页作废旧请求 | 编译及竞态分支审查；快速切换真机验收待补 |
| F13 | 历史、详情与保存后刷新有错误状态和重试；保存成功不因列表刷新失败丢失结果；保留取消语义 | UI 错误分支、数据库异常处理路径 |
| F14 | OkHttp 异步请求关联协程取消；取消同时中止响应读取；用 delay 重试；整个操作限制 60 秒，响应限制 1 MiB | 本地 HTTP 延迟响应中取消后不再重试 |
| F15 | AI 错误态提供“重试分析” | UI 代码检查，重新分析清理旧错误 |
| F16 | 配置 AndroidJUnitRunner；增加 1→6、5→6、损坏旧 JSON、写库失败及检查点测试；CI 增加实际模拟器执行 | 设备测试结果见下文，旧 schema 1–5 保留并新增 6.json |
| F17 | 移除停用模型回退；Debug 直连必须显式配置 GEMINI_MODEL；代理不依赖客户端模型或密钥；合并有效文本 parts | 代理不配置 key/model 仍可工作；模型权限及可用性由服务账户确认 |

旧轨迹坐标没有自动重写。已有错误坐标需要先识别来源和受影响范围，避免二次转换。统计算法升级则使用 statsVersion 管理，历史记录按当前算法重新汇总。

## 风险项处理

| 编号 | 当前状态 | 剩余验收 |
| --- | --- | --- |
| R01 | Google 图标工厂移到 GoogleMap 的内容回调内，保证拿到地图之后调用 | 有效 Google Key 的全新进程和无 GMS 设备 |
| R02 | 检测 GMS 可用性；订阅 Task 失败回退系统定位；停止时移除系统订阅，过期回调不能重新开启定位 | 无 GMS、权限撤回、定位开关变化等实机组合 |
| R03 | Service 提升前台异常会暂停会话；Service 销毁与 Manager 协调；通知不再周期性重复启动失败服务 | Android 多版本权限/后台限制、锁屏长骑、进程重建 |
| R04 | 默认 versionCode=2、versionName=1.0.2，可覆盖版本号；增加 validateReleaseConfiguration 和 verifyReleaseReady；CI 增加 Release/R8 构建 | 商店最高 versionCode、正式签名/地图绑定、代理配置、Release 安装冒烟 |
| R05 | 客户端沿用同一 requestId 重试并限制响应大小 | 服务端代码不在本仓库；鉴权、限流、幂等与数据留存仍需部署方验证 |
| R06 | 增加导航 Tab 选中语义，图表显示真实时间和暂停边界 | 方向传感器四方位、小屏、字体 200%、TalkBack 和导航模式真机检查 |

代理契约仍是 HTTPS POST `{requestId, prompt}` → `{text}`。部署方需要保证相同 requestId 的重复请求不会重复计费或重复生成，明确身份验证与限额策略；不能用 APK 中的固定秘密替代服务端鉴权。此次未向真实 AI 服务发送骑行数据。

## 优化项进展

| 编号 | 状态 | 本轮落地及后续工作 |
| --- | --- | --- |
| O01 | 已实施主要路径 | RidePresentationData 在 IO 准备地图及图表；UI 不再过滤完整轨迹；图表抽样保留局部峰谷及段端点。仍需在目标手机测量掉帧和长轨迹打开耗时 |
| O02 | 部分实施 | 不可见地图跳过几何计算；轨迹 key 按点列缓存，方向变化不再重新扫描。高德所有坐标转换的缓存可在性能测量后继续细化 |
| O03 | 已实施 | 最终保存改为更新父记录并补写未确认的尾部，保留已写点；位置预览缓存每 10 秒最多写一次。失败后最终事务仍可补回全部未确认点 |
| O04 | 部分实施 | 抽出可测试的 RecordingClock 和 DraftWriteBuffer，增加 RecordingIssue 类型，HUD 不再依赖中文子串。LocationSource/RideStore/Service 的完整依赖注入留待下一轮 |
| O05 | 待实施 | 历史列表仍读取摘要全表；分页、Flow、独立摘要模型及索引需结合记录规模测量推进 |
| O06 | 部分实施 | Room 增加 statsVersion，修复按记录版本执行，列表/详情/AI 使用一致的时长语义；定位质量元数据持久化尚未扩展 |
| O07 | 已实施 | ViewModel 清理自己的全局日志回调；AI 缓存限制 32 条，在主线程访问并随删除失效 |
| O08 | 已实施 | README 改为 local.properties，说明显式模型和发布验证；旧 ADR 标为被 D5 替代，跨端设计文件加历史标记 |
| O09 | 部分实施 | 移除当前 Gradle 无效的 tooling.parallel；Wrapper 使用 bin 包并增加官方 SHA-256。依赖锁定和完整依赖校验尚未引入 |
| O10 | 待实施 | 用户导出/导入备份、完整数据说明与撤回同意入口尚未实施；现有隐私同意与关闭系统备份策略保持原行为 |
| O11 | 部分实施 | 导航 selected/Tab 语义；图表按时间绘制、不跨暂停连接。完整字符串资源化、图表朗读说明和动态字号布局仍需推进 |

地图点数预算优先让位于真实分段边界：如果轨迹包含大量短段，可以超过 2,000 点以保留每段端点。当前抽样不是精确道路匹配；转弯保真和目标机性能仍需测量。

## 验证记录

| 检查 | 最终结果 |
| --- | --- |
| 正式 Gradle JVM 单测 | **40 个通过，0 失败，0 错误**；包括失败批次、低速统计、长轨迹、信号恢复、暂停时钟、网络取消和图表抽样 |
| Android 设备测试 | **9 个通过，0 失败，0 错误**；Medium_Phone_API_36.1 模拟器，Android 16；包括迁移、事务失败重试、检查点、增量结束保存及高德坐标 SDK |
| Debug lint | **0 错误，20 项警告**；均为依赖/AGP 版本更新提示，未为消除提示盲目升级工具链 |
| Debug APK 与测试 APK | 构建成功，设备测试实际安装执行 |
| Release/R8 | 构建成功，生成 `app/build/outputs/apk/release/app-release-unsigned.apk`；未配置正式签名，不是可发布成品 |
| 发布配置负向检查 | 按预期失败，明确指出缺少正式签名配置、AI_PROXY_URL、GOOGLE_MAPS_API_KEY；未上传或发布 |
| Diff 格式 | `git diff --check` 通过 |

实际执行使用本机已安装的 Gradle **8.14**、Android Studio JBR 和项目配置的 AGP/Kotlin 版本：

```bash
gradle :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleRelease --console=plain
gradle :app:validateReleaseConfiguration --console=plain
```

项目 Wrapper 仍固定 **8.11.1**，分发 URL 改为官方 bin 包并加入官方校验和。尝试通过 Wrapper 验证时，下载约 9 分钟仅完成 1.6 MiB，因此终止该下载，使用已安装版本完成上述验证；不能声称已经在 Wrapper 8.11.1 上通过构建。CI 会使用 Wrapper，并实际执行设备测试，但本轮尚未推送触发远端 CI。

新增测试暴露并修正过测试资源关闭方式与 Gradle Kotlin DSL 导入问题；上表统计均来自最后一次成功运行。设备测试结果不涵盖真实卫星定位、长时锁屏、Google 地图远端授权、AI 真实代理或正式签名安装。

审查报告保留原始基线。本轮变更包含源码、测试、Room v6 schema、CI 与本实施记录；提交状态以 Git 历史为准。

## 下一轮顺序

1. 使用目标手机完成 R01–R03、R06 的 SDK、权限、后台骑行和无障碍回归。
2. 补全 O04 依赖注入和 ViewModel 导航/错误状态的自动化交互测试。
3. 根据大数据样本的耗时与内存结果实施 O02、O05，并补 O06 的质量元数据。
4. 设计并实现 O10 的用户自主备份、导入恢复及隐私管理入口，再完成 O11 的资源化与动态字号。
5. 配合服务端和发布账户验收 R04–R05，推进 O09 的依赖锁定；全部通过后再发布。

## SDK 与构建依据

- [高德 CoordinateConverter](https://a.amap.com/lbs/static/unzip/Android_Map_Doc/3D/com/amap/api/maps/CoordinateConverter.html)：区域判定由 SDK 提供。
- [高德 AMapLocation](https://a.amap.com/lbs/static/unzip/Android_Location_Doc/com/amap/api/location/AMapLocation.html)：依据 coordType 区分 GCJ-02 与 WGS-84。
- [Android Emulator Runner](https://github.com/ReactiveCircus/android-emulator-runner)：CI 模拟器和 KVM 配置。
- [Gradle 8.11.1 bin 官方校验和](https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256)：Wrapper 完整性校验来源。
