package com.velotrack.velotrack

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.velotrack.velotrack.ui.VeloTheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
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
        LocationTracker(this, mapProvider, ::dispatchLocation, viewModel::onLocationDebug)
    }

    private var startCountdownAfterPermission = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val shouldBeginStartCountdown = startCountdownAfterPermission &&
                permissions.values.any { it } &&
                viewModel.uiState.value.view == AppView.RECORDING &&
                !viewModel.uiState.value.isRecording
            startCountdownAfterPermission = false
            if (shouldBeginStartCountdown) {
                viewModel.beginStartCountdown()
            }
            syncLocationSubscription()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT,
            ),
        )
        disableSystemBarContrastOverlays()
        enableAdaptiveHighRefreshRate()
        logMapStartupDiagnostics()
        restoreCachedLocation()

        setContent {
            VeloTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(state.isRecording, state.isPaused) {
                    syncLocationSubscription()
                }
                LaunchedEffect(state.isRecording, state.startCountdownSeconds) {
                    syncKeepScreenOn()
                }
                VeloMainScreen(
                    state = state,
                    provider = mapProvider,
                    debugPermissions = locationPermissionSnapshot(),
                    onStartRecording = { requestStartCountdown() },
                    onCancelStartCountdown = { viewModel.cancelStartCountdown() },
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
        enableAdaptiveHighRefreshRate()
        syncKeepScreenOn()
        syncLocationSubscription()
    }

    override fun onPause() {
        super.onPause()
        viewModel.cancelStartCountdown()
        setKeepScreenOn(false)
        locationTracker.stop()
    }

    override fun onStop() {
        super.onStop()
        startCountdownAfterPermission = false
    }

    @Suppress("DEPRECATION")
    private fun disableSystemBarContrastOverlays() {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
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
        return hasFineLocationPermission() || hasCoarseLocationPermission()
    }

    private fun locationPermissionSnapshot(): LocationPermissionSnapshot =
        LocationPermissionSnapshot(
            fine = hasFineLocationPermission(),
            coarse = hasCoarseLocationPermission(),
        )

    private fun hasFineLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasCoarseLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestStartCountdown() {
        val state = viewModel.uiState.value
        if (state.isRecording || state.startCountdownSeconds != null) return
        if (!hasLocationPermission()) {
            startCountdownAfterPermission = true
            requestLocationPermissions()
            return
        }
        viewModel.beginStartCountdown()
    }

    private fun syncKeepScreenOn() {
        val state = viewModel.uiState.value
        setKeepScreenOn(state.isRecording || state.startCountdownSeconds != null)
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun syncLocationSubscription() {
        val state = viewModel.uiState.value
        val shouldTrack = state.isRecording && !state.isPaused
        if (shouldTrack && !hasLocationPermission()) {
            requestLocationPermissions()
            return
        }
        if (shouldTrack && hasLocationPermission()) {
            locationTracker.start(precise = hasFineLocationPermission())
        } else if (!shouldTrack) {
            locationTracker.stop()
        }
    }
}
