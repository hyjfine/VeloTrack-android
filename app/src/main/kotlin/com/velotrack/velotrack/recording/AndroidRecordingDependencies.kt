package com.velotrack.velotrack.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.velotrack.velotrack.BuildConfig
import com.velotrack.velotrack.GnssSatelliteSnapshot
import com.velotrack.velotrack.LastLocationStore
import com.velotrack.velotrack.LocationTracker
import com.velotrack.velotrack.MapProvider
import com.velotrack.velotrack.debug.DebugLogFormats
import com.velotrack.velotrack.debug.DebugLogRecorder
import kotlinx.coroutines.Dispatchers

fun androidRecordingDependencies(context: Context, mapProvider: MapProvider): RecordingDependencies {
    val appContext = context.applicationContext
    val lastLocationStore = LastLocationStore(appContext)
    return RecordingDependencies(
        clock = object : RecordingTimeSource {
            override fun currentTimeMillis() = System.currentTimeMillis()
            override fun elapsedRealtime() = SystemClock.elapsedRealtime()
        },
        locationFactory = RecordingLocationFactory { callbacks ->
            val tracker = LocationTracker(appContext, mapProvider, callbacks.onLocation,
                callbacks.onDebug, callbacks.onGnss, callbacks.onError)
            object : RecordingLocationSource {
                override fun start(precise: Boolean) = tracker.start(precise, recordingMode = true)
                override fun stop() = tracker.stop()
            }
        },
        foregroundService = object : RecordingServiceController {
            override fun start() {
                appContext.startForegroundService(Intent(appContext, RecordingForegroundService::class.java).apply {
                    action = RecordingForegroundService.ACTION_START
                })
            }
            // Services are matched by Intent component, not listener/SAM object identity.
            @Suppress("ImplicitSamInstance")
            override fun stop() {
                appContext.stopService(Intent(appContext, RecordingForegroundService::class.java))
            }
            override fun update(state: RecordingSessionState) {
                RecordingNotificationHelper.updateNotification(appContext, state)
            }
        },
        hasFineLocationPermission = {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        },
        cacheLocation = lastLocationStore::write,
        mainDispatcher = Dispatchers.Main.immediate,
        ioDispatcher = Dispatchers.IO,
        diagnostics = object : RecordingDiagnostics {
            override fun error(message: String, error: Throwable) { Log.e("VeloRecording", message, error) }
            override fun location(message: String) {
                if (BuildConfig.DEBUG) DebugLogRecorder.append("LOC", message)
            }
            override fun processed(state: RecordingSessionState, accepted: Boolean) {
                if (BuildConfig.DEBUG && DebugLogRecorder.isRecording) {
                    DebugLogRecorder.append("PROC", DebugLogFormats.procLine(state, accepted))
                }
            }
            override fun gnss(snapshot: GnssSatelliteSnapshot) {
                if (BuildConfig.DEBUG && DebugLogRecorder.isRecording) {
                    DebugLogRecorder.append("GNSS", DebugLogFormats.gnssLine(snapshot))
                }
            }
        },
    )
}
