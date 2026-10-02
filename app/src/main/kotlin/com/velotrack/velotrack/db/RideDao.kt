package com.velotrack.velotrack.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert

@Dao
abstract class RideDao {
    @Query("SELECT * FROM rides WHERE endTime IS NOT NULL ORDER BY startTime DESC")
    abstract fun getAllBlocking(): List<RideEntity>

    @Query("SELECT * FROM rides WHERE id = :id LIMIT 1")
    abstract fun getByIdBlocking(id: String): RideEntity?

    @Query("SELECT * FROM gps_points WHERE rideId = :rideId ORDER BY pointIndex ASC")
    abstract fun getPointsForRideBlocking(rideId: String): List<GpsPointEntity>

    /** 未完成录制（后台服务进行中）。 */
    @Query("SELECT * FROM rides WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    abstract fun getActiveDraftRideBlocking(): RideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract fun insertRideBlocking(entity: RideEntity)

    @Upsert
    protected abstract fun upsertRideBlocking(entity: RideEntity)

    @Update
    protected abstract fun updateRideBlocking(entity: RideEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract fun insertPointsBlocking(points: List<GpsPointEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun ensureDraftBlocking(entity: RideEntity)

    @Query("DELETE FROM rides WHERE id = :id")
    protected abstract fun deleteRideByIdBlocking(id: String)

    @Query("DELETE FROM gps_points WHERE rideId = :rideId")
    protected abstract fun deletePointsByRideIdBlocking(rideId: String)

    @Query("UPDATE rides SET activeDurationMs = :elapsedMs WHERE id = :rideId AND endTime IS NULL")
    protected abstract fun updateCheckpointBlocking(rideId: String, elapsedMs: Long)

    @Transaction
    open fun saveRideWithPointsBlocking(entity: RideEntity, points: List<GpsPointEntity>) {
        insertRideBlocking(entity)
        deletePointsByRideIdBlocking(entity.id)
        if (points.isNotEmpty()) {
            insertPointsBlocking(points)
        }
    }

    /** 修复计算在事务外完成；写回前拒绝已删除或已被其他任务更新的旧快照。 */
    @Transaction
    open fun repairRideWithPointsBlocking(
        entity: RideEntity,
        expectedStatsVersion: Int,
        points: List<GpsPointEntity>,
    ): Boolean {
        val current = getByIdBlocking(entity.id) ?: return false
        if (current.endTime == null || current.endTime != entity.endTime ||
            current.statsVersion != expectedStatsVersion
        ) return false

        updateRideBlocking(
            current.copy(
                totalDistance = entity.totalDistance,
                avgSpeed = entity.avgSpeed,
                maxSpeed = entity.maxSpeed,
                movingDurationSec = entity.movingDurationSec,
                statsVersion = entity.statsVersion,
            ),
        )
        deletePointsByRideIdBlocking(entity.id)
        if (points.isNotEmpty()) insertPointsBlocking(points)
        return true
    }

    @Transaction
    open fun deleteByIdBlocking(id: String) {
        deletePointsByRideIdBlocking(id)
        deleteRideByIdBlocking(id)
    }

    @Transaction
    open fun insertDraftRideBlocking(entity: RideEntity) {
        // Retrying draft creation must not REPLACE the parent and cascade-delete its points.
        ensureDraftBlocking(entity)
    }

    @Transaction
    open fun appendPointsBlocking(rideId: String, points: List<GpsPointEntity>, elapsedMs: Long) {
        if (points.isNotEmpty()) {
            insertPointsBlocking(points)
        }
        updateCheckpointBlocking(rideId, elapsedMs)
    }

    @Transaction
    open fun finalizeRideBlocking(entity: RideEntity, points: List<GpsPointEntity>) {
        // UPDATE preserves the acknowledged prefix and its foreign-key children.
        upsertRideBlocking(entity)
        if (points.isNotEmpty()) {
            insertPointsBlocking(points)
        }
    }
}
