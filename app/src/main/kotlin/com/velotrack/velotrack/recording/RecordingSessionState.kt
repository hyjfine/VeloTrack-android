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
    val lastLocationAtMs: Long? = null,
    val lastLocationAccuracyM: Double? = null,
    val lastLocationCountedInTrack: Boolean = false,
    val lastLocationDropReason: String? = null,
    val locationDebugMessage: String? = null,
    val lastRawSpeedMps: Double? = null,
    val lastSpeedSource: String? = null,
    val gnss: GnssSatelliteSnapshot? = null,
)
