package com.velotrack.velotrack

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoordinateSdkTest {
    @Test fun sdkRegionGuardConvertsShenzhenAndLeavesTaipeiAndTokyoUnchanged() {
        for ((lat, lng) in listOf(22.54 to 114.05, 31.23 to 121.47)) {
            val gcj = CoordinateTransform.wgs84ToGcj02(lat, lng)
            assertTrue(GeoUtils.haversineMeters(lat, lng, gcj.lat, gcj.lng) > 100.0)
            val roundTrip = CoordinateTransform.gcj02ToWgs84(gcj.lat, gcj.lng)
            assertTrue(GeoUtils.haversineMeters(lat, lng, roundTrip.lat, roundTrip.lng) < 1.0)
        }
        for ((lat, lng) in listOf(25.03 to 121.56, 35.68 to 139.69)) {
            assertEquals(CoordinateTransform.Coordinate(lat, lng), CoordinateTransform.wgs84ToGcj02(lat, lng))
        }
    }
}
