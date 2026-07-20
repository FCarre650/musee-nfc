package com.museenfc.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {
    @Insert
    suspend fun insert(scan: ScanEntity): Long

    @Update
    suspend fun update(scan: ScanEntity)

    @Query("SELECT * FROM scan_queue WHERE syncState = 'PENDING' ORDER BY createdAtMillis ASC")
    suspend fun pending(): List<ScanEntity>

    @Query("SELECT * FROM scan_queue ORDER BY createdAtMillis DESC LIMIT 100")
    fun recent(): Flow<List<ScanEntity>>

    @Query("SELECT COUNT(*) FROM scan_queue WHERE syncState = 'PENDING'")
    fun pendingCount(): Flow<Int>
}
