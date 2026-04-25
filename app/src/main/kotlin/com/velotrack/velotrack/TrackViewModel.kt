package com.velotrack.velotrack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class TrackUiState(
    val view: AppView = AppView.RECORDING,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val isHolding: Boolean = false,
    val holdVersion: Int = 0,
    val signalLost: Boolean = false,
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
)

class TrackViewModel(
    private val repo: RideRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TrackUiState())
    val uiState: StateFlow<TrackUiState> = _uiState

    private var elapsedTicker: Job? = null
    private var recordingStartAt = 0L
    private var accumulatedElapsed = 0L

    init {
        loadHistory()
    }

    fun setView(view: AppView) {
        _uiState.update { it.copy(view = view) }
    }

    fun startRecording() {
        if (_uiState.value.isRecording) return
        val now = System.currentTimeMillis()
        recordingStartAt = now
        accumulatedElapsed = 0L
        _uiState.update {
            it.copy(
                isRecording = true,
                isPaused = false,
                elapsedMs = 0L,
                livePoints = emptyList(),
                currentSpeedMps = 0.0,
                currentAltitude = null,
                signalLost = false,
                aiAnalysis = null,
                errorMessage = null,
            )
        }
        startTicker()
    }

    fun togglePause() {
        val s = _uiState.value
        if (!s.isRecording) return
        if (!s.isPaused) {
            accumulatedElapsed += (System.currentTimeMillis() - recordingStartAt)
            elapsedTicker?.cancel()
            _uiState.update { it.copy(isPaused = true, currentSpeedMps = 0.0) }
        } else {
            recordingStartAt = System.currentTimeMillis()
            _uiState.update { it.copy(isPaused = false) }
            startTicker()
        }
    }

    fun stopRecording() {
        val s = _uiState.value
        if (!s.isRecording) return
        if (!s.isPaused) {
            accumulatedElapsed += (System.currentTimeMillis() - recordingStartAt)
        }
        elapsedTicker?.cancel()

        val points = s.livePoints
        val totalDistance = points.zipWithNext()
            .sumOf { (a, b) -> GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng) }
        val moving = points.map { it.speedMps }.filter { it > 0.3 }
        val avgSpeed = if (moving.isEmpty()) 0.0 else moving.average()
        val maxSpeed = if (moving.isEmpty()) 0.0 else moving.max()
        val start = if (points.isEmpty()) System.currentTimeMillis() - accumulatedElapsed else points.first().timestamp
        val end = if (points.isEmpty()) System.currentTimeMillis() else points.last().timestamp
        val ride = Ride(
            id = System.currentTimeMillis().toString(),
            title = "Ride on ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}",
            startTime = start,
            endTime = end,
            points = points,
            totalDistance = totalDistance,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
        )
        viewModelScope.launch(Dispatchers.IO) {
            repo.saveRide(ride)
            val refreshed = repo.listRides()
            _uiState.update {
                it.copy(
                    isRecording = false,
                    isPaused = false,
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
        _uiState.update { it.copy(isHolding = true, holdVersion = it.holdVersion + 1) }
    }

    fun endHold() {
        _uiState.update { it.copy(isHolding = false) }
    }

    fun onLocation(point: GpsPoint) {
        if (point.accuracy > 40.0) return
        _uiState.update { s ->
            if (!s.isRecording || s.isPaused) return@update s
            val points = s.livePoints + point
            s.copy(
                livePoints = points,
                mapCenterLat = point.lat,
                mapCenterLng = point.lng,
                currentSpeedMps = max(0.0, point.speedMps),
                currentAltitude = point.altitude,
                signalLost = point.accuracy > 25.0,
            )
        }
    }

    fun restoreLastLocation(point: GpsPoint) {
        if (point.accuracy > 40.0) return
        _uiState.update { s ->
            if (s.isRecording) {
                s
            } else {
                s.copy(
                    mapCenterLat = point.lat,
                    mapCenterLng = point.lng,
                    currentAltitude = point.altitude,
                )
            }
        }
    }

    fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val rides = repo.listRides()
            _uiState.update { it.copy(history = rides) }
        }
    }

    fun openRide(ride: Ride) {
        _uiState.update { it.copy(selectedRide = ride, view = AppView.DETAIL, aiAnalysis = null) }
    }

    fun backFromDetail() {
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
        _uiState.update { it.copy(isAnalysing = true, aiAnalysis = null, errorMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                GeminiClient.generateContent(BuildConfig.GEMINI_API_KEY, buildPrompt(ride))
            }.onSuccess { text ->
                _uiState.update { it.copy(isAnalysing = false, aiAnalysis = text) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isAnalysing = false,
                        errorMessage = analysisErrorMessage(error),
                    )
                }
            }
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

    private fun startTicker() {
        elapsedTicker?.cancel()
        elapsedTicker = viewModelScope.launch {
            while (true) {
                val elapsed = accumulatedElapsed + (System.currentTimeMillis() - recordingStartAt)
                _uiState.update { it.copy(elapsedMs = elapsed) }
                delay(1000)
            }
        }
    }

    private fun buildPrompt(ride: Ride): String = """
        Analyze this cycling ride and provide professional coaching advice.
        Distance: ${formatDistanceMeters(ride.totalDistance)}
        Duration: ${formatDurationMs((ride.endTime ?: ride.startTime) - ride.startTime)}
        Avg Speed: ${formatSpeedKmh(ride.avgSpeed)} km/h
        Max Speed: ${formatSpeedKmh(ride.maxSpeed)} km/h
        Data samples: ${ride.points.size}
        Please give a concise analysis in Chinese (suitable for mobile display), including:
        1. Performance summary
        2. One area for improvement
        3. A motivational remark.
    """.trimIndent()

    companion object {
        fun factory(repo: RideRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = TrackViewModel(repo) as T
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
