package com.velotrack.velotrack

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.GnssStatus
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.velotrack.velotrack.tracking.TrackingPolicy

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
    private var runningRecordingMode = false
    private var currentLocationToken: CancellationTokenSource? = null
    private var amapClient: AMapLocationClient? = null
    private var platformFallbackStarted = false
    private var gnssCallbackRegistered = false
    private val deliveryGate = LocationDeliveryGate()

    private val gnssCallback: GnssStatus.Callback =
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
                            else -> Unit
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

    private fun gmsRequest(precise: Boolean, recordingMode: Boolean): LocationRequest {
        val intervalMs = if (recordingMode) 1_200L else 2_000L
        val minIntervalMs = if (recordingMode) 900L else 1_500L
        return LocationRequest.Builder(
            if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            intervalMs,
        )
            .setMinUpdateIntervalMillis(minIntervalMs)
            .setMinUpdateDistanceMeters(if (recordingMode) 1f else 3f)
            .setWaitForAccurateLocation(recordingMode)
            .build()
    }

    private val gmsCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val points = result.locations.map { location ->
                val cached = !runningRecordingMode && !location.isFreshFix()
                val msg = "GMS update prov=${location.provider} acc=${location.accuracyText()} " +
                    "spd=${location.speedText()} sacc=${location.speedAccuracyText()} cached=$cached"
                onDebugEvent(msg)
                Log.d(TAG_LOC, msg)
                location.toGpsPoint(GpsSource.GMS_FUSED, isGpsFix = !cached, isCached = cached)
            }
            LocationDeliveryGate.order(points).forEach(::deliverPoint)
        }
    }

    private val platformListener = LocationListener { location ->
        onDebugEvent("Platform ${location.provider} acc=${location.accuracyText()}")
        val cached = !location.isFreshFix()
        val source = when (location.provider) {
            LocationManager.GPS_PROVIDER -> GpsSource.PLATFORM_GPS
            LocationManager.NETWORK_PROVIDER -> GpsSource.PLATFORM_NETWORK
            LocationManager.PASSIVE_PROVIDER -> GpsSource.PLATFORM_PASSIVE
            else -> GpsSource.UNKNOWN
        }
        deliverPoint(
            location.toGpsPoint(
                source,
                isGpsFix = source == GpsSource.PLATFORM_GPS && !cached,
                isCached = cached,
            ),
        )
    }

    private val amapListener = AMapLocationListener { location ->
        if (location == null) return@AMapLocationListener
        if (location.errorCode == AMapLocation.LOCATION_SUCCESS) {
            onDebugEvent("AMap type=${location.locationType} coord=${location.coordType} acc=${location.accuracyText()}")
            deliverPoint(location.toGpsPoint())
        } else {
            val message = "AMap failed code=${location.errorCode} ${location.errorInfo.orEmpty()}"
            Log.w("VeloTrack", message)
            onDebugEvent(message)
            startPlatformFallbackIfNeeded()
        }
    }

    @SuppressLint("MissingPermission")
    fun start(precise: Boolean, recordingMode: Boolean = false) {
        if (running && runningPrecise == precise && runningRecordingMode == recordingMode) return
        if (running) stop()
        runningPrecise = precise
        runningRecordingMode = recordingMode
        onDebugEvent("start provider=$provider precise=$precise recording=$recordingMode")
        try {
            if (!recordingMode) emitRecentKnownLocation()
            registerGnssCallbackIfNeeded()
            when (provider) {
                MapProvider.AMAP -> startAmapLocation(precise, recordingMode)
                MapProvider.GOOGLE_MAPS -> startGoogleLocation(precise, recordingMode)
            }
            running = true
        } catch (error: SecurityException) {
            val message = "Location permission unavailable: ${error.message.orEmpty()}"
            Log.w(TAG_LOC, message)
            onDebugEvent(message)
            stopInternal()
        }
    }

    fun stop() {
        if (!running) return
        onDebugEvent("stop provider=$provider")
        stopInternal()
    }

    private fun stopInternal() {
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
        runningRecordingMode = false
    }

    @SuppressLint("MissingPermission")
    private fun startGoogleLocation(precise: Boolean, recordingMode: Boolean) {
        if (!recordingMode) {
            fusedClient.lastLocation.addOnSuccessListener { location ->
                if (location != null && location.isRecentEnough()) {
                    onDebugEvent("GMS last acc=${location.accuracyText()}")
                    deliverPoint(location.toGpsPoint(GpsSource.GMS_FUSED, isGpsFix = false, isCached = true))
                }
            }
        }
        if (!recordingMode) {
            currentLocationToken = CancellationTokenSource().also { tokenSource ->
                fusedClient.getCurrentLocation(
                    CurrentLocationRequest.Builder()
                        .setPriority(if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                        .setMaxUpdateAgeMillis(RECENT_LOCATION_MAX_AGE_MS)
                        .setDurationMillis(CURRENT_LOCATION_TIMEOUT_MS)
                        .build(),
                    tokenSource.token,
                ).addOnSuccessListener { location ->
                    if (location != null) {
                        val cached = !location.isFreshFix()
                        onDebugEvent("GMS current acc=${location.accuracyText()} cached=$cached")
                        deliverPoint(location.toGpsPoint(GpsSource.GMS_FUSED, isGpsFix = !cached, isCached = cached))
                    }
                }
            }
        }
        fusedClient.requestLocationUpdates(
            gmsRequest(precise, recordingMode),
            gmsCallback,
            Looper.getMainLooper(),
        )
    }

    @SuppressLint("MissingPermission")
    private fun startAmapLocation(precise: Boolean, recordingMode: Boolean) {
        runCatching {
            AMapLocationClient(appContext).also { client ->
                amapClient = client
                client.setLocationOption(amapOption(precise, recordingMode))
                client.setLocationListener(amapListener)
                if (!recordingMode) {
                    client.getLastKnownLocation()?.takeIf { it.isRecentEnough() }?.let {
                        onDebugEvent("AMap last type=${it.locationType} acc=${it.accuracyText()}")
                        deliverPoint(it.toGpsPoint(forceCached = true))
                    }
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

    private fun amapOption(precise: Boolean, recordingMode: Boolean): AMapLocationClientOption =
        AMapLocationClientOption().apply {
            locationMode = if (precise) {
                AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            } else {
                AMapLocationClientOption.AMapLocationMode.Battery_Saving
            }
            interval = if (recordingMode) 1_200L else 2_000L
            isOnceLocation = false
            isOnceLocationLatest = false
            isNeedAddress = false
            isMockEnable = false
            isGpsFirst = precise
            isLocationCacheEnable = !recordingMode
            isWifiScan = true
            isOffset = true
            httpTimeOut = 8000L
            setCacheCallBack(!recordingMode)
            if (!recordingMode) {
                setCacheCallBackTime(RECENT_LOCATION_MAX_AGE_MS.toInt())
                setLastLocationLifeCycle(RECENT_LOCATION_MAX_AGE_MS)
            }
        }

    @SuppressLint("MissingPermission")
    private fun startPlatformFallbackIfNeeded() {
        if (platformFallbackStarted) return
        platformFallbackStarted = true
        onDebugEvent("Platform fallback start precise=$runningPrecise")
        startPlatformLocation(runningPrecise, recordingMode = true)
    }

    @SuppressLint("MissingPermission")
    private fun startPlatformLocation(precise: Boolean, recordingMode: Boolean = false) {
        val minTimeMs = if (recordingMode) 1_200L else 2_000L
        val minDistanceM = if (recordingMode) 1f else 3f
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
                deliverPoint(it.toGpsPoint(source, isGpsFix = false, isCached = true))
            }
    }

    private fun deliverPoint(point: GpsPoint) {
        if (deliveryGate.accept(point)) {
            onLocation(point)
        } else {
            onDebugEvent("drop duplicate/out-of-order fix mono=${point.fixMonotonicMs}")
        }
    }

    private fun Location.isRecentEnough(): Boolean =
        time > 0 && (System.currentTimeMillis() - time) in 0..RECENT_LOCATION_MAX_AGE_MS

    private fun Location.isFreshFix(): Boolean {
        val ageMs = if (elapsedRealtimeNanos > 0L) {
            SystemClock.elapsedRealtime() - elapsedRealtimeNanos / 1_000_000L
        } else {
            System.currentTimeMillis() - time
        }
        return ageMs in 0..LIVE_FIX_MAX_AGE_MS
    }

    private fun Location.accuracyText(): String =
        if (hasAccuracy()) "${accuracy.toInt()}m" else "unknown"

    private fun Location.speedText(): String =
        if (hasSpeed()) String.format(java.util.Locale.US, "%.1fmps", speed) else "none"

    private fun Location.speedAccuracyText(): String {
        return if (hasSpeedAccuracy()) String.format(java.util.Locale.US, "%.2fmps", speedAccuracyMetersPerSecond) else "none"
    }

    private fun Location.trustworthyGnssSpeed(): Boolean {
        if (!hasSpeed() || !speed.isFinite() || speed < 0f ||
            speed > TrackingPolicy.MAX_PLAUSIBLE_SPEED_MPS.toFloat()
        ) {
            return false
        }
        if (!hasAccuracy() || accuracy > TrackingPolicy.DOPPLER_MAX_ACCURACY_M) return false
        if (hasSpeedAccuracy()) {
            if (speedAccuracyMetersPerSecond > 2.5f) return false
        }
        // GMS FusedLocationProvider 回调时 provider 通常是 "fused"；仅按 GPS_PROVIDER 判定会漏掉所有 GMS 帧。
        return when (provider) {
            LocationManager.GPS_PROVIDER, "fused" -> true
            LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER -> false
            else -> hasSpeed() // 未知 provider，但 SDK 给了 speed 也姑且采信，让后续融合校验
        }
    }

    private fun Location.fixMonotonicMsOrZero(): Long {
        val nanos = elapsedRealtimeNanos
        return if (nanos > 0L) nanos / 1_000_000L else 0L
    }

    private fun Location.speedAccuracyOrNull(): Double? {
        return if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null
    }

    private fun Location.toGpsPoint(
        source: GpsSource,
        isGpsFix: Boolean? = null,
        isCached: Boolean = false,
    ): GpsPoint {
        val receivedAt = SystemClock.elapsedRealtime()
        val fixMonotonicMs = fixMonotonicMsOrZero()
        val positionFix = isGpsFix ?: (!isCached &&
            (source == GpsSource.GMS_FUSED || source == GpsSource.PLATFORM_GPS))
        return GpsPoint(
            lat = latitude,
            lng = longitude,
            timestamp = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            speedMps = if (hasSpeed()) speed.toDouble() else 0.0,
            altitude = if (hasAltitude()) altitude else null,
            accuracy = if (hasAccuracy()) accuracy.toDouble() else 0.0,
            source = source,
            isGpsFix = positionFix,
            monotonicMs = fixMonotonicMs.takeIf { it > 0L } ?: receivedAt,
            fixMonotonicMs = fixMonotonicMs,
            speedAccuracyMps = speedAccuracyOrNull(),
            isSpeedTrustworthy = positionFix && !isCached && trustworthyGnssSpeed(),
            isCached = isCached,
            receivedMonotonicMs = receivedAt,
        )
    }

    private fun AMapLocation.toGpsPoint(forceCached: Boolean = false): GpsPoint {
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
        val cachedType = locationType == AMapLocation.LOCATION_TYPE_LAST_LOCATION_CACHE ||
            locationType == AMapLocation.LOCATION_TYPE_FIX_CACHE
        val isCached = forceCached || cachedType
        val isGpsFix = locationType == AMapLocation.LOCATION_TYPE_GPS && !isCached
        val receivedAt = SystemClock.elapsedRealtime()
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
            // AMapLocation 没暴露 elapsedRealtimeNanos；用回调时刻近似（仍单调），优于 time 字段。
            monotonicMs = receivedAt,
            fixMonotonicMs = 0L,
            speedAccuracyMps = null,
            isSpeedTrustworthy = isGpsFix && hasSpeed() && speed.isFinite() &&
                speed in 0f..TrackingPolicy.MAX_PLAUSIBLE_SPEED_MPS.toFloat() &&
                hasAccuracy() && accuracy in 0.1f..TrackingPolicy.DOPPLER_MAX_ACCURACY_M.toFloat(),
            isCached = isCached,
            receivedMonotonicMs = receivedAt,
        )
    }

    @SuppressLint("MissingPermission")
    private fun registerGnssCallbackIfNeeded() {
        if (gnssCallbackRegistered) return
        val callback = gnssCallback
        runCatching {
            locationManager.registerGnssStatusCallback(callback, android.os.Handler(Looper.getMainLooper()))
            gnssCallbackRegistered = true
        }.onFailure {
            onDebugEvent("GNSS register failed: ${it.message ?: it::class.java.simpleName}")
        }
    }

    private fun unregisterGnssCallback() {
        if (!gnssCallbackRegistered) return
        val callback = gnssCallback
        runCatching { locationManager.unregisterGnssStatusCallback(callback) }
        gnssCallbackRegistered = false
    }

    private companion object {
        const val RECENT_LOCATION_MAX_AGE_MS = 15 * 60 * 1000L
        const val LIVE_FIX_MAX_AGE_MS = 5_000L
        const val CURRENT_LOCATION_TIMEOUT_MS = 10_000L
        const val TAG_LOC = "VeloLoc"
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
