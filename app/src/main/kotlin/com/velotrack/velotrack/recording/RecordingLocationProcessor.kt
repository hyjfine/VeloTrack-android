package com.velotrack.velotrack.recording

import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.GpsSource
import com.velotrack.velotrack.speed.SpeedEstimator
import com.velotrack.velotrack.speed.TrackDataFilter

/**
 * 将原始定位样本合并进录制会话。
 * 瞬时速度以 [SpeedEstimator] 位移导数为主，可信 GNSS 多普勒为辅。
 */
object RecordingLocationProcessor {
    private const val TRACK_POINT_MAX_ACCURACY_M = 20.0
    /** 迟滞恢复期间仅用 GNSS Doppler 估速的精度上限（与 SpeedEstimator 一致）。 */
    private const val SPEED_DOPPLER_ONLY_MAX_ACCURACY_M = 15.0
    private const val MAP_LOCATION_MAX_ACCURACY_M = 200.0
    /** 连续劣化帧数达到此值才进入信号暂停（迟滞进入）。 */
    private const val SIGNAL_DEGRADE_REQUIRED_COUNT = 2
    /** 信号暂停后需连续好帧数才能恢复入库（迟滞退出）。 */
    private const val SIGNAL_RECOVERY_REQUIRED_COUNT = 3

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

        val meetsAccuracy = point.accuracy <= TRACK_POINT_MAX_ACCURACY_M
        val meetsSource = point.isTrustworthyTrackSource()
        val frameGood = meetsAccuracy && meetsSource
        val frameBad = !frameGood

        val nextConsecutiveBadGpsCount = when {
            frameGood -> 0
            state.trackPausedForSignal -> state.consecutiveBadGpsCount
            else -> state.consecutiveBadGpsCount + 1
        }
        val enteringPause = !state.trackPausedForSignal &&
            nextConsecutiveBadGpsCount >= SIGNAL_DEGRADE_REQUIRED_COUNT
        val trackPausedForSignal = state.trackPausedForSignal || enteringPause

        val consecutiveGoodGpsCount = when {
            frameBad -> 0
            trackPausedForSignal -> state.consecutiveGoodGpsCount + 1
            else -> 0
        }
        val recoverySatisfied = !trackPausedForSignal ||
            consecutiveGoodGpsCount >= SIGNAL_RECOVERY_REQUIRED_COUNT

        val lastAccepted = state.livePoints.lastOrNull()
        val speedOutlierReason = if (frameGood) {
            TrackDataFilter.rejectReasonForCandidate(lastAccepted, point)
        } else {
            null
        }
        val outlierReason = if (frameGood && recoverySatisfied) speedOutlierReason else null

        val canUseForTrack = frameGood && recoverySatisfied && outlierReason == null

        // 仪表速度与轨迹入库解耦：好点且非野点即可估速；迟滞恢复期间避免未入库点污染位移导数。
        val speedEstimate = when {
            !frameGood || speedOutlierReason != null -> null
            canUseForTrack -> SpeedEstimator.estimate(
                trackPointsIncludingNew = state.livePoints + point,
                newPoint = point,
                previousDisplaySpeedMps = state.currentSpeedMps,
                rawDopplerMps = rawSpeed,
                segmentStartIndex = segmentStartIndex,
            )
            trackPausedForSignal && point.accuracy <= SPEED_DOPPLER_ONLY_MAX_ACCURACY_M -> SpeedEstimator.estimate(
                trackPointsIncludingNew = listOf(point),
                newPoint = point,
                previousDisplaySpeedMps = state.currentSpeedMps,
                rawDopplerMps = rawSpeed,
                segmentStartIndex = 0,
            )
            else -> SpeedEstimator.estimate(
                trackPointsIncludingNew = state.livePoints + point,
                newPoint = point,
                previousDisplaySpeedMps = state.currentSpeedMps,
                rawDopplerMps = rawSpeed,
                segmentStartIndex = segmentStartIndex,
            )
        }

        val nextSpeed = speedEstimate?.displaySpeedMps ?: state.currentSpeedMps
        val dropReason = when {
            !meetsAccuracy -> "map only: accuracy>${TRACK_POINT_MAX_ACCURACY_M.toInt()}m"
            !meetsSource -> "map only: source=${point.source.label}"
            enteringPause -> "signal pause: degraded"
            !state.trackPausedForSignal && nextConsecutiveBadGpsCount > 0 ->
                "signal unstable ${nextConsecutiveBadGpsCount}/$SIGNAL_DEGRADE_REQUIRED_COUNT"
            trackPausedForSignal && !recoverySatisfied ->
                "signal recovery ${consecutiveGoodGpsCount}/$SIGNAL_RECOVERY_REQUIRED_COUNT"
            outlierReason != null -> outlierReason
            else -> null
        }

        val acceptedPoint = if (canUseForTrack) {
            point.copy(speedMps = speedEstimate!!.instantSpeedMps)
        } else {
            null
        }
        val points = if (acceptedPoint != null) state.livePoints + acceptedPoint else state.livePoints

        val nextTrackPausedForSignal = when {
            acceptedPoint != null -> false
            state.trackPausedForSignal || enteringPause -> true
            else -> false
        }
        val nextConsecutiveGoodGpsCount = when {
            acceptedPoint != null -> 0
            !nextTrackPausedForSignal -> 0
            frameBad || outlierReason != null -> 0
            else -> consecutiveGoodGpsCount
        }
        val nextConsecutiveBadGpsCountFinal = when {
            acceptedPoint != null -> 0
            frameGood -> 0
            else -> nextConsecutiveBadGpsCount
        }

        return Result(
            state = state.copy(
                livePoints = points,
                mapCenterLat = if (frameGood) point.lat else state.mapCenterLat,
                mapCenterLng = if (frameGood) point.lng else state.mapCenterLng,
                currentSpeedMps = nextSpeed.coerceAtLeast(0.0),
                currentAltitude = if (frameGood) point.altitude else state.currentAltitude,
                signalLost = nextTrackPausedForSignal,
                trackPausedForSignal = nextTrackPausedForSignal,
                consecutiveGoodGpsCount = nextConsecutiveGoodGpsCount,
                consecutiveBadGpsCount = nextConsecutiveBadGpsCountFinal,
                lastLocationAtMs = point.timestamp,
                lastLocationAccuracyM = point.accuracy,
                lastLocationCountedInTrack = acceptedPoint != null,
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

    private fun GpsPoint.isTrustworthyTrackSource(): Boolean {
        if (!isGpsFix) return false
        return when (source) {
            GpsSource.AMAP_GPS,
            GpsSource.PLATFORM_GPS,
            GpsSource.GMS_FUSED,
            -> true
            else -> false
        }
    }
}
