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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class FoodRepository(
    private val foodDao: FoodDao,
    private val foodEntryDao: FoodEntryDao,
    private val mealTypeDao: MealTypeDao,
    private val sparkyApiClient: SparkyApiClient,
    private val settingsRepository: SettingsRepository,
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
        return getRecentlyLoggedFoods(limit)
    }

    fun getRecentlyLoggedFoods(limit: Int = 30): Flow<List<FoodItem>> {
        return foodEntryDao.getRecentlyLoggedEntries(limit).map { entries ->
            entries.map { entry ->
                FoodItem(
                    id = entry.foodId ?: entry.id,
                    name = entry.foodName,
                    brand = entry.brandName,
                    servingSize = entry.quantity,
                    servingUnit = entry.unit,
                    calories = entry.calories,
                    protein = entry.protein,
                    carbs = entry.carbs,
                    fat = entry.fat,
                    fiber = 0f
                )
            }
        }
    }

    fun getFavoriteFoods(): Flow<List<FoodItem>> {
        return foodDao.getFavoriteFoods()
    }

    fun getEntriesInRange(startDate: String, endDate: String): Flow<List<FoodEntry>> {
        return foodEntryDao.getEntriesInRange(startDate, endDate)
    }

    fun getAllEntries(): Flow<List<FoodEntry>> {
        return foodEntryDao.getAllEntries()
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
        val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        return mealTypeDao.getAllMealTypes().map { list ->
            list.sortedByDescending { uuidRegex.matches(it.id) }
                .distinctBy { it.name.trim().lowercase() }
                .sortedBy { it.sortOrder }
        }
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
                    val serverId = syncResult.getOrNull()
                    if (!serverId.isNullOrBlank() && serverId != entry.id) {
                        foodEntryDao.deleteEntryById(entry.id)
                        foodEntryDao.insertEntry(entry.copy(id = serverId, syncStatus = FoodEntry.SYNC_STATUS_SYNCED))
                    } else {
                        foodEntryDao.updateSyncStatus(entry.id, FoodEntry.SYNC_STATUS_SYNCED)
                    }
                } else {
                    android.util.Log.e("ByteLog", "Failed to sync entry ${entry.foodName} to Sparky: ${syncResult.exceptionOrNull()?.message}")
                }
            }
        }
    }

    /**
     * Update an entry optimistically in local DB and sync changes to Sparky Fitness in background.
     */
    suspend fun updateEntry(entry: FoodEntry) = withContext(Dispatchers.IO) {
        foodEntryDao.updateEntry(entry.copy(syncStatus = FoodEntry.SYNC_STATUS_PENDING_UPDATE))
        appScope.launch(Dispatchers.IO) {
            val updateRes = sparkyApiClient.updateFoodEntry(entry)
            if (updateRes.isSuccess) {
                foodEntryDao.updateSyncStatus(entry.id, FoodEntry.SYNC_STATUS_SYNCED)
            } else {
                // If update failed (e.g. entry ID was client-side or missing on server), try creating
                val createRes = sparkyApiClient.createFoodEntry(entry)
                if (createRes.isSuccess) {
                    val serverId = createRes.getOrNull()
                    if (!serverId.isNullOrBlank() && serverId != entry.id) {
                        foodEntryDao.deleteEntryById(entry.id)
                        foodEntryDao.insertEntry(entry.copy(id = serverId, syncStatus = FoodEntry.SYNC_STATUS_SYNCED))
                    } else {
                        foodEntryDao.updateSyncStatus(entry.id, FoodEntry.SYNC_STATUS_SYNCED)
                    }
                } else {
                    android.util.Log.e("ByteLog", "Failed to update or recreate entry on Sparky: ${updateRes.exceptionOrNull()?.message}")
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
     * Delete an entry optimistically and sync deletion to Sparky in background.
     */
    suspend fun deleteEntry(entry: FoodEntry) = withContext(Dispatchers.IO) {
        foodEntryDao.deleteEntryById(entry.id)
        appScope.launch(Dispatchers.IO) {
            sparkyApiClient.deleteFoodEntry(entry.id)
        }
    }

    /**
     * Fully sync daily entries with Sparky Fitness Server:
     * 1. Push any local pending un-synced entries (inserts or updates).
     * 2. Pull latest entries from Sparky for this date and reconcile local DB.
     * 3. Sync authoritative meal types from Sparky.
     */
    suspend fun syncDateWithServer(date: String): Result<Int> = withContext(Dispatchers.IO) {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date

        // 1. Push local pending entries
        try {
            val pendingEntries = foodEntryDao.getPendingSyncEntries().filter { it.entryDate == cleanDate }
            pendingEntries.forEach { entry ->
                if (entry.syncStatus == FoodEntry.SYNC_STATUS_PENDING_UPDATE) {
                    val updateRes = sparkyApiClient.updateFoodEntry(entry)
                    if (updateRes.isSuccess) {
                        foodEntryDao.updateSyncStatus(entry.id, FoodEntry.SYNC_STATUS_SYNCED)
                    }
                } else {
                    val pushResult = sparkyApiClient.createFoodEntry(entry)
                    if (pushResult.isSuccess) {
                        val serverId = pushResult.getOrNull()
                        if (!serverId.isNullOrBlank() && serverId != entry.id) {
                            foodEntryDao.deleteEntryById(entry.id)
                            foodEntryDao.insertEntry(entry.copy(id = serverId, syncStatus = FoodEntry.SYNC_STATUS_SYNCED))
                        } else {
                            foodEntryDao.updateSyncStatus(entry.id, FoodEntry.SYNC_STATUS_SYNCED)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Fetch server entries for date
        val fetchResult = sparkyApiClient.fetchEntriesForDate(cleanDate)
        if (fetchResult.isSuccess) {
            val serverEntries = fetchResult.getOrNull().orEmpty()
            
            // Reconcile: delete previously synced entries for this date and insert fresh server entries
            foodEntryDao.deleteSyncedEntriesForDate(cleanDate)
            if (serverEntries.isNotEmpty()) {
                foodEntryDao.insertEntries(serverEntries)
            }

            // 3. Update meal types
            val mealTypesRes = sparkyApiClient.fetchMealTypes()
            if (mealTypesRes.isSuccess) {
                val types = mealTypesRes.getOrNull().orEmpty()
                if (types.isNotEmpty()) {
                    mealTypeDao.clearAllMealTypes()
                    mealTypeDao.insertMealTypes(types)
                }
            }

            // 4. Update daily user macro goals from Sparky
            try {
                val goalsRes = sparkyApiClient.fetchGoalsForDate(cleanDate)
                if (goalsRes.isSuccess) {
                    val goals = goalsRes.getOrNull()
                    if (goals != null && goals.calories > 0f) {
                        val waterGoalOz = kotlin.math.round(goals.waterGoalMl / 29.5735f)
                        settingsRepository.saveMacroTargets(
                            calories = goals.calories,
                            protein = goals.protein,
                            carbs = goals.carbs,
                            fat = goals.fat,
                            waterGoalOz = waterGoalOz
                        )
                    }
                }
            } catch (_: Exception) {}

            Result.success(serverEntries.size)
        } else {
            Result.failure(fetchResult.exceptionOrNull() ?: Exception("Failed to sync entries with server"))
        }
    }

    suspend fun clearLocalFoodCache() = withContext(Dispatchers.IO) {
        foodDao.clearAllFoods()
    }

    suspend fun testConnection(): com.bytelog.app.data.remote.ConnectionTestResult {
        return sparkyApiClient.testConnection()
    }

    suspend fun loginWithEmail(email: String, password: String): Result<String> {
        return sparkyApiClient.signInEmail(email, password)
    }

    suspend fun copyMealFromYesterday(mealType: String, targetDate: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val result = sparkyApiClient.copyMealFromYesterday(mealType, targetDate)
        if (result.isSuccess) {
            syncDateWithServer(targetDate)
        }
        result
    }

    suspend fun copyMeal(
        sourceDate: String,
        sourceMealType: String,
        targetDate: String,
        targetMealType: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val result = sparkyApiClient.copyMeal(sourceDate, sourceMealType, targetDate, targetMealType)
        if (result.isSuccess) {
            syncDateWithServer(targetDate)
        }
        result
    }

    suspend fun copyAllFromYesterday(targetDate: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val result = sparkyApiClient.copyAllFromYesterday(targetDate)
        if (result.isSuccess) {
            syncDateWithServer(targetDate)
        }
        result
    }

    suspend fun createCustomFood(
        name: String,
        brand: String? = null,
        servingSize: Float = 100f,
        servingUnit: String = "g",
        calories: Float,
        protein: Float,
        carbs: Float,
        fat: Float,
        fiber: Float? = 0f
    ): Result<FoodItem> = withContext(Dispatchers.IO) {
        val result = sparkyApiClient.createCustomFood(
            name = name,
            brand = brand,
            servingSize = servingSize,
            servingUnit = servingUnit,
            calories = calories,
            protein = protein,
            carbs = carbs,
            fat = fat,
            fiber = fiber
        )
        if (result.isSuccess) {
            val food = result.getOrNull()
            if (food != null) {
                foodDao.insertFood(food)
            }
        }
        result
    }
}
