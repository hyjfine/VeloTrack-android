package com.velotrack.velotrack

import androidx.compose.runtime.Immutable

/**
 * GPS sample stored in the app's canonical coordinate system.
 *
 * [lat] / [lng] are WGS-84 coordinates from platform location providers. Map providers that use
 * regional coordinate systems, such as AMap's GCJ-02 basemap in mainland China, must convert only
 * at render time so distance statistics, persistence, and future exports stay globally portable.
 */
@Immutable
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
    /**
     * 该样本到达时的 [android.os.SystemClock.elapsedRealtime] 时刻（毫秒），
     * 用于段速 dt，避免 Location.time 壁钟跳变 / 缓存帧时间倒置的影响。运行时字段，不持久化。
     */
    val monotonicMs: Long = 0L,
    /**
     * GNSS 多普勒速度 1σ 误差（m/s），可用于权重判定。仅 API 26+ Location 提供；缺失为 null。
     * 运行时字段，不持久化。
     */
    val speedAccuracyMps: Double? = null,
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

@Immutable
data class Ride(
    val id: String,
    val title: String,
    val startTime: Long,
    val endTime: Long?,
    val points: List<GpsPoint>,
    val totalDistance: Double,
    val avgSpeed: Double,
    val maxSpeed: Double,
    /** 有效移动时长（秒），经 [com.velotrack.velotrack.speed.TrackDataFilter] 计算；读库时填充。 */
    val movingDurationSec: Double = 0.0,
)

@Immutable
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
