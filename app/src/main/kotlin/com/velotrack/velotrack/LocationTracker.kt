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

class LocationTracker(
    context: Context,
    private val provider: MapProvider,
    private val onLocation: (GpsPoint) -> Unit,
) {
    private val appContext = context.applicationContext
    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager: LocationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false

    private val gmsRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
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
    fun start() {
        if (running) return
        when (provider) {
            MapProvider.AMAP -> startPlatformLocation()
            MapProvider.GOOGLE_MAPS -> fusedClient.requestLocationUpdates(gmsRequest, gmsCallback, Looper.getMainLooper())
        }
        running = true
    }

    fun stop() {
        if (!running) return
        when (provider) {
            MapProvider.AMAP -> runCatching { locationManager.removeUpdates(platformListener) }
            MapProvider.GOOGLE_MAPS -> runCatching { fusedClient.removeLocationUpdates(gmsCallback) }
        }
        running = false
    }

    @SuppressLint("MissingPermission")
    private fun startPlatformLocation() {
        val minTimeMs = 1000L
        val minDistanceM = 1f
        val mainLooper = Looper.getMainLooper()
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
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

    private fun Location.toGpsPoint(): GpsPoint =
        GpsPoint(
            lat = latitude,
            lng = longitude,
            timestamp = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            speedMps = if (hasSpeed()) speed.toDouble() else 0.0,
            altitude = if (hasAltitude()) altitude else null,
            accuracy = if (hasAccuracy()) accuracy.toDouble() else 0.0,
        )
}
