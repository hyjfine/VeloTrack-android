package com.velotrack.velotrack.recording

import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.GpsSource
import com.velotrack.velotrack.speed.TrackDataFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLocationProcessorTest {
    @Test
    fun incrementalDisplay_matchesFullRecalculation() {
        val samples = listOf(
            point(31.00000, 121.00000, 0),
            point(31.00003, 121.00000, 1),
            point(31.00006, 121.00000, 2),
            point(31.00015, 121.00012, 3), // isolated but still plausible-speed spike
            point(31.00009, 121.00000, 4),
            point(31.00012, 121.00000, 5),
        )
        var state = RecordingSessionState(isRecording = true, recordingStartAt = 1_000_000L)

        samples.forEach { sample ->
            state = RecordingLocationProcessor.apply(
                state = state,
                point = sample,
                recordingStartAt = 1_000_000L,
                isRecording = true,
                isPaused = false,
            ).state
            val full = TrackDataFilter.displaySnapshot(state.livePoints)
            assertEquals(full.points, state.displayPoints)
            assertEquals(full.totalDistanceM, state.displayDistanceM, 0.001)
            assertEquals(full.spikePointCount, state.spikePointIndices.size)
        }
    }

    @Test
    fun cachedLocation_neverBecomesInitialAnchor() {
        var state = RecordingSessionState(isRecording = true, recordingStartAt = 1_000_000L)
        repeat(3) { index ->
            val cached = point(30.0, 120.0, index).copy(isCached = true)
            state = apply(state, cached).state
        }

        assertTrue(state.livePoints.isEmpty())
        assertEquals("cached location", state.lastLocationDropReason)

        repeat(3) { index ->
            state = apply(state, point(31.0 + index * 0.00003, 121.0, index + 3)).state
        }
        assertEquals(3, state.livePoints.size)
        assertTrue(state.livePoints.all { it.lat > 30.9 })
        assertTrue(state.displayDistanceM > 0.0)
    }

    @Test
    fun initialAnchor_requiresThreeMutuallyConsistentFixes() {
        var state = RecordingSessionState(isRecording = true, recordingStartAt = 1_000_000L)
        state = apply(state, point(30.0, 120.0, 0)).state
        repeat(3) { index ->
            state = apply(state, point(31.0 + index * 0.00003, 121.0, index + 1)).state
        }

        assertEquals(3, state.livePoints.size)
        assertTrue(state.livePoints.all { it.lat > 30.9 })
    }

    @Test
    fun consecutiveOutliers_reanchorIntoNewSegmentWithoutCrossDistance() {
        var state = RecordingSessionState(isRecording = true, recordingStartAt = 1_000_000L)
        val startSamples = listOf(
            point(31.00000, 121.00000, 0),
            point(31.00001, 121.00000, 1),
            point(31.00002, 121.00000, 2),
            point(31.00005, 121.00000, 3),
        )
        startSamples.forEach { state = apply(state, it).state }
        val distanceBeforeJump = state.displayDistanceM

        val newArea = listOf(
            point(31.10000, 121.10000, 4),
            point(31.10001, 121.10000, 5),
            point(31.10002, 121.10000, 6),
            point(31.10005, 121.10000, 7),
        )
        newArea.forEach { state = apply(state, it).state }

        assertEquals(1, state.currentSegmentId)
        assertEquals(1, state.livePoints.last().segmentId)
        assertTrue(state.livePoints.any { it.segmentId == 0 })
        assertTrue(state.displayDistanceM - distanceBeforeJump < 20.0)
        assertEquals(2, TrackDataFilter.routeSegments(state.livePoints).size)
    }

    @Test
    fun rejectedLowQualityOutlier_doesNotPublishEstimateFromOldTrack() {
        var state = RecordingSessionState(isRecording = true, recordingStartAt = 1_000_000L)
        listOf(
            point(31.00000, 121.00000, 0),
            point(31.00003, 121.00000, 1),
            point(31.00006, 121.00000, 2),
            point(31.00009, 121.00000, 3),
        ).forEach { state = apply(state, it).state }

        val result = apply(
            state,
            point(31.10000, 121.10000, 4).copy(accuracy = 18.0),
        )

        assertTrue(result.state.lastLocationDropReason.orEmpty().startsWith("outlier:"))
        assertNull(result.state.lastSpeedMethod)
        assertNull(result.state.lastDerivedSpeedMps)
    }

    @Test
    fun fixProducedBeforeActiveSegment_isRejectedEvenWithNewWallTimestamp() {
        val state = RecordingSessionState(isRecording = true, recordingStartAt = 1_000_000L)
        val result = RecordingLocationProcessor.apply(
            state = state,
            point = point(31.0, 121.0, 1).copy(
                monotonicMs = 9_000L,
                fixMonotonicMs = 9_000L,
                receivedMonotonicMs = 10_500L,
            ),
            recordingStartAt = 1_000_000L,
            recordingStartMonotonicMs = 10_000L,
            isRecording = true,
            isPaused = false,
        )

        assertTrue(result.state.livePoints.isEmpty())
        assertEquals("before active segment", result.state.lastLocationDropReason)
    }

    @Test
    fun wallClockRollback_doesNotRejectFixFromCurrentMonotonicSegment() {
        val state = RecordingSessionState(isRecording = true, recordingStartAt = 1_000_000L)
        val result = RecordingLocationProcessor.apply(
            state = state,
            point = point(31.0, 121.0, 1).copy(
                timestamp = 900_000L,
                monotonicMs = 10_500L,
                fixMonotonicMs = 10_500L,
                receivedMonotonicMs = 0L,
            ),
            recordingStartAt = 1_000_000L,
            recordingStartMonotonicMs = 10_000L,
            isRecording = true,
            isPaused = false,
        )

        assertEquals("anchor confirmation 1/3", result.state.lastLocationDropReason)
        assertEquals(1, result.state.consecutiveAnchorCandidateCount)
    }

    @Test
    fun callbackTimeWithoutFixTime_doesNotHideOldProviderTimestamp() {
        val state = RecordingSessionState(isRecording = true, recordingStartAt = 1_000_000L)
        val result = RecordingLocationProcessor.apply(
            state = state,
            point = point(31.0, 121.0, 1).copy(
                timestamp = 900_000L,
                monotonicMs = 10_500L,
                fixMonotonicMs = 0L,
                receivedMonotonicMs = 10_500L,
            ),
            recordingStartAt = 1_000_000L,
            recordingStartMonotonicMs = 10_000L,
            isRecording = true,
            isPaused = false,
        )

        assertEquals("before recording start", result.state.lastLocationDropReason)
        assertTrue(result.state.livePoints.isEmpty())
    }

    @Test fun signalRecoveryCanReanchorToConsistentNewArea() {
        var state = RecordingSessionState(isRecording = true)
        repeat(4) { state = apply(state, point(31.0 + it * 0.00003, 121.0, it)).state }
        repeat(2) { state = apply(state, point(31.0, 121.0, it + 4).copy(accuracy = 50.0)).state }
        val count = state.livePoints.size
        repeat(5) { state = apply(state, point(31.1 + it * 0.00003, 121.0, it + 6)).state }
        assertTrue(state.livePoints.size > count)
        assertTrue(!state.trackPausedForSignal)
        assertEquals(1, state.currentSegmentId)
        assertTrue(state.displayDistanceM < 100.0)
    }

    @Test fun longLiveRouteRetainsItsBeginningAfterRepeatedSampling() {
        var state = RecordingSessionState(isRecording = true)
        repeat(6001) { state = apply(state, point(31.0 + it * 3.0 / 111194.9266, 121.0, it)).state }
        val segments = TrackDataFilter.routeSegments(state.mapPoints)
        assertEquals(1, segments.size)
        assertEquals(state.livePoints.first(), segments.single().first())
        assertEquals(state.livePoints.last(), segments.single().last())
        assertEquals(TrackDataFilter.summarize(state.livePoints).totalDistanceM, state.displayDistanceM, 0.001)
    }

    @Test fun extremelyPoorFixesEnterSignalLoss() {
        var state = RecordingSessionState(isRecording = true)
        repeat(4) { state = apply(state, point(31.0 + it * 0.00003, 121.0, it)).state }
        repeat(10) { state = apply(state, point(31.0, 121.0, it + 4).copy(accuracy = 300.0)).state }
        assertTrue(state.signalLost)
        assertEquals(0.0, state.currentSpeedMps, 0.001)
    }

    @Test fun amapFixFromPauseIsRejectedAfterResume() {
        val result = RecordingLocationProcessor.apply(
            state = RecordingSessionState(isRecording = true),
            point = point(31.0, 121.0, 1).copy(fixMonotonicMs = 0L, source = GpsSource.AMAP_GPS),
            recordingStartAt = 1_000_000L,
            activeSegmentStartedAt = 1_010_000L,
            isRecording = true, isPaused = false,
        )
        assertEquals("before active segment", result.state.lastLocationDropReason)
        assertTrue(result.acceptedPoints.isEmpty())
    }

    private fun apply(state: RecordingSessionState, point: GpsPoint): RecordingLocationProcessor.Result =
        RecordingLocationProcessor.apply(
            state = state,
            point = point,
            recordingStartAt = 1_000_000L,
            isRecording = true,
            isPaused = false,
        )

    private fun point(lat: Double, lng: Double, second: Int): GpsPoint =
        GpsPoint(
            lat = lat,
            lng = lng,
            timestamp = 1_000_000L + second * 1_200L,
            monotonicMs = 10_000L + second * 1_200L,
            fixMonotonicMs = 10_000L + second * 1_200L,
            speedMps = 4.0,
            altitude = 10.0,
            accuracy = 3.0,
            source = GpsSource.PLATFORM_GPS,
            isGpsFix = true,
            isSpeedTrustworthy = true,
            speedAccuracyMps = 0.4,
        )
}
