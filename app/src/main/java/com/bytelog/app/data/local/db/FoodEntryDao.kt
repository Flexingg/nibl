package com.bytelog.app.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bytelog.app.data.local.model.FoodEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodEntryDao {
    @Query("SELECT * FROM food_entries WHERE entryDate = :date ORDER BY createdAt ASC")
    fun getEntriesForDate(date: String): Flow<List<FoodEntry>>

    @Query("SELECT * FROM food_entries WHERE entryDate = :date ORDER BY createdAt ASC")
    suspend fun getEntriesForDateOnce(date: String): List<FoodEntry>

    @Query("SELECT * FROM food_entries WHERE id = :id LIMIT 1")
    suspend fun getEntryById(id: String): FoodEntry?

    @Query("SELECT * FROM food_entries WHERE syncStatus != 'synced'")
    suspend fun getPendingSyncEntries(): List<FoodEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<FoodEntry>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: FoodEntry)

    @Update
    suspend fun updateEntry(entry: FoodEntry)

    @Query("DELETE FROM food_entries WHERE id = :id")
    suspend fun deleteEntryById(id: String)

    @Query("DELETE FROM food_entries WHERE id IN (:ids)")
    suspend fun deleteEntriesByIds(ids: List<String>)

    @Query("DELETE FROM food_entries WHERE entryDate = :date AND LOWER(TRIM(mealName)) = LOWER(TRIM(:mealName))")
    suspend fun deleteMealEntries(date: String, mealName: String)

    @Query("UPDATE food_entries SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String)

    @Query("DELETE FROM food_entries WHERE entryDate = :date")
    suspend fun deleteEntriesForDate(date: String)

    @Query("DELETE FROM food_entries WHERE entryDate = :date AND syncStatus = 'synced'")
    suspend fun deleteSyncedEntriesForDate(date: String)

    @Query("DELETE FROM food_entries")
    suspend fun clearAllEntries()

    @Query("""
        SELECT fe.* FROM food_entries fe
        INNER JOIN (
            SELECT LOWER(foodName) as lowerName, MAX(createdAt) as maxCreated
            FROM food_entries
            GROUP BY LOWER(foodName)
        ) latest ON LOWER(fe.foodName) = latest.lowerName AND fe.createdAt = latest.maxCreated
        ORDER BY fe.createdAt DESC
        LIMIT :limit
    """)
    fun getRecentlyLoggedEntries(limit: Int = 30): Flow<List<FoodEntry>>

    @Query("SELECT * FROM food_entries WHERE entryDate >= :startDate AND entryDate <= :endDate ORDER BY entryDate ASC, createdAt ASC")
    fun getEntriesInRange(startDate: String, endDate: String): Flow<List<FoodEntry>>

    @Query("SELECT * FROM food_entries ORDER BY entryDate ASC, createdAt ASC")
    fun getAllEntries(): Flow<List<FoodEntry>>
}
