package com.bytelog.app.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bytelog.app.data.local.model.PlannedEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface PlannedEntryDao {
    @Query("SELECT * FROM planned_entries WHERE entryDate = :date ORDER BY createdAt ASC")
    fun forDate(date: String): Flow<List<PlannedEntry>>

    /** Everything planned from [from] to [to] (YYYY-MM-DD, both included). */
    @Query("SELECT * FROM planned_entries WHERE entryDate >= :from AND entryDate <= :to ORDER BY entryDate ASC, createdAt ASC")
    fun between(from: String, to: String): Flow<List<PlannedEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entries: List<PlannedEntry>)

    @Query("DELETE FROM planned_entries WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}
