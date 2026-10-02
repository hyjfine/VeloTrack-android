package com.velotrack.velotrack.recording

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.velotrack.velotrack.BuildConfig
import com.velotrack.velotrack.GnssSatelliteSnapshot
import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.debug.DebugLogFormats
import com.velotrack.velotrack.debug.DebugLogRecorder
import com.velotrack.velotrack.LastLocationStore
import com.velotrack.velotrack.LocationTracker
import com.velotrack.velotrack.MapProvider
import com.velotrack.velotrack.Ride
import com.velotrack.velotrack.RideRepository
import com.velotrack.velotrack.RideStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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

    private val _state = MutableStateFlow(RecordingSessionState(isRestoring = true))
    val state: StateFlow<RecordingSessionState> = _state.asStateFlow()

    private val _stopEvents = MutableSharedFlow<Ride>(extraBufferCapacity = 1)
    val stopEvents: SharedFlow<Ride> = _stopEvents.asSharedFlow()

    private var locationTracker: LocationTracker? = null
    private var elapsedTicker: Job? = null
    private var flushJob: Job? = null

    /** 所有 Room 写操作严格接在前一任务之后，避免 Begin/Append/Finalize 在 IO 线程池中乱序。 */
    private var dbWriteTail: Job = SupervisorJob().apply { complete() }
    private var recoveryStarted = false
    private var recoveryFinished = false
    private val recoveryCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var serviceRunning = false

    private var recordingStartAt = 0L
    /** 当前活动录制段开始的 elapsedRealtime；用于拒绝启动/恢复前产生的缓存 fix。 */
    private var recordingStartMonotonicMs = 0L
    private val recordingClock = RecordingClock(SystemClock::elapsedRealtime)
    private var persistedPointCount = 0
    private var draftWriteBuffer = DraftWriteBuffer()
    private var flushInFlight = false
    private var flushRequested = false
    private var rideTitle: String = ""

    /** 当前活动段在 livePoints 中的起始下标。每次开始/恢复后重置为已有点数。 */
    private var segmentStartIndex = 0

    /** 最近一次有效定位到达时的 elapsedRealtime；用于心跳归零。 */
    private var lastLocationMonotonicMs = 0L

    private var lastGnssLogMonotonicMs = 0L

    private val mapProviderRef = mapProvider

    init {
        recoverActiveDraft()
    }

    fun startRecording(hasFineLocation: Boolean) {
        if (_state.value.isRecording || _state.value.isRestoring) return
        if (!hasFineLocation) {
            _state.update { it.copy(persistenceError = "需要精确定位权限才能开始录制", issue = RecordingIssue.PERMISSION) }
            return
        }
        val rideId = System.currentTimeMillis().toString()
        val now = System.currentTimeMillis()
        recordingStartAt = now
        recordingStartMonotonicMs = SystemClock.elapsedRealtime()
        recordingClock.restore()
        persistedPointCount = 0
        draftWriteBuffer = DraftWriteBuffer()
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

        flushPendingPoints()

        enterActiveRecording(hasFineLocation)
    }

    fun togglePause(hasFineLocation: Boolean) {
        val s = _state.value
        if (!s.isRecording || s.isSaving) return
        if (!s.isPaused) {
            pauseActiveRecording()
            flushPendingPoints()
        } else {
            recordingStartMonotonicMs = SystemClock.elapsedRealtime()
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
                    currentSegmentId = it.currentSegmentId + 1,
                    pendingAnchorPoint = null,
                    pendingAnchorPoints = emptyList(),
                    consecutiveAnchorCandidateCount = 0,
                    pendingOutlierPoint = null,
                    consecutiveTrackOutlierCount = 0,
                )
            }
            enterActiveRecording(hasFineLocation)
        }
        notifyService()
    }

    fun pause() {
        if (_state.value.isRecording && !_state.value.isPaused) togglePause(hasFineLocationPermission())
    }

    fun resume(hasFineLocation: Boolean) {
        if (_state.value.isRecording && _state.value.isPaused) {
            togglePause(hasFineLocation)
        }
    }

    fun resumeWithCurrentPermission() {
        resume(hasFineLocation = hasFineLocationPermission())
    }

    fun stopRecording(onComplete: (Ride?) -> Unit) {
        val s = _state.value
        if (!s.isRecording || s.isSaving) {
            onComplete(null)
            return
        }
        pauseActiveRecording()
        flushJob?.cancel()

        val rideId = s.rideId ?: return onComplete(null)
        val points = s.livePoints
        val start = recordingStartAt
        val end = if (points.isEmpty()) System.currentTimeMillis() else points.last().timestamp
        val title = rideTitle

        _state.update {
            it.copy(
                isPaused = true,
                isSaving = true,
                currentSpeedMps = 0.0,
                persistenceError = null, issue = null,
            )
        }

        // 先把不足一个 batch 的点排入队列，再让最终事务排在所有增量写之后。
        flushPendingPoints()
        scope.launch {
            // 长轨迹的尖峰扫描和汇总不应阻塞主线程或 Compose 绘制。
            val statsResult = withContext(io) { runCatching {
                val stats = RideStats.summarize(points)
                stats to com.velotrack.velotrack.RidePresentationData.build(points, stats)
            } }
            val (stats, presentation) = statsResult.getOrElse { error ->
                Log.e(TAG, "summarize ride failed; draft retained for retry", error)
                _state.update {
                    it.copy(
                        isSaving = false,
                        isPaused = true,
                        persistenceError = "轨迹统计失败，请长按停止重试", issue = RecordingIssue.SAVE,
                    )
                }
                notifyService()
                onComplete(null)
                return@launch
            }
            val ride = Ride(
                id = rideId,
                title = title,
                startTime = start,
                endTime = end,
                points = points,
                totalDistance = stats.totalDistanceM,
                avgSpeed = stats.avgSpeedMps,
                maxSpeed = stats.maxSpeedMps,
                movingDurationSec = stats.movingDurationSec,
                activeDurationMs = recordingClock.elapsedMs,
                statsVersion = com.velotrack.velotrack.speed.TrackDataFilter.STATS_VERSION,
                presentation = presentation,
            )
            val buffer = draftWriteBuffer
            val finalize = enqueueDbWrite("finalizeRide") { repo.finalizeRide(ride, buffer.acknowledgedPointCount) }
            val result = finalize.await()
            if (result.isSuccess) {
                _state.value = RecordingSessionState(
                    mapCenterLat = s.mapCenterLat,
                    mapCenterLng = s.mapCenterLng,
                    currentAltitude = s.currentAltitude,
                )
                stopForegroundService()
                _stopEvents.tryEmit(ride)
                onComplete(ride)
            } else {
                val error = result.exceptionOrNull()
                Log.e(TAG, "finalizeRide failed; draft retained for retry", error)
                _state.update {
                    it.copy(
                        isSaving = false,
                        isPaused = true,
                        persistenceError = "保存失败，请长按停止重试", issue = RecordingIssue.SAVE,
                    )
                }
                notifyService()
                onComplete(null)
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
        val s = _state.value
        val result = RecordingLocationProcessor.apply(
            state = s,
            point = point,
            recordingStartAt = recordingStartAt,
            recordingStartMonotonicMs = recordingStartMonotonicMs,
            activeSegmentStartedAt = System.currentTimeMillis() -
                (SystemClock.elapsedRealtime() - recordingStartMonotonicMs).coerceAtLeast(0L),
            isRecording = s.isRecording,
            isPaused = s.isPaused,
            segmentStartIndex = segmentStartIndex,
        )
        if (result.state.currentSegmentId != s.currentSegmentId) {
            segmentStartIndex = s.livePoints.size
        }
        result.acceptedPoints.lastOrNull()?.let(lastLocationStore::write)
        _state.value = result.state
        if (BuildConfig.DEBUG && DebugLogRecorder.isRecording) {
            DebugLogRecorder.append(
                "PROC",
                DebugLogFormats.procLine(result.state, result.acceptedPoints.isNotEmpty()),
            )
        }
        if (result.acceptedPoints.isNotEmpty() || result.state.pendingAnchorPoints.size > s.pendingAnchorPoints.size ||
            result.state.consecutiveGoodGpsCount > s.consecutiveGoodGpsCount) {
            lastLocationMonotonicMs = SystemClock.elapsedRealtime()
        }
        if (result.acceptedPoints.isNotEmpty()) {
            if (result.state.livePoints.size - persistedPointCount >= FLUSH_BATCH_SIZE) {
                flushPendingPoints()
            }
        }
    }

    /** 长时间无新点时把仪表归零，避免显示卡住旧速度（隧道、信号丢失、回前台等场景）。 */
    private fun maybeDecaySpeedOnSilence() {
        val s = _state.value
        if (!s.isRecording || s.isPaused) return
        val lastUsable = lastLocationMonotonicMs.takeIf { it > 0L } ?: recordingStartMonotonicMs
        val silentMs = SystemClock.elapsedRealtime() - lastUsable
        if (silentMs >= LOCATION_SILENCE_ZERO_MS) {
            _state.update { it.copy(currentSpeedMps = 0.0, signalLost = true,
                trackPausedForSignal = true, consecutiveGoodGpsCount = 0,
                lastLocationDropReason = if (lastLocationMonotonicMs == 0L) "waiting for GPS" else "location timeout") }
        }
    }

    fun onGnssStatus(snapshot: GnssSatelliteSnapshot) {
        _state.update { it.copy(gnss = snapshot) }
        if (BuildConfig.DEBUG && DebugLogRecorder.isRecording) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastGnssLogMonotonicMs >= 2_000L) {
                lastGnssLogMonotonicMs = now
                DebugLogRecorder.append("GNSS", DebugLogFormats.gnssLine(snapshot))
            }
        }
    }

    fun onLocationDebug(message: String) {
        if (BuildConfig.DEBUG) {
            DebugLogRecorder.append("LOC", message)
        }
        _state.update { it.copy(locationDebugMessage = message) }
    }

    fun attachService(): RecordingSessionState = _state.value

    fun setServiceRunning(running: Boolean) {
        serviceRunning = running
    }

    /**
     * 进程异常退出后把未完成草稿恢复为“已暂停”会话。用户确认恢复后再继续定位，
     * 避免 Application 启动阶段在后台擅自开启定位。
     */
    fun recoverActiveDraft(onComplete: (Boolean) -> Unit = {}) {
        if (recoveryFinished) {
            onComplete(_state.value.isRecording)
            return
        }
        recoveryCallbacks += onComplete
        if (recoveryStarted) return
        recoveryStarted = true
        scope.launch {
            val draft = withContext(io) { runCatching { repo.getActiveDraftRide() } }
            val ride = draft.getOrElse {
                Log.e(TAG, "recover active draft failed", it)
                finishRecovery(false)
                return@launch
            }
            if (ride == null || _state.value.isRecording) {
                _state.update { it.copy(isRestoring = false) }
                finishRecovery(_state.value.isRecording)
                return@launch
            }
            val points = ride.points
            val display = com.velotrack.velotrack.speed.TrackDataFilter.displaySnapshot(points)
            val spikes = com.velotrack.velotrack.speed.TrackDataFilter.spikeIndices(points)
            // Legacy drafts have no checkpoint; only contiguous segments provide a lower-bound estimate.
            val elapsed = ride.activeDurationMs ?: points.zipWithNext().sumOf { (a, b) ->
                val dt = if (a.monotonicMs > 0L && b.monotonicMs > 0L) b.monotonicMs - a.monotonicMs else b.timestamp - a.timestamp
                if (a.segmentId == b.segmentId && dt in 1L..10_000L) dt else 0L
            }
            recordingStartAt = ride.startTime
            recordingStartMonotonicMs = SystemClock.elapsedRealtime()
            recordingClock.restore(elapsed)
            persistedPointCount = points.size
            draftWriteBuffer = DraftWriteBuffer(points.size)
            rideTitle = ride.title
            segmentStartIndex = points.size
            val last = points.lastOrNull()
            _state.value = RecordingSessionState(
                isRestoring = false,
                isRecording = true,
                isPaused = true,
                rideId = ride.id,
                recordingStartAt = ride.startTime,
                elapsedMs = elapsed,
                livePoints = points.asPersistentTrackPoints(),
                displayPoints = display.points.asPersistentTrackPoints(),
                mapPoints = com.velotrack.velotrack.speed.TrackDataFilter.downsampleForMap(display.points),
                displayDistanceM = display.totalDistanceM,
                distanceState = com.velotrack.velotrack.speed.TrackDataFilter.distanceState(display.points),
                distanceBeforeLastPoint = com.velotrack.velotrack.speed.TrackDataFilter.distanceState(display.points.dropLast(1)),
                spikePointIndices = spikes,
                currentSegmentId = points.maxOfOrNull { it.segmentId } ?: 0,
                mapCenterLat = last?.lat ?: _state.value.mapCenterLat,
                mapCenterLng = last?.lng ?: _state.value.mapCenterLng,
                currentAltitude = last?.altitude,
                persistenceError = "检测到未完成骑行，已暂停恢复", issue = RecordingIssue.RECOVERED,
            )
            Log.i(TAG, "recovered draft rideId=${ride.id} points=${points.size}")
            finishRecovery(true)
        }
    }

    private fun finishRecovery(recovered: Boolean) {
        recoveryFinished = true
        _state.update { it.copy(isRestoring = false) }
        val callbacks = recoveryCallbacks.toList()
        recoveryCallbacks.clear()
        callbacks.forEach { it(recovered) }
    }

    fun notifyService() {
        if (serviceRunning) {
            RecordingNotificationHelper.updateNotification(appContext, _state.value)
        }
    }

    private fun enterActiveRecording(precise: Boolean) {
        if (!precise || !hasFineLocationPermission()) {
            pauseWithError("需要精确定位权限才能记录轨迹", RecordingIssue.PERMISSION)
            return
        }
        recordingStartMonotonicMs = SystemClock.elapsedRealtime()
        lastLocationMonotonicMs = 0L
        recordingClock.resume()
        _state.update { it.copy(isPaused = false, persistenceError = null, issue = null, signalLost = false) }
        if (!startForegroundService(RecordingForegroundService.ACTION_START)) return
        try {
            startLocationTracker(precise)
        } catch (error: Exception) {
            Log.e(TAG, "location subscription failed", error)
            pauseWithError("定位启动失败，请检查定位权限后重试")
            return
        }
        if (_state.value.isPaused) return
        startElapsedTicker()
        startPeriodicFlush()
        flushPendingPoints()
    }

    private fun pauseActiveRecording() {
        recordingClock.pause()
        elapsedTicker?.cancel()
        elapsedTicker = null
        locationTracker?.stop()
        locationTracker = null
        _state.update { it.copy(isPaused = true, elapsedMs = recordingClock.elapsedMs, currentSpeedMps = 0.0) }
    }

    fun pauseWithError(message: String, issue: RecordingIssue = RecordingIssue.SERVICE) {
        if (!_state.value.isRecording) return
        pauseActiveRecording()
        _state.update { it.copy(persistenceError = message, issue = issue) }
        flushPendingPoints()
        notifyService()
    }

    fun onServiceDestroyed() {
        serviceRunning = false
        if (_state.value.isRecording && !_state.value.isPaused) {
            pauseWithError("后台录制服务已停止，请恢复录制")
        }
    }

    private fun startLocationTracker(precise: Boolean) {
        if (!precise || !hasFineLocationPermission()) {
            pauseWithError("需要精确定位权限才能记录轨迹", RecordingIssue.PERMISSION)
            return
        }
        locationTracker?.stop()
        locationTracker = LocationTracker(
            appContext,
            mapProviderRef,
            ::onLocation,
            ::onLocationDebug,
            ::onGnssStatus,
            { pauseWithError(it) },
        ).also { it.start(precise = precise, recordingMode = true) }
    }

    private fun startElapsedTicker() {
        elapsedTicker?.cancel()
        elapsedTicker = scope.launch {
            var tick = 0
            while (_state.value.isRecording && !_state.value.isPaused) {
                val elapsed = recordingClock.elapsedMs
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
        if (flushInFlight) {
            flushRequested = true
            return
        }
        val points = _state.value.livePoints
        val elapsed = recordingClock.elapsedMs
        val buffer = draftWriteBuffer
        val title = rideTitle
        val start = recordingStartAt
        flushInFlight = true
        val task = enqueueDbWrite("flushDraft") {
            buffer.flush(points,
                ensureDraft = { repo.beginDraftRide(rideId, title, start) },
                append = { index, batch -> repo.appendTrackPoints(rideId, index, batch, elapsed) },
            )
        }
        scope.launch {
            val result = task.await()
            flushInFlight = false
            val requested = flushRequested
            flushRequested = false
            if (_state.value.rideId != rideId) return@launch
            if (result.isSuccess) {
                persistedPointCount = buffer.acknowledgedPointCount
                _state.update { s ->
                    if (s.issue == RecordingIssue.STORAGE) s.copy(persistenceError = null, issue = null) else s
                }
            } else {
                _state.update { it.copy(persistenceError = DRAFT_WRITE_ERROR, issue = RecordingIssue.STORAGE) }
            }
            if (requested && !_state.value.isSaving) flushPendingPoints()
        }
    }

    private fun enqueueDbWrite(
        operation: String,
        block: () -> Unit,
    ): Deferred<Result<Unit>> {
        val previous = dbWriteTail
        val task = scope.async(io, start = CoroutineStart.LAZY) {
            previous.join()
            var lastError: Throwable? = null
            repeat(DB_WRITE_ATTEMPTS) { attempt ->
                val result = runCatching(block)
                if (result.isSuccess) return@async Result.success(Unit)
                lastError = result.exceptionOrNull()
                Log.w(TAG, "$operation failed attempt=${attempt + 1}/$DB_WRITE_ATTEMPTS", lastError)
                if (attempt < DB_WRITE_ATTEMPTS - 1) delay(DB_RETRY_DELAY_MS * (attempt + 1))
            }
            Result.failure(lastError ?: IllegalStateException("$operation failed"))
        }
        dbWriteTail = task
        task.start()
        return task
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    private fun startForegroundService(action: String): Boolean {
        val intent = Intent(appContext, RecordingForegroundService::class.java).apply { this.action = action }
        return try {
            appContext.startForegroundService(intent)
            true
        } catch (error: Exception) {
            Log.e(TAG, "start foreground service failed action=$action", error)
            pauseWithError("后台录制服务启动失败，请重试")
            false
        }
    }

    @Suppress("ImplicitSamInstance")
    private fun stopForegroundService() {
        appContext.stopService(Intent(appContext, RecordingForegroundService::class.java))
    }

    companion object {
        private const val TAG = "VeloRecording"
        private const val FLUSH_BATCH_SIZE = 10
        private const val FLUSH_INTERVAL_MS = 30_000L
        private const val DB_WRITE_ATTEMPTS = 3
        private const val DB_RETRY_DELAY_MS = 250L
        private const val DRAFT_WRITE_ERROR = "轨迹暂未写入存储，正在保留并重试；请勿退出应用"

        /** 连续 4s 没有新有效点，仪表显示速度归零。 */
        private const val LOCATION_SILENCE_ZERO_MS = 4_000L
    }
}
