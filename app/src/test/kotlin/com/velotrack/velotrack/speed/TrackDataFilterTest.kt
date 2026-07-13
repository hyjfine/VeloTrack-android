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
