package com.velotrack.velotrack.recording

import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.speed.SpeedEstimator

/**
 * 将原始定位样本合并进录制会话。
 * 瞬时速度以 [SpeedEstimator] 位移导数为主，可信 GNSS 多普勒为辅。
 */
object RecordingLocationProcessor {
    private const val TRACK_POINT_MAX_ACCURACY_M = 40.0
    private const val GOOD_SIGNAL_MAX_ACCURACY_M = 25.0
    private const val MAP_LOCATION_MAX_ACCURACY_M = 200.0

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
        segmentStartIndex: Int = 0,
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
                    lastDerivedSpeedMps = null,
                    lastSpeedSource = speedSourceLabel,
                    lastSpeedMethod = null,
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
                    lastDerivedSpeedMps = null,
                    lastSpeedSource = speedSourceLabel,
                    lastSpeedMethod = null,
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
                    lastDerivedSpeedMps = null,
                    lastSpeedSource = speedSourceLabel,
                    lastSpeedMethod = null,
                ),
            )
        }

        val canUseForTrack = point.accuracy <= TRACK_POINT_MAX_ACCURACY_M

        val speedEstimate = if (canUseForTrack) {
            SpeedEstimator.estimate(
                trackPointsIncludingNew = state.livePoints + point,
                newPoint = point,
                previousDisplaySpeedMps = state.currentSpeedMps,
                rawDopplerMps = rawSpeed,
                segmentStartIndex = segmentStartIndex,
            )
        } else {
            null
        }

        val nextSpeed = speedEstimate?.displaySpeedMps ?: state.currentSpeedMps
        val dropReason = when {
            !canUseForTrack -> "map only: accuracy>${TRACK_POINT_MAX_ACCURACY_M.toInt()}m"
            else -> null
        }

        // 入库存「瞬时速度」（未 EMA），让详情曲线与 RideStats.maxSpeed 更忠实；
        // 仪表显示用 displaySpeedMps（在 state.currentSpeedMps）。
        val acceptedPoint = if (canUseForTrack && speedEstimate != null) {
            point.copy(speedMps = speedEstimate.instantSpeedMps)
        } else {
            null
        }
        val points = if (acceptedPoint != null) state.livePoints + acceptedPoint else state.livePoints

        return Result(
            state = state.copy(
                livePoints = points,
                mapCenterLat = point.lat,
                mapCenterLng = point.lng,
                currentSpeedMps = nextSpeed.coerceAtLeast(0.0),
                currentAltitude = point.altitude,
                signalLost = point.accuracy > GOOD_SIGNAL_MAX_ACCURACY_M,
                lastLocationAtMs = point.timestamp,
                lastLocationAccuracyM = point.accuracy,
                lastLocationCountedInTrack = canUseForTrack,
                lastLocationDropReason = dropReason,
                lastRawSpeedMps = rawSpeed,
                lastDerivedSpeedMps = speedEstimate?.derivedSpeedMps,
                lastSpeedSource = speedSourceLabel,
                lastSpeedMethod = speedEstimate?.method,
                lastDopplerWeight = speedEstimate?.dopplerWeight,
                lastSpeedAccuracyMps = point.speedAccuracyMps,
                lastSegmentDtMs = speedEstimate?.lastSegmentDtMs,
                lastSegmentCount = speedEstimate?.segmentCount,
            ),
            acceptedPoint = acceptedPoint,
        )
    }
}
