package com.velotrack.velotrack.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.Ride
import com.velotrack.velotrack.RideRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RidePersistenceTest {
    @Test fun repeatedCreationCheckpointAndIncrementalFinalizePreservePoints() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val repo = RideRepository(db.rideDao())
            val points = (0..2).map { GpsPoint(31.0, 121.0, 1000L + it * 1200, 0.0, null, 3.0) }
            repo.beginDraftRide("draft", "Test", 1000)
            repo.appendTrackPoints("draft", 0, points.take(2), 240_000)
            repo.beginDraftRide("draft", "Test", 1000)
            val draft = requireNotNull(repo.getActiveDraftRide())
            assertEquals(2, draft.points.size)
            assertEquals(240_000L, draft.activeDurationMs)
            val ride = Ride("draft", "Test", 1000, 900000, points, 0.0, 0.0, 0.0, 0.0, 240_000)
            repo.finalizeRide(ride, acknowledgedPointCount = 2)
            assertEquals(3, requireNotNull(repo.getRide("draft")).points.size)
            assertEquals(0.0, repo.listRides().single().movingDurationSec, 0.0)
            assertEquals(0.0, requireNotNull(repo.getRide("draft")).movingDurationSec, 0.0)
            assertNull(repo.getActiveDraftRide())
            repo.deleteRide("draft")
            assertTrue(db.rideDao().getPointsForRideBlocking("draft").isEmpty())
        } finally { db.close() }
    }
    @Test fun failedRoomTransactionKeepsUnacknowledgedBatch() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val repo = RideRepository(db.rideDao())
            val buffer = com.velotrack.velotrack.recording.DraftWriteBuffer()
            val points = (0..2).map { GpsPoint(31.0, 121.0, 1000L + it * 1200, 0.0, null, 3.0) }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER fail_append BEFORE INSERT ON gps_points BEGIN SELECT RAISE(ABORT, 'injected disk failure'); END")
            fun flush() = buffer.flush(points,
                ensureDraft = { repo.beginDraftRide("retry", "Test", 1000) },
                append = { index, batch -> repo.appendTrackPoints("retry", index, batch, 2400) })
            repeat(3) { assertTrue(runCatching { flush() }.isFailure) }
            assertEquals(0, buffer.acknowledgedPointCount)
            assertTrue(requireNotNull(repo.getActiveDraftRide()).points.isEmpty())
            sql.execSQL("DROP TRIGGER fail_append")
            flush()
            assertEquals(3, buffer.acknowledgedPointCount)
            assertEquals(3, requireNotNull(repo.getActiveDraftRide()).points.size)
            assertEquals(2400L, requireNotNull(repo.getActiveDraftRide()).activeDurationMs)
        } finally { db.close() }
    }

}
