package com.bytelog.app.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bytelog.app.data.local.model.WaterLogEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface WaterLogDao {
    @Query("SELECT * FROM water_log_entries WHERE entryDate = :date AND syncStatus != 2 ORDER BY loggedAt DESC, id DESC")
    fun getLogsForDate(date: String): Flow<List<WaterLogEntry>>

    @Query("SELECT COALESCE(SUM(waterMl), 0.0) FROM water_log_entries WHERE entryDate = :date AND syncStatus != 2")
    fun getTotalWaterForDate(date: String): Flow<Float>

    @Query("SELECT * FROM water_log_entries WHERE entryDate = :date AND syncStatus != 2 ORDER BY loggedAt DESC, id DESC LIMIT 1")
    suspend fun getLatestLogForDate(date: String): WaterLogEntry?

    @Query("SELECT * FROM water_log_entries WHERE syncStatus = 1")
    suspend fun getPendingInsertLogs(): List<WaterLogEntry>

    @Query("SELECT * FROM water_log_entries WHERE syncStatus = 2")
    suspend fun getPendingDeleteLogs(): List<WaterLogEntry>

    @Query("SELECT COUNT(*) FROM water_log_entries WHERE syncStatus != 0")
    suspend fun countPendingSync(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(entry: WaterLogEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(entries: List<WaterLogEntry>)

    @Update
    suspend fun updateLog(entry: WaterLogEntry)

    @Query("UPDATE water_log_entries SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: Int)

    @Query("DELETE FROM water_log_entries WHERE id = :id")
    suspend fun deleteLogById(id: String)

    @Query("DELETE FROM water_log_entries WHERE entryDate = :date AND syncStatus = 0")
    suspend fun deleteSyncedLogsForDate(date: String)

    @Query("DELETE FROM water_log_entries")
    suspend fun clearAllLogs()
}
