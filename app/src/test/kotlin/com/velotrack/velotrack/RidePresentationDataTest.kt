package com.velotrack.velotrack

import com.velotrack.velotrack.speed.TrackDataFilter
import org.junit.Assert.*
import org.junit.Test

class RidePresentationDataTest {
    @Test fun longRideKeepsChartPeakAndPauseEndpoints() {
        val points = (0..20000).map { i -> GpsPoint(
            lat = 31.0 + i * 3.0 / 111194.9266, lng = 121.0,
            timestamp = 1000L + i * 1200L + if (i >= 10000) 600_000 else 0,
            monotonicMs = 10_000L + i * 1200L,
            speedMps = 2.5, altitude = null, accuracy = 3.0,
            segmentId = if (i < 10000) 0 else 1,
        ) }
        val speeds = List(points.size) { if (it == 12345) 20.0 else 2.5 }
        val summary = TrackDataFilter.summarize(points).copy(chartSpeedMps = speeds)
        val data = RidePresentationData.build(points, summary)
        assertTrue(data.chart.size <= 1004)
        assertEquals(72f, data.chart.maxOf { it.speedKmh }, 0.01f)
        val firstAfterPause = data.chart.indexOfFirst { it.segmentId == 1 }
        assertEquals(601200L, data.chart[firstAfterPause].elapsedMs - data.chart[firstAfterPause - 1].elapsedMs)
        assertEquals(2, TrackDataFilter.routeSegments(data.mapPoints).size)
    }
}
