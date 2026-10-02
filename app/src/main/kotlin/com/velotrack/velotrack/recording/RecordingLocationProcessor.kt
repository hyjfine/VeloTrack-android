package com.velotrack.velotrack.recording

import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.GpsSource
import com.velotrack.velotrack.speed.SpeedEstimator
import com.velotrack.velotrack.speed.TrackDataFilter
import com.velotrack.velotrack.tracking.TrackingPolicy

/**
 * 将原始定位样本合并进录制会话。
 * 瞬时速度以 [SpeedEstimator] 位移导数为主，可信 GNSS 多普勒为辅。
 */
object RecordingLocationProcessor {
    private const val TRACK_POINT_MAX_ACCURACY_M = 20.0
    private const val TRACK_POINT_MIN_ACCURACY_M = 0.1
    private const val MAP_LOCATION_MAX_ACCURACY_M = 200.0
    /** 连续劣化帧数达到此值才进入信号暂停（迟滞进入）。 */
    private const val SIGNAL_DEGRADE_REQUIRED_COUNT = 2
    /** 信号暂停后需连续好帧数才能恢复入库（迟滞退出）。 */
    private const val SIGNAL_RECOVERY_REQUIRED_COUNT = 3
    /** 连续野点拒绝达到此值后强制重锚，打破锚点死亡螺旋。 */
    private const val OUTLIER_REANCHOR_COUNT = 3
    /** 首点也需连续稳定，避免单个伪装成 GPS 的缓存点成为整段锚点。 */
    private const val INITIAL_ANCHOR_REQUIRED_COUNT = 3
    private const val CALLBACK_MAX_AGE_MS = 5_000L
    private const val FUTURE_TIMESTAMP_TOLERANCE_MS = 10_000L

    data class Result(
        val state: RecordingSessionState,
        val acceptedPoints: List<GpsPoint> = emptyList(),
    )

    fun apply(
        state: RecordingSessionState,
        point: GpsPoint,
        recordingStartAt: Long,
        recordingStartMonotonicMs: Long = 0L,
        activeSegmentStartedAt: Long = recordingStartAt,
        isRecording: Boolean,
        isPaused: Boolean,
        segmentStartIndex: Int = 0,
    ): Result {
        val rawSpeed = point.speedMps
        val speedSourceLabel = point.source.label + if (point.isGpsFix) "*" else ""

        val freshnessDropReason = freshnessDropReason(point, recordingStartAt, recordingStartMonotonicMs, activeSegmentStartedAt)
        if (freshnessDropReason != null) {
            return Result(
                state.copy(
                    lastLocationAtMs = point.timestamp,
                    lastLocationAccuracyM = point.accuracy,
                    lastLocationCountedInTrack = false,
                    lastLocationDropReason = freshnessDropReason,
                    lastRawSpeedMps = rawSpeed,
                    lastDerivedSpeedMps = null,
                    lastSpeedSource = speedSourceLabel,
                    lastSpeedMethod = null,
                    pendingAnchorPoint = if (state.livePoints.isEmpty()) null else state.pendingAnchorPoint,
                    pendingAnchorPoints = if (state.livePoints.isEmpty()) emptyList() else state.pendingAnchorPoints,
                    consecutiveAnchorCandidateCount = if (state.livePoints.isEmpty()) 0 else state.consecutiveAnchorCandidateCount,
                ),
            )
        }

        if (!isRecording || isPaused) {
            if (point.accuracy !in TRACK_POINT_MIN_ACCURACY_M..MAP_LOCATION_MAX_ACCURACY_M) return Result(state)
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

        val meetsAccuracy = point.accuracy in TRACK_POINT_MIN_ACCURACY_M..TRACK_POINT_MAX_ACCURACY_M &&
            point.lat in -90.0..90.0 && point.lng in -180.0..180.0
        val meetsSource = point.isTrustworthyTrackSource()
        val frameGood = meetsAccuracy && meetsSource
        val frameBad = !frameGood

        val pointForCurrentSegment = point.copy(segmentId = state.currentSegmentId)
        if (state.livePoints.isEmpty() && frameGood) {
            return applyInitialAnchorCandidate(
                state = state,
                point = pointForCurrentSegment,
                rawSpeed = rawSpeed,
                speedSourceLabel = speedSourceLabel,
            )
        }

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
            TrackDataFilter.rejectReasonForCandidate(state.livePoints, pointForCurrentSegment)
        } else {
            null
        }
        val consistentWithPendingOutlier = state.pendingOutlierPoint?.let {
            TrackDataFilter.rejectReasonForCandidate(it, pointForCurrentSegment) == null
        } ?: true
        val nextConsecutiveTrackOutlierCount = when {
            !frameGood || trackOutlierReason == null -> 0
            consistentWithPendingOutlier -> state.consecutiveTrackOutlierCount + 1
            else -> 1
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
        val startsNewSegment = canUseForTrack && (forceReanchor || trackPausedForSignal)
        val candidateForTrack = if (startsNewSegment) {
            pointForCurrentSegment.copy(segmentId = state.currentSegmentId + 1)
        } else {
            pointForCurrentSegment
        }
        val speedSegmentStartIndex = if (startsNewSegment) state.livePoints.size else segmentStartIndex

        // 仪表速度与轨迹入库解耦：野点不入库但仍可估速；迟滞恢复期间避免未入库点污染位移导数。
        val speedEstimate = when {
            !frameGood -> null
            canUseForTrack -> SpeedEstimator.estimate(
                trackPointsIncludingNew = AppendedPointView(state.livePoints, candidateForTrack),
                newPoint = candidateForTrack,
                previousDisplaySpeedMps = state.currentSpeedMps,
                rawDopplerMps = rawSpeed,
                segmentStartIndex = speedSegmentStartIndex,
            )
            trackPausedForSignal && point.accuracy <= TrackingPolicy.DOPPLER_MAX_ACCURACY_M -> SpeedEstimator.estimate(
                trackPointsIncludingNew = listOf(candidateForTrack),
                newPoint = candidateForTrack,
                previousDisplaySpeedMps = state.currentSpeedMps,
                rawDopplerMps = rawSpeed,
                segmentStartIndex = 0,
            )
            outlierReason != null && point.accuracy <= TrackingPolicy.DOPPLER_MAX_ACCURACY_M -> SpeedEstimator.estimate(
                trackPointsIncludingNew = listOf(candidateForTrack),
                newPoint = candidateForTrack,
                previousDisplaySpeedMps = state.currentSpeedMps,
                rawDopplerMps = rawSpeed,
                segmentStartIndex = 0,
            )
            outlierReason != null -> null
            else -> SpeedEstimator.estimate(
                trackPointsIncludingNew = AppendedPointView(state.livePoints, candidateForTrack),
                newPoint = candidateForTrack,
                previousDisplaySpeedMps = state.currentSpeedMps,
                rawDopplerMps = rawSpeed,
                segmentStartIndex = segmentStartIndex,
            )
        }

        val nextSpeed = speedEstimate?.displaySpeedMps ?: if (trackPausedForSignal) 0.0 else state.currentSpeedMps
        val dropReason = when {
            !meetsAccuracy -> if (point.accuracy < TRACK_POINT_MIN_ACCURACY_M) {
                "map only: accuracy unknown"
            } else {
                "map only: accuracy>${TRACK_POINT_MAX_ACCURACY_M.toInt()}m"
            }
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
            candidateForTrack.copy(speedMps = speedEstimate!!.instantSpeedMps)
        } else {
            null
        }
        val displayUpdate = if (acceptedPoint != null) {
            updateDisplayTrack(state, acceptedPoint)
        } else {
            null
        }
        val points = if (acceptedPoint != null) {
            state.livePoints.appendTrackPoint(acceptedPoint)
        } else {
            state.livePoints
        }

        val nextTrackPausedForSignal = when {
            acceptedPoint != null -> false
            state.trackPausedForSignal || enteringPause -> true
            else -> false
        }
        val nextConsecutiveGoodGpsCount = when {
            acceptedPoint != null -> 0
            !nextTrackPausedForSignal -> 0
            frameBad -> 0
            else -> consecutiveGoodGpsCount
        }
        val nextConsecutiveBadGpsCountFinal = when {
            acceptedPoint != null -> 0
            frameGood -> 0
            else -> nextConsecutiveBadGpsCount
        }
        val nextConsecutiveTrackOutlierCountFinal = when {
            acceptedPoint != null -> 0
            frameGood && trackOutlierReason != null -> nextConsecutiveTrackOutlierCount
            else -> 0
        }

        return Result(
            state = state.copy(
                livePoints = points,
                displayPoints = displayUpdate?.points ?: state.displayPoints,
                mapPoints = displayUpdate?.mapPoints ?: state.mapPoints,
                displayDistanceM = displayUpdate?.distanceM ?: state.displayDistanceM,
                distanceState = displayUpdate?.distanceState ?: state.distanceState,
                distanceBeforeLastPoint = displayUpdate?.distanceBeforeLastPoint ?: state.distanceBeforeLastPoint,
                spikePointIndices = displayUpdate?.spikeIndices ?: state.spikePointIndices,
                currentSegmentId = if (startsNewSegment) state.currentSegmentId + 1 else state.currentSegmentId,
                pendingAnchorPoint = null,
                pendingAnchorPoints = emptyList(),
                consecutiveAnchorCandidateCount = 0,
                pendingOutlierPoint = if (trackOutlierReason != null && !forceReanchor) {
                    pointForCurrentSegment
                } else {
                    null
                },
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
            acceptedPoints = listOfNotNull(acceptedPoint),
        )
    }

    private fun freshnessDropReason(
        point: GpsPoint,
        recordingStartAt: Long,
        recordingStartMonotonicMs: Long,
        activeSegmentStartedAt: Long,
    ): String? {
        if (point.isCached) return "cached location"
        val hasComparableMonotonicTime = recordingStartMonotonicMs > 0L && point.fixMonotonicMs > 0L
        if (hasComparableMonotonicTime) {
            if (point.fixMonotonicMs < recordingStartMonotonicMs) return "before active segment"
        } else {
            // Location.time 与系统壁钟都会受手动校时/NTP 回拨影响，仅在缺少单调时间时兜底。
            if (point.timestamp < recordingStartAt && activeSegmentStartedAt == recordingStartAt) return "before recording start"
            if (point.timestamp < activeSegmentStartedAt) return "before active segment"
            if (recordingStartMonotonicMs > 0L && System.currentTimeMillis() - point.timestamp > CALLBACK_MAX_AGE_MS) {
                return "stale location timestamp"
            }
            if (point.timestamp > System.currentTimeMillis() + FUTURE_TIMESTAMP_TOLERANCE_MS) {
                return "future location timestamp"
            }
        }
        if (recordingStartMonotonicMs > 0L && point.receivedMonotonicMs > 0L) {
            val callbackAgeMs = android.os.SystemClock.elapsedRealtime() - point.receivedMonotonicMs
            if (callbackAgeMs !in 0..CALLBACK_MAX_AGE_MS) return "stale callback"
        }
        return null
    }

    private fun applyInitialAnchorCandidate(
        state: RecordingSessionState,
        point: GpsPoint,
        rawSpeed: Double,
        speedSourceLabel: String,
    ): Result {
        val consistent = state.pendingAnchorPoints.lastOrNull()?.let {
            TrackDataFilter.rejectReasonForCandidate(it, point) == null
        } ?: true
        val candidates = if (consistent) state.pendingAnchorPoints + point else listOf(point)
        val candidateCount = candidates.size
        if (candidateCount < INITIAL_ANCHOR_REQUIRED_COUNT) {
            return Result(
                state.copy(
                    pendingAnchorPoint = point,
                    pendingAnchorPoints = candidates,
                    consecutiveAnchorCandidateCount = candidateCount,
                    pendingOutlierPoint = null,
                    consecutiveTrackOutlierCount = 0,
                    mapCenterLat = point.lat,
                    mapCenterLng = point.lng,
                    currentAltitude = point.altitude,
                    currentSpeedMps = 0.0,
                    lastLocationAtMs = point.timestamp,
                    lastLocationAccuracyM = point.accuracy,
                    lastLocationCountedInTrack = false,
                    lastLocationDropReason = "anchor confirmation $candidateCount/$INITIAL_ANCHOR_REQUIRED_COUNT",
                    lastRawSpeedMps = rawSpeed,
                    lastDerivedSpeedMps = null,
                    lastSpeedSource = speedSourceLabel,
                    lastSpeedMethod = null,
                ),
            )
        }

        val acceptedPoints = ArrayList<GpsPoint>(candidates.size)
        var previousDisplaySpeedMps = 0.0
        var estimate: SpeedEstimator.Estimate? = null
        candidates.forEach { candidate ->
            estimate = SpeedEstimator.estimate(
                trackPointsIncludingNew = acceptedPoints + candidate,
                newPoint = candidate,
                previousDisplaySpeedMps = previousDisplaySpeedMps,
                rawDopplerMps = candidate.speedMps,
                segmentStartIndex = 0,
            )
            acceptedPoints += candidate.copy(speedMps = estimate!!.instantSpeedMps)
            previousDisplaySpeedMps = estimate!!.displaySpeedMps
        }
        val lastEstimate = requireNotNull(estimate)
        val display = TrackDataFilter.displaySnapshot(acceptedPoints)
        return Result(
            state = state.copy(
                livePoints = acceptedPoints.asPersistentTrackPoints(),
                displayPoints = display.points.asPersistentTrackPoints(),
                mapPoints = TrackDataFilter.downsampleForMap(display.points),
                displayDistanceM = display.totalDistanceM,
                distanceState = TrackDataFilter.distanceState(display.points),
                distanceBeforeLastPoint = TrackDataFilter.distanceState(display.points.dropLast(1)),
                spikePointIndices = TrackDataFilter.spikeIndices(acceptedPoints),
                pendingAnchorPoint = null,
                pendingAnchorPoints = emptyList(),
                consecutiveAnchorCandidateCount = 0,
                pendingOutlierPoint = null,
                consecutiveTrackOutlierCount = 0,
                mapCenterLat = point.lat,
                mapCenterLng = point.lng,
                currentAltitude = point.altitude,
                currentSpeedMps = lastEstimate.displaySpeedMps.coerceAtLeast(0.0),
                signalLost = false,
                trackPausedForSignal = false,
                consecutiveGoodGpsCount = 0,
                consecutiveBadGpsCount = 0,
                lastLocationAtMs = point.timestamp,
                lastLocationAccuracyM = point.accuracy,
                lastLocationCountedInTrack = true,
                lastLocationDropReason = null,
                lastRawSpeedMps = rawSpeed,
                lastDerivedSpeedMps = lastEstimate.derivedSpeedMps,
                lastSpeedSource = speedSourceLabel,
                lastSpeedMethod = lastEstimate.method,
                lastDopplerWeight = lastEstimate.dopplerWeight,
                lastSpeedAccuracyMps = point.speedAccuracyMps,
                lastSegmentDtMs = lastEstimate.lastSegmentDtMs,
                lastSegmentCount = lastEstimate.segmentCount,
            ),
            acceptedPoints = acceptedPoints,
        )
    }

    private data class DisplayUpdate(
        val points: List<GpsPoint>,
        val mapPoints: List<GpsPoint>,
        val distanceM: Double,
        val spikeIndices: Set<Int>,
        val distanceState: TrackDataFilter.DistanceState,
        val distanceBeforeLastPoint: TrackDataFilter.DistanceState,
    )

    private fun updateDisplayTrack(state: RecordingSessionState, acceptedPoint: GpsPoint): DisplayUpdate {
        val raw = state.livePoints
        if (raw.isEmpty()) {
            return DisplayUpdate(
                points = listOf(acceptedPoint),
                mapPoints = listOf(acceptedPoint),
                distanceM = 0.0,
                spikeIndices = emptySet(),
                distanceState = TrackDataFilter.advanceDistance(TrackDataFilter.DistanceState(), acceptedPoint),
                distanceBeforeLastPoint = TrackDataFilter.DistanceState(),
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
            state.displayPoints.replaceLastTrackPoint(acceptedPoint)
        } else {
            state.displayPoints.appendTrackPoint(acceptedPoint)
        }
        val beforeLast = if (previousIsNewSpike) state.distanceBeforeLastPoint else state.distanceState
        val distance = TrackDataFilter.advanceDistance(beforeLast, acceptedPoint)
        val nextMapBase = if (previousIsNewSpike) state.mapPoints.dropLast(1) else state.mapPoints
        val previousDisplay = if (previousIsNewSpike) {
            state.displayPoints.getOrNull(state.displayPoints.size - 2)
        } else state.displayPoints.lastOrNull()
        val nextMap = TrackDataFilter.appendMapPoint(nextMapBase, previousDisplay, acceptedPoint)
        return DisplayUpdate(nextDisplay, nextMap, distance.totalDistanceM, nextSpikes, distance, beforeLast)
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
