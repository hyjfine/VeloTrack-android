package com.velotrack.velotrack.recording

import com.velotrack.velotrack.GnssSatelliteSnapshot
import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.GpsSource
import com.velotrack.velotrack.RecordingRideStore
import com.velotrack.velotrack.Ride
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingSessionManagerTest {
    @Test
    fun recoveredDraft_staysPausedUntilResumeThenPeriodicallyFlushesNewPointsAndTime() = runTest {
        val oldPoints = listOf(point(0), point(1))
        val store = FakeStore().apply { draft = ride(oldPoints, elapsedMs = 6_000L) }
        val h = Harness(testScheduler, store)
        try {
            runCurrent()
            assertTrue(h.manager.state.value.isPaused)
            assertEquals(6_000L, h.manager.state.value.elapsedMs)
            assertEquals(0, h.service.starts)
            assertTrue(h.locations.isEmpty())

            h.manager.togglePause(true)
            runCurrent()
            repeat(3) { index ->
                advanceTimeBy(1_000)
                runCurrent()
                h.emit(index + 2)
            }
            assertEquals(5, h.manager.state.value.livePoints.size)
            assertEquals(1, h.manager.state.value.livePoints.last().segmentId)
            // Below the ten-point batch threshold: only the periodic job can persist this tail.
            assertTrue(store.appends.all { it.points.isEmpty() })
            advanceTimeBy(27_000)
            runCurrent()
            val append = store.appends.last()
            assertEquals(2, append.index)
            assertEquals(3, append.points.size)
            assertEquals(36_000L, append.elapsedMs)
            assertEquals(36_000L, h.manager.state.value.elapsedMs)
        } finally { h.manager.close() }
    }

    @Test
    fun permissionDenied_doesNotStartLocationOrService_andPermissionRevocationPausesDraft() = runTest {
        val h = Harness(testScheduler)
        try {
            runCurrent()
            h.manager.startRecording(false)
            assertFalse(h.manager.state.value.isRecording)
            assertEquals(RecordingIssue.PERMISSION, h.manager.state.value.issue)
            assertEquals(0, h.store.beginCalls)

            h.permission = false
            h.manager.startRecording(true)
            runCurrent()
            assertTrue(h.manager.state.value.isRecording)
            assertTrue(h.manager.state.value.isPaused)
            assertEquals(RecordingIssue.PERMISSION, h.manager.state.value.issue)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(0L, h.manager.state.value.elapsedMs)
            assertEquals(0, h.service.starts)
            assertTrue(h.locations.isEmpty())

            h.permission = true
            h.manager.togglePause(true)
            runCurrent()
            assertFalse(h.manager.state.value.isPaused)
            assertEquals(1, h.service.starts)
            assertTrue(h.locations.single().running)
        } finally { h.manager.close() }
    }

    @Test
    fun foregroundServiceStartFailure_freezesTimeAndNeverStartsLocationOrRetriesService() = runTest {
        val h = Harness(testScheduler)
        try {
            runCurrent()
            h.service.startError = IllegalStateException("background start rejected")
            h.manager.startRecording(true)
            runCurrent()
            val writes = h.store.appendAttempts
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(h.manager.state.value.isPaused)
            assertEquals(RecordingIssue.SERVICE, h.manager.state.value.issue)
            assertEquals(0L, h.manager.state.value.elapsedMs)
            assertEquals(1, h.service.starts)
            assertTrue(h.locations.isEmpty())
            assertEquals(writes, h.store.appendAttempts)
            h.manager.notifyService()
            assertEquals(1, h.service.starts)
        } finally { h.manager.close() }
    }

    @Test
    fun synchronousLocationFailure_releasesSubscriptionBeforeStartReturns() = runTest {
        val h = Harness(testScheduler)
        try {
            runCurrent()
            h.onLocationStart = { it.callbacks.onError("provider unavailable") }
            h.manager.startRecording(true)
            assertTrue(h.manager.state.value.isPaused)
            assertEquals(1, h.locations.single().stops)
            assertFalse(h.locations.single().running)
            runCurrent()
            val writes = h.store.appendAttempts
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(0L, h.manager.state.value.elapsedMs)
            assertEquals(writes, h.store.appendAttempts)
        } finally { h.manager.close() }
    }

    @Test
    fun throwingLocationStart_releasesInstalledSubscriptionAndKeepsDraftPaused() = runTest {
        val h = Harness(testScheduler)
        try {
            runCurrent()
            h.onLocationStart = { throw SecurityException("permission revoked during start") }
            h.manager.startRecording(true)
            runCurrent()
            assertTrue(h.manager.state.value.isPaused)
            assertEquals(RecordingIssue.SERVICE, h.manager.state.value.issue)
            assertEquals(1, h.locations.single().stops)
            assertFalse(h.locations.single().running)
            assertTrue(h.store.beginCalls > 0)
        } finally { h.manager.close() }
    }

    @Test
    fun failedSave_retainsSessionAndRetriesWithoutCountingWaitTimeOrDuplicatingPoints() = runTest {
        val h = Harness(testScheduler)
        val events = mutableListOf<Ride>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.manager.stopEvents.toList(events) }
        try {
            runCurrent()
            h.manager.startRecording(true)
            runCurrent()
            repeat(3) { index ->
                advanceTimeBy(1_000)
                runCurrent()
                h.emit(index)
            }
            h.store.finalizeFailures = 3
            var callbackCount = 0
            var saved: Ride? = null
            h.manager.stopRecording { saved = it; callbackCount++ }
            runCurrent()
            advanceTimeBy(750)
            runCurrent()
            assertEquals(1, callbackCount)
            assertNull(saved)
            assertEquals(3, h.store.finalizeAttempts)
            assertTrue(h.manager.state.value.isRecording)
            assertTrue(h.manager.state.value.isPaused)
            assertFalse(h.manager.state.value.isSaving)
            assertEquals(RecordingIssue.SAVE, h.manager.state.value.issue)
            assertEquals(3, h.manager.state.value.livePoints.size)
            assertTrue(events.isEmpty())

            advanceTimeBy(60_000)
            runCurrent()
            h.manager.stopRecording { saved = it; callbackCount++ }
            runCurrent()
            assertEquals(2, callbackCount)
            assertNotNull(saved)
            assertEquals(3_000L, saved?.activeDurationMs)
            assertEquals(3, saved?.points?.size)
            assertEquals(3, h.store.finalizedPrefix)
            assertEquals(4, h.store.finalizeAttempts)
            assertFalse(h.manager.state.value.isRecording)
            assertEquals(1, h.service.stops)
            assertEquals(1, h.locations.single().stops)
            assertEquals(listOf(saved), events)
        } finally { h.manager.close() }
    }

    @Test
    fun failedAppend_retainsUnacknowledgedPointsAndResumeRestartsPeriodicFlush() = runTest {
        val h = Harness(testScheduler)
        try {
            runCurrent()
            h.manager.startRecording(true)
            runCurrent()
            repeat(3) { index ->
                advanceTimeBy(1_000)
                runCurrent()
                h.emit(index)
            }
            h.store.appendFailures = 3
            h.manager.togglePause(true)
            runCurrent()
            advanceTimeBy(750)
            runCurrent()
            assertEquals(RecordingIssue.STORAGE, h.manager.state.value.issue)
            assertTrue(h.store.appends.all { it.points.isEmpty() })

            h.manager.togglePause(true)
            runCurrent()
            assertEquals(0, h.store.appends.last().index)
            assertEquals(3, h.store.appends.last().points.size)
            assertNull(h.manager.state.value.issue)
            val appends = h.store.appends.size
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(appends + 1, h.store.appends.size)
            assertEquals(3, h.store.appends.last().index)
            assertEquals(33_000L, h.store.appends.last().elapsedMs)
        } finally { h.manager.close() }
    }

    @Test
    fun pausedDraft_retriesFailedAppendUntilRecoveryThenStopsIdleWrites() = runTest {
        val h = Harness(testScheduler)
        try {
            runCurrent()
            h.manager.startRecording(true)
            runCurrent()
            repeat(3) { index ->
                advanceTimeBy(1_000)
                runCurrent()
                h.emit(index)
            }
            h.store.appendFailures = 6
            h.manager.togglePause(true)
            runCurrent()
            advanceTimeBy(750)
            runCurrent()
            assertEquals(RecordingIssue.STORAGE, h.manager.state.value.issue)
            advanceTimeBy(30_750)
            runCurrent()
            assertEquals(RecordingIssue.STORAGE, h.manager.state.value.issue)
            assertTrue(h.store.appends.all { it.points.isEmpty() })
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(3, h.store.appends.last().points.size)
            assertEquals(0, h.store.appends.last().index)
            assertNull(h.manager.state.value.issue)
            assertTrue(h.manager.state.value.isPaused)
            assertEquals(3_000L, h.manager.state.value.elapsedMs)
            assertEquals(3_000L, h.store.appends.last().elapsedMs)
            assertFalse(h.locations.single().running)
            val writes = h.store.appendAttempts
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(writes, h.store.appendAttempts)
        } finally { h.manager.close() }
    }

    @Test
    fun errorPausedDraft_retriesPersistenceWithoutRestartingServiceOrLocation() = runTest {
        for (issue in listOf(RecordingIssue.SERVICE, RecordingIssue.PERMISSION)) {
            val h = Harness(testScheduler)
            try {
                runCurrent()
                h.manager.startRecording(true)
                runCurrent()
                repeat(3) { index ->
                    advanceTimeBy(1_000)
                    runCurrent()
                    h.emit(index)
                }
                h.store.appendFailures = 3
                h.manager.pauseWithError("recording unavailable", issue)
                runCurrent()
                advanceTimeBy(750)
                runCurrent()
                assertEquals(RecordingIssue.STORAGE, h.manager.state.value.issue)
                advanceTimeBy(30_000)
                runCurrent()
                assertEquals(3, h.store.appends.last().points.size)
                assertTrue(h.manager.state.value.isPaused)
                assertEquals(3_000L, h.manager.state.value.elapsedMs)
                assertEquals(1, h.service.starts)
                assertFalse(h.locations.single().running)
            } finally { h.manager.close() }
        }
    }

    @Test
    fun failedFinalize_restartsDraftRetryAfterSaveLeavesPendingData() = runTest {
        val h = Harness(testScheduler)
        try {
            runCurrent()
            h.manager.startRecording(true)
            runCurrent()
            repeat(3) { index ->
                advanceTimeBy(1_000)
                runCurrent()
                h.emit(index)
            }
            h.store.appendFailures = 3
            h.store.finalizeFailures = 3
            var completed = false
            h.manager.stopRecording { assertNull(it); completed = true }
            runCurrent()
            advanceTimeBy(1_500)
            runCurrent()
            assertTrue(completed)
            assertEquals(RecordingIssue.SAVE, h.manager.state.value.issue)
            assertTrue(h.store.appends.all { it.points.isEmpty() })
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(3, h.store.appends.last().points.size)
            assertTrue(h.manager.state.value.isPaused)
            assertFalse(h.manager.state.value.isSaving)
            assertEquals(3, h.store.finalizeAttempts) // retry persists the draft; explicit stop finalizes it
            assertEquals(3_000L, h.manager.state.value.elapsedMs)
        } finally { h.manager.close() }
    }

    @Test
    fun close_cancelsScheduledRetryForPausedDraft() = runTest {
        val h = Harness(testScheduler)
        runCurrent()
        h.manager.startRecording(true)
        runCurrent()
        h.store.appendFailures = 3
        h.manager.togglePause(true)
        runCurrent()
        advanceTimeBy(750)
        runCurrent()
        assertEquals(RecordingIssue.STORAGE, h.manager.state.value.issue)
        val attempts = h.store.appendAttempts
        h.manager.close()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(attempts, h.store.appendAttempts)
        assertEquals(1, h.locations.single().stops)
    }

    @Test
    fun callbackAge_usesInjectedMonotonicTimeAndRejectsStalePoints() = runTest {
        val h = Harness(testScheduler)
        try {
            runCurrent()
            h.manager.startRecording(true)
            runCurrent()
            advanceTimeBy(6_000)
            runCurrent()
            val old = point(0).copy(fixMonotonicMs = 10_000L, receivedMonotonicMs = 10_000L)
            h.locations.single().callbacks.onLocation(old)
            assertEquals("stale callback", h.manager.state.value.lastLocationDropReason)
            assertTrue(h.manager.state.value.pendingAnchorPoints.isEmpty())
            assertTrue(h.cached.isEmpty())
        } finally { h.manager.close() }
    }

    @Test
    fun queuedAndLateCallbacksFromOldSubscription_cannotMutateResumedSession() = runTest {
        val h = Harness(testScheduler, immediateMain = false)
        try {
            runCurrent()
            h.manager.startRecording(true)
            runCurrent()
            val old = h.locations.single()
            old.callbacks.onLocation(point(0)) // queued on main, then invalidated by pause
            h.manager.togglePause(true)
            h.manager.togglePause(true)
            old.callbacks.onDebug("stale debug")
            old.callbacks.onGnss(GnssSatelliteSnapshot(1, 1, 0, 0, 1, 0, 0))
            old.callbacks.onError("stale error")
            runCurrent()
            assertFalse(h.manager.state.value.isPaused)
            assertTrue(h.manager.state.value.livePoints.isEmpty())
            assertTrue(h.manager.state.value.pendingAnchorPoints.isEmpty())
            assertNull(h.manager.state.value.locationDebugMessage)
            assertNull(h.manager.state.value.gnss)
            assertNull(h.manager.state.value.issue)
            assertTrue(h.cached.isEmpty())

            repeat(3) { index ->
                advanceTimeBy(1_000)
                runCurrent()
                h.emit(index)
                runCurrent()
            }
            assertEquals(3, h.manager.state.value.livePoints.size)
            assertEquals(h.manager.state.value.livePoints.last(), h.cached.single())
        } finally { h.manager.close() }
    }

    @Test
    fun activeTime_ignoresWallClockChangesAndUserPause() = runTest {
        val h = Harness(testScheduler)
        try {
            runCurrent()
            h.manager.startRecording(true)
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            h.manager.togglePause(true)
            h.wallOffset = -3_600_000L
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(5_000L, h.manager.state.value.elapsedMs)
            h.manager.togglePause(true)
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            var saved: Ride? = null
            h.manager.stopRecording { saved = it }
            runCurrent()
            assertEquals(7_000L, saved?.activeDurationMs)
        } finally { h.manager.close() }
    }

    @Test
    fun close_cancelsDatabaseRetryAndTickerAndReleasesPlatformResourcesOnce() = runTest {
        val h = Harness(testScheduler)
        runCurrent()
        h.store.appendFailures = 100
        h.manager.startRecording(true)
        runCurrent()
        assertEquals(1, h.store.appendAttempts)
        h.manager.close()
        h.manager.close()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, h.store.appendAttempts)
        assertEquals(1, h.locations.single().stops)
        assertEquals(1, h.service.stops)
        assertEquals(0L, h.manager.state.value.elapsedMs)
        h.manager.startRecording(true)
        h.manager.stopRecording { assertNull(it) }
        runCurrent()
        assertEquals(1, h.service.starts)
        assertEquals(0, h.store.finalizeAttempts)
    }

    private class Harness(
        scheduler: TestCoroutineScheduler,
        val store: FakeStore = FakeStore(),
        immediateMain: Boolean = true,
    ) {
        var permission = true
        var wallOffset = 0L
        var onLocationStart: (FakeLocation) -> Unit = {}
        val locations = mutableListOf<FakeLocation>()
        val cached = mutableListOf<GpsPoint>()
        val service = FakeService()
        val clock = object : RecordingTimeSource {
            override fun currentTimeMillis() = 1_000_000L + scheduler.currentTime + wallOffset
            override fun elapsedRealtime() = 10_000L + scheduler.currentTime
        }
        val manager = RecordingSessionManager(store, RecordingDependencies(
            clock = clock,
            locationFactory = RecordingLocationFactory { callbacks ->
                FakeLocation(callbacks) { onLocationStart(it) }.also(locations::add)
            },
            foregroundService = service,
            hasFineLocationPermission = { permission },
            cacheLocation = { cached += it },
            mainDispatcher = if (immediateMain) UnconfinedTestDispatcher(scheduler) else StandardTestDispatcher(scheduler),
            ioDispatcher = StandardTestDispatcher(scheduler),
        ))
        fun emit(index: Int) {
            locations.last().callbacks.onLocation(point(index).copy(
                timestamp = clock.currentTimeMillis(), monotonicMs = clock.elapsedRealtime(),
                fixMonotonicMs = clock.elapsedRealtime(), receivedMonotonicMs = clock.elapsedRealtime(),
            ))
        }
    }

    private class FakeLocation(
        val callbacks: RecordingLocationCallbacks,
        val onStart: (FakeLocation) -> Unit,
    ) : RecordingLocationSource {
        var running = false
        var stops = 0
        override fun start(precise: Boolean) { running = true; onStart(this) }
        override fun stop() { running = false; stops++ }
    }

    private class FakeService : RecordingServiceController {
        var starts = 0
        var stops = 0
        var startError: RuntimeException? = null
        override fun start() { starts++; startError?.let { throw it } }
        override fun stop() { stops++ }
        override fun update(state: RecordingSessionState) = Unit
    }

    private data class Append(val index: Int, val points: List<GpsPoint>, val elapsedMs: Long)
    private class FakeStore : RecordingRideStore {
        var draft: Ride? = null
        var beginCalls = 0
        var appendAttempts = 0
        var appendFailures = 0
        var finalizeAttempts = 0
        var finalizeFailures = 0
        var finalizedPrefix = -1
        val appends = mutableListOf<Append>()
        override fun getActiveDraftRide(): Ride? = draft
        override fun beginDraftRide(rideId: String, title: String, startTime: Long) { beginCalls++ }
        override fun appendTrackPoints(rideId: String, startIndex: Int, points: List<GpsPoint>, elapsedMs: Long) {
            appendAttempts++
            if (appendFailures > 0) { appendFailures--; error("disk unavailable") }
            appends += Append(startIndex, points.toList(), elapsedMs)
        }
        override fun finalizeRide(ride: Ride, acknowledgedPointCount: Int) {
            finalizeAttempts++
            if (finalizeFailures > 0) { finalizeFailures--; error("transaction failed") }
            finalizedPrefix = acknowledgedPointCount
        }
    }

    companion object {
        private fun point(index: Int) = GpsPoint(
            lat = 31.0 + index * 0.00003, lng = 121.0, timestamp = 1_000_000L + index * 1_000L,
            speedMps = 3.3, altitude = null, accuracy = 3.0, source = GpsSource.PLATFORM_GPS,
            isGpsFix = true, monotonicMs = 10_000L + index * 1_000L,
        )
        private fun ride(points: List<GpsPoint>, elapsedMs: Long) = Ride(
            id = "draft", title = "Recovered ride", startTime = 990_000L, endTime = null,
            points = points, totalDistance = 0.0, avgSpeed = 0.0, maxSpeed = 0.0,
            activeDurationMs = elapsedMs,
        )
    }
}
