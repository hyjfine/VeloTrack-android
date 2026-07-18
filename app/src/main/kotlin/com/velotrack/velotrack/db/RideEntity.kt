package com.velotrack.velotrack.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startTime: Long,
    val endTime: Long?,
    val totalDistance: Double,
    val avgSpeed: Double,
    val maxSpeed: Double,
    val movingDurationSec: Double,
)

@Entity(
    tableName = "gps_points",
    primaryKeys = ["rideId", "pointIndex"],
    foreignKeys = [
        ForeignKey(
            entity = RideEntity::class,
            parentColumns = ["id"],
            childColumns = ["rideId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rideId")],
)
data class GpsPointEntity(
    val rideId: String,
    val pointIndex: Int,
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val speedMps: Double,
    val altitude: Double?,
    val accuracy: Double,
    val segmentId: Int,
    /** 同一 segmentId 内有效的单调时间；跨段禁止比较。 */
    val monotonicMs: Long,
)
