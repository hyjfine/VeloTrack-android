package com.velotrack.velotrack

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
) {
    private val appContext = context.applicationContext
    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager: LocationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false
    private var runningPrecise = true
    private var currentLocationToken: CancellationTokenSource? = null
    private var amapClient: AMapLocationClient? = null
    private var platformFallbackStarted = false

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
                onLocation(it.toGpsPoint())
            }
        }
    }

    private val platformListener = LocationListener { location ->
        onDebugEvent("Platform ${location.provider} acc=${location.accuracyText()}")
        onLocation(location.toGpsPoint())
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
                onLocation(location.toGpsPoint())
            }
        }
        currentLocationToken = CancellationTokenSource().also { tokenSource ->
            fusedClient.getCurrentLocation(
                if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                tokenSource.token,
            ).addOnSuccessListener { location ->
                if (location != null) {
                    onDebugEvent("GMS current acc=${location.accuracyText()}")
                    onLocation(location.toGpsPoint())
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
            isGpsFirst = false
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
                onLocation(it.toGpsPoint())
            }
    }

    private fun Location.isRecentEnough(): Boolean =
        time > 0 && System.currentTimeMillis() - time <= RECENT_LOCATION_MAX_AGE_MS

    private fun Location.accuracyText(): String =
        if (hasAccuracy()) "${accuracy.toInt()}m" else "unknown"

    private fun Location.toGpsPoint(): GpsPoint =
        GpsPoint(
            lat = latitude,
            lng = longitude,
            timestamp = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            speedMps = if (hasSpeed()) speed.toDouble() else 0.0,
            altitude = if (hasAltitude()) altitude else null,
            accuracy = if (hasAccuracy()) accuracy.toDouble() else 0.0,
        )

    private fun AMapLocation.toGpsPoint(): GpsPoint {
        val normalized = if (coordType == AMapLocation.COORD_TYPE_GCJ02) {
            CoordinateTransform.gcj02ToWgs84(latitude, longitude)
        } else {
            CoordinateTransform.Coordinate(latitude, longitude)
        }
        return GpsPoint(
            lat = normalized.lat,
            lng = normalized.lng,
            timestamp = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            speedMps = if (hasSpeed()) speed.toDouble() else 0.0,
            altitude = if (hasAltitude()) altitude else null,
            accuracy = if (hasAccuracy()) accuracy.toDouble() else 0.0,
        )
    }

    private companion object {
        const val RECENT_LOCATION_MAX_AGE_MS = 15 * 60 * 1000L
    }
}
