# VeloTrack Android 全项目审查与优化建议

审查日期：2026-10-02。代码基线：main 分支 ea4f2fc76928e4ce3677d75e61656111a76e6c55。

本报告覆盖 Android 仓库的 43 个 Kotlin 生产源码文件、现有测试、Manifest、Room schema、Gradle、CI 和项目文档。重点检查轨迹数据正确性、后台录制可靠性、地图展示、界面状态、AI 调用及发布流程。兄弟目录中的 Flutter 和 H5 工程不属于本次代码审查范围。

当前最应优先处理的是：写库失败后未保留待写批次、信号暂停后的重锚失败、低速距离漏算、长轨迹降采样导致断线，以及坐标转换的区域误判。报告列出 **17 项有明确代码证据的问题、6 项需要进一步验证的风险、11 项改进建议**。这些条目描述审查时的状态，尚未实施修复。

> 后续实施进度见 [优化实施记录](android-optimization-implementation-2026-10-02.md)。本报告保留审查基线，问题描述不是当前修复后的状态。

## 阅读方式与验证范围

- **P1**：优先修复，涉及轨迹丢失、核心统计错误或关键功能不可用。
- **P2**：后续迭代修复，涉及特定条件下的可靠性、状态一致性和验证缺口。
- **P3**：维护性、使用体验或规模增长后的优化。
- **已复现**：直接编译仓库中的算法源码，运行合成输入得到反例。
- **代码确认**：可以从调用链或配置确定缺口；没有宣称已经在真机触发。
- **待验证风险**：有具体依据，但还依赖 SDK、设备、远端配置或实际发布状态。

文中代码链接固定到本次审查的 Git 提交，避免后续修改导致行号失效。外部文档核对日期同为 2026-10-02。

| 检查项 | 本次结果与边界 |
| --- | --- |
| 静态审查 | 已检查录制、定位、过滤、统计、地图、数据库、UI、AI、权限、构建和 CI 的主路径及异常分支 |
| 现有算法单元测试 | 在独立 JVM 环境运行 5 个测试类，共 25 个测试全部通过 |
| 补充反例 | 复现信号恢复、低速里程、恢复后的地图、实时长轨迹、极差 GPS 状态、高德恢复旧点、坐标区域判定等问题，见验证记录 |
| 测试工具差异 | 独立环境使用本机可用的 Kotlin 2.0.21 编译器与 JUnit 4.13.2；项目配置为 Kotlin 2.1.0。仅为 Android 日志、时钟、Compose 注解等提供最小桩，未改动算法源码 |
| 完整 Gradle 检查 | Wrapper 所需 Gradle 8.11.1 未下载完整；改用本机 Gradle 8.14 离线运行单测、lint、debug APK 和 androidTest APK 构建，但在配置阶段因 AGP 8.10.0、Kotlin 2.1.0、KSP 等依赖缓存缺失失败，未进入源码编译 |
| 真机与远端验证 | 未执行定位 SDK 实测、长时锁屏骑行、Android instrumentation、Release/R8 运行验证，也未调用 AI 服务或检查服务端配置 |

项目已有前台服务、精确定位权限检查、Room 双表和迁移、串行写入队列、草稿恢复、分段轨迹、增量显示轨迹、Release R8、隐私同意页及 Release AI 代理入口。这些已实现能力不重复列为“尚未实现”的问题。旧版优化清单不能替代本报告。

## 问题优先级总览

| 编号 | 优先级 | 问题 | 证据 |
| --- | --- | --- | --- |
| F01 | P1 | 增量写库最终失败后批次被遗忘，录制界面不报错 | 代码确认 |
| F02 | P1 | 信号暂停和野点重锚计数互相重置，阻止恢复入库 | 已复现 |
| F03 | P1 | 逐段最小距离门槛导致持续低速骑行里程为零 | 已复现 |
| F04 | P1 | 降采样后的时间间隔被当作真实断线，长轨迹消失 | 已复现 |
| F05 | P1 | 港澳排除矩形覆盖深圳部分区域，跳过必要坐标转换 | 已复现 |
| F06 | P2 | 活动录制时长未持久化，恢复时把暂停时间算回来 | 代码确认 |
| F07 | P2 | 合法的零移动时长被替换成总经过时长 | 代码确认 |
| F08 | P2 | 草稿恢复或保存失败后继续录制，没有恢复定时落盘 | 代码确认 |
| F09 | P2 | 服务启动失败后显示暂停，但计时协程仍运行 | 代码确认 |
| F10 | P2 | 无回调和极差精度不能正确进入信号丢失状态 | 已复现及代码确认 |
| F11 | P2 | 高德恢复录制时无法排除暂停期间生成的旧点 | 已复现 |
| F12 | P2 | 已取消的详情读取仍可覆盖新的页面状态 | 代码确认 |
| F13 | P2 | 历史和详情读取异常缺少捕获，可能导致进程崩溃 | 代码确认 |
| F14 | P2 | 取消 AI 协程没有取消 HTTP 请求和重试 | 代码确认 |
| F15 | P2 | AI 请求失败后当前页面没有重试入口 | 代码确认 |
| F16 | P2 | 迁移测试缺少 AndroidJUnitRunner 配置，CI 也不执行设备测试 | 代码及官方文档确认 |
| F17 | P2 | Debug 直连 AI 的备用模型已经停用 | 代码及官方公告确认 |

## 优先修复的问题

### F01 增量写库失败后批次被遗忘

**P1，代码确认。** 位置：[RecordingSessionManager.kt 第 481 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingSessionManager.kt#L481)、[第 493 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingSessionManager.kt#L493)。

flushPendingPoints 在数据库确认前就清空 pendingFlushPoints，并推进 persistedPointCount。写入连续三次失败后，Deferred 返回失败，但 append 和 beginDraftRide 的调用方均不读取结果，也不更新 persistenceError。后续队列只等待任务结束，不检查前一任务是否成功。

磁盘不足或写入持续失败时，界面仍显示正常录制；失败批次不再等待重试。完整轨迹暂时仍在内存，正常停止时的全量 finalize 可以补回，因此不是“任何写入失败都会永久丢点”。但在最终成功保存前进程退出，会永久丢失这些未落盘点；草稿创建失败还会使后续点写入因外键失败而继续失败。

**建议**：区分已确认写入和待确认批次，成功后推进落盘游标；失败批次保留并支持重试，将写入失败反馈给会话状态。草稿创建成功后再允许后续 append，明确积压上限与用户恢复动作。

**验收**：注入连续三次 append 失败以及 beginDraftRide 失败；验证界面提示、批次保留、恢复后幂等补写、强制终止进程后草稿完整性。

### F02 信号暂停后无法顺利重锚

**P1，已复现。** 位置：[RecordingLocationProcessor.kt 第 124 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingLocationProcessor.kt#L124)、[第 140 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingLocationProcessor.kt#L140)、[第 235 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingLocationProcessor.kt#L235)。

进入 trackPausedForSignal 后，需要三个好点才满足恢复条件。第三个好点若相对旧锚点仍是野点，会让 consecutiveGoodGpsCount 归零；下一帧因尚未满足恢复条件，又把 consecutiveTrackOutlierCount 归零。这样“三个稳定野点后强制重锚”的条件无法满足。

反例先建立轨迹，再输入两个精度 50 米的点进入信号暂停，然后输入位于新区域、彼此间隔 3 米的 30 个稳定好点。结果新增入库点为 0，仍处于信号暂停，segmentId 未变化。持续时间取决于旧锚点与新位置的距离及拒绝阈值；不是所有信号恢复都会永久卡死。

**建议**：把信号质量恢复计数和新位置一致性计数独立维护。连续稳定的新锚点成立后，新开 segment 并恢复采集，不跨越断线累加距离。

**验收**：覆盖“劣化后恢复到原区域”“劣化后恢复到新区域”“候选新锚点再次跳动”三种序列，验证可恢复且不会生成跨区域连线。

### F03 持续低速骑行的距离被过滤为零

**P1，已复现。** 位置：[TrackDataFilter.kt 第 297 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/speed/TrackDataFilter.kt#L297)、[SpeedEstimator.kt 第 200 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/speed/SpeedEstimator.kt#L200)。

距离统计逐相邻点判断位移是否达到 max(2 米, 0.3 × 精度)，未达到便丢弃该段，但下一段仍从紧邻的新点开始，微小真实位移不会积累到阈值。以 1 米/秒、1.2 秒一次、精度 3 米直行 120 米，统计距离和移动时长均为 0。精度 15 米时门槛升到 4.5 米，问题可影响更高速度。

实时速度另有多普勒支路，因此可能出现“仪表有速度，里程不增长”，保存后的统计仍错误。

**建议**：引入独立距离锚点或时间窗口，累积到足够位移后再确认一段，结合精度与可信速度抑制静止漂移。不要通过直接降低阈值来换取明显的静止漂移。

**验收**：用已知长度的 1、2、4、8 米/秒轨迹，以及精度 3、10、15 米的组合验证距离误差；同时覆盖静止抖动和折返骑行。

### F04 长轨迹降采样导致路线断裂或消失

**P1，已复现。** 位置：[RecordingLocationProcessor.kt 第 457 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingLocationProcessor.kt#L457)、[TrackDataFilter.kt 第 179 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/speed/TrackDataFilter.kt#L179)、[第 316 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/speed/TrackDataFilter.kt#L316)、[MapPane.kt 第 256 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/MapPane.kt#L256)。

实时地图和草稿恢复先压缩点列，地图随后对压缩后的邻点调用 routeSegments。压缩使邻点时间差增加，超过 10 秒就被解释为信号断线；单点段随后被 drawableSegments 过滤掉。

连续 20,001 个点，每 1.2 秒一个，恢复时降采样到 1,251 点后，可绘制线段为 0。实时连续输入 6,001 点后，最早可绘制部分从录制第 3,600 秒才开始，较早路线丢失。这是显示错误，原始存储轨迹仍可保留。当前详情页直接使用完整过滤轨迹，没有这条降采样路径，见优化 O01。

**建议**：先对完整轨迹识别真实 segment，再在每段内部简化；把分段结果传给地图，避免把采样间隔当成真实中断。保留每段首尾和重要转弯点，实时与恢复共享实现。

**验收**：覆盖 2,001、6,001、20,001、50,000 点，断言连续输入仍有连续路线，真实暂停边界仍不连线；对比恢复前后的几何结果。

### F05 坐标区域判定误排除了深圳部分地区

**P1，已复现。** 位置：[CoordinateTransform.kt 第 47 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/CoordinateTransform.kt#L47)、[LocationTracker.kt 第 418 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/LocationTracker.kt#L418)。

“香港/澳门”排除区域使用纬度 21.75–22.65、经度 113.75–114.65 的整块矩形，覆盖深圳部分大陆区域。测试坐标 22.54, 114.05 被判定为非大陆，WGS-84 转 GCJ-02 直接原样返回。深圳地理范围可参照[深圳市政府地理介绍](https://www.sz.gov.cn/cn/zjsz/gl/content/post_12318788.html)。

这会影响平台 GPS 在高德底图上的位置，也会让区域内高德 GCJ-02 点未经归一化就以 WGS-84 身份入库。跨越错误边界或切换定位来源时，存在偏移和跳点风险。大矩形外框还不能准确表达周边国家边界。

**建议**：采用经过验证的适用区域判定或 SDK 支持的转换方式；建立大陆、深圳、港澳、台湾、境外及边界两侧的固定坐标测试。历史数据是否已污染需另行评估，不能盲目对全部旧点再次转换。

**验收**：验证深圳样本应执行转换、境外样本保持原坐标，并用高德和平台 GPS 的同地点观测检查归一化后是否一致。

## 可靠性与交互问题

### F06 恢复录制时无法恢复真实活动时长

**P2，代码确认。** 位置：[RecordingSessionManager.kt 第 377 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingSessionManager.kt#L377)、[RideEntity.kt 第 9 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/db/RideEntity.kt#L9)。

录制期间 accumulatedElapsed 使用单调时间并排除手动暂停，但此值没有持久化。恢复草稿时直接用末点 timestamp 减首点 timestamp，暂停间隔会被重新计入，开始至首个 GPS 点、末点至暂停之间的活动时间又会丢失；壁钟回拨也会影响结果。

**建议**：单独持久化活动录制时长和会话检查点，明确总经过时长、活动时长、移动时长三种口径，恢复时不能从两个 GPS 壁钟时间反推活动时长。

**验收**：录制 2 分钟、暂停 10 分钟、继续 2 分钟后结束进程并恢复；活动时长应约 4 分钟，而非 14 分钟，并覆盖校时和设备重启。

### F07 零移动时长被当成缺失值

**P2，代码确认。** 位置：[RideRepository.kt 第 132 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/RideRepository.kt#L132)、[第 151 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/RideRepository.kt#L151)、[DetailScreen.kt 第 178 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/ui/detail/DetailScreen.kt#L178)。

代码只接受大于零的 movingDurationSec，否则回退到 endTime − startTime。完全静止、无有效移动段等情况下，零是合法统计结果，却会显示为整段“Moving Time”。旧库默认零与新记录真实零没有区分。

**建议**：通过统计版本或可空字段区分未知和已计算的零；统计、列表和详情统一口径。AI prompt 也应明确传递哪一种时长。

**验收**：静止录制后保存并重新打开，移动时长仍为零；旧版未计算记录应显式重算或显示未知。

### F08 恢复后继续录制缺少定时落盘

**P2，代码确认。** 位置：[RecordingSessionManager.kt 第 139 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingSessionManager.kt#L139)、[第 198 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingSessionManager.kt#L198)、[第 471 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingSessionManager.kt#L471)。

startPeriodicFlush 仅在全新开始录制时启动。进程恢复草稿后点击继续只启动定位和计时器；停止保存失败会取消 flushJob，之后继续录制也不会重启。此时不足 10 个待写点只能等下一批、暂停或停止，原有 30 秒落盘上限不再成立。

**建议**：把进入活动录制态的定位、计时、周期落盘作为同一组生命周期操作，恢复和重试复用。

**验收**：恢复草稿后仅产生 3 个有效点，等待超过 30 秒，验证点已入库；覆盖保存失败后继续录制同一路径。

### F09 服务启动失败后计时器没有停止

**P2，代码确认。** 位置：[RecordingSessionManager.kt 第 450 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingSessionManager.kt#L450)、[第 521 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingSessionManager.kt#L521)。

startForegroundService 捕获失败后停止定位并设置 isPaused，却不取消 elapsedTicker，也不冻结累计时长。ticker 的循环没有暂停条件，还会每五次 tick 调用 notifyService，造成“显示暂停但时间增长、周期性再次尝试启动服务”。定位权限检查失败的恢复路径也会继续执行 startElapsedTicker。

**建议**：统一 pauseWithError 状态转换，原子地冻结计时、停止定位及关联任务；只在用户恢复或明确恢复策略触发时重启，并在成功后清理旧错误。

**验收**：注入服务启动异常和恢复时权限失效，验证暂停后 elapsedMs 不增长、不会无界重复发起服务启动。

### F10 完全失去定位时仍显示正常录制状态

**P2，已复现及代码确认。** 位置：[RecordingLocationProcessor.kt 第 64 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingLocationProcessor.kt#L64)、[RecordingSessionManager.kt 第 315 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingSessionManager.kt#L315)、[RecordingScreen.kt 第 311 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/ui/recording/RecordingScreen.kt#L311)。

精度大于 200 米的点在信号劣化计数之前直接返回。正常轨迹后连续输入 10 个精度 300 米的点，signalLost 仍为 false，bad_count 为 0。定位完全停止回调时，心跳只把速度归零，不设置 signalLost；若从未收到定位，lastLocationMonotonicMs 为零，相关逻辑直接退出。

**建议**：区分“等待首次定位”“定位正常”“精度劣化”“定位超时”，使用最近可用位置时间维护状态，并让过差精度与无回调进入同一健康检查。

**验收**：室内开始录制、骑行中关闭定位、隧道无回调、连续低精度四种场景，界面和通知均准确提示状态。

### F11 高德恢复录制时会接收暂停期间的旧点

**P2，已复现，实际 SDK 触发频率需真机验证。** 位置：[LocationTracker.kt 第 450 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/LocationTracker.kt#L450)、[RecordingLocationProcessor.kt 第 294 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingLocationProcessor.kt#L294)。

高德点固定设置 fixMonotonicMs 为零，只用当前回调时间作为 monotonicMs。缺少 fix 时间的分支仅比较最初的 recordingStartAt，没有比较本次恢复的开始壁钟时间；“回调刚到、位置产生于暂停期间、时间晚于整场开始”的点可以通过。独立输入验证产生 1 个 acceptedPoint。

**建议**：优先使用 SDK 确实可靠的 fix 时间；缺失时保留每个活动段的壁钟锚点及位置年龄判断，并明确校时策略。回调年龄和位置年龄不能共用一个字段。

**验收**：恢复前后各注入一条高德点，旧点拒绝，新点正常进入新 segment；同时测试系统时间调整。

### F12 已取消的详情加载仍可改变当前页面

**P2，代码确认。** 位置：[TrackViewModel.kt 第 130 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/TrackViewModel.kt#L130)、[第 328 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/TrackViewModel.kt#L328)。

openRide 在 IO 协程内执行同步 Room 读取和轨迹汇总，随后直接更新 UI。取消 Job 不会中断这些同步函数，更新前也没有检查活动请求；setView 切 Tab 不取消详情加载。快速打开 A、再打开 B，或在长轨迹加载时切回骑行页，较晚完成的旧任务仍可能把界面切回错误详情。

**建议**：使用可取消的 suspend 查询，在读取后检查协程取消与 requestId，统一在主线程提交导航状态；离开详情意图时取消加载。增加明确的加载状态。

**验收**：用可控延迟使 A 的结果晚于 B 返回，最终必须显示 B；加载中切回骑行后，旧结果不能再改页面。

### F13 数据读取异常可能升级为进程崩溃

**P2，代码确认。** 位置：[TrackViewModel.kt 第 200 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/TrackViewModel.kt#L200)、[第 306 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/TrackViewModel.kt#L306)、[第 332 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/TrackViewModel.kt#L332)。

loadHistory、openRide、applyRideStopped 中的数据库读取没有 try/catch 或错误状态。repairHistoricalRidesIfNeeded 只捕获修复阶段异常，并不能保护紧随其后的 listRides。SQLite 读取、数据库打开或迁移失败会成为 launch 中未处理的异常。

**建议**：为列表、详情和停止后的刷新分别建模 loading/error 状态，捕获具体存储异常并提供重试，保留 CancellationException 的取消语义；不要自动清库解决失败。

**验收**：模拟列表、详情、保存后刷新各一次失败，验证进程仍可使用、已保存记录不被误判为丢失，并能恢复读取。

### F14 AI 取消操作没有取消网络请求

**P2，代码确认。** 位置：[TrackViewModel.kt 第 442 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/TrackViewModel.kt#L442)、[GeminiClient.kt 第 99 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/GeminiClient.kt#L99)、[第 201 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/GeminiClient.kt#L201)。

cancelAnalysis 只取消协程，但请求使用同步 Call.execute，未保存 Call 或调用 cancel，重试等待使用 Thread.sleep。离开详情后旧请求仍可继续执行并重试；现有 requestId 检查能避免多数旧结果覆盖 UI，却无法阻止带宽和服务端调用消耗。每次调用 60 秒的 timeout 也不是整个分析操作的总超时。

**建议**：用支持协程取消的异步封装，将取消关联到 Call.cancel；重试使用 delay，尊重取消并限制整个分析的总时限。

**验收**：使用延迟响应的本地测试服务器，离开详情后确认请求被取消且不再重试；快速进入另一条记录时只有新请求有效。

### F15 AI 失败后当前页没有重试按钮

**P2，代码确认。** 位置：[DetailScreen.kt 第 352 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/ui/detail/DetailScreen.kt#L352)。

分析按钮只在 errorMessage 为空时出现；错误分支仅渲染文字。网络临时失败后即便恢复连接，用户也必须退出再打开记录，才能重新分析。

**建议**：错误态保留“重试”操作，加载时禁用重复提交；可按网络、配额、配置缺失显示不同恢复提示。

**验收**：首次失败、第二次成功的 UI 测试不应需要离开当前详情页。

### F16 数据库迁移测试没有完整执行链路

**P2，代码及官方文档确认。** 位置：[app/build.gradle.kts 第 35 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/build.gradle.kts#L35)、[AppDatabaseMigrationTest.kt 第 12 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/androidTest/kotlin/com/velotrack/velotrack/db/AppDatabaseMigrationTest.kt#L12)、[android.yml 第 20 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/.github/workflows/android.yml#L20)。

迁移测试使用 AndroidJUnit4 和 InstrumentationRegistry，但 defaultConfig 没有指定 androidx.test.runner.AndroidJUnitRunner。CI 仅 assembleDebugAndroidTest，生成测试 APK 并不代表执行了迁移测试。现有测试还缺少 1→2 JSON 拆表迁移和 1→5 完整升级路径。

**建议**：配置 runner，增加 emulator 或 managed device 的 connectedDebugAndroidTest 验证；覆盖旧版完整升级、数据保留、外键级联、损坏旧 JSON 的可恢复策略。配置依据见 [AndroidJUnitRunner 官方文档](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner)。

**验收**：CI 输出真实执行的迁移测试数量与结果；人为破坏迁移 SQL 时流水线必须失败。

### F17 Debug 直连 AI 的备用模型失效

**P2，代码及官方公告确认。** 位置：[GeminiClient.kt 第 36 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/GeminiClient.kt#L36)。

首选 gemini-3-flash-preview 的备用列表为 gemini-2.0-flash、gemini-1.5-flash、gemini-1.5-flash-8b。官方发布记录显示 Gemini 1.5 相关模型于 2025-09-29 停用，Gemini 2.0 Flash 于 2026-06-01 停用，因此这些名称已不能提供有效故障回退。依据见 [Gemini API 发布记录](https://ai.google.dev/gemini-api/docs/changelog)。

该列表只影响未配置代理时的直连路径；配置 AI_PROXY_URL 的 Release 请求不会遍历这些模型，不能据此断言 Release AI 已经不可用。

**建议**：把模型选择集中到服务端或开发配置中，移除已停用回退，建立可控的模型可用性检查。解析回复时合并有效 text parts，而非只读取 parts[0]。

**验收**：模拟首选模型 404，备用名称必须是已配置可用的模型；代理路径不受客户端备用列表影响。

## 需要设备或外部信息进一步确认的风险

### R01 Google 地图图标早于 SDK 初始化

**P1，静态路径与 API 前置条件明确，冷启动崩溃待真机确认。** [MapPane.kt 第 423 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/MapPane.kt#L423)在调用 GoogleMap Composable 之前无条件执行 BitmapDescriptorFactory.fromBitmap。仓库没有 Google MapsInitializer 初始化调用，VeloApp 中初始化的是高德。Google 官方要求使用工厂前取得可用 GoogleMap 或显式初始化，见 [BitmapDescriptorFactory 文档](https://developers.google.com/android/reference/com/google/android/gms/maps/model/BitmapDescriptorFactory)。

建议将工厂调用延后到地图就绪，或在用户同意后显式初始化并处理失败。用配置有效 Google Key 的全新进程测试首次启动、再次启动及没有 GMS 的设备，不仅测试从已打开地图返回。

### R02 Google 定位订阅失败没有恢复流程

**P2，代码缺口明确，设备影响待验证。** [LocationTracker.kt 第 223 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/LocationTracker.kt#L223)没有处理 requestLocationUpdates 返回 Task 的异步失败，也没有 Google 分支的系统定位 fallback。MapProviderSelector 只基于 Locale 和编译配置选择，实现中没有运行时设置或 GMS 可用性判断。

英文地区设置的国内设备、无 GMS 或 GMS 异常设备可能进入无法定位的路径。建议定位引擎选择与底图选择解耦，暴露订阅失败状态并提供可测试的系统 GPS fallback；验证无 GMS、定位开关关闭及订阅失败。

### R03 前台服务内部异常与资源生命周期

**P2，需 Android 多版本回归。** [RecordingForegroundService.kt 第 52 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/recording/RecordingForegroundService.kt#L52)调用 startForeground 时没有异常恢复；Manager 的 try/catch 只覆盖发送启动请求，无法捕获随后 Service 中的异常。onDestroy 也只清除标记和通知，没有与 Manager 协调暂停采集。

建议明确 Activity、Manager、Service 在权限变化、通知操作、系统重建和服务销毁时的状态契约。测试 Android 14+ 的位置权限撤回、通知恢复、START_STICKY 重建、服务启动被拒绝和长时间锁屏，核对定位、通知与计时保持一致。此处未断言这些系统路径全部会失败。

### R04 发布版本与配置校验不足

**P1 发布前检查项，是否已经阻塞发布取决于外部状态。** [app/build.gradle.kts 第 39 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/build.gradle.kts#L39)的 versionName 已是 1.0.1，但 versionCode 仍为 1；若商店已有 versionCode 1，这个版本号不能作为新版本再次上传，依据见 [Android 版本管理](https://developer.android.com/studio/publish/versioning)。本次未查看商店发布记录。

Release 签名、地图 Key、AI_PROXY_URL 缺失不会阻止配置完成；CI 仅构建 Debug，无法保证 Release 的签名、R8、地图和 AI 配置可用。建议增加显式发布校验任务、Release 构建与安装冒烟测试，确认依赖的原生库在目标设备上的兼容性。不同渠道允许禁用的功能应有明确策略。

### R05 AI 代理的鉴权与幂等策略尚无证据

**P2，服务端不在本仓库，不能据客户端判断是否存在漏洞。** [GeminiClient.kt 第 90 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/GeminiClient.kt#L90)仅发送 requestId 和 prompt，接口没有体现用户鉴权或限额信息。客户端会重试网络异常，响应丢失时可能产生重复服务端工作。

建议在代理契约中明确鉴权或设备证明、限流、相同 requestId 的幂等行为、请求大小和响应大小上限，并针对现有部署验证。不要把固定服务端密钥重新放回 APK 作为解决办法。

### R06 方向箭头和界面适配需要统一真机验收

**P2，尚未测量真实显示效果。** [DeviceHeading.kt 第 88 行](https://github.com/hyjfine/VeloTrack-android/blob/ea4f2fc76928e4ce3677d75e61656111a76e6c55/app/src/main/kotlin/com/velotrack/velotrack/DeviceHeading.kt#L88)使用 180 − azimuth，而两个地图 SDK 又各自接受旋转角，需验证坐标约定和图标朝向是否一致。UI 同时存在固定尺寸卡片、大字号速度及较小提示文案。

建议分别在高德和 Google 地图验证手机朝北、东、南、西、横竖屏与无方向传感器回退，并检查小屏、字体 200%、TalkBack、手势导航、三键导航和后台返回。没有截图或真机证据前，不把视觉疑点直接判为已复现缺陷。

## 可改进和优化的点

| 编号 | 优先级 | 依据与建议 | 验证收益的方式 |
| --- | --- | --- | --- |
| O01 | P2 | **长轨迹详情在主线程重复计算。** DetailScreen 第 79 行执行完整过滤，第 222 行再次计算 chartSpeedMps；Repository 读取时也已 summarize。地图未限点，Canvas 每次绘制遍历完整速度列表。将摘要、分段、图表抽样预计算到后台，缓存到详情展示模型，并在修复 F04 后按段简化地图。 | 用 5 万/10 万点记录测量打开详情耗时、主线程阻塞和掉帧；对比简化前后转弯和峰值保真度 |
| O02 | P2 | **隐藏地图仍计算路线几何。** GooglePane 和 AmapPane 在判断 isActive 前就计算 routePointsTrackKey/routeGeometry；方向更新也会重复扫描 segmentId 计算 key。把不可见页计算暂停，使用稳定的轨迹版本和分段数据缓存，缓存高德坐标转换。 | 分别测量录制页可见、历史页覆盖、后台三种状态的 CPU、分配量和地图更新频率 |
| O03 | P2 | **结束骑行全量重写和每点偏好写入增加 I/O。** finalizeRide 删除再插入所有点；LastLocationStore.write 每个有效点 apply。确认增量写可靠后，最终事务尽量只补缺口并更新摘要；位置缓存按时间或距离节流。 | 长轨迹停止保存延迟、事务耗时、磁盘写入量，同时保留失败可重试和数据完整性测试 |
| O04 | P2 | **状态管理依赖不便测试。** Manager 直接构造定位器、访问系统时间及 Service，ViewModel 依赖 VeloApp.instance。注入 Clock、LocationSource、RideStore 和服务控制接口，把状态转换提取为可测试逻辑；用结构化错误代替 UI contains 中文字符串判断。 | 不依赖 Android 设备即可覆盖写失败、权限失败、恢复、保存重试和并发导航 |
| O05 | P3 | **历史列表只有 UI 懒加载，数据仍一次性读取。** RideDao.getAllBlocking 取全表，列表模型复用 points 为空的 Ride。引入独立 RideSummary、Flow 和按需分页；在实际规模需要时增加匹配查询的索引。 | 以 1 千/1 万条记录测量首次加载和刷新，确认不会把摘要对象误作完整轨迹 |
| O06 | P2 | **历史统计缺少算法版本和质量元数据。** 读详情总是按当前算法重算，而列表使用已存摘要，未来算法升级可能出现同一记录两个数值。保存统计版本和必要质量信息，统一迁移与重算策略。 | 升级算法前后列表、详情、导出、AI 输入一致，旧算法结果可解释 |
| O07 | P2 | **进程级回调和缓存生命周期不完整。** TrackViewModel 第 102 行把实例方法注册到全局 DebugLogRecorder.onStateChanged，未见 onCleared 清理；analysisCache 无界且跨 IO 线程读写。退出 Activity 后可能继续持有旧 ViewModel 及轨迹。明确注销、主线程访问或同步策略，以及缓存大小与删除失效策略。 | 反复进入退出、录制大量点、分析及删除历史，检查堆快照和旧 ViewModel 是否释放 |
| O08 | P3 | **文档仍有相互冲突的指导。** README 第 18 行让用户把地图 Key 写入受 Git 跟踪的 gradle.properties；docs/properties.md 则正确要求 local.properties。decisions.md 仍将不支持后台、客户端保管 AI Key 等旧方案标为 Accepted，部分 UI 文档仍以 Flutter/Carto 为当前描述。统一原生现状和密钥配置示例，将旧 ADR 标为已被替代。 | 新开发者仅按 README 即可配置运行且 git diff 不出现密钥；设计入口能明确区分现行与历史方案 |
| O09 | P3 | **构建配置与当前版本不完全匹配。** Wrapper 为 8.11.1，却新增仅在 Gradle 9.4 引入的 org.gradle.tooling.parallel；该项不能证明当前同步已经提速。整理无效或未来配置，补 Wrapper 分发校验和及依赖来源/校验策略，评估 -bin 分发包。 | 标准 Wrapper 可复现构建、依赖来源明确；用同步耗时数据判断优化效果 |
| O10 | P2 | **用户数据恢复和隐私管理入口不足。** 已关闭云备份及设备迁移，源码未见骑行导出/导入；PrivacyConsentScreen 只有概要和同意/退出，未见完整说明、查看或撤回入口。优先设计本地导出备份和可查阅的数据说明，并明确撤回后的 SDK 与录制行为。 | 用户能主动备份并恢复轨迹；拒绝、同意、查看说明、撤回路径符合已定义的产品行为 |
| O11 | P3 | **信息表达与无障碍可提升。** 中英文文案散落源码；导航缺少明确 selected 语义；Performance 图按样本序号等距连接，没有时间轴、暂停断段和可访问的统计替代。移动到字符串资源，补语义及动态字号布局，图表改用时间与 segment 信息。 | 中文和英文环境、TalkBack、字体放大、长暂停及不等间隔样本都能正确理解和操作 |

O09 的版本依据见 [Gradle 官方升级文档](https://docs.gradle.org/current/userguide/upgrading_version_9.html#deprecate_implicit_parallel_model_building)。它说明该属性从 9.4.0 开始引入；不建议仅为使用此属性直接跨大版本升级整个工具链。

## 建议实施顺序

1. **先保证轨迹可信与可恢复**：修复 F01–F05，补对应反例测试；同步验证 R01 的 Google 冷启动。
2. **统一会话生命周期和时间模型**：处理 F06–F11、R03，把开始、暂停、恢复、保存失败和异常退出纳入同一状态契约。
3. **补齐异步与错误交互**：处理 F12–F15、R02，避免旧任务覆盖页面、读取错误崩溃和无法取消/重试的 AI 请求。
4. **让验证和发布成为完整链路**：处理 F16–F17、R04–R05；执行迁移测试、Release 冒烟以及地图和 AI 外部配置检查。
5. **再基于测量优化性能与体验**：优先 O01–O04、O07，随后按数据规模推进其余项。每次算法调整必须重新验证距离、断段与静止漂移，不能只看速度数字是否平滑。

## 本次独立验证记录

验证直接编译本仓库的 TrackDataFilter、SpeedEstimator、RecordingLocationProcessor、PersistentTrackPoints、LocationDeliveryGate、CoordinateTransform 及相关模型。平台桩只替代日志、时钟和注解，不替代轨迹算法。现有测试通过不能推出完整应用、Room 或 SDK 行为已经通过验证。

| 场景 | 输入要点 | 实际输出 |
| --- | --- | --- |
| 现有单测 | LocationDeliveryGate、PersistentTrackPoints、RecordingLocationProcessor、SpeedEstimator、TrackDataFilter 五个测试类 | OK，25 tests |
| 信号恢复 | 初始 4 点；2 个精度 50 米点；新区域 30 个间隔 3 米、1.2 秒的好点 | accepted=0，paused=true，segment=0，outlier_count=1 |
| 低速直行 | 101 点，1.2 米/1.2 秒，精度 3 米，总长 120 米 | computed_m=0.0，moving_s=0.0 |
| 恢复地图 | 20,001 点，3 米/1.2 秒，原始路线连续；压缩到不超过 2,000 点 | simplified=1251，raw_segments=1，drawable_segments=0 |
| 实时长轨迹 | 连续输入 6,001 个 3 米/1.2 秒样本 | map=1001，singleton_segments=125，first_drawable_s=3600 |
| 极差精度 | 初始好点后连续 10 个 accuracy=300 的点 | signalLost=false，bad_count=0 |
| 高德恢复旧点 | 活动段开始晚于样本生成时间，fixMonotonicMs=0，回调时间为当前值，timestamp 晚于整场录制开始 | accepted=1，reason=null |
| 区域边界 | 22.54, 114.05 | mainland=false，转换结果仍为 22.54, 114.05 |

### 可以加入正式测试的最小反例

以下是使用现有模型的 Kotlin 示例，可放入后续算法回归测试。两项 check 表达期望行为，在本次审查基线均会失败。

    fun sample(i: Int, meters: Double) = GpsPoint(
        lat = 31.0 + meters / 111194.9266,
        lng = 121.0,
        timestamp = 1_000_000L + i * 1_200L,
        monotonicMs = 10_000L + i * 1_200L,
        speedMps = 1.0,
        altitude = null,
        accuracy = 3.0,
    )

    val slow = (0..100).map { sample(it, it * 1.2) }
    check(TrackDataFilter.summarize(slow).totalDistanceM > 110.0)

    val longRoute = (0..20_000).map { sample(it, it * 3.0) }
    val mapPoints = TrackDataFilter.downsampleForMap(longRoute)
    check(TrackDataFilter.routeSegments(mapPoints).any { it.size >= 2 })

### 后续完整验收矩阵

| 范围 | 必测场景 | 通过条件 |
| --- | --- | --- |
| 持久化 | 创建/追加/结束各阶段失败、进程终止、恢复后少于一批点、重复停止 | 不静默丢点，重试幂等，恢复状态可解释 |
| 定位与统计 | 缓存点、乱序、批量回调、壁钟回拨、无信号、低速、静止、折返、暂停恢复 | 距离与时间口径一致，断段正确，无野点重锚卡死 |
| 地图 | 高德与 Google 冷启动、长轨迹、后台返回、深圳及区域边界 | 位置转换一致，轨迹不因降采样消失，SDK 生命周期稳定 |
| 数据库 | 1→2、2→3、3→4、4→5、1→5、删除级联和非法旧数据 | schema 与记录内容均验证通过，无静默清库 |
| UI 与 AI | 慢详情加载时切 Tab、快速切记录、网络失败重试、离开页面取消、字体放大 | 当前用户意图不会被旧任务覆盖，错误可恢复 |
| 发布 | 正式签名、递增 versionCode、地图包名和签名绑定、AI 代理、R8、目标设备原生库 | Release APK/AAB 与实际安装验证通过，外部功能配置明确 |

本次完整 Gradle 失败属于本机依赖准备问题，不能作为项目源码无法编译的证据。已有 build 目录中的旧产物也没有作为当前提交通过构建或 lint 的依据。
