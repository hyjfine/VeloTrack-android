package com.velotrack.velotrack

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.velotrack.velotrack.ui.VeloTheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.velotrack.velotrack.db.AppDatabase
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: TrackViewModel by viewModels {
        TrackViewModel.factory(RideRepository(AppDatabase.get(this).rideDao()))
    }

    /** 与地图一致：国内不用 GMS 定位，避免「需要启动 Google Play 服务」弹窗。 */
    private val mapProvider: MapProvider by lazy { MapProviderSelector.select() }

    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private val locationManager: LocationManager by lazy {
        getSystemService(LOCATION_SERVICE) as LocationManager
    }

    private var requestingLocation = false

    private val gmsRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
        .setMinUpdateIntervalMillis(800L)
        .setWaitForAccurateLocation(false)
        .build()

    private val gmsCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { dispatchLocation(it) }
        }
    }

    private val platformListener = LocationListener { loc -> dispatchLocation(loc) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            syncLocationSubscription()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            VeloTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                VeloMainScreen(
                    state = state,
                    provider = mapProvider,
                    onStartRecording = { viewModel.startRecording() },
                    onTogglePause = { viewModel.togglePause() },
                    onStopRecording = { viewModel.stopRecording() },
                    onBeginHold = { viewModel.beginHold() },
                    onEndHold = { viewModel.endHold() },
                    onSetView = {
                        viewModel.setView(it)
                        if (it == AppView.HISTORY) viewModel.loadHistory()
                    },
                    onOpenRide = { viewModel.openRide(it) },
                    onRequestDelete = { viewModel.requestDeleteRide(it) },
                    onConfirmDelete = { viewModel.confirmDeleteRide() },
                    onCancelDelete = { viewModel.cancelDeleteRide() },
                    onAnalyze = { viewModel.runAnalysis() },
                    onBackDetail = { viewModel.backFromDetail() },
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { syncLocationSubscription() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncLocationSubscription()
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    private fun dispatchLocation(loc: Location) {
        viewModel.onLocation(
            GpsPoint(
                lat = loc.latitude,
                lng = loc.longitude,
                timestamp = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                altitude = if (loc.hasAltitude()) loc.altitude else null,
                accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0,
            ),
        )
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun requestLocationPermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun syncLocationSubscription() {
        val state = viewModel.uiState.value
        val shouldTrack = state.isRecording && !state.isPaused
        if (shouldTrack && !hasLocationPermission()) {
            requestLocationPermissions()
            return
        }
        if (shouldTrack && hasLocationPermission() && !requestingLocation) {
            when (mapProvider) {
                MapProvider.AMAP -> startPlatformLocation()
                MapProvider.GOOGLE_MAPS -> startGmsLocation()
            }
            requestingLocation = true
        } else if (!shouldTrack) {
            stopLocationUpdates()
        }
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

    @SuppressLint("MissingPermission")
    private fun startGmsLocation() {
        fusedClient.requestLocationUpdates(gmsRequest, gmsCallback, mainLooper)
    }

    private fun stopLocationUpdates() {
        if (!requestingLocation) return
        when (mapProvider) {
            MapProvider.AMAP -> runCatching { locationManager.removeUpdates(platformListener) }
            MapProvider.GOOGLE_MAPS -> runCatching { fusedClient.removeLocationUpdates(gmsCallback) }
        }
        requestingLocation = false
    }
}
