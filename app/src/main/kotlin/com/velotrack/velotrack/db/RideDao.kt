package com.velotrack.velotrack.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RideDao {
    @Query("SELECT * FROM rides ORDER BY startTime DESC")
    fun getAllBlocking(): List<RideEntity>

    @Query("SELECT * FROM rides WHERE id = :id LIMIT 1")
    fun getByIdBlocking(id: String): RideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBlocking(entity: RideEntity)

    @Query("DELETE FROM rides WHERE id = :id")
    fun deleteByIdBlocking(id: String)
}
