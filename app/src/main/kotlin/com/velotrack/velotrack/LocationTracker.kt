package com.velotrack.velotrack

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.GnssStatus
import android.os.Build
import android.os.Looper
import android.util.Log
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

class LocationTracker(
    context: Context,
    private val provider: MapProvider,
    private val onLocation: (GpsPoint) -> Unit,
    private val onDebugEvent: (String) -> Unit = {},
    private val onGnssStatus: (GnssSatelliteSnapshot) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager: LocationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false
    private var runningPrecise = true
    private var currentLocationToken: CancellationTokenSource? = null
    private var amapClient: AMapLocationClient? = null
    private var platformFallbackStarted = false
    private var gnssCallbackRegistered = false

    private val gnssCallback: GnssStatus.Callback? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    var visible = 0
                    var inUse = 0
                    var beidouVisible = 0
                    var beidouInUse = 0
                    var gpsInUse = 0
                    var glonassInUse = 0
                    var galileoInUse = 0
                    val count = status.satelliteCount
                    for (i in 0 until count) {
                        val constellation = status.getConstellationType(i)
                        val used = status.usedInFix(i)
                        visible++
                        if (used) inUse++
                        when (constellation) {
                            GnssStatus.CONSTELLATION_BEIDOU -> {
                                beidouVisible++
                                if (used) beidouInUse++
                            }
                            GnssStatus.CONSTELLATION_GPS -> if (used) gpsInUse++
                            GnssStatus.CONSTELLATION_GLONASS -> if (used) glonassInUse++
                            GnssStatus.CONSTELLATION_GALILEO -> if (used) galileoInUse++
                        }
                    }
                    onGnssStatus(
                        GnssSatelliteSnapshot(
                            visible = visible,
                            inUse = inUse,
                            beidouVisible = beidouVisible,
                            beidouInUse = beidouInUse,
                            gpsInUse = gpsInUse,
                            glonassInUse = glonassInUse,
                            galileoInUse = galileoInUse,
                        ),
                    )
                }
            }
        } else {
            null
        }

    private fun gmsRequest(precise: Boolean): LocationRequest =
        LocationRequest.Builder(
            if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            1000L,
        )
            .setMinUpdateIntervalMillis(800L)
            .setWaitForAccurateLocation(false)
            .build()

    private val gmsCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                onDebugEvent("GMS update acc=${it.accuracyText()}")
                onLocation(it.toGpsPoint(GpsSource.GMS_FUSED, isGpsFix = true))
            }
        }
    }

    private val platformListener = LocationListener { location ->
        onDebugEvent("Platform ${location.provider} acc=${location.accuracyText()}")
        val source = when (location.provider) {
            LocationManager.GPS_PROVIDER -> GpsSource.PLATFORM_GPS
            LocationManager.NETWORK_PROVIDER -> GpsSource.PLATFORM_NETWORK
            LocationManager.PASSIVE_PROVIDER -> GpsSource.PLATFORM_PASSIVE
            else -> GpsSource.UNKNOWN
        }
        onLocation(location.toGpsPoint(source, isGpsFix = source == GpsSource.PLATFORM_GPS))
    }

    private val amapListener = AMapLocationListener { location ->
        if (location == null) return@AMapLocationListener
        if (location.errorCode == AMapLocation.LOCATION_SUCCESS) {
            onDebugEvent("AMap type=${location.locationType} coord=${location.coordType} acc=${location.accuracyText()}")
            onLocation(location.toGpsPoint())
        } else {
            val message = "AMap failed code=${location.errorCode} ${location.errorInfo.orEmpty()}"
            Log.w("VeloTrack", message)
            onDebugEvent(message)
            startPlatformFallbackIfNeeded()
        }
    }

    @SuppressLint("MissingPermission")
    fun start(precise: Boolean) {
        if (running && runningPrecise == precise) return
        if (running) stop()
        runningPrecise = precise
        onDebugEvent("start provider=$provider precise=$precise")
        emitRecentKnownLocation()
        registerGnssCallbackIfNeeded()
        when (provider) {
            MapProvider.AMAP -> startAmapLocation(precise)
            MapProvider.GOOGLE_MAPS -> startGoogleLocation(precise)
        }
        running = true
    }

    fun stop() {
        if (!running) return
        onDebugEvent("stop provider=$provider")
        currentLocationToken?.cancel()
        currentLocationToken = null
        unregisterGnssCallback()
        when (provider) {
            MapProvider.AMAP -> {
                runCatching { locationManager.removeUpdates(platformListener) }
                runCatching {
                    amapClient?.unRegisterLocationListener(amapListener)
                    amapClient?.stopLocation()
                    amapClient?.onDestroy()
                }
                amapClient = null
                platformFallbackStarted = false
            }
            MapProvider.GOOGLE_MAPS -> runCatching { fusedClient.removeLocationUpdates(gmsCallback) }
        }
        running = false
    }

    @SuppressLint("MissingPermission")
    private fun startGoogleLocation(precise: Boolean) {
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location != null && location.isRecentEnough()) {
                onDebugEvent("GMS last acc=${location.accuracyText()}")
                onLocation(location.toGpsPoint(GpsSource.GMS_FUSED, isGpsFix = false))
            }
        }
        currentLocationToken = CancellationTokenSource().also { tokenSource ->
            fusedClient.getCurrentLocation(
                if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                tokenSource.token,
            ).addOnSuccessListener { location ->
                if (location != null) {
                    onDebugEvent("GMS current acc=${location.accuracyText()}")
                    onLocation(location.toGpsPoint(GpsSource.GMS_FUSED, isGpsFix = true))
                }
            }
        }
        fusedClient.requestLocationUpdates(gmsRequest(precise), gmsCallback, Looper.getMainLooper())
    }

    @SuppressLint("MissingPermission")
    private fun startAmapLocation(precise: Boolean) {
        runCatching {
            AMapLocationClient(appContext).also { client ->
                amapClient = client
                client.setLocationOption(amapOption(precise))
                client.setLocationListener(amapListener)
                client.getLastKnownLocation()?.takeIf { it.isRecentEnough() }?.let {
                    onDebugEvent("AMap last type=${it.locationType} acc=${it.accuracyText()}")
                    onLocation(it.toGpsPoint())
                }
                client.startLocation()
            }
        }.onFailure { error ->
            val message = "AMap client start failed: ${error.message ?: error::class.java.simpleName}"
            Log.e("VeloTrack", "$message; falling back to platform providers", error)
            onDebugEvent(message)
            startPlatformFallbackIfNeeded()
        }
    }

    private fun amapOption(precise: Boolean): AMapLocationClientOption =
        AMapLocationClientOption().apply {
            locationMode = if (precise) {
                AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            } else {
                AMapLocationClientOption.AMapLocationMode.Battery_Saving
            }
            interval = 1000L
            isOnceLocation = false
            isOnceLocationLatest = false
            isNeedAddress = false
            isMockEnable = false
            // GNSS（含北斗）优先，提升速度精度；高精度模式下 30s 内有 GPS 即返 GPS。
            isGpsFirst = precise
            isLocationCacheEnable = true
            isWifiScan = true
            isOffset = true
            httpTimeOut = 8000L
            setCacheCallBack(true)
            setCacheCallBackTime(RECENT_LOCATION_MAX_AGE_MS.toInt())
            setLastLocationLifeCycle(RECENT_LOCATION_MAX_AGE_MS)
        }

    @SuppressLint("MissingPermission")
    private fun startPlatformFallbackIfNeeded() {
        if (platformFallbackStarted) return
        platformFallbackStarted = true
        onDebugEvent("Platform fallback start precise=$runningPrecise")
        startPlatformLocation(runningPrecise)
    }

    @SuppressLint("MissingPermission")
    private fun startPlatformLocation(precise: Boolean) {
        val minTimeMs = 1000L
        val minDistanceM = 1f
        val mainLooper = Looper.getMainLooper()
        if (precise && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                minTimeMs,
                minDistanceM,
                platformListener,
                mainLooper,
            )
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                minTimeMs,
                minDistanceM,
                platformListener,
                mainLooper,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun emitRecentKnownLocation() {
        val candidates = buildList {
            add(runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull())
            add(runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull())
            add(runCatching { locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull())
        }.filterNotNull()

        candidates
            .filter { it.isRecentEnough() }
            .minWithOrNull(compareBy<Location> { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }.thenByDescending { it.time })
            ?.let {
                onDebugEvent("Platform recent ${it.provider} acc=${it.accuracyText()}")
                val source = when (it.provider) {
                    LocationManager.GPS_PROVIDER -> GpsSource.PLATFORM_GPS
                    LocationManager.NETWORK_PROVIDER -> GpsSource.PLATFORM_NETWORK
                    LocationManager.PASSIVE_PROVIDER -> GpsSource.PLATFORM_PASSIVE
                    else -> GpsSource.UNKNOWN
                }
                // last-known 不算实时，速度不参与跟踪。
                onLocation(it.toGpsPoint(source, isGpsFix = false))
            }
    }

    private fun Location.isRecentEnough(): Boolean =
        time > 0 && System.currentTimeMillis() - time <= RECENT_LOCATION_MAX_AGE_MS

    private fun Location.accuracyText(): String =
        if (hasAccuracy()) "${accuracy.toInt()}m" else "unknown"

    private fun Location.toGpsPoint(source: GpsSource, isGpsFix: Boolean): GpsPoint =
        GpsPoint(
            lat = latitude,
            lng = longitude,
            timestamp = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            speedMps = if (hasSpeed()) speed.toDouble() else 0.0,
            altitude = if (hasAltitude()) altitude else null,
            accuracy = if (hasAccuracy()) accuracy.toDouble() else 0.0,
            source = source,
            isGpsFix = isGpsFix,
        )

    private fun AMapLocation.toGpsPoint(): GpsPoint {
        val normalized = if (coordType == AMapLocation.COORD_TYPE_GCJ02) {
            CoordinateTransform.gcj02ToWgs84(latitude, longitude)
        } else {
            CoordinateTransform.Coordinate(latitude, longitude)
        }
        // AMap locationType: 1=GPS, 2=前次位置, 4=缓存, 5=WiFi, 6=基站, 8=离线; 仅 GPS 速度可信
        val source = when (locationType) {
            AMapLocation.LOCATION_TYPE_GPS -> GpsSource.AMAP_GPS
            AMapLocation.LOCATION_TYPE_WIFI -> GpsSource.AMAP_WIFI
            AMapLocation.LOCATION_TYPE_CELL -> GpsSource.AMAP_CELL
            AMapLocation.LOCATION_TYPE_LAST_LOCATION_CACHE,
            AMapLocation.LOCATION_TYPE_FIX_CACHE,
            -> GpsSource.AMAP_CACHE
            AMapLocation.LOCATION_TYPE_SAME_REQ -> GpsSource.AMAP_NETWORK
            else -> GpsSource.AMAP_OTHER
        }
        val isGpsFix = locationType == AMapLocation.LOCATION_TYPE_GPS
        return GpsPoint(
            lat = normalized.lat,
            lng = normalized.lng,
            timestamp = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            // 非 GPS 来源的 speed 在 AMap SDK 中常常无意义；仅 GPS 时透传。
            speedMps = if (isGpsFix && hasSpeed()) speed.toDouble() else 0.0,
            altitude = if (hasAltitude()) altitude else null,
            accuracy = if (hasAccuracy()) accuracy.toDouble() else 0.0,
            source = source,
            isGpsFix = isGpsFix,
        )
    }

    @SuppressLint("MissingPermission")
    private fun registerGnssCallbackIfNeeded() {
        if (gnssCallbackRegistered) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val callback = gnssCallback ?: return
        runCatching {
            locationManager.registerGnssStatusCallback(callback, android.os.Handler(Looper.getMainLooper()))
            gnssCallbackRegistered = true
        }.onFailure {
            onDebugEvent("GNSS register failed: ${it.message ?: it::class.java.simpleName}")
        }
    }

    private fun unregisterGnssCallback() {
        if (!gnssCallbackRegistered) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val callback = gnssCallback ?: return
        runCatching { locationManager.unregisterGnssStatusCallback(callback) }
        gnssCallbackRegistered = false
    }

    private companion object {
        const val RECENT_LOCATION_MAX_AGE_MS = 15 * 60 * 1000L
    }
}

data class GnssSatelliteSnapshot(
    val visible: Int,
    val inUse: Int,
    val beidouVisible: Int,
    val beidouInUse: Int,
    val gpsInUse: Int,
    val glonassInUse: Int,
    val galileoInUse: Int,
)

