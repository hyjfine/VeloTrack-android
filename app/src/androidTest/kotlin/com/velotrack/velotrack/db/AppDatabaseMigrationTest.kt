package com.velotrack.velotrack.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate2To3_addsMovingDurationWithoutLosingRide() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                """
                INSERT INTO rides
                (id, title, startTime, endTime, totalDistance, avgSpeed, maxSpeed)
                VALUES ('ride-1', 'Test', 1000, 61000, 1000.0, 5.0, 8.0)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            AppDatabase.MIGRATION_2_3,
        )
        db.query("SELECT id, movingDurationSec FROM rides").use { cursor ->
            cursor.moveToFirst()
            assertEquals("ride-1", cursor.getString(0))
            assertEquals(0.0, cursor.getDouble(1), 0.0)
        }
        db.close()
    }

    @Test
    fun migrate3To4_addsSegmentIdWithoutLosingPoint() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                """
                INSERT INTO rides
                (id, title, startTime, endTime, totalDistance, avgSpeed, maxSpeed, movingDurationSec)
                VALUES ('ride-1', 'Test', 1000, 61000, 1000.0, 5.0, 8.0, 60.0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO gps_points
                (rideId, pointIndex, lat, lng, timestamp, speedMps, altitude, accuracy)
                VALUES ('ride-1', 0, 31.0, 121.0, 1000, 5.0, NULL, 3.0)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            AppDatabase.MIGRATION_3_4,
        )
        db.query("SELECT rideId, segmentId FROM gps_points").use { cursor ->
            cursor.moveToFirst()
            assertEquals("ride-1", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
        db.close()
    }

    @Test
    fun migrate4To5_addsMonotonicTimeWithoutLosingPoint() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                """
                INSERT INTO rides
                (id, title, startTime, endTime, totalDistance, avgSpeed, maxSpeed, movingDurationSec)
                VALUES ('ride-1', 'Test', 1000, 61000, 1000.0, 5.0, 8.0, 60.0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO gps_points
                (rideId, pointIndex, lat, lng, timestamp, speedMps, altitude, accuracy, segmentId)
                VALUES ('ride-1', 0, 31.0, 121.0, 1000, 5.0, NULL, 3.0, 0)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            AppDatabase.MIGRATION_4_5,
        )
        db.query("SELECT rideId, monotonicMs FROM gps_points").use { cursor ->
            cursor.moveToFirst()
            assertEquals("ride-1", cursor.getString(0))
            assertEquals(0L, cursor.getLong(1))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
