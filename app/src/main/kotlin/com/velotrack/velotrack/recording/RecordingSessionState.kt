package com.velotrack.velotrack.recording

import com.velotrack.velotrack.GnssSatelliteSnapshot
import com.velotrack.velotrack.GpsPoint

/** 前台录制会话快照，由 [RecordingSessionManager] 发布。 */
data class RecordingSessionState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val rideId: String? = null,
    val recordingStartAt: Long = 0L,
    val elapsedMs: Long = 0L,
    val livePoints: List<GpsPoint> = emptyList(),
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
)
