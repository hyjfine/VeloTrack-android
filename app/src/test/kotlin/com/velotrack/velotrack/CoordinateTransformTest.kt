package com.velotrack.velotrack

import org.junit.Assert.*
import org.junit.Test

class CoordinateTransformTest {
    @Test fun shenzhenGcjFixIsNormalizedInsteadOfMistakenForHongKong() {
        val wgs = CoordinateTransform.gcj02ToWgs84(22.54, 114.05)
        assertTrue(GeoUtils.haversineMeters(22.54, 114.05, wgs.lat, wgs.lng) > 100.0)
        assertEquals(22.5427, wgs.lat, 0.001)
        assertEquals(114.0449, wgs.lng, 0.001)
    }
}
