package com.bytelog.app.data.repository

import com.bytelog.app.data.local.db.FoodDao
import com.bytelog.app.data.local.db.FoodEntryDao
import com.bytelog.app.data.local.db.MealTypeDao
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.remote.SparkyApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class FoodRepository(
    private val foodDao: FoodDao,
    private val foodEntryDao: FoodEntryDao,
    private val mealTypeDao: MealTypeDao,
    private val sparkyApiClient: SparkyApiClient,
    private val appScope: CoroutineScope
) {

    // --- Food Queries ---

    fun searchLocalFoods(query: String): Flow<List<FoodItem>> {
        return foodDao.searchFoods(query.trim())
    }

    suspend fun searchRemoteFoods(query: String): List<FoodItem> {
        val result = sparkyApiClient.searchFoods(query)
        if (result.isSuccess) {
            val remoteFoods = result.getOrNull().orEmpty()
            if (remoteFoods.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    foodDao.insertFoods(remoteFoods)
                }
            }
            return remoteFoods
        }
        return emptyList()
    }

    fun getRecentFoods(limit: Int = 25): Flow<List<FoodItem>> {
        return foodDao.getRecentFoods(limit)
    }

    fun getFavoriteFoods(): Flow<List<FoodItem>> {
        return foodDao.getFavoriteFoods()
    }

    fun getMealSuggestions(mealName: String): Flow<List<FoodItem>> {
        val affinity = when (mealName.lowercase()) {
            "breakfast" -> "breakfast"
            "lunch" -> "lunch"
            "dinner" -> "dinner"
            else -> "snacks"
        }
        return foodDao.getSuggestedFoodsForMeal(affinity)
    }

    suspend fun lookupBarcode(barcode: String): FoodItem? {
        val localMatch = foodDao.getFoodByBarcode(barcode)
        if (localMatch != null) return localMatch

        val remoteResult = sparkyApiClient.getFoodByBarcode(barcode)
        val remoteFood = remoteResult.getOrNull()
        if (remoteFood != null) {
            foodDao.insertFood(remoteFood)
            return remoteFood
        }
        return null
    }

    suspend fun toggleFavorite(foodId: String, currentStatus: Boolean) {
        foodDao.setFavorite(foodId, !currentStatus)
    }

    // --- Diary Entry Queries & Mutations ---

    fun getEntriesForDate(date: String): Flow<List<FoodEntry>> {
        return foodEntryDao.getEntriesForDate(date)
    }

    fun getAllMealTypes(): Flow<List<MealType>> {
        return mealTypeDao.getAllMealTypes()
    }

    /**
     * Commits a batch of entries (from Multi-Add Tray) atomically to local DB,
     * updates usage counts, and triggers background sync to Sparky Fitness.
     */
    suspend fun commitMultiAdd(entries: List<FoodEntry>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext

        // 1. Instant local persistence
        foodEntryDao.insertEntries(entries)

        // 2. Update food usage statistics for intelligent ranking
        val now = System.currentTimeMillis()
        entries.forEach { entry ->
            entry.foodId?.let { fid ->
                foodDao.recordFoodUsage(fid, now)
            }
        }

        // 3. Asynchronously sync to Sparky Fitness Server
        appScope.launch(Dispatchers.IO) {
            entries.forEach { entry ->
                val syncResult = sparkyApiClient.createFoodEntry(entry)
                if (syncResult.isSuccess) {
                    foodEntryDao.updateSyncStatus(entry.id, FoodEntry.SYNC_STATUS_SYNCED)
                }
            }
        }
    }

    /**
     * Duplicate an existing entry to a new time/date/meal.
     */
    suspend fun duplicateEntry(
        source: FoodEntry,
        targetDate: String,
        targetMealTypeId: String,
        targetMealName: String
    ) = withContext(Dispatchers.IO) {
        val newEntry = source.copy(
            id = UUID.randomUUID().toString(),
            entryDate = targetDate,
            mealTypeId = targetMealTypeId,
            mealName = targetMealName,
            syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT,
            createdAt = System.currentTimeMillis()
        )
        commitMultiAdd(listOf(newEntry))
    }

    /**
     * Delete an entry optimistically.
     */
    suspend fun deleteEntry(entry: FoodEntry) = withContext(Dispatchers.IO) {
        foodEntryDao.deleteEntryById(entry.id)
        appScope.launch(Dispatchers.IO) {
            sparkyApiClient.deleteFoodEntry(entry.id)
        }
    }

    /**
     * Sync daily entries from Sparky Fitness Server.
     */
    suspend fun syncDateWithServer(date: String) = withContext(Dispatchers.IO) {
        val result = sparkyApiClient.fetchEntriesForDate(date)
        if (result.isSuccess) {
            val serverEntries = result.getOrNull().orEmpty()
            if (serverEntries.isNotEmpty()) {
                foodEntryDao.insertEntries(serverEntries)
            }
        }
    }
}
