package com.velotrack.velotrack.recording

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.velotrack.velotrack.GnssSatelliteSnapshot
import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.LastLocationStore
import com.velotrack.velotrack.LocationTracker
import com.velotrack.velotrack.MapProvider
import com.velotrack.velotrack.Ride
import com.velotrack.velotrack.RideRepository
import com.velotrack.velotrack.RideStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingSessionManager(
    private val appContext: Context,
    private val repo: RideRepository,
    mapProvider: MapProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = Dispatchers.IO
    private val lastLocationStore = LastLocationStore(appContext)

    private val _state = MutableStateFlow(RecordingSessionState())
    val state: StateFlow<RecordingSessionState> = _state.asStateFlow()

    private val _stopEvents = MutableSharedFlow<Ride>(extraBufferCapacity = 1)
    val stopEvents: SharedFlow<Ride> = _stopEvents.asSharedFlow()

    private var locationTracker: LocationTracker? = null
    private var elapsedTicker: Job? = null
    private var flushJob: Job? = null

    private var recordingStartAt = 0L
    private var accumulatedElapsed = 0L
    private var tickerAnchorElapsed = SystemClock.elapsedRealtime()
    private var persistedPointCount = 0
    private var pendingFlushPoints = mutableListOf<GpsPoint>()
    private var rideTitle: String = ""

    /** 当前活动段在 livePoints 中的起始下标。每次开始/恢复后重置为已有点数。 */
    private var segmentStartIndex = 0

    /** 最近一次有效定位到达时的 elapsedRealtime；用于心跳归零。 */
    private var lastLocationMonotonicMs = 0L

    private val mapProviderRef = mapProvider

    fun startRecording(hasFineLocation: Boolean) {
        if (_state.value.isRecording) return
        val rideId = System.currentTimeMillis().toString()
        val now = System.currentTimeMillis()
        recordingStartAt = now
        accumulatedElapsed = 0L
        persistedPointCount = 0
        pendingFlushPoints = mutableListOf()
        rideTitle = "Ride on ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}"
        segmentStartIndex = 0
        lastLocationMonotonicMs = 0L

        _state.value = RecordingSessionState(
            isRecording = true,
            isPaused = false,
            rideId = rideId,
            recordingStartAt = now,
            elapsedMs = 0L,
            livePoints = emptyList(),
            currentSpeedMps = 0.0,
            mapCenterLat = _state.value.mapCenterLat,
            mapCenterLng = _state.value.mapCenterLng,
        )

        scope.launch(io) {
            runCatching { repo.beginDraftRide(rideId, rideTitle, now) }
                .onFailure { e -> Log.e(TAG, "beginDraftRide failed", e) }
        }

        startLocationTracker(hasFineLocation)
        startElapsedTicker()
        startPeriodicFlush()
        startForegroundService(RecordingForegroundService.ACTION_START)
    }

    fun togglePause(hasFineLocation: Boolean) {
        val s = _state.value
        if (!s.isRecording) return
        if (!s.isPaused) {
            accumulatedElapsed += elapsedSinceTickerAnchor()
            elapsedTicker?.cancel()
            flushPendingPoints()
            locationTracker?.stop()
            _state.update { it.copy(isPaused = true, currentSpeedMps = 0.0) }
        } else {
            tickerAnchorElapsed = SystemClock.elapsedRealtime()
            lastLocationMonotonicMs = 0L
            // 恢复录制：开启新段，避免跨过暂停期的位移被算成异常速度。
            segmentStartIndex = _state.value.livePoints.size
            _state.update {
                it.copy(
                    isPaused = false,
                    currentSpeedMps = 0.0,
                    trackPausedForSignal = false,
                    consecutiveGoodGpsCount = 0,
                    consecutiveBadGpsCount = 0,
                )
            }
            startLocationTracker(hasFineLocation)
            startElapsedTicker()
        }
        notifyService()
    }

    fun pause() = togglePause(hasFineLocation = true)

    fun resume(hasFineLocation: Boolean) {
        if (_state.value.isRecording && _state.value.isPaused) {
            togglePause(hasFineLocation)
        }
    }

    fun stopRecording(onComplete: (Ride?) -> Unit) {
        val s = _state.value
        if (!s.isRecording) {
            onComplete(null)
            return
        }
        if (!s.isPaused) {
            accumulatedElapsed += elapsedSinceTickerAnchor()
        }
        elapsedTicker?.cancel()
        flushJob?.cancel()
        locationTracker?.stop()
        locationTracker = null

        val rideId = s.rideId ?: return onComplete(null)
        val points = s.livePoints

        val stats = RideStats.summarize(points)
        val totalDistance = stats.totalDistanceM
        val avgSpeed = stats.avgSpeedMps
        val maxSpeed = stats.maxSpeedMps
        val start = if (points.isEmpty()) recordingStartAt else points.first().timestamp
        val end = if (points.isEmpty()) System.currentTimeMillis() else points.last().timestamp
        val ride = Ride(
            id = rideId,
            title = rideTitle,
            startTime = start,
            endTime = end,
            points = points,
            totalDistance = totalDistance,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
            movingDurationSec = stats.movingDurationSec,
        )

        _state.value = RecordingSessionState(
            mapCenterLat = s.mapCenterLat,
            mapCenterLng = s.mapCenterLng,
            currentAltitude = s.currentAltitude,
        )

        scope.launch(io) {
            runCatching { repo.finalizeRide(ride) }
                .onFailure { e ->
                    Log.e(TAG, "finalizeRide failed", e)
                    runCatching { repo.deleteDraftRide(rideId) }
                }
            withContext(Dispatchers.Main) {
                stopForegroundService()
                _stopEvents.tryEmit(ride)
                onComplete(ride)
            }
        }
    }

    fun onLocation(point: GpsPoint) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyLocation(point)
        } else {
            scope.launch { applyLocation(point) }
        }
    }

    private fun applyLocation(point: GpsPoint) {
        lastLocationStore.write(point)
        val s = _state.value
        val result = RecordingLocationProcessor.apply(
            state = s,
            point = point,
            recordingStartAt = recordingStartAt,
            isRecording = s.isRecording,
            isPaused = s.isPaused,
            segmentStartIndex = segmentStartIndex,
        )
        _state.value = result.state
        if (result.acceptedPoint != null) {
            lastLocationMonotonicMs = SystemClock.elapsedRealtime()
            pendingFlushPoints.add(result.acceptedPoint)
            if (pendingFlushPoints.size >= FLUSH_BATCH_SIZE) {
                flushPendingPoints()
            }
        }
    }

    /** 长时间无新点时把仪表归零，避免显示卡住旧速度（隧道、信号丢失、回前台等场景）。 */
    private fun maybeDecaySpeedOnSilence() {
        val s = _state.value
        if (!s.isRecording || s.isPaused) return
        if (s.currentSpeedMps <= 0.0) return
        if (lastLocationMonotonicMs <= 0L) return
        val silentMs = SystemClock.elapsedRealtime() - lastLocationMonotonicMs
        if (silentMs >= LOCATION_SILENCE_ZERO_MS) {
            _state.update { it.copy(currentSpeedMps = 0.0) }
        }
    }

    fun onGnssStatus(snapshot: GnssSatelliteSnapshot) {
        _state.update { it.copy(gnss = snapshot) }
    }

    fun onLocationDebug(message: String) {
        _state.update { it.copy(locationDebugMessage = message) }
    }

    fun attachService(): RecordingSessionState = _state.value

    fun notifyService() {
        startForegroundService(RecordingForegroundService.ACTION_UPDATE)
    }

    private fun startLocationTracker(precise: Boolean) {
        locationTracker?.stop()
        locationTracker = LocationTracker(
            appContext,
            mapProviderRef,
            ::onLocation,
            ::onLocationDebug,
            ::onGnssStatus,
        ).also { it.start(precise = precise, recordingMode = true) }
    }

    private fun startElapsedTicker() {
        elapsedTicker?.cancel()
        tickerAnchorElapsed = SystemClock.elapsedRealtime()
        elapsedTicker = scope.launch {
            var tick = 0
            while (true) {
                val elapsed = accumulatedElapsed + elapsedSinceTickerAnchor()
                _state.update { it.copy(elapsedMs = elapsed) }
                maybeDecaySpeedOnSilence()
                if (tick % 5 == 0) {
                    notifyService()
                }
                tick++
                delay(1000)
            }
        }
    }

    private fun elapsedSinceTickerAnchor(): Long =
        SystemClock.elapsedRealtime() - tickerAnchorElapsed

    private fun startPeriodicFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                flushPendingPoints()
            }
        }
    }

    private fun flushPendingPoints() {
        val rideId = _state.value.rideId ?: return
        if (pendingFlushPoints.isEmpty()) return
        val batch = pendingFlushPoints.toList()
        pendingFlushPoints.clear()
        val startIndex = persistedPointCount
        persistedPointCount += batch.size
        scope.launch(io) {
            runCatching { repo.appendTrackPoints(rideId, startIndex, batch) }
                .onFailure { e -> Log.e(TAG, "appendTrackPoints failed", e) }
        }
    }

    private fun startForegroundService(action: String) {
        val intent = Intent(appContext, RecordingForegroundService::class.java).apply {
            this.action = action
        }
        appContext.startForegroundService(intent)
    }

    private fun stopForegroundService() {
        appContext.stopService(Intent(appContext, RecordingForegroundService::class.java))
    }

    companion object {
        private const val TAG = "VeloRecording"
        private const val FLUSH_BATCH_SIZE = 10
        private const val FLUSH_INTERVAL_MS = 30_000L

        /** 连续 4s 没有新有效点，仪表显示速度归零。 */
        private const val LOCATION_SILENCE_ZERO_MS = 4_000L
    }
}
