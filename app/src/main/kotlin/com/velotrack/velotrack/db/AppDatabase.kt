package com.velotrack.velotrack.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray

@Database(entities = [RideEntity::class, GpsPointEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "velotrack.db",
                ).addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `gps_points_migration` (
                        `rideId` TEXT NOT NULL,
                        `pointIndex` INTEGER NOT NULL,
                        `lat` REAL NOT NULL,
                        `lng` REAL NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `speedMps` REAL NOT NULL,
                        `altitude` REAL,
                        `accuracy` REAL NOT NULL,
                        PRIMARY KEY(`rideId`, `pointIndex`)
                    )
                    """.trimIndent(),
                )

                db.query("SELECT `id`, `pointsJson` FROM `rides`").use { cursor ->
                    val rideIdIndex = cursor.getColumnIndexOrThrow("id")
                    val pointsJsonIndex = cursor.getColumnIndexOrThrow("pointsJson")
                    val insertPoint = db.compileStatement(
                        """
                        INSERT OR REPLACE INTO `gps_points_migration`
                        (`rideId`, `pointIndex`, `lat`, `lng`, `timestamp`, `speedMps`, `altitude`, `accuracy`)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    )
                    while (cursor.moveToNext()) {
                        val rideId = cursor.getString(rideIdIndex)
                        val rawPoints = cursor.getString(pointsJsonIndex).orEmpty()
                        if (rawPoints.isBlank()) continue

                        val points = runCatching { JSONArray(rawPoints) }.getOrNull() ?: continue
                        for (i in 0 until points.length()) {
                            val point = points.optJSONObject(i) ?: continue
                            insertPoint.clearBindings()
                            insertPoint.bindString(1, rideId)
                            insertPoint.bindLong(2, i.toLong())
                            insertPoint.bindDouble(3, point.optDouble("lat"))
                            insertPoint.bindDouble(4, point.optDouble("lng"))
                            insertPoint.bindLong(5, point.optLong("timestamp"))
                            insertPoint.bindDouble(6, point.optDouble("speed"))
                            if (point.isNull("altitude")) {
                                insertPoint.bindNull(7)
                            } else {
                                insertPoint.bindDouble(7, point.optDouble("altitude"))
                            }
                            insertPoint.bindDouble(8, point.optDouble("accuracy", 0.0))
                            insertPoint.executeInsert()
                        }
                    }
                }

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `rides_new` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `startTime` INTEGER NOT NULL,
                        `endTime` INTEGER,
                        `totalDistance` REAL NOT NULL,
                        `avgSpeed` REAL NOT NULL,
                        `maxSpeed` REAL NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `rides_new` (`id`, `title`, `startTime`, `endTime`, `totalDistance`, `avgSpeed`, `maxSpeed`)
                    SELECT `id`, `title`, `startTime`, `endTime`, `totalDistance`, `avgSpeed`, `maxSpeed` FROM `rides`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `rides`")
                db.execSQL("ALTER TABLE `rides_new` RENAME TO `rides`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `gps_points` (
                        `rideId` TEXT NOT NULL,
                        `pointIndex` INTEGER NOT NULL,
                        `lat` REAL NOT NULL,
                        `lng` REAL NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `speedMps` REAL NOT NULL,
                        `altitude` REAL,
                        `accuracy` REAL NOT NULL,
                        PRIMARY KEY(`rideId`, `pointIndex`),
                        FOREIGN KEY(`rideId`) REFERENCES `rides`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gps_points_rideId` ON `gps_points` (`rideId`)")
                db.execSQL(
                    """
                    INSERT INTO `gps_points` (`rideId`, `pointIndex`, `lat`, `lng`, `timestamp`, `speedMps`, `altitude`, `accuracy`)
                    SELECT `rideId`, `pointIndex`, `lat`, `lng`, `timestamp`, `speedMps`, `altitude`, `accuracy`
                    FROM `gps_points_migration`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `gps_points_migration`")
            }
        }
    }
}
