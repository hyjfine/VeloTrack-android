package com.velotrack.velotrack.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoricalRideRepairTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: RideDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.rideDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun staleRepairCannotRecreateDeletedRide() {
        val snapshot = seedRide()
        val repair = repairedSnapshot(snapshot)

        dao.deleteByIdBlocking(snapshot.id)
        val updated = dao.repairRideWithPointsBlocking(
            repair,
            expectedStatsVersion = snapshot.statsVersion,
            points = listOf(point.copy(segmentId = 1)),
        )

        assertFalse(updated)
        assertNull(dao.getByIdBlocking(snapshot.id))
        assertTrue(dao.getPointsForRideBlocking(snapshot.id).isEmpty())
    }

    @Test
    fun staleRepairCannotOverwriteNewerStatisticsOrPoints() {
        val snapshot = seedRide()
        val newer = snapshot.copy(statsVersion = 2, totalDistance = 42.0)
        val newerPoints = listOf(point.copy(lat = 32.0, segmentId = 2))
        dao.saveRideWithPointsBlocking(newer, newerPoints)

        val updated = dao.repairRideWithPointsBlocking(
            repairedSnapshot(snapshot),
            expectedStatsVersion = snapshot.statsVersion,
            points = listOf(point.copy(segmentId = 1)),
        )

        assertFalse(updated)
        assertEquals(newer, dao.getByIdBlocking(snapshot.id))
        assertEquals(newerPoints, dao.getPointsForRideBlocking(snapshot.id))
    }

    @Test
    fun staleRepairCannotOverwriteChangedEndTimeOrActiveDraft() {
        val snapshot = seedRide()
        for (endTime in listOf(null, 4_000L)) {
            val changed = snapshot.copy(endTime = endTime)
            dao.saveRideWithPointsBlocking(changed, listOf(point))

            val updated = dao.repairRideWithPointsBlocking(
                repairedSnapshot(snapshot),
                expectedStatsVersion = snapshot.statsVersion,
                points = listOf(point.copy(segmentId = 1)),
            )

            assertFalse(updated)
            assertEquals(changed, dao.getByIdBlocking(snapshot.id))
            assertEquals(listOf(point), dao.getPointsForRideBlocking(snapshot.id))
        }
    }

    @Test
    fun matchingRepairUpdatesStatisticsAndSegmentsWithoutOverwritingMetadata() {
        val snapshot = seedRide()
        val current = snapshot.copy(title = "Renamed ride", activeDurationMs = 2_500L)
        dao.saveRideWithPointsBlocking(current, listOf(point))
        val repair = repairedSnapshot(snapshot)
        val repairedPoints = listOf(point.copy(segmentId = 1))

        assertTrue(
            dao.repairRideWithPointsBlocking(
                repair,
                expectedStatsVersion = snapshot.statsVersion,
                points = repairedPoints,
            ),
        )

        assertEquals(
            repair.copy(title = current.title, activeDurationMs = current.activeDurationMs),
            dao.getByIdBlocking(snapshot.id),
        )
        assertEquals(repairedPoints, dao.getPointsForRideBlocking(snapshot.id))
    }

    private fun seedRide(): RideEntity {
        val ride = RideEntity(
            id = "history",
            title = "Original ride",
            startTime = 1_000L,
            endTime = 3_000L,
            totalDistance = 0.0,
            avgSpeed = 0.0,
            maxSpeed = 0.0,
            movingDurationSec = 0.0,
            activeDurationMs = 2_000L,
            statsVersion = 0,
        )
        dao.saveRideWithPointsBlocking(ride, listOf(point))
        return requireNotNull(dao.getByIdBlocking(ride.id))
    }

    private fun repairedSnapshot(snapshot: RideEntity): RideEntity = snapshot.copy(
        totalDistance = 12.0,
        avgSpeed = 3.0,
        maxSpeed = 4.0,
        movingDurationSec = 4.0,
        statsVersion = 1,
    )

    private val point = GpsPointEntity(
        rideId = "history",
        pointIndex = 0,
        lat = 31.0,
        lng = 121.0,
        timestamp = 2_000L,
        speedMps = 3.0,
        altitude = null,
        accuracy = 3.0,
        segmentId = 0,
        monotonicMs = 10_000L,
    )
}
