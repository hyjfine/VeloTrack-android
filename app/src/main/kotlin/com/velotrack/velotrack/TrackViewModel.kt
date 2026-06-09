package com.velotrack.velotrack

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.velotrack.velotrack.debug.DebugLogExporter
import com.velotrack.velotrack.debug.DebugLogRecorder
import com.velotrack.velotrack.recording.RecordingSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class TrackUiState(
    val view: AppView = AppView.RECORDING,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val startCountdownSeconds: Int? = null,
    val isHolding: Boolean = false,
    val holdVersion: Int = 0,
    val signalLost: Boolean = false,
    val trackPausedForSignal: Boolean = false,
    val consecutiveGoodGpsCount: Int = 0,
    val consecutiveBadGpsCount: Int = 0,
    val elapsedMs: Long = 0L,
    val mapCenterLat: Double = 31.2304,
    val mapCenterLng: Double = 121.4737,
    val livePoints: List<GpsPoint> = emptyList(),
    val currentSpeedMps: Double = 0.0,
    val currentAltitude: Double? = null,
    val history: List<Ride> = emptyList(),
    val selectedRide: Ride? = null,
    val pendingDeleteRideId: String? = null,
    val aiAnalysis: String? = null,
    val isAnalysing: Boolean = false,
    val errorMessage: String? = null,
    val lastLocationAtMs: Long? = null,
    val lastLocationAccuracyM: Double? = null,
    val lastLocationCountedInTrack: Boolean = false,
    val lastLocationDropReason: String? = null,
    val locationDebugMessage: String? = null,
    val lastRawSpeedMps: Double? = null,
    val lastDerivedSpeedMps: Double? = null,
    val lastSpeedSource: String? = null,
    val lastSpeedMethod: String? = null,
    val lastDopplerWeight: Double? = null,
    val lastSpeedAccuracyMps: Double? = null,
    val lastSegmentDtMs: Long? = null,
    val lastSegmentCount: Int? = null,
    val gnss: GnssSatelliteSnapshot? = null,
    val debugLogRecording: Boolean = false,
    val debugLogLineCount: Int = 0,
    val debugLogStatus: String? = null,
)

class TrackViewModel(
    application: Application,
    private val repo: RideRepository,
) : AndroidViewModel(application) {

    private val recording = VeloApp.instance.recordingManager

    private val _uiState = MutableStateFlow(TrackUiState())
    val uiState: StateFlow<TrackUiState> = _uiState

    private var startCountdownJob: Job? = null
    var hasFineLocation: Boolean = true
        private set

    init {
        DebugLogRecorder.onStateChanged = ::syncDebugLogUi
        loadHistory()
        viewModelScope.launch {
            recording.state.collect { session -> mergeRecordingSession(session) }
        }
        viewModelScope.launch {
            recording.stopEvents.collect { ride -> applyRideStopped(ride) }
        }
    }

    fun setLocationPrecision(fine: Boolean) {
        hasFineLocation = fine
    }

    /** 从后台回到前台时强制同步录制会话到 UI（地图轨迹等）。 */
    fun syncRecordingUi() {
        if (recording.state.value.isRecording) {
            mergeRecordingSession(recording.state.value)
        }
    }

    fun setView(view: AppView) {
        if (view != AppView.RECORDING) {
            cancelStartCountdown()
        }
        _uiState.update { it.copy(view = view) }
    }

    fun beginStartCountdown() {
        val s = _uiState.value
        if (s.isRecording || s.startCountdownSeconds != null) return
        startCountdownJob?.cancel()
        startCountdownJob = viewModelScope.launch {
            for (seconds in START_COUNTDOWN_SECONDS downTo 1) {
                _uiState.update {
                    it.copy(
                        startCountdownSeconds = seconds,
                        isPaused = false,
                        isHolding = false,
                        elapsedMs = 0L,
                        currentSpeedMps = 0.0,
                        signalLost = false,
                        errorMessage = null,
                    )
                }
                delay(1000)
            }
            startCountdownJob = null
            startRecordingNow()
        }
    }

    fun cancelStartCountdown() {
        startCountdownJob?.cancel()
        startCountdownJob = null
        if (_uiState.value.startCountdownSeconds != null) {
            _uiState.update { it.copy(startCountdownSeconds = null, isHolding = false) }
        }
    }

    fun startRecording() {
        beginStartCountdown()
    }

    private fun startRecordingNow() {
        if (_uiState.value.isRecording) return
        _uiState.update {
            it.copy(
                startCountdownSeconds = null,
                isHolding = false,
                aiAnalysis = null,
                errorMessage = null,
            )
        }
        recording.startRecording(hasFineLocation)
    }

    fun togglePause() {
        if (!_uiState.value.isRecording || _uiState.value.startCountdownSeconds != null) return
        recording.togglePause(hasFineLocation)
    }

    fun stopRecording() {
        val s = _uiState.value
        if (!s.isRecording) {
            cancelStartCountdown()
            return
        }
        recording.stopRecording { }
    }

    private fun applyRideStopped(ride: Ride) {
        viewModelScope.launch(Dispatchers.IO) {
            val refreshed = repo.listRides()
            _uiState.update {
                it.copy(
                    isRecording = false,
                    isPaused = false,
                    startCountdownSeconds = null,
                    isHolding = false,
                    elapsedMs = 0L,
                    livePoints = emptyList(),
                    currentSpeedMps = 0.0,
                    currentAltitude = null,
                    history = refreshed,
                    selectedRide = ride,
                    view = AppView.DETAIL,
                    aiAnalysis = null,
                    errorMessage = null,
                )
            }
        }
    }

    fun beginHold() {
        if (!_uiState.value.isRecording) return
        _uiState.update { it.copy(isHolding = true, holdVersion = it.holdVersion + 1) }
    }

    fun endHold() {
        _uiState.update { it.copy(isHolding = false) }
    }

    /** 倒计时预热阶段：由 Activity 的 LocationTracker 回调。 */
    fun onLocation(point: GpsPoint) {
        if (_uiState.value.isRecording) return
        onLocationForMapPreview(point)
    }

    fun onGnssStatus(snapshot: GnssSatelliteSnapshot) {
        if (_uiState.value.isRecording) return
        _uiState.update { it.copy(gnss = snapshot) }
    }

    fun restoreLastLocation(point: GpsPoint) {
        if (point.accuracy > MAP_LOCATION_MAX_ACCURACY_M) return
        _uiState.update { s ->
            if (s.isRecording) {
                s
            } else {
                s.copy(
                    mapCenterLat = point.lat,
                    mapCenterLng = point.lng,
                    currentAltitude = point.altitude,
                    lastLocationAtMs = point.timestamp,
                    lastLocationAccuracyM = point.accuracy,
                    lastLocationCountedInTrack = false,
                    lastLocationDropReason = "restored cache",
                )
            }
        }
    }

    fun onLocationDebug(message: String) {
        if (_uiState.value.isRecording) return
        if (BuildConfig.DEBUG) {
            DebugLogRecorder.append("LOC", message)
        }
        _uiState.update { it.copy(locationDebugMessage = message) }
    }

    fun toggleDebugLog() {
        if (!BuildConfig.DEBUG) return
        val recording = DebugLogRecorder.toggle()
        _uiState.update {
            it.copy(
                debugLogRecording = recording,
                debugLogLineCount = DebugLogRecorder.lineCount,
                debugLogStatus = if (recording) "recording..." else "stopped",
            )
        }
    }

    fun saveDebugLog() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = DebugLogExporter.save(getApplication(), DebugLogRecorder.snapshot())
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        debugLogLineCount = DebugLogRecorder.lineCount,
                        debugLogStatus = result.statusText,
                    )
                }
            }
        }
    }

    private fun syncDebugLogUi() {
        _uiState.update {
            it.copy(
                debugLogRecording = DebugLogRecorder.isRecording,
                debugLogLineCount = DebugLogRecorder.lineCount,
            )
        }
    }

    fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val rides = repo.listRides()
            _uiState.update { it.copy(history = rides) }
        }
    }

    fun openRide(ride: Ride) {
        cancelStartCountdown()
        _uiState.update { it.copy(selectedRide = ride, view = AppView.DETAIL, aiAnalysis = null) }
    }

    fun backFromDetail() {
        cancelStartCountdown()
        _uiState.update { it.copy(view = AppView.HISTORY, selectedRide = null, aiAnalysis = null) }
    }

    fun requestDeleteRide(id: String) {
        _uiState.update { it.copy(pendingDeleteRideId = id) }
    }

    fun cancelDeleteRide() {
        _uiState.update { it.copy(pendingDeleteRideId = null) }
    }

    fun confirmDeleteRide() {
        val id = _uiState.value.pendingDeleteRideId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repo.deleteRide(id)
            val rides = repo.listRides()
            _uiState.update { it.copy(history = rides, pendingDeleteRideId = null) }
        }
    }

    fun runAnalysis() {
        val ride = _uiState.value.selectedRide ?: return
        val requestId = UUID.randomUUID().toString().take(8)
        val prompt = buildPrompt(ride)
        Log.d(
            AI_LOG_TAG,
            "analysis start requestId=$requestId rideId=${ride.id} points=${ride.points.size} " +
                "distanceBucket=${distanceBucket(ride.totalDistance)} durationBucket=${durationBucket(ride)} " +
                "hasEnd=${ride.endTime != null} apiKeyConfigured=${BuildConfig.GEMINI_API_KEY.isNotBlank()} " +
                "promptChars=${prompt.length}",
        )
        _uiState.update { it.copy(isAnalysing = true, aiAnalysis = null, errorMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            runCatching {
                GeminiClient.generateContent(BuildConfig.GEMINI_API_KEY, prompt, requestId)
            }.onSuccess { text ->
                Log.d(
                    AI_LOG_TAG,
                    "analysis success requestId=$requestId elapsedMs=${System.currentTimeMillis() - startedAt} responseChars=${text.length}",
                )
                _uiState.update { it.copy(isAnalysing = false, aiAnalysis = text) }
            }.onFailure { error ->
                Log.w(
                    AI_LOG_TAG,
                    "analysis failed requestId=$requestId elapsedMs=${System.currentTimeMillis() - startedAt} " +
                        "reason=${(error as? GeminiClient.GeminiProxyException)?.reason ?: "unknown"} " +
                        "type=${error::class.java.simpleName}",
                    error,
                )
                _uiState.update {
                    it.copy(
                        isAnalysing = false,
                        errorMessage = analysisErrorMessage(error),
                    )
                }
            }
        }
    }

    private fun mergeRecordingSession(session: RecordingSessionState) {
        _uiState.update { ui ->
            ui.copy(
                isRecording = session.isRecording,
                isPaused = session.isPaused,
                elapsedMs = session.elapsedMs,
                livePoints = session.livePoints,
                currentSpeedMps = session.currentSpeedMps,
                mapCenterLat = session.mapCenterLat,
                mapCenterLng = session.mapCenterLng,
                currentAltitude = session.currentAltitude,
                signalLost = session.signalLost,
                trackPausedForSignal = session.trackPausedForSignal,
                consecutiveGoodGpsCount = session.consecutiveGoodGpsCount,
                consecutiveBadGpsCount = session.consecutiveBadGpsCount,
                lastLocationAtMs = session.lastLocationAtMs,
                lastLocationAccuracyM = session.lastLocationAccuracyM,
                lastLocationCountedInTrack = session.lastLocationCountedInTrack,
                lastLocationDropReason = session.lastLocationDropReason,
                locationDebugMessage = session.locationDebugMessage,
                lastRawSpeedMps = session.lastRawSpeedMps,
                lastDerivedSpeedMps = session.lastDerivedSpeedMps,
                lastSpeedSource = session.lastSpeedSource,
                lastSpeedMethod = session.lastSpeedMethod,
                lastDopplerWeight = session.lastDopplerWeight,
                lastSpeedAccuracyMps = session.lastSpeedAccuracyMps,
                lastSegmentDtMs = session.lastSegmentDtMs,
                lastSegmentCount = session.lastSegmentCount,
                gnss = session.gnss,
            )
        }
    }

    private fun onLocationForMapPreview(point: GpsPoint) {
        if (point.accuracy > MAP_LOCATION_MAX_ACCURACY_M) return
        val rawSpeed = point.speedMps
        val speedSourceLabel = point.source.label + if (point.isGpsFix) "*" else ""
        _uiState.update {
            it.copy(
                mapCenterLat = point.lat,
                mapCenterLng = point.lng,
                currentAltitude = point.altitude,
                lastLocationAtMs = point.timestamp,
                lastLocationAccuracyM = point.accuracy,
                lastLocationCountedInTrack = false,
                lastLocationDropReason = "prewarm",
                lastRawSpeedMps = rawSpeed,
                lastSpeedSource = speedSourceLabel,
            )
        }
    }

    private fun distanceBucket(meters: Double): String = when {
        meters < 1_000.0 -> "<1km"
        meters < 5_000.0 -> "1-5km"
        meters < 20_000.0 -> "5-20km"
        else -> ">=20km"
    }

    private fun durationBucket(ride: Ride): String {
        val durationMs = ((ride.endTime ?: ride.startTime) - ride.startTime).coerceAtLeast(0L)
        val minutes = durationMs / 60_000L
        return when {
            minutes < 5 -> "<5m"
            minutes < 30 -> "5-30m"
            minutes < 120 -> "30-120m"
            else -> ">=120m"
        }
    }

    private fun analysisErrorMessage(error: Throwable): String =
        when ((error as? GeminiClient.GeminiProxyException)?.reason) {
            GeminiClient.GeminiProxyException.Reason.MissingApiKey -> "AI 服务未配置，请稍后再试"
            GeminiClient.GeminiProxyException.Reason.RateLimited -> "AI 请求过于频繁，请稍后再试"
            GeminiClient.GeminiProxyException.Reason.Network -> "网络连接异常，请稍后重试"
            GeminiClient.GeminiProxyException.Reason.ServerRejected,
            GeminiClient.GeminiProxyException.Reason.EmptyResponse,
            null,
            -> "分析失败，请稍后重试"
        }

    private fun buildPrompt(ride: Ride): String = """
        Analyze this cycling ride and provide professional coaching advice.
        Distance: ${formatDistanceMeters(ride.totalDistance)}
        Duration: ${formatDurationMs((ride.endTime ?: ride.startTime) - ride.startTime)}
        Avg Speed: ${formatSpeedKmh(ride.avgSpeed)} km/h
        Max Speed: ${formatSpeedKmh(ride.maxSpeed)} km/h
        Data samples: ${ride.points.size}
        Please reply in concise Chinese using simple Markdown only:
        ## 表现总结
        - one short bullet about overall performance
        ## 改进建议
        - one practical coaching tip
        ## 鼓励
        - one motivational sentence
        Do not use tables, code blocks, links, or long paragraphs.
    """.trimIndent()

    companion object {
        private const val MAP_LOCATION_MAX_ACCURACY_M = 200.0
        private const val START_COUNTDOWN_SECONDS = 3
        private const val AI_LOG_TAG = "VeloAI"

        fun factory(application: Application, repo: RideRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TrackViewModel(application, repo) as T
            }
    }
}

fun formatDistanceMeters(meters: Double): String =
    if (meters < 1000) "${meters.toInt()}m" else String.format(Locale.US, "%.2fkm", meters / 1000.0)

fun formatSpeedKmh(mps: Double): String = String.format(Locale.US, "%.1f", mps * 3.6)

fun formatDurationMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
