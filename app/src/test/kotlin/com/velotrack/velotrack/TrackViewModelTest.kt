package com.velotrack.velotrack

import androidx.lifecycle.ViewModelStore
import com.velotrack.velotrack.recording.RecordingController
import com.velotrack.velotrack.recording.RecordingSessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class TrackViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val main = StandardTestDispatcher(scheduler)
    private val io = QueuedDispatcher()
    private val store = FakeStore()
    private val recording = FakeRecording()
    private val logs = FakeLogs()
    private val analyzer = DeferredAnalysis()
    private val lifecycle = ViewModelStore()
    private var maintenanceCalls = 0
    private var maintenanceFailure: Exception? = null
    private lateinit var vm: TrackViewModel

    @Before fun setUp() {
        Dispatchers.setMain(main)
        vm = newViewModel()
        drain()
    }

    @After fun tearDown() {
        lifecycle.clear()
        // Complete intentionally non-cancellable fake calls so no coroutine is left behind.
        analyzer.pending.forEach { it.resume("late") }
        analyzer.pending.clear()
        drain()
        Dispatchers.resetMain()
    }

    private fun newViewModel(client: RideAnalysisClient = analyzer): TrackViewModel = TrackViewModel(
        store, recording,
        HistoryMaintenance { maintenanceCalls++; maintenanceFailure?.let { throw it } },
        client, logs, io,
    ).also { lifecycle.put("vm-${lifecycle.keys().size}", it) }

    @Test fun historyFailure_isRetryableAndDoesNotDiscardExistingRows() {
        store.listFailure = IllegalStateException("disk unavailable")
        vm.loadHistory()
        drain()
        assertFalse(vm.uiState.value.isHistoryLoading)
        assertNotNull(vm.uiState.value.historyError)
        assertEquals(listOf("a", "b"), vm.uiState.value.history.map { it.id })
        store.listFailure = null
        vm.retryHistoryLoad()
        drain()
        assertNull(vm.uiState.value.historyError)
    }

    @Test fun failedMaintenance_isRetriedBeforeHistoryRead() {
        maintenanceFailure = IllegalStateException("repair failed")
        val initialReads = store.listCalls
        vm.loadHistory()
        drain()
        assertEquals(initialReads, store.listCalls)
        assertNotNull(vm.uiState.value.historyError)
        maintenanceFailure = null
        vm.retryHistoryLoad()
        drain()
        assertEquals(initialReads + 1, store.listCalls)
        assertEquals(3, maintenanceCalls)
    }

    @Test fun completedDetailRead_cannotNavigateAfterSwitchingTab() {
        vm.setView(AppView.HISTORY)
        vm.openRide(ride("a"))
        scheduler.runCurrent()
        io.runFirst() // blocking DB read completed; Main has not received its result yet
        vm.setView(AppView.RECORDING)
        drain()
        assertEquals(AppView.RECORDING, vm.uiState.value.view)
        assertNull(vm.uiState.value.selectedRide)
        assertFalse(vm.uiState.value.isDetailLoading)
    }

    @Test fun secondDetailRequest_winsOverAlreadyReadFirstRide() {
        vm.openRide(ride("a"))
        scheduler.runCurrent()
        io.runFirst()
        vm.openRide(ride("b"))
        drain()
        assertEquals("b", vm.uiState.value.selectedRide?.id)
        assertEquals(AppView.DETAIL, vm.uiState.value.view)
    }

    @Test fun detailError_survivesUnrelatedHistoryRefreshAndRetriesCorrectRide() {
        vm.setView(AppView.HISTORY)
        store.detailFailure = IllegalStateException("read failed")
        vm.openRide(ride("b"))
        drain()
        assertNotNull(vm.uiState.value.detailError)
        vm.loadHistory()
        drain()
        assertNotNull(vm.uiState.value.detailError)
        store.detailFailure = null
        vm.retryHistoryLoad()
        drain()
        assertEquals("b", vm.uiState.value.selectedRide?.id)
        assertNull(vm.uiState.value.detailError)
    }

    @Test fun leavingFailedDetail_clearsItsRetryTarget() {
        store.detailFailure = IllegalStateException("read failed")
        vm.openRide(ride("a"))
        drain()
        vm.setView(AppView.RECORDING)
        store.detailFailure = null
        vm.retryHistoryLoad()
        drain()
        assertEquals(AppView.RECORDING, vm.uiState.value.view)
        assertNull(vm.uiState.value.detailError)
        assertNull(vm.uiState.value.selectedRide)
    }

    @Test fun savedRide_remainsVisibleWhenHistoryRefreshFails() {
        store.listFailure = IllegalStateException("history unavailable")
        recording.stopEvents.tryEmit(ride("saved"))
        drain()
        assertEquals("saved", vm.uiState.value.selectedRide?.id)
        assertEquals(AppView.DETAIL, vm.uiState.value.view)
        assertNotNull(vm.uiState.value.historyError)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test fun deleteSuccess_refreshFailureDoesNotBecomeDeleteFailure() {
        open("a")
        vm.runAnalysis()
        scheduler.runCurrent()
        analyzer.succeed("cached analysis")
        drain()
        store.listFailure = IllegalStateException("refresh failed")
        vm.requestDeleteRide("a")
        vm.confirmDeleteRide()
        drain()
        assertNull(vm.uiState.value.pendingDeleteRideId)
        assertNull(vm.uiState.value.deleteRideError)
        assertNull(vm.uiState.value.selectedRide)
        assertEquals(listOf("b"), vm.uiState.value.history.map { it.id })
        assertNotNull(vm.uiState.value.historyError)
        assertFalse(vm.uiState.value.isDeletingRide)
        // A restored ride with the same id must not reuse the deleted ride's analysis.
        store.rides["a"] = ride("a")
        open("a")
        assertNull(vm.uiState.value.aiAnalysis)
    }

    @Test fun deleteFailure_keepsRowAndCanBeRetried() {
        store.deleteFailure = IllegalStateException("write failed")
        vm.requestDeleteRide("a")
        vm.confirmDeleteRide()
        drain()
        assertEquals("a", vm.uiState.value.pendingDeleteRideId)
        assertNotNull(vm.uiState.value.deleteRideError)
        assertTrue(vm.uiState.value.history.any { it.id == "a" })
        store.deleteFailure = null
        vm.confirmDeleteRide()
        drain()
        assertEquals(listOf("b"), vm.uiState.value.history.map { it.id })
    }

    @Test fun deleteInFlight_cannotDismissOrReplaceTargetOrDoubleSubmit() {
        vm.requestDeleteRide("a")
        vm.confirmDeleteRide()
        vm.cancelDeleteRide()
        vm.requestDeleteRide("b")
        vm.confirmDeleteRide()
        assertEquals("a", vm.uiState.value.pendingDeleteRideId)
        drain()
        assertEquals(listOf("a"), store.deleted)
        assertNotNull(store.rides["b"])
    }

    @Test fun delete_invalidatesOldHistoryAndDetailSnapshots() {
        val queuedMain = QueuedMainDispatcher()
        val queuedIo = QueuedDispatcher()
        val localLifecycle = ViewModelStore()
        val localStore = FakeStore()
        var historySnapshot = emptyList<Ride>()
        var detailSnapshot: Ride? = null
        val observedStore = object : RideHistoryStore by localStore {
            override fun listRides(): List<Ride> = localStore.listRides().also { historySnapshot = it }
            override fun getRide(id: String): Ride? = localStore.getRide(id).also { detailSnapshot = it }
        }
        val delayedReplies = ArrayDeque<Runnable>()
        fun drainLocal() {
            repeat(100) {
                when {
                    queuedMain.tasks.isNotEmpty() -> queuedMain.runFirst()
                    queuedIo.tasks.isNotEmpty() -> queuedIo.runFirst()
                    else -> return
                }
            }
            error("Unexpected infinite dispatcher loop")
        }

        Dispatchers.setMain(queuedMain)
        try {
            val localVm = TrackViewModel(
                observedStore, FakeRecording(), HistoryMaintenance {},
                RideAnalysisClient { _, _ -> error("Unexpected analysis request") },
                FakeLogs(), queuedIo,
            ).also { localLifecycle.put("vm", it) }
            drainLocal()
            localVm.setView(AppView.HISTORY)
            localVm.loadHistory()
            localVm.openRide(ride("a"))
            queuedMain.runFirst() // start the history IO read
            queuedMain.runFirst() // start the detail IO read

            queuedIo.runFirst()
            assertEquals(listOf("a", "b"), historySnapshot.map { it.id })
            delayedReplies.addLast(queuedMain.tasks.removeFirst())
            queuedIo.runFirst()
            assertEquals("a", detailSnapshot?.id)
            delayedReplies.addLast(queuedMain.tasks.removeFirst())
            assertTrue(queuedMain.tasks.isEmpty())
            assertTrue(queuedIo.tasks.isEmpty())

            // Both DB reads have returned real snapshots containing A. Hold their Main replies
            // until deletion AND the subsequent fresh history read have completely finished.
            localVm.requestDeleteRide("a")
            localVm.confirmDeleteRide()
            drainLocal()
            assertEquals(listOf("a"), localStore.deleted)
            assertEquals(listOf("b"), historySnapshot.map { it.id })
            assertEquals(listOf("b"), localVm.uiState.value.history.map { it.id })
            assertEquals(AppView.HISTORY, localVm.uiState.value.view)
            assertNull(localVm.uiState.value.selectedRide)
            assertFalse(localVm.uiState.value.isDetailLoading)
            assertFalse(localVm.uiState.value.isHistoryLoading)
            val stateAfterDeletion = localVm.uiState.value

            delayedReplies.removeFirst().run() // old history containing A arrives last
            drainLocal()
            assertEquals(stateAfterDeletion, localVm.uiState.value)
            delayedReplies.removeFirst().run() // old full detail A arrives even later
            drainLocal()
            assertEquals(stateAfterDeletion, localVm.uiState.value)
        } finally {
            try {
                localLifecycle.clear()
                while (delayedReplies.isNotEmpty()) delayedReplies.removeFirst().run()
                drainLocal()
            } finally {
                Dispatchers.setMain(main)
            }
        }
    }

    @Test fun analysisFailure_canRetryAndIgnoresRepeatedClicks() {
        open("a")
        vm.runAnalysis()
        vm.runAnalysis()
        scheduler.runCurrent()
        assertEquals(1, analyzer.calls)
        analyzer.fail(IllegalStateException("offline"))
        drain()
        assertNotNull(vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isAnalysing)
        vm.runAnalysis()
        assertNull(vm.uiState.value.errorMessage)
        scheduler.runCurrent()
        analyzer.succeed("recovered")
        drain()
        assertEquals("recovered", vm.uiState.value.aiAnalysis)
        assertEquals(2, analyzer.calls)
    }

    @Test fun analysisCannotStartFromExitAnimationOrWhileAnotherDetailLoads() {
        open("a")
        vm.setView(AppView.RECORDING)
        vm.runAnalysis()
        drain()
        assertEquals(0, analyzer.calls)
        open("a")
        vm.openRide(ride("b"))
        vm.runAnalysis()
        drain()
        assertEquals(0, analyzer.calls)
    }

    @Test fun nonCooperativeAnalysis_cannotOverwriteNewRideOrItsAnalysis() {
        open("a")
        vm.runAnalysis()
        scheduler.runCurrent()
        val stale = analyzer.pending.removeFirst()
        open("b")
        vm.runAnalysis()
        scheduler.runCurrent()
        analyzer.succeed("B analysis")
        drain()
        stale.resume("late A analysis")
        drain()
        assertEquals("b", vm.uiState.value.selectedRide?.id)
        assertEquals("B analysis", vm.uiState.value.aiAnalysis)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test fun nonCooperativeAnalysisFailure_cannotClearNewRequestLoadingState() {
        open("a")
        vm.runAnalysis()
        scheduler.runCurrent()
        val stale = analyzer.pending.removeFirst()
        open("b")
        vm.runAnalysis()
        scheduler.runCurrent()
        stale.resumeWithException(IllegalStateException("late failure"))
        drain()
        assertTrue(vm.uiState.value.isAnalysing)
        assertNull(vm.uiState.value.errorMessage)
        analyzer.succeed("B")
        drain()
        assertEquals("B", vm.uiState.value.aiAnalysis)
    }

    @Test fun countdownCancelledByNavigation_neverStartsRecording() {
        vm.beginStartCountdown()
        scheduler.runCurrent()
        scheduler.advanceTimeBy(1_500)
        vm.setView(AppView.HISTORY)
        scheduler.advanceTimeBy(5_000)
        drain()
        assertEquals(0, recording.starts)
        assertNull(vm.uiState.value.startCountdownSeconds)
        vm.setView(AppView.RECORDING)
        vm.beginStartCountdown()
        scheduler.advanceTimeBy(3_000)
        drain()
        assertEquals(1, recording.starts)
    }

    @Test fun clear_cancelsAnalysisAndCollectorsButDoesNotStopApplicationRecording() {
        var cancelled = false
        val waiting = CompletableDeferred<String>()
        val second = newViewModel(RideAnalysisClient { _, _ ->
            try { waiting.await() } finally { cancelled = true }
        })
        drain()
        assertEquals(2, logs.state.subscriptionCount.value)
        second.openRide(ride("a"))
        drain()
        second.runAnalysis()
        scheduler.runCurrent()
        lifecycle.clear()
        drain()
        assertTrue(cancelled)
        assertEquals(0, logs.state.subscriptionCount.value)
        assertEquals(0, recording.state.subscriptionCount.value)
        assertEquals(0, recording.stops)
    }

    @Test fun clear_rejectsLateNonCooperativeAnalysisResult() {
        open("a")
        vm.runAnalysis()
        scheduler.runCurrent()
        val before = vm.uiState.value
        lifecycle.clear()
        analyzer.succeed("late result after clearing")
        drain()
        assertEquals(before, vm.uiState.value)
        assertEquals(0, logs.state.subscriptionCount.value)
    }

    private fun open(id: String) { vm.openRide(ride(id)); drain() }

    /** Drain only explicit queued work. No sleeps, real threads, SDKs or network. */
    private fun drain() {
        repeat(100) {
            scheduler.runCurrent()
            if (io.tasks.isEmpty()) return
            io.runFirst()
        }
        error("Unexpected infinite dispatcher loop")
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runFirst() = tasks.removeFirst().run()
        fun runLast() = tasks.removeLast().run()
    }

    /** Main replies can be withheld independently of IO, including after their jobs are cancelled. */
    private class QueuedMainDispatcher : MainCoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override val immediate: MainCoroutineDispatcher get() = this
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runFirst() = tasks.removeFirst().run()
    }

    private class FakeStore : RideHistoryStore {
        val rides = linkedMapOf("a" to ride("a"), "b" to ride("b"))
        var listFailure: Exception? = null
        var detailFailure: Exception? = null
        var deleteFailure: Exception? = null
        var listCalls = 0
        val deleted = mutableListOf<String>()
        override fun listRides(): List<Ride> {
            listCalls++
            listFailure?.let { throw it }
            return rides.values.toList()
        }
        override fun getRide(id: String): Ride? { detailFailure?.let { throw it }; return rides[id] }
        override fun deleteRide(id: String) { deleteFailure?.let { throw it }; rides.remove(id); deleted += id }
        override fun repairHistoricalRides(): Int = 0
    }

    private class FakeRecording : RecordingController {
        override val state = MutableStateFlow(RecordingSessionState())
        override val stopEvents = MutableSharedFlow<Ride>(extraBufferCapacity = 1)
        var starts = 0
        var stops = 0
        override fun startRecording(hasFineLocation: Boolean) { starts++ }
        override fun togglePause(hasFineLocation: Boolean) {}
        override fun stopRecording(onComplete: (Ride?) -> Unit) { stops++ }
    }

    private class FakeLogs : TrackDebugLogs {
        override val state = MutableStateFlow(DebugLogState())
        override fun toggle() { state.value = state.value.copy(isRecording = !state.value.isRecording) }
        override fun appendLocation(message: String) {}
        override fun save() = "saved"
    }

    private class DeferredAnalysis : RideAnalysisClient {
        var calls = 0
        val pending = ArrayDeque<Continuation<String>>()
        override suspend fun analyze(prompt: String, requestId: String): String {
            calls++
            return suspendCoroutine { pending.addLast(it) }
        }
        fun succeed(text: String) { pending.removeFirst().resume(text) }
        fun fail(error: Exception) { pending.removeFirst().resumeWithException(error) }
    }

    companion object {
        private fun ride(id: String) = Ride(id, "Ride $id", 1_000L, 2_000L, emptyList(), 50.0, 5.0, 6.0)
    }
}
