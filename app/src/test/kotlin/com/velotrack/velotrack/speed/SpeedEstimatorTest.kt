package com.velotrack.velotrack.speed

import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.GpsSource
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedEstimatorTest {
    @Test
    fun positionFixWithoutTrustworthyDoppler_doesNotReuseRawSpeed() {
        val point = GpsPoint(
            lat = 31.0,
            lng = 121.0,
            timestamp = 1_000_000L,
            speedMps = 18.0,
            altitude = null,
            accuracy = 3.0,
            source = GpsSource.GMS_FUSED,
            isGpsFix = true,
            monotonicMs = 10_000L,
            speedAccuracyMps = 0.4,
            isSpeedTrustworthy = false,
        )

        val estimate = SpeedEstimator.estimate(
            trackPointsIncludingNew = listOf(point),
            newPoint = point,
            previousDisplaySpeedMps = 0.0,
            rawDopplerMps = point.speedMps,
        )

        assertEquals(0.0, estimate.instantSpeedMps, 0.0)
        assertEquals("hold-zero", estimate.method)
    }
}
