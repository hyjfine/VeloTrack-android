package com.velotrack.velotrack

/**
 * GPS sample stored in the app's canonical coordinate system.
 *
 * [lat] / [lng] are WGS-84 coordinates from platform location providers. Map providers that use
 * regional coordinate systems, such as AMap's GCJ-02 basemap in mainland China, must convert only
 * at render time so distance statistics, persistence, and future exports stay globally portable.
 */
data class GpsPoint(
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val speedMps: Double,
    val altitude: Double?,
    val accuracy: Double,
)

data class Ride(
    val id: String,
    val title: String,
    val startTime: Long,
    val endTime: Long?,
    val points: List<GpsPoint>,
    val totalDistance: Double,
    val avgSpeed: Double,
    val maxSpeed: Double,
)

enum class AppView {
    RECORDING,
    HISTORY,
    DETAIL,
}
