package com.velotrack.velotrack.recording

import com.velotrack.velotrack.GpsPoint
import kotlin.math.max

/**
 * 将原始定位样本合并进录制会话（与 [com.velotrack.velotrack.TrackViewModel.onLocation] 口径一致）。
 */
object RecordingLocationProcessor {
    private const val TRACK_POINT_MAX_ACCURACY_M = 40.0
    private const val GOOD_SIGNAL_MAX_ACCURACY_M = 25.0
    private const val MAP_LOCATION_MAX_ACCURACY_M = 200.0
    private const val STANDSTILL_THRESHOLD_MPS = 0.5
    private const val SPEED_EMA_ALPHA = 0.4

    data class Result(
        val state: RecordingSessionState,
        val acceptedPoint: GpsPoint? = null,
    )

    fun apply(
        state: RecordingSessionState,
        point: GpsPoint,
        recordingStartAt: Long,
        isRecording: Boolean,
        isPaused: Boolean,
    ): Result {
        val rawSpeed = point.speedMps
        val speedSourceLabel = point.source.label + if (point.isGpsFix) "*" else ""

        if (point.accuracy > MAP_LOCATION_MAX_ACCURACY_M) {
            return Result(
                state.copy(
                    lastLocationAtMs = point.timestamp,
                    lastLocationAccuracyM = point.accuracy,
                    lastLocationCountedInTrack = false,
                    lastLocationDropReason = "accuracy>${MAP_LOCATION_MAX_ACCURACY_M.toInt()}m",
                    lastRawSpeedMps = rawSpeed,
                    lastSpeedSource = speedSourceLabel,
                ),
            )
        }

        if (!isRecording || isPaused) {
            return Result(
                state.copy(
                    lastLocationAtMs = point.timestamp,
                    lastLocationAccuracyM = point.accuracy,
                    lastLocationCountedInTrack = false,
                    lastLocationDropReason = if (!isRecording) "not recording" else "paused",
                    mapCenterLat = point.lat,
                    mapCenterLng = point.lng,
                    currentAltitude = point.altitude,
                    lastRawSpeedMps = rawSpeed,
                    lastSpeedSource = speedSourceLabel,
                ),
            )
        }

        if (point.timestamp < recordingStartAt) {
            return Result(
                state.copy(
                    lastLocationAtMs = point.timestamp,
                    lastLocationAccuracyM = point.accuracy,
                    lastLocationCountedInTrack = false,
                    lastLocationDropReason = "before recording start",
                    mapCenterLat = point.lat,
                    mapCenterLng = point.lng,
                    currentAltitude = point.altitude,
                    lastRawSpeedMps = rawSpeed,
                    lastSpeedSource = speedSourceLabel,
                ),
            )
        }

        val canUseForTrack = point.accuracy <= TRACK_POINT_MAX_ACCURACY_M
        val points = if (canUseForTrack) state.livePoints + point else state.livePoints
        val nextSpeed = if (canUseForTrack && point.isGpsFix) {
            val gated = if (rawSpeed < STANDSTILL_THRESHOLD_MPS) 0.0 else rawSpeed
            if (state.currentSpeedMps <= 0.0) {
                gated
            } else {
                SPEED_EMA_ALPHA * gated + (1 - SPEED_EMA_ALPHA) * state.currentSpeedMps
            }
        } else {
            state.currentSpeedMps
        }
        val dropReason = when {
            !canUseForTrack -> "map only: accuracy>${TRACK_POINT_MAX_ACCURACY_M.toInt()}m"
            !point.isGpsFix -> "speed ignored: non-gps source"
            else -> null
        }

        return Result(
            state = state.copy(
                livePoints = points,
                mapCenterLat = point.lat,
                mapCenterLng = point.lng,
                currentSpeedMps = max(0.0, nextSpeed),
                currentAltitude = point.altitude,
                signalLost = point.accuracy > GOOD_SIGNAL_MAX_ACCURACY_M,
                lastLocationAtMs = point.timestamp,
                lastLocationAccuracyM = point.accuracy,
                lastLocationCountedInTrack = canUseForTrack,
                lastLocationDropReason = dropReason,
                lastRawSpeedMps = rawSpeed,
                lastSpeedSource = speedSourceLabel,
            ),
            acceptedPoint = if (canUseForTrack) point else null,
        )
    }
}
