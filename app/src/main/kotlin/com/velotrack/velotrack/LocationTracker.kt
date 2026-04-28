package com.velotrack.velotrack

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
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
) {
    private val appContext = context.applicationContext
    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager: LocationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false
    private var runningPrecise = true
    private var currentLocationToken: CancellationTokenSource? = null

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
            result.lastLocation?.let { onLocation(it.toGpsPoint()) }
        }
    }

    private val platformListener = LocationListener { location ->
        onLocation(location.toGpsPoint())
    }

    @SuppressLint("MissingPermission")
    fun start(precise: Boolean) {
        if (running && runningPrecise == precise) return
        if (running) stop()
        emitRecentKnownLocation()
        when (provider) {
            MapProvider.AMAP -> startPlatformLocation(precise)
            MapProvider.GOOGLE_MAPS -> startGoogleLocation(precise)
        }
        runningPrecise = precise
        running = true
    }

    fun stop() {
        if (!running) return
        currentLocationToken?.cancel()
        currentLocationToken = null
        when (provider) {
            MapProvider.AMAP -> runCatching { locationManager.removeUpdates(platformListener) }
            MapProvider.GOOGLE_MAPS -> runCatching { fusedClient.removeLocationUpdates(gmsCallback) }
        }
        running = false
    }

    @SuppressLint("MissingPermission")
    private fun startGoogleLocation(precise: Boolean) {
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location != null && location.isRecentEnough()) onLocation(location.toGpsPoint())
        }
        currentLocationToken = CancellationTokenSource().also { tokenSource ->
            fusedClient.getCurrentLocation(
                if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                tokenSource.token,
            ).addOnSuccessListener { location ->
                if (location != null) onLocation(location.toGpsPoint())
            }
        }
        fusedClient.requestLocationUpdates(gmsRequest(precise), gmsCallback, Looper.getMainLooper())
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
            ?.let { onLocation(it.toGpsPoint()) }
    }

    private fun Location.isRecentEnough(): Boolean =
        time > 0 && System.currentTimeMillis() - time <= RECENT_LOCATION_MAX_AGE_MS

    private fun Location.toGpsPoint(): GpsPoint =
        GpsPoint(
            lat = latitude,
            lng = longitude,
            timestamp = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            speedMps = if (hasSpeed()) speed.toDouble() else 0.0,
            altitude = if (hasAltitude()) altitude else null,
            accuracy = if (hasAccuracy()) accuracy.toDouble() else 0.0,
        )

    private companion object {
        const val RECENT_LOCATION_MAX_AGE_MS = 15 * 60 * 1000L
    }
}
