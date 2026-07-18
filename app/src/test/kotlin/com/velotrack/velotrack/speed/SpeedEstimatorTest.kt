package com.velotrack.velotrack.speed

import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.GpsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun longGap_doesNotReuseSegmentsOutsideDerivedWindow() {
        val points = listOf(
            point(31.00000, 121.0, 0),
            point(31.00005, 121.0, 1),
            point(31.00010, 121.0, 2),
            point(31.00010, 121.0, 100),
        )

        val estimate = SpeedEstimator.estimate(
            trackPointsIncludingNew = points,
            newPoint = points.last(),
            previousDisplaySpeedMps = 0.0,
            rawDopplerMps = 0.0,
        )

        assertNull(estimate.derivedSpeedMps)
        assertEquals(0.0, estimate.instantSpeedMps, 0.0)
    }

    @Test
    fun implausibleDopplerWithoutDerivedSpeed_isRejected() {
        val sample = point(31.0, 121.0, 0).copy(
            speedMps = 100.0,
            isSpeedTrustworthy = true,
            speedAccuracyMps = 0.2,
        )

        val estimate = SpeedEstimator.estimate(
            trackPointsIncludingNew = listOf(sample),
            newPoint = sample,
            previousDisplaySpeedMps = 0.0,
            rawDopplerMps = sample.speedMps,
        )

        assertEquals(0.0, estimate.instantSpeedMps, 0.0)
        assertEquals("hold-zero", estimate.method)
    }

    @Test
    fun nonFiniteDopplerWithoutDerivedSpeed_isRejected() {
        val sample = point(31.0, 121.0, 0).copy(
            speedMps = Double.NaN,
            isSpeedTrustworthy = true,
            speedAccuracyMps = 0.2,
        )

        val estimate = SpeedEstimator.estimate(
            trackPointsIncludingNew = listOf(sample),
            newPoint = sample,
            previousDisplaySpeedMps = 0.0,
            rawDopplerMps = sample.speedMps,
        )

        assertEquals(0.0, estimate.instantSpeedMps, 0.0)
        assertEquals("hold-zero", estimate.method)
    }

    @Test
    fun missingDoppler_doesNotAttenuateFreshDerivedSpeed() {
        val points = listOf(
            point(31.00000, 121.0, 0),
            point(31.00005, 121.0, 1),
        )

        val estimate = SpeedEstimator.estimate(
            trackPointsIncludingNew = points,
            newPoint = points.last(),
            previousDisplaySpeedMps = 0.0,
            rawDopplerMps = 0.0,
        )

        assertTrue(requireNotNull(estimate.derivedSpeedMps) > 0.0)
        assertEquals(estimate.derivedSpeedMps, estimate.instantSpeedMps, 0.0001)
        assertEquals("derived", estimate.method)
    }

    private fun point(lat: Double, lng: Double, second: Int): GpsPoint =
        GpsPoint(
            lat = lat,
            lng = lng,
            timestamp = 1_000_000L + second * 1_000L,
            speedMps = 0.0,
            altitude = null,
            accuracy = 3.0,
            source = GpsSource.GMS_FUSED,
            isGpsFix = true,
            monotonicMs = 10_000L + second * 1_000L,
            fixMonotonicMs = 10_000L + second * 1_000L,
            isSpeedTrustworthy = false,
        )
}
