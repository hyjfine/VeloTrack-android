package com.velotrack.velotrack

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velotrack.velotrack.recording.RecordingController
import com.velotrack.velotrack.recording.RecordingSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

@Immutable
data class TrackUiState(
    val view: AppView = AppView.RECORDING,
    val isRestoringRecording: Boolean = false,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val isSavingRide: Boolean = false,
    val locationPermissionDenied: Boolean = false,
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
    val displayPoints: List<GpsPoint> = emptyList(),
    val mapPoints: List<GpsPoint> = emptyList(),
    val liveDistanceM: Double = 0.0,
    val liveSpikePointCount: Int = 0,
    val currentSpeedMps: Double = 0.0,
    val currentAltitude: Double? = null,
    val history: List<Ride> = emptyList(),
    val historyError: String? = null,
    val isHistoryLoading: Boolean = false,
    val isDetailLoading: Boolean = false,
    val detailError: String? = null,
    val selectedRide: Ride? = null,
    val pendingDeleteRideId: String? = null,
    val isDeletingRide: Boolean = false,
    val deleteRideError: String? = null,
    val aiAnalysis: String? = null,
    val isAnalysing: Boolean = false,
    val errorMessage: String? = null,
    val recordingErrorMessage: String? = null,
    val recordingIssue: com.velotrack.velotrack.recording.RecordingIssue? = null,
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
    private val repo: RideHistoryStore,
    private val recording: RecordingController,
    private val historyMaintenance: HistoryMaintenance,
    private val analysisClient: RideAnalysisClient,
    private val debugLogs: TrackDebugLogs,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrackUiState())
    val uiState: StateFlow<TrackUiState> = _uiState

    private var startCountdownJob: Job? = null
    private var analysisJob: Job? = null
    private var detailLoadJob: Job? = null
    private var historyLoadJob: Job? = null
    private var detailRequestVersion = 0L
    private var historyRequestVersion = 0L
    private var loadingRideId: String? = null
    private var failedDetailRide: Ride? = null
    private var activeAnalysisRequestId: String? = null
    private val analysisCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 32
    }
    var hasFineLocation: Boolean = true
        private set

    init {
        viewModelScope.launch {
            debugLogs.state.collect { log ->
                _uiState.update { it.copy(debugLogRecording = log.isRecording, debugLogLineCount = log.lineCount) }
            }
        }
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
        if (fine) {
            _uiState.update { it.copy(locationPermissionDenied = false) }
        }
    }

    fun setLocationPermissionDenied(denied: Boolean) {
        _uiState.update { it.copy(locationPermissionDenied = denied) }
    }

    /** 从后台回到前台时强制同步录制会话到 UI（地图轨迹等）。 */
    fun syncRecordingUi() {
        if (recording.state.value.isRecording) {
            mergeRecordingSession(recording.state.value)
        }
    }

    fun setView(view: AppView) {
        cancelDetailLoad()
        if (view != AppView.DETAIL) cancelAnalysis()
        if (view != AppView.RECORDING) {
            cancelStartCountdown()
        }
        _uiState.update {
            it.copy(view = view, selectedRide = if (view == AppView.DETAIL) it.selectedRide else null,
                aiAnalysis = if (view == AppView.DETAIL) it.aiAnalysis else null)
        }
    }

    fun beginStartCountdown() {
        val s = _uiState.value
        if (s.isRecording || s.isRestoringRecording || s.startCountdownSeconds != null) return
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
        cancelDetailLoad()
        cancelAnalysis()
        _uiState.update {
            it.copy(isRecording = false, isPaused = false, startCountdownSeconds = null,
                isHolding = false, elapsedMs = 0L, livePoints = emptyList(), currentSpeedMps = 0.0,
                selectedRide = ride, view = AppView.DETAIL, aiAnalysis = null, errorMessage = null)
        }
        loadHistory()
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
            debugLogs.appendLocation(message)
        }
        _uiState.update { it.copy(locationDebugMessage = message) }
    }

    fun toggleDebugLog() {
        if (!BuildConfig.DEBUG) return
        debugLogs.toggle()
        _uiState.update {
            it.copy(debugLogStatus = if (debugLogs.state.value.isRecording) "recording..." else "stopped")
        }
    }

    fun saveDebugLog() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            val status = withContext(ioDispatcher) { debugLogs.save() }
            _uiState.update { it.copy(debugLogStatus = status) }
        }
    }

    fun loadHistory() {
        historyLoadJob?.cancel()
        val request = ++historyRequestVersion
        _uiState.update { it.copy(isHistoryLoading = true, historyError = null) }
        historyLoadJob = viewModelScope.launch {
            try {
                val rides = withContext(ioDispatcher) {
                    historyMaintenance.ensureCurrent()
                    repo.listRides()
                }
                ensureActive()
                if (request != historyRequestVersion) return@launch
                _uiState.update { it.copy(history = rides, isHistoryLoading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request != historyRequestVersion) return@launch
                Log.e("VeloDB", "history read failed", error)
                _uiState.update { it.copy(isHistoryLoading = false, historyError = "读取骑行记录失败，请重试") }
            }
        }
    }

    fun retryHistoryLoad() {
        failedDetailRide?.let(::openRide) ?: loadHistory()
    }

    private fun cancelDetailLoad() {
        failedDetailRide = null
        loadingRideId = null
        detailRequestVersion++
        detailLoadJob?.cancel()
        detailLoadJob = null
        _uiState.update { it.copy(isDetailLoading = false, detailError = null) }
    }

    fun openRide(ride: Ride) {
        cancelStartCountdown()
        cancelAnalysis()
        cancelDetailLoad()
        loadingRideId = ride.id
        val request = detailRequestVersion
        _uiState.update { it.copy(isDetailLoading = true, detailError = null) }
        detailLoadJob = viewModelScope.launch {
            try {
                val fullRide = withContext(ioDispatcher) { repo.getRide(ride.id) }
                    ?: error("Ride no longer exists")
                ensureActive()
                if (request != detailRequestVersion) return@launch
                loadingRideId = null
                _uiState.update {
                    it.copy(selectedRide = fullRide, view = AppView.DETAIL,
                        aiAnalysis = analysisCache[fullRide.id], isAnalysing = false,
                        isDetailLoading = false, errorMessage = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request != detailRequestVersion) return@launch
                Log.e("VeloDB", "detail read failed", error)
                loadingRideId = null
                failedDetailRide = ride
                _uiState.update { it.copy(isDetailLoading = false, detailError = "读取这条骑行失败，请重试") }
            }
        }
    }

    fun backFromDetail() {
        cancelStartCountdown()
        cancelDetailLoad()
        cancelAnalysis()
        _uiState.update {
            it.copy(view = AppView.HISTORY, selectedRide = null, aiAnalysis = null, isAnalysing = false)
        }
    }

    fun requestDeleteRide(id: String) {
        if (_uiState.value.isDeletingRide) return
        _uiState.update { it.copy(pendingDeleteRideId = id, deleteRideError = null) }
    }

    fun cancelDeleteRide() {
        if (_uiState.value.isDeletingRide) return
        _uiState.update { it.copy(pendingDeleteRideId = null, deleteRideError = null) }
    }

    fun confirmDeleteRide() {
        val id = _uiState.value.pendingDeleteRideId ?: return
        if (_uiState.value.isDeletingRide) return
        _uiState.update { it.copy(isDeletingRide = true, deleteRideError = null) }
        // A history snapshot captured before deletion must not restore the removed row.
        historyLoadJob?.cancel()
        historyRequestVersion++
        _uiState.update { it.copy(isHistoryLoading = false) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { repo.deleteRide(id) }
                analysisCache.remove(id)
                if (loadingRideId == id || failedDetailRide?.id == id) cancelDetailLoad()
                if (_uiState.value.selectedRide?.id == id) {
                    cancelAnalysis()
                    _uiState.update {
                        it.copy(selectedRide = null, aiAnalysis = null, errorMessage = null,
                            view = if (it.view == AppView.DETAIL) AppView.HISTORY else it.view)
                    }
                }
                _uiState.update {
                    it.copy(history = it.history.filterNot { ride -> ride.id == id },
                        pendingDeleteRideId = null, isDeletingRide = false, deleteRideError = null)
                }
                // Deletion is committed even if this independent refresh subsequently fails.
                loadHistory()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("VeloDB", "delete ride failed id=$id", error)
                _uiState.update { it.copy(isDeletingRide = false, deleteRideError = "删除失败，请重试") }
            }
        }
    }

    fun runAnalysis() {
        val state = _uiState.value
        if (state.view != AppView.DETAIL || state.isDetailLoading || state.isAnalysing) return
        val ride = _uiState.value.selectedRide ?: return
        val requestId = UUID.randomUUID().toString().take(8)
        activeAnalysisRequestId = requestId
        val prompt = buildPrompt(ride)
        Log.d(
            AI_LOG_TAG,
            "analysis start requestId=$requestId rideId=${ride.id} points=${ride.points.size} " +
                "distanceBucket=${distanceBucket(ride.totalDistance)} durationBucket=${durationBucket(ride)} " +
                "hasEnd=${ride.endTime != null} apiKeyConfigured=${BuildConfig.GEMINI_API_KEY.isNotBlank()} " +
                "promptChars=${prompt.length}",
        )
        _uiState.update { it.copy(isAnalysing = true, aiAnalysis = null, errorMessage = null) }
        analysisJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            runCatching {
                analysisClient.analyze(prompt, requestId)
            }.onSuccess { text ->
                Log.d(
                    AI_LOG_TAG,
                    "analysis success requestId=$requestId elapsedMs=${System.currentTimeMillis() - startedAt} responseChars=${text.length}",
                )
                if (activeAnalysisRequestId == requestId && _uiState.value.selectedRide?.id == ride.id) {
                    analysisCache[ride.id] = text
                    _uiState.update { it.copy(isAnalysing = false, aiAnalysis = text) }
                }
            }.onFailure { error ->
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                Log.w(
                    AI_LOG_TAG,
                    "analysis failed requestId=$requestId elapsedMs=${System.currentTimeMillis() - startedAt} " +
                        "reason=${(error as? GeminiClient.GeminiProxyException)?.reason ?: "unknown"} " +
                        "type=${error::class.java.simpleName}",
                    error,
                )
                if (activeAnalysisRequestId == requestId && _uiState.value.selectedRide?.id == ride.id) {
                    _uiState.update {
                        it.copy(
                            isAnalysing = false,
                            errorMessage = analysisErrorMessage(error),
                        )
                    }
                }
            }
        }
    }

    private fun cancelAnalysis() {
        activeAnalysisRequestId = null
        analysisJob?.cancel()
        analysisJob = null
        _uiState.update { it.copy(isAnalysing = false) }
    }

    override fun onCleared() {
        activeAnalysisRequestId = null
        detailRequestVersion++
        historyRequestVersion++
        analysisCache.clear()
        super.onCleared()
    }

    private fun mergeRecordingSession(session: RecordingSessionState) {
        _uiState.update { ui ->
            ui.copy(
                isRestoringRecording = session.isRestoring,
                isRecording = session.isRecording,
                isPaused = session.isPaused,
                isSavingRide = session.isSaving,
                elapsedMs = session.elapsedMs,
                livePoints = session.livePoints,
                displayPoints = session.displayPoints,
                mapPoints = session.mapPoints,
                liveDistanceM = session.displayDistanceM,
                liveSpikePointCount = session.spikePointIndices.size,
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
                recordingErrorMessage = session.persistenceError,
                recordingIssue = session.issue,
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
            GeminiClient.GeminiProxyException.Reason.MissingModel -> "请在开发配置中设置可用的 GEMINI_MODEL"
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
        Moving duration: ${formatDurationMs((ride.movingDurationSec * 1000).toLong())}
        Active recording duration: ${ride.activeDurationMs?.let(::formatDurationMs) ?: "unknown"}
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
