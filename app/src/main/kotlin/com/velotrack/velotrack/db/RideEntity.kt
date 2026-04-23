package com.velotrack.velotrack.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startTime: Long,
    val endTime: Long?,
    val pointsJson: String,
    val totalDistance: Double,
    val avgSpeed: Double,
    val maxSpeed: Double,
)
