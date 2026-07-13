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

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
