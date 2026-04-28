package com.velotrack.velotrack

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Coordinate helpers used only at map-rendering boundaries. */
object CoordinateTransform {
    data class Coordinate(val lat: Double, val lng: Double)

    private const val PI = 3.1415926535897932384626
    private const val EARTH_RADIUS_A = 6378245.0
    private const val ECCENTRICITY_EE = 0.00669342162296594323

    /**
     * Converts WGS-84 GPS coordinates to GCJ-02 for mainland China map providers such as AMap.
     * Coordinates outside mainland China are returned unchanged.
     */
    fun wgs84ToGcj02(lat: Double, lng: Double): Coordinate {
        if (!isInMainlandChina(lat, lng)) return Coordinate(lat, lng)

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
     * Converts GCJ-02 coordinates back to WGS-84 using a lightweight inverse approximation.
     * This is intended for normalizing AMap location results before storing them as [GpsPoint].
     */
    fun gcj02ToWgs84(lat: Double, lng: Double): Coordinate {
        if (!isInMainlandChina(lat, lng)) return Coordinate(lat, lng)

        val gcj = wgs84ToGcj02(lat, lng)
        return Coordinate(
            lat = lat * 2 - gcj.lat,
            lng = lng * 2 - gcj.lng,
        )
    }

    /** Rough mainland-China guard. Hong Kong, Macau, and Taiwan are left unchanged. */
    fun isInMainlandChina(lat: Double, lng: Double): Boolean {
        if (lat !in 3.86..53.55 || lng !in 73.66..135.05) return false
        if (lat in 21.75..22.65 && lng in 113.75..114.65) return false // Hong Kong / Macau
        if (lat in 21.8..25.4 && lng in 119.3..122.1) return false // Taiwan
        return true
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

