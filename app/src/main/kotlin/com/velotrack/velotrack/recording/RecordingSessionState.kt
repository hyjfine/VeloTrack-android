package com.velotrack.velotrack.recording

import androidx.compose.runtime.Immutable
import com.velotrack.velotrack.GnssSatelliteSnapshot
import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.speed.TrackDataFilter

enum class RecordingIssue { STORAGE, SAVE, PERMISSION, SERVICE, RECOVERED }

/** 前台录制会话快照，由 [RecordingSessionManager] 发布。 */
@Immutable
data class RecordingSessionState(
    val isRestoring: Boolean = false,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    /** 已停止采集，正在等待最终事务落库。 */
    val isSaving: Boolean = false,
    val rideId: String? = null,
    val recordingStartAt: Long = 0L,
    val elapsedMs: Long = 0L,
    val livePoints: List<GpsPoint> = emptyList(),
    /** 已增量剔除尖峰的显示轨迹，避免 UI 每帧扫描完整骑行。 */
    val displayPoints: List<GpsPoint> = emptyList(),
    /** 有上限的地图绘制点；完整原始点仍保留在 [livePoints] 并写入 Room。 */
    val mapPoints: List<GpsPoint> = emptyList(),
    val displayDistanceM: Double = 0.0,
    val distanceState: TrackDataFilter.DistanceState = TrackDataFilter.DistanceState(),
    /** One-point rollback lets an isolated spike be removed without rescanning the whole ride. */
    val distanceBeforeLastPoint: TrackDataFilter.DistanceState = TrackDataFilter.DistanceState(),
    val spikePointIndices: Set<Int> = emptySet(),
    /** 当前写入段；暂停恢复或异常重锚时递增。 */
    val currentSegmentId: Int = 0,
    /** 录制刚开始时的候选锚点，连续稳定后才允许首点入库。 */
    val pendingAnchorPoint: GpsPoint? = null,
    /** 首锚确认期间的完整候选序列；确认成功后整体补入轨迹，避免丢失起步路段。 */
    val pendingAnchorPoints: List<GpsPoint> = emptyList(),
    val consecutiveAnchorCandidateCount: Int = 0,
    /** 相对当前轨迹异常、但可能属于新真实位置的候选重锚点。 */
    val pendingOutlierPoint: GpsPoint? = null,
    val currentSpeedMps: Double = 0.0,
    val mapCenterLat: Double = 31.2304,
    val mapCenterLng: Double = 121.4737,
    val currentAltitude: Double? = null,
    val signalLost: Boolean = false,
    /** 信号退化后暂停入库，待连续好点恢复。 */
    val trackPausedForSignal: Boolean = false,
    /** 信号恢复阶段已连续满足精度/来源的帧数。 */
    val consecutiveGoodGpsCount: Int = 0,
    /** 进入信号暂停前已连续劣化的帧数。 */
    val consecutiveBadGpsCount: Int = 0,
    /** 已连续因野点拒绝入库的帧数（好点且满足恢复条件时累计）。 */
    val consecutiveTrackOutlierCount: Int = 0,
    val lastLocationAtMs: Long? = null,
    val lastLocationAccuracyM: Double? = null,
    val lastLocationCountedInTrack: Boolean = false,
    val lastLocationDropReason: String? = null,
    val locationDebugMessage: String? = null,
    val lastRawSpeedMps: Double? = null,
    /** 位移导数中值速度（m/s），未 EMA。 */
    val lastDerivedSpeedMps: Double? = null,
    val lastSpeedSource: String? = null,
    /** derived / fused / doppler / hold-zero */
    val lastSpeedMethod: String? = null,
    /** 多普勒在融合中的实际权重（0~1），debug 用。 */
    val lastDopplerWeight: Double? = null,
    /** 上一帧 GNSS 多普勒速度 1σ 误差（m/s），debug 用。 */
    val lastSpeedAccuracyMps: Double? = null,
    /** 上一段 dt（ms），debug 用；<=0 表示无段。 */
    val lastSegmentDtMs: Long? = null,
    /** 本帧参与导数计算的段数，debug 用。 */
    val lastSegmentCount: Int? = null,
    val gnss: GnssSatelliteSnapshot? = null,
    /** 录制持久化错误；保留内存会话，允许用户再次长按重试保存。 */
    val persistenceError: String? = null,
    val issue: RecordingIssue? = null,
)
