package com.velotrack.velotrack.recording

import com.velotrack.velotrack.GnssSatelliteSnapshot
import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.Ride
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Commands are called on [RecordingDependencies.mainDispatcher], as are all state transitions. */
interface RecordingController {
    val state: StateFlow<RecordingSessionState>
    val stopEvents: SharedFlow<Ride>
    fun startRecording(hasFineLocation: Boolean)
    fun togglePause(hasFineLocation: Boolean)
    fun stopRecording(onComplete: (Ride?) -> Unit)
}

interface RecordingTimeSource {
    fun currentTimeMillis(): Long
    fun elapsedRealtime(): Long
}

data class RecordingLocationCallbacks(
    val onLocation: (GpsPoint) -> Unit,
    val onDebug: (String) -> Unit,
    val onGnss: (GnssSatelliteSnapshot) -> Unit,
    val onError: (String) -> Unit,
)

interface RecordingLocationSource {
    fun start(precise: Boolean)
    fun stop()
}

fun interface RecordingLocationFactory {
    fun create(callbacks: RecordingLocationCallbacks): RecordingLocationSource
}

interface RecordingServiceController {
    fun start()
    fun stop()
    fun update(state: RecordingSessionState)
}

interface RecordingDiagnostics {
    fun error(message: String, error: Throwable) {}
    fun location(message: String) {}
    fun processed(state: RecordingSessionState, accepted: Boolean) {}
    fun gnss(snapshot: GnssSatelliteSnapshot) {}
}

/** Platform effects live at the application boundary; the state machine also runs on the JVM. */
data class RecordingDependencies(
    val clock: RecordingTimeSource,
    val locationFactory: RecordingLocationFactory,
    val foregroundService: RecordingServiceController,
    val hasFineLocationPermission: () -> Boolean,
    val cacheLocation: (GpsPoint) -> Unit,
    val mainDispatcher: CoroutineDispatcher,
    val ioDispatcher: CoroutineDispatcher,
    val diagnostics: RecordingDiagnostics = object : RecordingDiagnostics {},
)
