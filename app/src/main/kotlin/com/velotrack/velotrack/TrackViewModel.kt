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
    val startCountdownSeconds: Int? = null,
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
    val lastLocationAtMs: Long? = null,
    val lastLocationAccuracyM: Double? = null,
    val lastLocationCountedInTrack: Boolean = false,
    val lastLocationDropReason: String? = null,
    val locationDebugMessage: String? = null,
    /** 最近一次原始速度（m/s），不经平滑/门限，仅用于 debug 校核。 */
    val lastRawSpeedMps: Double? = null,
    /** 最近一次速度来源标签，用于 debug。 */
    val lastSpeedSource: String? = null,
    /** 最近一次 GNSS 卫星快照。 */
    val gnss: GnssSatelliteSnapshot? = null,
)

class TrackViewModel(
    private val repo: RideRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TrackUiState())
    val uiState: StateFlow<TrackUiState> = _uiState

    private var elapsedTicker: Job? = null
    private var startCountdownJob: Job? = null
    private var recordingStartAt = 0L
    private var accumulatedElapsed = 0L

    init {
        loadHistory()
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
        val now = System.currentTimeMillis()
        recordingStartAt = now
        accumulatedElapsed = 0L
        _uiState.update {
            it.copy(
                isRecording = true,
                isPaused = false,
                startCountdownSeconds = null,
                isHolding = false,
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
        if (!s.isRecording || s.startCountdownSeconds != null) return
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
        if (!s.isRecording) {
            cancelStartCountdown()
            return
        }
        if (!s.isPaused) {
            accumulatedElapsed += (System.currentTimeMillis() - recordingStartAt)
        }
        elapsedTicker?.cancel()

        val points = s.livePoints
        val totalDistance = points.zipWithNext()
            .sumOf { (a, b) -> GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng) }
        // 平均速度使用“运动距离 / 运动时长”，更贴近主流运动 App 口径。
        val movingTimeSec = movingDurationSeconds(points)
        val avgSpeed = if (movingTimeSec > 0.0) totalDistance / movingTimeSec else 0.0
        // 最大速度使用滑动窗口均值的最大值，剔除瞬时尖峰。
        val maxSpeed = slidingWindowMaxSpeed(points, MAX_SPEED_WINDOW)
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

    fun onLocation(point: GpsPoint) {
        val rawSpeed = point.speedMps
        val speedSourceLabel = point.source.label + if (point.isGpsFix) "*" else ""
        if (point.accuracy > MAP_LOCATION_MAX_ACCURACY_M) {
            _uiState.update {
                it.copy(
                    lastLocationAtMs = point.timestamp,
                    lastLocationAccuracyM = point.accuracy,
                    lastLocationCountedInTrack = false,
                    lastLocationDropReason = "accuracy>${MAP_LOCATION_MAX_ACCURACY_M.toInt()}m",
                    lastRawSpeedMps = rawSpeed,
                    lastSpeedSource = speedSourceLabel,
                )
            }
            return
        }
        _uiState.update { s ->
            if (!s.isRecording || s.isPaused) {
                return@update s.copy(
                    lastLocationAtMs = point.timestamp,
                    lastLocationAccuracyM = point.accuracy,
                    lastLocationCountedInTrack = false,
                    lastLocationDropReason = if (!s.isRecording) "not recording" else "paused",
                    mapCenterLat = point.lat,
                    mapCenterLng = point.lng,
                    currentAltitude = point.altitude,
                    lastRawSpeedMps = rawSpeed,
                    lastSpeedSource = speedSourceLabel,
                )
            }
            if (point.timestamp < recordingStartAt) {
                return@update s.copy(
                    lastLocationAtMs = point.timestamp,
                    lastLocationAccuracyM = point.accuracy,
                    lastLocationCountedInTrack = false,
                    lastLocationDropReason = "before recording start",
                    mapCenterLat = point.lat,
                    mapCenterLng = point.lng,
                    currentAltitude = point.altitude,
                    lastRawSpeedMps = rawSpeed,
                    lastSpeedSource = speedSourceLabel,
                )
            }
            val canUseForTrack = point.accuracy <= TRACK_POINT_MAX_ACCURACY_M
            val points = if (canUseForTrack) s.livePoints + point else s.livePoints
            // 速度仅信任 GNSS 来源；其它来源不更新瞬时速度，避免 0 值闪烁与单位踩坑。
            val nextSpeed = if (canUseForTrack && point.isGpsFix) {
                val gated = if (rawSpeed < STANDSTILL_THRESHOLD_MPS) 0.0 else rawSpeed
                if (s.currentSpeedMps <= 0.0) {
                    gated
                } else {
                    SPEED_EMA_ALPHA * gated + (1 - SPEED_EMA_ALPHA) * s.currentSpeedMps
                }
            } else {
                s.currentSpeedMps
            }
            val dropReason = when {
                !canUseForTrack -> "map only: accuracy>${TRACK_POINT_MAX_ACCURACY_M.toInt()}m"
                !point.isGpsFix -> "speed ignored: non-gps source"
                else -> null
            }
            s.copy(
                livePoints = points,
                mapCenterLat = point.lat,
                mapCenterLng = point.lng,
                currentSpeedMps = max(0.0, nextSpeed),
                currentAltitude = point.altitude,
                signalLost = point.accuracy > GOOD_SIGNAL_MAX_ACCURACY_M,
                lastLocationAtMs = point.timestamp,
                lastLocationAccuracyM = point.accuracy,
                lastLocationCountedInTrack = canUseForTrack,
                lastLocationDropReason = dropReason,
                lastRawSpeedMps = rawSpeed,
                lastSpeedSource = speedSourceLabel,
            )
        }
    }

    fun onGnssStatus(snapshot: GnssSatelliteSnapshot) {
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
        _uiState.update { it.copy(locationDebugMessage = message) }
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
        private const val TRACK_POINT_MAX_ACCURACY_M = 40.0
        private const val GOOD_SIGNAL_MAX_ACCURACY_M = 25.0
        private const val MAP_LOCATION_MAX_ACCURACY_M = 200.0
        private const val START_COUNTDOWN_SECONDS = 3
        private const val STANDSTILL_THRESHOLD_MPS = 0.5
        private const val SPEED_EMA_ALPHA = 0.4
        private const val MOVING_THRESHOLD_MPS = 0.5
        private const val MAX_SPEED_WINDOW = 5
        private const val MAX_SEGMENT_GAP_MS = 10_000L

        fun factory(repo: RideRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = TrackViewModel(repo) as T
            }

        /** 段速度（m/s）= 相邻两点 haversine 距离 / 时间差，dt 异常段忽略。 */
        private fun segmentSpeedsMps(points: List<GpsPoint>): List<Double> {
            if (points.size < 2) return emptyList()
            val out = ArrayList<Double>(points.size - 1)
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val dtMs = b.timestamp - a.timestamp
                if (dtMs <= 0L || dtMs > MAX_SEGMENT_GAP_MS) continue
                val dist = GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng)
                out += dist / (dtMs / 1000.0)
            }
            return out
        }

        private fun movingDurationSeconds(points: List<GpsPoint>): Double {
            if (points.size < 2) return 0.0
            var totalMs = 0L
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val dtMs = b.timestamp - a.timestamp
                if (dtMs <= 0L || dtMs > MAX_SEGMENT_GAP_MS) continue
                val dist = GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng)
                val v = dist / (dtMs / 1000.0)
                if (v > MOVING_THRESHOLD_MPS) totalMs += dtMs
            }
            return totalMs / 1000.0
        }

        private fun slidingWindowMaxSpeed(points: List<GpsPoint>, window: Int): Double {
            val speeds = segmentSpeedsMps(points)
            if (speeds.isEmpty()) return 0.0
            if (speeds.size < window) {
                return speeds.max()
            }
            var sum = 0.0
            for (i in 0 until window) sum += speeds[i]
            var maxAvg = sum / window
            for (i in window until speeds.size) {
                sum += speeds[i] - speeds[i - window]
                val avg = sum / window
                if (avg > maxAvg) maxAvg = avg
            }
            return maxAvg
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
