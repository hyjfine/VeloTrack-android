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
    /** 连续野点拒绝达到此值后强制重锚，打破锚点死亡螺旋。 */
    private const val OUTLIER_REANCHOR_COUNT = 3

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

        val trackOutlierReason = if (frameGood) {
            TrackDataFilter.rejectReasonForCandidate(state.livePoints, point)
        } else {
            null
        }
        val nextConsecutiveTrackOutlierCount = when {
            !frameGood || !recoverySatisfied || trackOutlierReason == null -> 0
            else -> state.consecutiveTrackOutlierCount + 1
        }
        val forceReanchor = frameGood && recoverySatisfied &&
            trackOutlierReason != null &&
            nextConsecutiveTrackOutlierCount >= OUTLIER_REANCHOR_COUNT
        val outlierReason = if (frameGood && recoverySatisfied && trackOutlierReason != null && !forceReanchor) {
            trackOutlierReason
        } else {
            null
        }

        val canUseForTrack = frameGood && recoverySatisfied && outlierReason == null

        // 仪表速度与轨迹入库解耦：野点不入库但仍可估速；迟滞恢复期间避免未入库点污染位移导数。
        val speedEstimate = when {
            !frameGood -> null
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
            outlierReason != null -> SpeedEstimator.estimate(
                trackPointsIncludingNew = if (point.accuracy <= SPEED_DOPPLER_ONLY_MAX_ACCURACY_M) {
                    listOf(point)
                } else {
                    state.livePoints
                },
                newPoint = point,
                previousDisplaySpeedMps = state.currentSpeedMps,
                rawDopplerMps = rawSpeed,
                segmentStartIndex = if (point.accuracy <= SPEED_DOPPLER_ONLY_MAX_ACCURACY_M) 0 else segmentStartIndex,
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
            forceReanchor -> "outlier re-anchor"
            outlierReason != null -> outlierReason
            else -> null
        }

        val acceptedPoint = if (canUseForTrack) {
            point.copy(speedMps = speedEstimate!!.instantSpeedMps)
        } else {
            null
        }
        val displayUpdate = if (acceptedPoint != null) {
            updateDisplayTrack(state, acceptedPoint)
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
        val nextConsecutiveTrackOutlierCountFinal = when {
            acceptedPoint != null -> 0
            outlierReason != null -> nextConsecutiveTrackOutlierCount
            else -> 0
        }

        return Result(
            state = state.copy(
                livePoints = points,
                displayPoints = displayUpdate?.points ?: state.displayPoints,
                mapPoints = displayUpdate?.mapPoints ?: state.mapPoints,
                displayDistanceM = displayUpdate?.distanceM ?: state.displayDistanceM,
                spikePointIndices = displayUpdate?.spikeIndices ?: state.spikePointIndices,
                mapCenterLat = if (frameGood) point.lat else state.mapCenterLat,
                mapCenterLng = if (frameGood) point.lng else state.mapCenterLng,
                currentSpeedMps = nextSpeed.coerceAtLeast(0.0),
                currentAltitude = if (frameGood) point.altitude else state.currentAltitude,
                signalLost = nextTrackPausedForSignal,
                trackPausedForSignal = nextTrackPausedForSignal,
                consecutiveGoodGpsCount = nextConsecutiveGoodGpsCount,
                consecutiveBadGpsCount = nextConsecutiveBadGpsCountFinal,
                consecutiveTrackOutlierCount = nextConsecutiveTrackOutlierCountFinal,
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

    private data class DisplayUpdate(
        val points: List<GpsPoint>,
        val mapPoints: List<GpsPoint>,
        val distanceM: Double,
        val spikeIndices: Set<Int>,
    )

    private fun updateDisplayTrack(state: RecordingSessionState, acceptedPoint: GpsPoint): DisplayUpdate {
        val raw = state.livePoints
        if (raw.isEmpty()) {
            return DisplayUpdate(
                points = listOf(acceptedPoint),
                mapPoints = listOf(acceptedPoint),
                distanceM = 0.0,
                spikeIndices = emptySet(),
            )
        }

        val previousIndex = raw.lastIndex
        val previous = raw.last()
        val previousPrevious = raw.getOrNull(previousIndex - 1)
        val previousIsNewSpike = previousPrevious != null &&
            TrackDataFilter.isPositionSpike(previousPrevious, previous, acceptedPoint)
        val nextSpikes = if (previousIsNewSpike) {
            state.spikePointIndices + previousIndex
        } else {
            state.spikePointIndices
        }
        val nextDisplay = if (previousIsNewSpike) {
            state.displayPoints.dropLast(1) + acceptedPoint
        } else {
            state.displayPoints + acceptedPoint
        }
        val segmentDistance = TrackDataFilter.validSegmentDistanceMeters(previous, acceptedPoint) ?: 0.0
        val previousSegmentDistance = previousPrevious?.let {
            TrackDataFilter.validSegmentDistanceMeters(it, previous)
        } ?: 0.0
        val distance = when {
            previousIsNewSpike && previousIndex - 1 !in state.spikePointIndices ->
                state.displayDistanceM - previousSegmentDistance
            previousIndex in state.spikePointIndices -> state.displayDistanceM
            else -> state.displayDistanceM + segmentDistance
        }.coerceAtLeast(0.0)

        val nextMapBase = if (previousIsNewSpike) state.mapPoints.dropLast(1) else state.mapPoints
        val nextMap = reduceMapPointsIfNeeded(nextMapBase + acceptedPoint)
        return DisplayUpdate(nextDisplay, nextMap, distance, nextSpikes)
    }

    private fun reduceMapPointsIfNeeded(points: List<GpsPoint>): List<GpsPoint> {
        if (points.size <= MAX_LIVE_MAP_POINTS) return points
        val reduced = ArrayList<GpsPoint>(points.size / 2 + 2)
        points.forEachIndexed { index, point ->
            if (index == 0 || index == points.lastIndex || index % 2 == 0) reduced += point
        }
        return reduced
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

    private const val MAX_LIVE_MAP_POINTS = 2_000
}
