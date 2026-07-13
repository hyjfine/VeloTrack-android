package com.velotrack.velotrack.recording

import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.GpsSource
import com.velotrack.velotrack.speed.TrackDataFilter
import org.junit.Assert.assertEquals
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
            speedAccuracyMps = 0.4,
        )
}
