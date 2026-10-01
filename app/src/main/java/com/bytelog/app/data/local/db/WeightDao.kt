package com.bytelog.app.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bytelog.app.data.local.model.WeightEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface WeightDao {

    @Query("SELECT * FROM weight_entries WHERE entryDate = :date LIMIT 1")
    fun getWeightForDate(date: String): Flow<WeightEntry?>

    @Query("SELECT * FROM weight_entries WHERE entryDate <= :date ORDER BY entryDate DESC LIMIT 1")
    fun getLatestWeightOnOrBefore(date: String): Flow<WeightEntry?>

    @Query("SELECT * FROM weight_entries ORDER BY entryDate DESC LIMIT :limit")
    fun getRecentWeights(limit: Int = 30): Flow<List<WeightEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entry: WeightEntry)

    @Query("DELETE FROM weight_entries WHERE entryDate = :date")
    suspend fun deleteByDate(date: String)
}
