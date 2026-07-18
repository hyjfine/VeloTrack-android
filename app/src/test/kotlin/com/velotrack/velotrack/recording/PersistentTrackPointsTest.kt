package com.velotrack.velotrack.recording

import com.velotrack.velotrack.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class PersistentTrackPointsTest {
    @Test
    fun appendAndReplaceLast_keepPreviousVersionsImmutableAcrossChunks() {
        var points = PersistentTrackPoints.from(emptyList())
        repeat(600) { points = points.append(point(it)) }
        val previous = points
        val appended = points.append(point(600))
        val replaced = appended.replaceLast(point(999))

        assertEquals(600, previous.size)
        assertEquals(599.0, previous.last().lat, 0.0)
        assertEquals(601, appended.size)
        assertEquals(600.0, appended.last().lat, 0.0)
        assertEquals(999.0, replaced.last().lat, 0.0)
        assertEquals((0 until 600).map(Int::toDouble), previous.map { it.lat })
    }

    private fun point(index: Int): GpsPoint =
        GpsPoint(
            lat = index.toDouble(),
            lng = 0.0,
            timestamp = index.toLong(),
            speedMps = 0.0,
            altitude = null,
            accuracy = 3.0,
        )
}
