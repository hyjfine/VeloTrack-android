package com.velotrack.velotrack.recording

import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.GpsSource
import com.velotrack.velotrack.speed.TrackDataFilter
import org.junit.Assert.assertEquals
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
    fun fixProducedBeforeActiveSegment_isRejectedEvenWithNewWallTimestamp() {
        val state = RecordingSessionState(isRecording = true, recordingStartAt = 1_000_000L)
        val result = RecordingLocationProcessor.apply(
            state = state,
            point = point(31.0, 121.0, 1).copy(monotonicMs = 9_000L, receivedMonotonicMs = 10_500L),
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
            speedMps = 4.0,
            altitude = 10.0,
            accuracy = 3.0,
            source = GpsSource.PLATFORM_GPS,
            isGpsFix = true,
            isSpeedTrustworthy = true,
            speedAccuracyMps = 0.4,
        )
}
