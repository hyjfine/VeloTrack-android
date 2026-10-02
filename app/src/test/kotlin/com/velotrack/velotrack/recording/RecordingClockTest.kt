package com.velotrack.velotrack.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingClockTest {
    @Test fun pauseAndRecoveryExcludeTimeSpentPaused() {
        var now = 0L
        val clock = RecordingClock { now }
        clock.resume()
        now += 120_000
        clock.pause()
        now += 600_000
        assertEquals(120_000, clock.elapsedMs)
        clock.resume()
        now += 120_000
        clock.pause()
        val restored = RecordingClock { now }.apply { restore(clock.elapsedMs) }
        assertEquals(240_000, restored.elapsedMs)
        now += 1_000_000
        assertEquals(240_000, restored.elapsedMs)
    }

    @Test fun repeatedPauseIsIdempotentAfterFailure() {
        var now = 100L
        val clock = RecordingClock { now }
        clock.resume()
        now += 1000
        clock.pause()
        now += 1000
        clock.pause()
        assertEquals(1000, clock.elapsedMs)
    }
}
