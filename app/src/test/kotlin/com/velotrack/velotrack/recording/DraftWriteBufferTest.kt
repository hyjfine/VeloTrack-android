package com.velotrack.velotrack.recording

import com.velotrack.velotrack.GpsPoint
import org.junit.Assert.*
import org.junit.Test

class DraftWriteBufferTest {
    private fun point(i: Int) = GpsPoint(31.0, 121.0, i.toLong(), 0.0, null, 3.0)

    @Test fun failedAppendKeepsEntireBatchForRetry() {
        val buffer = DraftWriteBuffer()
        val points = (0..2).map(::point)
        repeat(3) {
            runCatching { buffer.flush(points, {}, { _, _ -> error("disk full") }) }
        }
        assertEquals(0, buffer.acknowledgedPointCount)
        buffer.flush(points + point(3), {}, { start, batch ->
            assertEquals(0, start)
            assertEquals(4, batch.size)
        })
        assertEquals(4, buffer.acknowledgedPointCount)
        buffer.flush(points + point(3), {}, { _, batch -> assertTrue(batch.isEmpty()) })
    }

    @Test fun creationFailurePreventsAppendAndCanRecover() {
        val buffer = DraftWriteBuffer()
        var appended = false
        runCatching { buffer.flush(listOf(point(0)), { error("cannot create draft") }, { _, _ -> appended = true }) }
        assertFalse(appended)
        assertEquals(0, buffer.acknowledgedPointCount)
        buffer.flush(listOf(point(0)), {}, { _, _ -> appended = true })
        assertTrue(appended)
    }
}
