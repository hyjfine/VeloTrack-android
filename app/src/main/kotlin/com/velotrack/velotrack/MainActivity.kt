package com.velotrack.velotrack

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.velotrack.velotrack.ui.VeloTheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.velotrack.velotrack.db.AppDatabase
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: TrackViewModel by viewModels {
        TrackViewModel.factory(RideRepository(AppDatabase.get(this).rideDao()))
    }

    /** 与地图一致：国内不用 GMS 定位，避免「需要启动 Google Play 服务」弹窗。 */
    private val mapProvider: MapProvider by lazy { MapProviderSelector.select() }

    private val lastLocationStore: LastLocationStore by lazy {
        LastLocationStore(this)
    }

    private val locationTracker: LocationTracker by lazy {
        LocationTracker(this, mapProvider, ::dispatchLocation)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            syncLocationSubscription()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        logMapStartupDiagnostics()
        restoreCachedLocation()

        setContent {
            VeloTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(state.isRecording, state.isPaused) {
                    syncLocationSubscription()
                }
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
    }

    override fun onResume() {
        super.onResume()
        syncLocationSubscription()
    }

    override fun onPause() {
        super.onPause()
        locationTracker.stop()
    }

    private fun dispatchLocation(point: GpsPoint) {
        lastLocationStore.write(point)
        viewModel.onLocation(point)
    }

    private fun logMapStartupDiagnostics() {
        Log.d(
            "VeloTrack",
            "Map startup: provider=$mapProvider, region=${Locale.getDefault().country}, " +
                "override=${BuildConfig.MAP_PROVIDER_OVERRIDE.ifBlank { "auto" }}, " +
                "amapKeyPresent=${BuildConfig.AMAP_API_KEY.isNotBlank()}, " +
                "googleKeyPresent=${BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()}",
        )
    }

    private fun restoreCachedLocation() {
        lastLocationStore.read()?.let(viewModel::restoreLastLocation)
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
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun syncLocationSubscription() {
        val state = viewModel.uiState.value
        val shouldTrack = state.isRecording && !state.isPaused
        if (shouldTrack && !hasLocationPermission()) {
            requestLocationPermissions()
            return
        }
        if (shouldTrack && hasLocationPermission()) {
            locationTracker.start()
        } else if (!shouldTrack) {
            locationTracker.stop()
        }
    }
}
