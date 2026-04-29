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
    /** 定位来源标签，仅供运行时判断与调试，不持久化。 */
    val source: GpsSource = GpsSource.UNKNOWN,
    /** true 表示该样本的速度来自 GNSS，可信度高；false 表示来自网络/WiFi/缓存等。 */
    val isGpsFix: Boolean = false,
)

enum class GpsSource(val label: String) {
    AMAP_GPS("amap-gps"),
    AMAP_NETWORK("amap-net"),
    AMAP_WIFI("amap-wifi"),
    AMAP_CELL("amap-cell"),
    AMAP_CACHE("amap-cache"),
    AMAP_OTHER("amap-other"),
    PLATFORM_GPS("plat-gps"),
    PLATFORM_NETWORK("plat-net"),
    PLATFORM_PASSIVE("plat-passive"),
    GMS_FUSED("gms-fused"),
    UNKNOWN("unknown"),
}

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

data class LocationPermissionSnapshot(
    val fine: Boolean = false,
    val coarse: Boolean = false,
) {
    val any: Boolean get() = fine || coarse
}

enum class AppView {
    RECORDING,
    HISTORY,
    DETAIL,
}
