package com.velotrack.velotrack.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class RideDao {
    @Query("SELECT * FROM rides ORDER BY startTime DESC")
    abstract fun getAllBlocking(): List<RideEntity>

    @Query("SELECT * FROM rides WHERE id = :id LIMIT 1")
    abstract fun getByIdBlocking(id: String): RideEntity?

    @Query("SELECT * FROM gps_points WHERE rideId = :rideId ORDER BY pointIndex ASC")
    abstract fun getPointsForRideBlocking(rideId: String): List<GpsPointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract fun insertRideBlocking(entity: RideEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract fun insertPointsBlocking(points: List<GpsPointEntity>)

    @Query("DELETE FROM rides WHERE id = :id")
    protected abstract fun deleteRideByIdBlocking(id: String)

    @Query("DELETE FROM gps_points WHERE rideId = :rideId")
    protected abstract fun deletePointsByRideIdBlocking(rideId: String)

    @Transaction
    open fun saveRideWithPointsBlocking(entity: RideEntity, points: List<GpsPointEntity>) {
        insertRideBlocking(entity)
        deletePointsByRideIdBlocking(entity.id)
        if (points.isNotEmpty()) {
            insertPointsBlocking(points)
        }
    }

    @Transaction
    open fun deleteByIdBlocking(id: String) {
        deletePointsByRideIdBlocking(id)
        deleteRideByIdBlocking(id)
    }
}
