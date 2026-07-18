package com.velotrack.velotrack.speed

import com.velotrack.velotrack.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackDataFilterTest {
    @Test
    fun displaySnapshot_removesIsolatedPositionSpike() {
        val points = listOf(
            point(31.00000, 121.00000, 0),
            point(31.00045, 121.00045, 1),
            point(31.00002, 121.00002, 2),
        )

        val snapshot = TrackDataFilter.displaySnapshot(points)

        assertEquals(1, snapshot.spikePointCount)
        assertEquals(listOf(points.first(), points.last()), snapshot.points)
        assertTrue(snapshot.totalDistanceM in 2.0..5.0)

        val summary = TrackDataFilter.summarize(points)
        assertEquals(2.0, summary.movingDurationSec, 0.001)
    }

    @Test
    fun highSpeedLeg_marksModerateDetourAsSpike() {
        val a = point(31.00000, 121.0, 0)
        val b = point(31.00027, 121.0, 1)
        val c = point(31.00018, 121.0, 2)

        assertTrue(TrackDataFilter.isPositionSpike(a, b, c))
    }

    @Test
    fun historicalSummary_usesPersistedMonotonicTimeAcrossWallClockRollback() {
        val points = listOf(
            point(31.00000, 121.0, 0).copy(timestamp = 2_000_000L),
            point(31.00005, 121.0, 1).copy(timestamp = 1_000_000L),
        )

        val summary = TrackDataFilter.summarize(points)

        assertTrue(summary.totalDistanceM > 2.0)
        assertEquals(1.0, summary.movingDurationSec, 0.001)
        assertEquals(1, TrackDataFilter.routeSegments(points).size)
    }

    @Test
    fun downsampleForMap_keepsBoundsAndLimit() {
        val points = (0 until 10_000).map { index ->
            point(31.0 + index * 0.000001, 121.0, index)
        }

        val result = TrackDataFilter.downsampleForMap(points, maxPoints = 2_000)

        assertTrue(result.size <= 2_000)
        assertEquals(points.first(), result.first())
        assertEquals(points.last(), result.last())
    }

    @Test
    fun impossibleCrossCitySegment_isNeitherCountedNorConnected() {
        val points = listOf(
            point(31.0, 121.0, 0),
            point(31.1, 121.1, 1),
            point(31.10003, 121.1, 2),
        )

        val summary = TrackDataFilter.summarize(points)
        val segments = TrackDataFilter.routeSegments(points)

        assertTrue(summary.totalDistanceM < 10.0)
        assertEquals(2, segments.size)
        assertEquals(1, segments.first().size)
        assertEquals(2, segments.last().size)
    }

    @Test
    fun explicitSegmentBoundary_isNotCountedOrConnected() {
        val points = listOf(
            point(31.0, 121.0, 0).copy(segmentId = 0),
            point(31.00003, 121.0, 1).copy(segmentId = 0),
            point(31.00100, 121.0, 2).copy(segmentId = 1),
            point(31.00103, 121.0, 3).copy(segmentId = 1),
        )

        val summary = TrackDataFilter.summarize(points)

        assertTrue(summary.totalDistanceM < 10.0)
        assertEquals(2, TrackDataFilter.routeSegments(points).size)
    }

    @Test
    fun routeSegments_keepsSingletonActiveSegmentForCurrentPosition() {
        val points = listOf(
            point(31.00000, 121.00000, 0).copy(segmentId = 0),
            point(31.00003, 121.00000, 1).copy(segmentId = 0),
            point(31.10000, 121.10000, 2).copy(segmentId = 1),
        )

        val segments = TrackDataFilter.routeSegments(points)

        assertEquals(2, segments.size)
        assertEquals(listOf(points.last()), segments.last())
    }

    @Test
    fun isolatedSegmentPoint_doesNotKeepOrSpreadStoredSpeed() {
        val points = listOf(
            point(31.0, 121.0, 0).copy(segmentId = 0, speedMps = 20.0),
            point(31.10000, 121.10000, 1).copy(segmentId = 1, speedMps = 5.0),
            point(31.10005, 121.10000, 2).copy(segmentId = 1, speedMps = 5.0),
        )

        val chart = TrackDataFilter.summarize(points).chartSpeedMps

        assertEquals(0.0, chart.first(), 0.001)
        assertTrue(chart.drop(1).all { it < 10.0 })
    }

    @Test
    fun withInferredSegments_persistsLegacyImpossibleJumpAsBoundary() {
        val original = listOf(
            point(31.0, 121.0, 0),
            point(31.1, 121.1, 1),
            point(31.10003, 121.1, 2),
        )

        val normalized = TrackDataFilter.withInferredSegments(original)

        assertEquals(listOf(0, 1, 1), normalized.map { it.segmentId })
    }

    @Test
    fun withInferredSegments_doesNotTurnIsolatedSpikeIntoPermanentBreaks() {
        val original = listOf(
            point(31.00000, 121.00000, 0),
            point(31.00045, 121.00045, 1),
            point(31.00002, 121.00002, 2),
        )

        val normalized = TrackDataFilter.withInferredSegments(original)

        assertEquals(listOf(0, 0, 0), normalized.map { it.segmentId })
        assertEquals(listOf(normalized.first(), normalized.last()), TrackDataFilter.filterForDisplay(normalized))
    }

    private fun point(lat: Double, lng: Double, second: Int): GpsPoint =
        GpsPoint(
            lat = lat,
            lng = lng,
            timestamp = 1_000_000L + second * 1_000L,
            monotonicMs = 10_000L + second * 1_000L,
            speedMps = 5.0,
            altitude = null,
            accuracy = 3.0,
        )
}
