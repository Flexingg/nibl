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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class FoodRepository(
    private val foodDao: FoodDao,
    private val foodEntryDao: FoodEntryDao,
    private val mealTypeDao: MealTypeDao,
    private val sparkyApiClient: SparkyApiClient,
    private val settingsRepository: SettingsRepository,
    private val appScope: CoroutineScope,
    private val prepBatchDao: com.bytelog.app.data.local.db.PrepBatchDao? = null
) {

    /**
     * Replaces a pending entry with its server-assigned id. Batch links follow the new id
     * so a meal-prep portion stays attached to its diary entry after sync.
     */
    // Batch links of recently deleted entries, so the diary's Undo can re-attach them.
    private val deletedBatchLinks = java.util.concurrent.ConcurrentHashMap<String, com.bytelog.app.data.local.model.BatchConsumption>()

    private suspend fun detachBatchLinks(entryIds: List<String>) {
        prepBatchDao?.onEntriesDeleted(entryIds)?.forEach { deletedBatchLinks[it.foodEntryId] = it }
    }

    private suspend fun rekeyEntry(entry: FoodEntry, serverId: String) {
        foodEntryDao.deleteEntryById(entry.id)
        foodEntryDao.insertEntry(entry.copy(id = serverId, syncStatus = FoodEntry.SYNC_STATUS_SYNCED))
        prepBatchDao?.remapEntryId(entry.id, serverId)
    }

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
        entries.forEach { entry ->
            deletedBatchLinks.remove(entry.id)?.let { link -> prepBatchDao?.insertConsumption(link) }
        }

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
                        rekeyEntry(entry, serverId)
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
        prepBatchDao?.onEntryQuantityChanged(entry.id, entry.quantity)
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
                        rekeyEntry(entry, serverId)
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
        detachBatchLinks(listOf(entry.id))
        appScope.launch(Dispatchers.IO) {
            sparkyApiClient.deleteFoodEntry(entry.id)
        }
    }

    /**
     * Delete multiple entries locally and sync deletion to Sparky in background.
     */
    suspend fun deleteEntries(entries: List<FoodEntry>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        val ids = entries.map { it.id }
        foodEntryDao.deleteEntriesByIds(ids)
        detachBatchLinks(ids)
        appScope.launch(Dispatchers.IO) {
            entries.forEach { entry ->
                sparkyApiClient.deleteFoodEntry(entry.id)
            }
        }
    }

    /**
     * Delete an entire meal (all entries matching date and meal name) and sync deletion to Sparky.
     * Returns deleted entries so caller can perform Undo.
     */
    suspend fun deleteMeal(date: String, mealName: String): List<FoodEntry> = withContext(Dispatchers.IO) {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
        val entries = foodEntryDao.getEntriesForDateOnce(cleanDate)
            .filter { it.mealName.trim().equals(mealName.trim(), ignoreCase = true) }
        if (entries.isNotEmpty()) {
            deleteEntries(entries)
        }
        entries
    }

    /**
     * Move entries to a new date and/or meal category.
     */
    suspend fun moveEntries(
        entries: List<FoodEntry>,
        targetDate: String,
        targetMealTypeId: String,
        targetMealName: String
    ) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        val cleanDate = if (targetDate.contains("T")) targetDate.substringBefore("T") else targetDate
        val cleanMealName = targetMealName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        val updatedEntries = entries.map { entry ->
            entry.copy(
                entryDate = cleanDate,
                mealTypeId = targetMealTypeId,
                mealName = cleanMealName,
                syncStatus = FoodEntry.SYNC_STATUS_PENDING_UPDATE
            )
        }
        foodEntryDao.insertEntries(updatedEntries)
        appScope.launch(Dispatchers.IO) {
            updatedEntries.forEach { entry ->
                sparkyApiClient.updateFoodEntry(entry)
            }
        }
    }

    /**
     * Copy entries to a new date and/or meal category.
     */
    suspend fun copyEntries(
        entries: List<FoodEntry>,
        targetDate: String,
        targetMealTypeId: String,
        targetMealName: String
    ) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        val cleanDate = if (targetDate.contains("T")) targetDate.substringBefore("T") else targetDate
        val cleanMealName = targetMealName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        val copiedEntries = entries.map { entry ->
            entry.copy(
                id = UUID.randomUUID().toString(),
                entryDate = cleanDate,
                mealTypeId = targetMealTypeId,
                mealName = cleanMealName,
                syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT,
                createdAt = System.currentTimeMillis()
            )
        }
        commitMultiAdd(copiedEntries)
    }

    /**
     * Move an entire meal to a new date and/or meal category.
     */
    suspend fun moveMeal(
        sourceDate: String,
        sourceMealName: String,
        targetDate: String,
        targetMealName: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        val sDate = if (sourceDate.contains("T")) sourceDate.substringBefore("T") else sourceDate
        val tDate = if (targetDate.contains("T")) targetDate.substringBefore("T") else targetDate
        val allTypes = mealTypeDao.getAllMealTypesList()
        val targetTypeObj = allTypes.find { it.name.trim().equals(targetMealName.trim(), ignoreCase = true) }
        val targetTypeId = targetTypeObj?.id ?: targetMealName.lowercase()
        val cleanTargetMealName = targetMealName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        val entries = foodEntryDao.getEntriesForDateOnce(sDate)
            .filter { it.mealName.trim().equals(sourceMealName.trim(), ignoreCase = true) }

        if (entries.isEmpty()) {
            return@withContext Result.success(0)
        }

        moveEntries(entries, tDate, targetTypeId, cleanTargetMealName)
        syncDateWithServer(tDate)
        if (sDate != tDate) {
            syncDateWithServer(sDate)
        }
        Result.success(entries.size)
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
                            rekeyEntry(entry, serverId)
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
        val sDate = if (sourceDate.contains("T")) sourceDate.substringBefore("T") else sourceDate
        val tDate = if (targetDate.contains("T")) targetDate.substringBefore("T") else targetDate

        // Optimistically copy locally in Room so UI updates instantly
        try {
            val srcEntries = foodEntryDao.getEntriesForDateOnce(sDate)
                .filter { it.mealName.equals(sourceMealType, ignoreCase = true) }
            if (srcEntries.isNotEmpty()) {
                val allTypes = mealTypeDao.getAllMealTypesList()
                val targetTypeObj = allTypes.find { it.name.equals(targetMealType, ignoreCase = true) }
                val targetTypeId = targetTypeObj?.id ?: targetMealType.lowercase()
                val copied = srcEntries.map { entry ->
                    entry.copy(
                        id = UUID.randomUUID().toString(),
                        entryDate = tDate,
                        mealName = targetMealType,
                        mealTypeId = targetTypeId,
                        syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT,
                        createdAt = System.currentTimeMillis()
                    )
                }
                commitMultiAdd(copied)
            }
        } catch (_: Exception) {}

        val result = sparkyApiClient.copyMeal(sDate, sourceMealType, tDate, targetMealType)
        if (result.isSuccess) {
            syncDateWithServer(tDate)
        }
        result
    }

    suspend fun copyMultipleMeals(
        meals: List<Pair<String, List<FoodEntry>>>,
        targetDate: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        val tDate = if (targetDate.contains("T")) targetDate.substringBefore("T") else targetDate
        val allTypes = mealTypeDao.getAllMealTypesList()
        val allNewEntries = mutableListOf<FoodEntry>()
        for ((targetMeal, entries) in meals) {
            val targetTypeObj = allTypes.find { it.name.equals(targetMeal, ignoreCase = true) }
            val targetTypeId = targetTypeObj?.id ?: targetMeal.lowercase()
            for (entry in entries) {
                allNewEntries.add(
                    entry.copy(
                        id = UUID.randomUUID().toString(),
                        entryDate = tDate,
                        mealName = targetMeal,
                        mealTypeId = targetTypeId,
                        syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
        }
        if (allNewEntries.isNotEmpty()) {
            commitMultiAdd(allNewEntries)
        }
        syncDateWithServer(tDate)
        Result.success(allNewEntries.size)
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

    /**
     * Generate and estimate food nutrition with Sparky AI on the backend.
     * Supports photo estimation (/api/foods/estimate-food-photo) or text-based AI estimation
     * using Sparky's agent / chat (/api/chat), creating the resulting food in Sparky Fitness.
     */
    suspend fun generateFoodWithAi(
        name: String,
        description: String = "",
        imageBase64: String? = null,
        imageMimeType: String = "image/jpeg"
    ): Result<FoodItem> = withContext(Dispatchers.IO) {
        val cleanName = name.trim().ifBlank { "Custom Food" }

        // Path 1: Photo estimate via SparkyFitnessServer
        if (!imageBase64.isNullOrBlank()) {
            val photoResult = sparkyApiClient.estimateFoodPhoto(
                base64 = imageBase64,
                mimeType = imageMimeType,
                description = if (description.isNotBlank()) "$cleanName: $description" else cleanName
            )
            if (photoResult.isSuccess) {
                val est = photoResult.getOrThrow()
                val finalName = if (cleanName != "Custom Food") cleanName else est.mealSummary
                return@withContext createCustomFood(
                    name = finalName,
                    brand = "Sparky AI",
                    servingSize = if (est.servingSize > 0f) est.servingSize else 100f,
                    servingUnit = est.servingUnit,
                    calories = est.calories,
                    protein = est.protein,
                    carbs = est.carbs,
                    fat = est.fat,
                    fiber = est.fiber
                )
            }
        }

        // Path 2: Text / Name / Description via Sparky AI Agent (/api/chat)
        val prompt = buildString {
            append("Please analyze and estimate realistic nutritional values for 1 serving of: \"").append(cleanName).append("\"")
            if (description.isNotBlank()) {
                append(" (Details: ").append(description.trim()).append(")")
            }
            append(". Estimate realistic calories, protein, carbs, fat, fiber for 1 standard serving. ")
            append("Then create it with create_food, and output the nutritional summary in this exact JSON format at the very end: ")
            append("```json\n{\"name\":\"").append(cleanName.replace("\"", "\\\"")).append("\",\"serving_size\":100,\"serving_unit\":\"g\",\"calories\":250,\"protein\":15,\"carbs\":30,\"fat\":8,\"fiber\":3}\n```")
        }

        val chatResult = sparkyApiClient.sendChatMessage(
            listOf(com.bytelog.app.data.remote.ChatMessageInput("user", prompt))
        )
        if (chatResult.isSuccess) {
            val output = chatResult.getOrThrow()
            val text = output.content

            // Extract JSON from output
            val jsonPattern = Regex("""```(?:json)?\s*(\{[\s\S]*?\})\s*```""")
            val rawJson = jsonPattern.find(text)?.groupValues?.get(1)
                ?: Regex("""(\{[\s\S]*?"calories"[\s\S]*?\})""").find(text)?.groupValues?.get(1)

            var calories = 0f
            var protein = 0f
            var carbs = 0f
            var fat = 0f
            var fiber = 0f
            var servingSize = 100f
            var servingUnit = "g"
            var parsedName = cleanName

            if (rawJson != null) {
                try {
                    val element = Json.parseToJsonElement(rawJson).jsonObject
                    calories = element["calories"]?.jsonPrimitive?.floatOrNull ?: 0f
                    protein = element["protein"]?.jsonPrimitive?.floatOrNull ?: 0f
                    carbs = element["carbs"]?.jsonPrimitive?.floatOrNull ?: 0f
                    fat = element["fat"]?.jsonPrimitive?.floatOrNull ?: 0f
                    fiber = element["fiber"]?.jsonPrimitive?.floatOrNull ?: 0f
                    servingSize = element["serving_size"]?.jsonPrimitive?.floatOrNull ?: 100f
                    servingUnit = element["serving_unit"]?.jsonPrimitive?.content ?: "g"
                    parsedName = element["name"]?.jsonPrimitive?.content ?: cleanName
                } catch (_: Exception) {}
            }

            // Fallback regex parsing if JSON block was absent or zeroed
            if (calories == 0f && protein == 0f && carbs == 0f && fat == 0f) {
                val calMatch = Regex("""(?:calories|kcal)[:\s]+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(text)
                val pMatch = Regex("""(?:protein)[:\s]+(\d+(?:\.\d+)?)\s*g?""", RegexOption.IGNORE_CASE).find(text)
                val cMatch = Regex("""(?:carbs|carbohydrates)[:\s]+(\d+(?:\.\d+)?)\s*g?""", RegexOption.IGNORE_CASE).find(text)
                val fMatch = Regex("""(?:fat)[:\s]+(\d+(?:\.\d+)?)\s*g?""", RegexOption.IGNORE_CASE).find(text)
                val fibMatch = Regex("""(?:fiber)[:\s]+(\d+(?:\.\d+)?)\s*g?""", RegexOption.IGNORE_CASE).find(text)

                calories = calMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 180f
                protein = pMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 12f
                carbs = cMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 18f
                fat = fMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 6f
                fiber = fibMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 2f
            }

            return@withContext createCustomFood(
                name = parsedName,
                brand = "Sparky AI",
                servingSize = servingSize,
                servingUnit = servingUnit,
                calories = calories,
                protein = protein,
                carbs = carbs,
                fat = fat,
                fiber = fiber
            )
        }

        Result.failure(chatResult.exceptionOrNull() ?: java.io.IOException("AI food generation failed"))
    }
}
