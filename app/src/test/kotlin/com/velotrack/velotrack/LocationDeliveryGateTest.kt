package com.velotrack.velotrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationDeliveryGateTest {
    @Test
    fun batch_isOrderedAndDuplicateFixesAreRejectedAcrossCalls() {
        val gate = LocationDeliveryGate()
        val newer = point(12_000L)
        val older = point(10_000L)
        val ordered = LocationDeliveryGate.order(listOf(newer, older))

        assertEquals(listOf(older, newer), ordered)
        assertTrue(gate.accept(ordered[0]))
        assertTrue(gate.accept(ordered[1]))
        assertFalse(gate.accept(point(12_000L)))
        assertFalse(gate.accept(point(11_000L)))
    }

    private fun point(fixTime: Long): GpsPoint =
        GpsPoint(
            lat = 31.0,
            lng = 121.0,
            timestamp = 1_000_000L + fixTime,
            speedMps = 0.0,
            altitude = null,
            accuracy = 3.0,
            monotonicMs = fixTime,
            fixMonotonicMs = fixTime,
        )
}
