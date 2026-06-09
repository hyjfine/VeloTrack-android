package com.velotrack.velotrack

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.velotrack.velotrack.db.AppDatabase
import com.velotrack.velotrack.recording.RecordingNotificationHelper
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: TrackViewModel by viewModels {
        TrackViewModel.factory(
            application,
            RideRepository(AppDatabase.get(this).rideDao()),
        )
    }

    private val mapProvider: MapProvider by lazy { MapProviderSelector.select() }

    private val lastLocationStore: LastLocationStore by lazy {
        LastLocationStore(this)
    }

    /** 仅用于开始倒计时前的定位预热；正式录制由 [RecordingSessionManager] 负责。 */
    private val prewarmLocationTracker: LocationTracker by lazy {
        LocationTracker(
            this,
            mapProvider,
            ::dispatchPrewarmLocation,
            viewModel::onLocationDebug,
            viewModel::onGnssStatus,
        )
    }

    private var startCountdownAfterPermission = false
    private var pendingNotificationAfterPermission = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            syncLocationPrecision()
            val hasLocation = permissions.values.any { it }
            if (startCountdownAfterPermission && hasLocation &&
                viewModel.uiState.value.view == AppView.RECORDING &&
                !viewModel.uiState.value.isRecording
            ) {
                viewModel.beginStartCountdown()
            }
            startCountdownAfterPermission = false
            if (pendingNotificationAfterPermission) {
                pendingNotificationAfterPermission = false
                requestNotificationPermissionIfNeeded()
            }
            syncPrewarmLocationSubscription()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 无额外逻辑；用户拒绝后仍尝试 startForeground（部分机型会降级）
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecordingNotificationHelper.ensureChannel(this)
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
        syncLocationPrecision()

        setContent {
            VeloTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(state.startCountdownSeconds) {
                    syncPrewarmLocationSubscription()
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
                    onToggleDebugLog = { viewModel.toggleDebugLog() },
                    onSaveDebugLog = { viewModel.saveDebugLog() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableAdaptiveHighRefreshRate()
        syncKeepScreenOn()
        syncPrewarmLocationSubscription()
        viewModel.syncRecordingUi()
    }

    override fun onPause() {
        super.onPause()
        val state = viewModel.uiState.value
        if (!state.isRecording) {
            viewModel.cancelStartCountdown()
        }
        setKeepScreenOn(false)
        if (!state.isRecording) {
            prewarmLocationTracker.stop()
        }
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

    private fun dispatchPrewarmLocation(point: GpsPoint) {
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

    private fun syncLocationPrecision() {
        viewModel.setLocationPrecision(hasFineLocationPermission())
    }

    private fun hasLocationPermission(): Boolean =
        hasFineLocationPermission() || hasCoarseLocationPermission()

    private fun locationPermissionSnapshot(): LocationPermissionSnapshot =
        LocationPermissionSnapshot(
            fine = hasFineLocationPermission(),
            coarse = hasCoarseLocationPermission(),
        )

    private fun hasFineLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasCoarseLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasNotificationPermission()) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestStartCountdown() {
        val state = viewModel.uiState.value
        if (state.isRecording || state.startCountdownSeconds != null) return
        if (!hasLocationPermission()) {
            startCountdownAfterPermission = true
            pendingNotificationAfterPermission = true
            requestLocationPermissions()
            return
        }
        if (!hasNotificationPermission()) {
            requestNotificationPermissionIfNeeded()
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

    private fun syncPrewarmLocationSubscription() {
        val state = viewModel.uiState.value
        val shouldPrewarm = state.startCountdownSeconds != null && !state.isRecording
        if (shouldPrewarm && !hasLocationPermission()) {
            requestLocationPermissions()
            return
        }
        if (shouldPrewarm && hasLocationPermission()) {
            prewarmLocationTracker.start(precise = hasFineLocationPermission())
        } else if (!state.isRecording) {
            prewarmLocationTracker.stop()
        }
    }
}
