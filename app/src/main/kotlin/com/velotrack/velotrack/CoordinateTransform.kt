package com.velotrack.velotrack

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import com.amap.api.maps.CoordinateConverter

/** SDK boundaries: canonical WGS-84 storage and AMap GCJ-02 rendering/location results. */
object CoordinateTransform {
    data class Coordinate(val lat: Double, val lng: Double)

    private const val PI = 3.1415926535897932384626
    private const val EARTH_RADIUS_A = 6378245.0
    private const val ECCENTRICITY_EE = 0.00669342162296594323

    /**
     * Converts WGS-84 coordinates in the AMap SDK's supported GCJ-02 data region.
     * The SDK owns geographic boundaries; points outside its region remain unchanged.
     */
    fun wgs84ToGcj02(lat: Double, lng: Double): Coordinate {
        if (!CoordinateConverter.isAMapDataAvailable(lat, lng)) return Coordinate(lat, lng)
        return offsetToGcj02(lat, lng)
    }

    private fun offsetToGcj02(lat: Double, lng: Double): Coordinate {
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - ECCENTRICITY_EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((EARTH_RADIUS_A * (1 - ECCENTRICITY_EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (EARTH_RADIUS_A / sqrtMagic * cos(radLat) * PI)
        return Coordinate(lat + dLat, lng + dLng)
    }

    /**
     * Only call for coordinates explicitly identified as GCJ-02 by the provider.
     * Do not second-guess the provider's coordinate type using geographic rectangles.
     */
    fun gcj02ToWgs84(lat: Double, lng: Double): Coordinate {
        var wgs = Coordinate(lat, lng)
        repeat(4) {
            val gcj = offsetToGcj02(wgs.lat, wgs.lng)
            wgs = Coordinate(wgs.lat + lat - gcj.lat, wgs.lng + lng - gcj.lng)
        }
        return wgs
    }

    private fun transformLat(x: Double, y: Double): Double {
        var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(kotlin.math.abs(x))
        result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        result += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        result += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return result
    }

    private fun transformLng(x: Double, y: Double): Double {
        var result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(kotlin.math.abs(x))
        result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        result += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        result += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return result
    }
}
