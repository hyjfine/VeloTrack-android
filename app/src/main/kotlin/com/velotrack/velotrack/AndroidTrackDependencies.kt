package com.velotrack.velotrack

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.velotrack.velotrack.debug.DebugLogExporter
import com.velotrack.velotrack.debug.DebugLogRecorder
import com.velotrack.velotrack.speed.TrackDataFilter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-scoped so overlapping Activity instances cannot repair the same history at once. */
class AndroidHistoryMaintenance(context: Context, private val store: RideHistoryStore) : HistoryMaintenance {
    private val preferences = context.getSharedPreferences("data_repair", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun ensureCurrent() = mutex.withLock {
        if (preferences.getInt("stats_version", 0) != TrackDataFilter.STATS_VERSION) {
            store.repairHistoricalRides()
            preferences.edit { putInt("stats_version", TrackDataFilter.STATS_VERSION) }
        }
    }
}

object GeminiRideAnalysisClient : RideAnalysisClient {
    override suspend fun analyze(prompt: String, requestId: String): String = GeminiClient.generateContent(
        apiKey = BuildConfig.GEMINI_API_KEY,
        prompt = prompt,
        requestId = requestId,
        proxyUrl = BuildConfig.AI_PROXY_URL,
    )
}

class AndroidTrackDebugLogs(private val context: Context) : TrackDebugLogs {
    override val state = DebugLogRecorder.state

    override fun toggle() { DebugLogRecorder.toggle() }
    override fun appendLocation(message: String) { DebugLogRecorder.append("LOC", message) }
    override fun save(): String = DebugLogExporter.save(context, DebugLogRecorder.snapshot()).statusText
}

class TrackViewModelFactory(private val app: VeloApp) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TrackViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return TrackViewModel(
            repo = app.rideRepository,
            recording = app.recordingManager,
            historyMaintenance = app.historyMaintenance,
            analysisClient = GeminiRideAnalysisClient,
            debugLogs = app.debugLogs,
        ) as T
    }
}
