package com.bytelog.app.data.repository

import com.bytelog.app.data.local.db.FoodDao
import com.bytelog.app.data.local.db.FoodEntryDao
import com.bytelog.app.data.local.db.MealTypeDao
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.remote.SparkyFood
import com.bytelog.app.data.remote.SparkyNutrients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
        foodEntryDao.insertEntry(entry.copy(id = serverId, syncStatus = FoodEntry.SYNC_STATUS_UPLOADED))
        prepBatchDao?.remapEntryId(entry.id, serverId)
    }

    /** Last reason Sparky gave for refusing a change, shown after a sync. */
    @Volatile var lastPushError: String? = null
        private set

    /** Phone food id → the Sparky food and serving it was linked to, for this run of the app. */
    private val sparkyLinks = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    private val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * The Sparky food and serving (food id, variant id) an entry is logged against. Sparky's row-level security
     * only accepts diary entries that point at a food in its library, so a food Sparky doesn't have yet is
     * created first: an OpenFoodFacts result as that product (matched by barcode), a food from the phone's
     * library with its own serving, and a quick add or voice entry as a quick food holding exactly its numbers.
     */
    private suspend fun linkToSparkyFood(entry: FoodEntry): Result<Pair<String, String>> = runCatching {
        val localId = entry.foodId
        localId?.let { sparkyLinks[it] }?.let { return@runCatching it }

        // Already a Sparky food: just needs its default serving
        if (localId != null && uuidRegex.matches(localId)) {
            val sparky = sparkyApiClient.getFoodDetails(localId).getOrNull()
            if (sparky?.variantId != null) return@runCatching (sparky.id to sparky.variantId).also { sparkyLinks[localId] = it }
        }

        val local = localId?.let { foodDao.getFoodById(it) }
        val created = if (local != null) {
            val fromOff = local.id.startsWith("off_")
            sparkyApiClient.createFoodFull(
                name = local.name, brand = local.brand,
                n = SparkyNutrients(
                    servingSize = local.servingSize, servingUnit = local.servingUnit,
                    calories = local.calories, protein = local.protein, carbs = local.carbs, fat = local.fat,
                    fiber = local.fiber?.takeIf { it > 0f }
                ),
                barcode = if (fromOff) local.barcode else null,
                providerType = if (fromOff) "openfoodfacts" else null
            ).getOrThrow()
        } else {
            // Quick add / voice: a food holding exactly this entry's amount and nutrition, kept out of search
            sparkyApiClient.createFoodFull(
                name = entry.foodName, brand = entry.brandName,
                n = SparkyNutrients(
                    servingSize = entry.quantity.takeIf { it > 0f } ?: 1f, servingUnit = entry.unit.ifBlank { "serving" },
                    calories = entry.calories, protein = entry.protein, carbs = entry.carbs, fat = entry.fat
                ),
                quickFood = true
            ).getOrThrow()
        }
        val variantId = created.variantId ?: sparkyApiClient.getFoodDetails(created.id).getOrThrow().variantId
            ?: error("Sparky has no serving for ${entry.foodName}")
        // Its household servings ("2 cookies") go to Sparky too, so Sparky's own apps offer them (best effort)
        local?.let { food ->
            com.bytelog.app.recipes.HouseholdServings.decode(food.householdServings).forEach { h ->
                val r = h.perUnit * h.quantity
                sparkyApiClient.createFoodVariant(
                    created.id, h.quantity, com.bytelog.app.recipes.HouseholdServings.plural(h.unit, h.quantity),
                    food.calories * r, food.protein * r, food.carbs * r, food.fat * r
                )
            }
        }
        val link = created.id to variantId

        // The phone's food takes Sparky's id, so later logs of it (and other entries waiting to sync) reuse it
        if (local != null && local.id != created.id) {
            foodDao.insertFood(local.copy(id = created.id))
            foodDao.deleteFood(local.id)
            foodEntryDao.relinkFood(local.id, created.id)
        }
        localId?.let { sparkyLinks[it] = link }
        sparkyLinks[created.id] = link
        link
    }

    /** The Sparky food and serving [entry] belongs to (food id, variant id), creating the food on Sparky if needed. */
    suspend fun sparkyLinkFor(entry: FoodEntry): Result<Pair<String, String>> = linkToSparkyFood(entry)

    /** Sends one new entry; on success it carries Sparky's id and is marked uploaded. */
    private suspend fun pushCreate(entry: FoodEntry): Boolean {
        val link = linkToSparkyFood(entry).getOrElse { e ->
            lastPushError = "Couldn't add ${entry.foodName} to Sparky's food library: ${e.message}"
            return false
        }
        val linked = entry.copy(foodId = link.first)
        val res = sparkyApiClient.createFoodEntry(linked, link.first, link.second)
        if (res.isFailure) { lastPushError = res.exceptionOrNull()?.message; return false }
        val serverId = res.getOrNull()
        if (!serverId.isNullOrBlank() && serverId != entry.id) rekeyEntry(linked, serverId)
        else {
            if (linked.foodId != entry.foodId) foodEntryDao.updateEntry(linked)
            foodEntryDao.updateSyncStatus(entry.id, FoodEntry.SYNC_STATUS_UPLOADED)
        }
        return true
    }

    /** Sends an edit; if Sparky doesn't know the entry (it never got there), creates it instead. */
    private suspend fun pushUpdate(entry: FoodEntry): Boolean {
        val res = sparkyApiClient.updateFoodEntry(entry)
        if (res.isSuccess) { foodEntryDao.updateSyncStatus(entry.id, FoodEntry.SYNC_STATUS_UPLOADED); return true }
        return pushCreate(entry)
    }

    private suspend fun pushDelete(entry: FoodEntry): Boolean {
        val res = sparkyApiClient.deleteFoodEntry(entry.id)   // 404 counts as done
        if (res.isFailure) { lastPushError = res.exceptionOrNull()?.message; return false }
        foodEntryDao.deleteEntryById(entry.id)
        return true
    }

    /** Result of sending the phone's changes to Sparky. */
    data class PushReport(val sent: Int, val failed: Int, val error: String?)

    private val pushLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Sends every change made on the phone that Sparky hasn't accepted yet, for any date: new entries, edits
     * and deletions. Anything Sparky refuses stays on the phone, marked pending, and is tried again next time.
     */
    suspend fun pushPendingEntries(): PushReport = withContext(Dispatchers.IO) {
        pushLock.withLock {
            var sent = 0; var failed = 0
            lastPushError = null
            for (entry in foodEntryDao.getPendingSyncEntries()) {
                val ok = try {
                    when (entry.syncStatus) {
                        FoodEntry.SYNC_STATUS_PENDING_DELETE -> pushDelete(entry)
                        FoodEntry.SYNC_STATUS_PENDING_UPDATE -> pushUpdate(entry)
                        else -> pushCreate(entry)
                    }
                } catch (e: Exception) { lastPushError = e.message; false }
                if (ok) sent++ else failed++
            }
            PushReport(sent, failed, lastPushError)
        }
    }

    suspend fun countPendingSync(): Int = withContext(Dispatchers.IO) { foodEntryDao.countPendingSync() }

    // --- Food Queries ---

    /**
     * On-device search, updated as you log: matching foods you've logged (best match, then most logged) and saved
     * foods you haven't. Instant; no network.
     */
    fun searchOnDevice(query: String): Flow<Pair<List<SearchHit>, List<SearchHit>>> {
        val tokens = FoodSearch.tokens(query)
        if (tokens.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyList<SearchHit>() to emptyList())
        val (a, b, c) = FoodSearch.prefilterTokens(query)
        return kotlinx.coroutines.flow.combine(foodEntryDao.searchEntries(a, b, c), foodDao.searchFoodsByToken(a, b, c)) { entries, saved ->
            entries to saved
        }.mapLatest { (entries, saved) ->
            val byId = saved.associateBy { it.id }
            val missing = entries.mapNotNull { it.foodId }.distinct().filter { it !in byId }
            val foods = if (missing.isEmpty()) byId else byId + missing.chunked(500).flatMap { foodDao.getFoodsByIds(it) }.associateBy { it.id }
            val logged = FoodSearch.rankLogged(entries, foods, tokens)
            val exclude = HashSet<String>()
            logged.forEach { exclude += it.food.id; exclude += FoodSearch.identity(it.food.name, it.food.brand) }
            entries.forEach { e -> e.foodId?.let { exclude += it } }
            logged to FoodSearch.rankSaved(saved, tokens, exclude)
        }.flowOn(Dispatchers.Default)
    }

    /** Recent server searches, so going back to a query (or deleting a letter) shows its results at once. */
    private val remoteCache = object : LinkedHashMap<String, List<FoodItem>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FoodItem>>?) = size > 40
    }

    fun cachedRemoteResults(query: String): List<FoodItem>? = synchronized(remoteCache) { remoteCache[FoodSearch.normalize(query)] }

    /**
     * Searches Sparky and Open Food Facts at the same time, sending results as each answers (Sparky's first in the
     * list). Results aren't saved to the phone until you use one.
     */
    fun searchRemote(query: String): Flow<List<FoodItem>> = kotlinx.coroutines.flow.channelFlow {
        val key = FoodSearch.normalize(query)
        var sparky: List<FoodItem>? = null
        var off: List<FoodItem>? = null
        val lock = kotlinx.coroutines.sync.Mutex()
        suspend fun publish() {
            val merged = SparkyApiClient.mergeFoodResults(sparky.orEmpty(), off.orEmpty())
            if (sparky != null && off != null) synchronized(remoteCache) { remoteCache[key] = merged }
            send(merged)
        }
        launch(Dispatchers.IO) {
            val r = sparkyApiClient.searchSparkyFoods(query).getOrNull().orEmpty()
            lock.withLock { sparky = r; publish() }
        }
        launch(Dispatchers.IO) {
            val r = sparkyApiClient.searchOpenFoodFacts(query, page = 1).getOrNull().orEmpty()
            lock.withLock { off = r; publish() }
        }
    }

    /**
     * Keeps a food on the phone once you use it (add, log or favourite it), without resetting how often you've
     * used it or whether it's a favourite.
     */
    suspend fun rememberFood(food: FoodItem) = withContext(Dispatchers.IO) {
        cacheFoods(listOf(food))
        fetchHouseholdServings(food)
    }

    /**
     * Foods you often eat in the same meal as [seeds] (what's in the tray), from your history, as foods ready to add,
     * leaving out what's already in [alreadyIn] (the meal being added to).
     */
    suspend fun foodsOftenWith(seeds: List<FoodEntry>, alreadyIn: List<FoodEntry> = emptyList()): List<FoodItem> = withContext(Dispatchers.Default) {
        if (seeds.isEmpty()) return@withContext emptyList()
        val history = foodEntryDao.entriesOnDaysWith(
            seeds.mapNotNull { it.foodId }.distinct(),
            seeds.map { it.foodName.trim().lowercase() }.distinct()
        )
        val found = CoLogging.suggest(history, seeds, alreadyIn)
        val saved = found.mapNotNull { it.entry.foodId }.distinct().chunked(500).flatMap { foodDao.getFoodsByIds(it) }.associateBy { it.id }
        // Two names can point at one saved food (renamed, re-saved); lists key suggestions by id, so each id once
        found.map { saved[it.entry.foodId] ?: FoodSearch.foodOf(it.entry) }.distinctBy { it.id }
    }

    /** Foods whose servings were asked of Sparky this session (each is asked once). */
    private val servingsAsked = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * A Sparky library food's other servings ("1 slice", "1 cup") aren't in search results, so they're fetched
     * once when the food is first used and kept on the phone.
     */
    private fun fetchHouseholdServings(food: FoodItem) {
        if (food.id.startsWith("off_") || !servingsAsked.add(food.id)) return
        appScope.launch(Dispatchers.IO) {
            // Best effort: without them the food still logs by its own serving
            runCatching {
                if (foodDao.getFoodById(food.id)?.householdServings != null) return@launch
                val variants = sparkyApiClient.fetchFoodVariants(food.id).getOrNull() ?: return@launch
                val default = variants.firstOrNull { (it["is_default"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" } ?: return@launch
                val households = SparkyFood.householdsFrom(default, variants, food.name)
                com.bytelog.app.recipes.HouseholdServings.encode(households)?.let { foodDao.setHouseholdServings(food.id, it) }
            }
        }
    }

    private suspend fun cacheFoods(incoming: List<FoodItem>) {
        if (incoming.isEmpty()) return
        val existing = incoming.map { it.id }.distinct().chunked(500).flatMap { foodDao.getFoodsByIds(it) }.associateBy { it.id }
        foodDao.insertFoods(incoming.map { f ->
            val e = existing[f.id] ?: return@map f
            f.copy(
                isFavorite = e.isFavorite || f.isFavorite, useCount = e.useCount, lastUsedAt = e.lastUsedAt,
                mealTypeAffinity = e.mealTypeAffinity ?: f.mealTypeAffinity, householdServings = f.householdServings ?: e.householdServings
            )
        })
    }

    // --- Editing foods (Sparky is the source of truth; the phone's copy follows a successful save) ---

    /** Who the signed-in user is on Sparky, learned from their own foods; null until they have one. */
    @Volatile private var myUserId: String? = null

    private suspend fun ownUserId(): String? {
        myUserId?.let { return it }
        return sparkyApiClient.listMyFoods(perPage = 1).getOrNull()?.firstOrNull()?.userId?.also { myUserId = it }
    }

    /**
     * A food opened for editing. [sparky] is null when Sparky doesn't have it (e.g. an OpenFoodFacts result that
     * was never saved). [owned] foods are edited in place; anything else is saved as the user's own copy.
     */
    data class EditableFood(val local: FoodItem?, val sparky: SparkyFood?, val owned: Boolean) {
        val name get() = sparky?.name ?: local?.name.orEmpty()
    }

    suspend fun loadFoodForEditing(foodId: String): Result<EditableFood> = withContext(Dispatchers.IO) {
        val local = foodDao.getFoodById(foodId)
        val remote = if (foodId.startsWith("off_")) null else sparkyApiClient.getFoodDetails(foodId)
        val sparky = remote?.getOrNull()
        when {
            sparky != null -> {
                val me = ownUserId()
                Result.success(EditableFood(local, sparky, owned = sparky.userId != null && sparky.userId == me))
            }
            local != null -> Result.success(EditableFood(local, null, owned = false))
            else -> Result.failure(remote?.exceptionOrNull() ?: IllegalStateException("Food not found"))
        }
    }

    suspend fun getLocalFood(id: String): FoodItem? = withContext(Dispatchers.IO) { foodDao.getFoodById(id) }

    /** The user's own foods on Sparky, for the "My foods" library. */
    suspend fun myFoods(search: String = ""): Result<List<SparkyFood>> {
        val res = sparkyApiClient.listMyFoods(search)
        res.getOrNull()?.firstOrNull()?.userId?.let { myUserId = it }
        return res
    }

    /**
     * Saves an edit to Sparky first, then updates the phone. An owned food is changed in place (and, if asked, the
     * user's past diary entries of it are rewritten on Sparky and here); any other food becomes the user's own copy.
     * Returns the food as now stored.
     */
    suspend fun saveFoodEdit(
        original: EditableFood,
        name: String,
        brand: String?,
        barcode: String?,
        nutrients: SparkyNutrients,
        updatePastEntries: Boolean
    ): Result<FoodItem> = withContext(Dispatchers.IO) {
        runCatching {
            val sparky = original.sparky
            if (original.owned && sparky != null) {
                sparkyApiClient.updateFood(sparky.id, name, brand, barcode).getOrThrow()
                val variantId = sparky.variantId ?: sparkyApiClient.getFoodDetails(sparky.id).getOrThrow().variantId
                    ?: error("Sparky has no serving for this food")
                sparkyApiClient.updateFoodVariant(variantId, sparky.id, nutrients).getOrThrow()
                val saved = sparky.copy(name = name, brand = brand, barcode = barcode, nutrients = nutrients).toFoodItem(original.local)
                foodDao.insertFood(saved)
                if (updatePastEntries) {
                    sparkyApiClient.updatePastEntries(sparky.id, variantId).getOrThrow()
                    rewriteLocalEntries(saved)
                }
                saved
            } else {
                val created = sparkyApiClient.createFoodFull(name, brand, nutrients).getOrThrow()
                if (!barcode.isNullOrBlank()) sparkyApiClient.updateFood(created.id, name, brand, barcode).getOrThrow()
                val saved = created.copy(barcode = barcode).toFoodItem().copy(
                    isFavorite = original.local?.isFavorite ?: false,
                    mealTypeAffinity = original.local?.mealTypeAffinity
                )
                foodDao.insertFood(saved)
                // The phone's cached original (an OpenFoodFacts result or a shared food) would otherwise sit next to
                // the user's edited copy in search
                original.local?.let { if (it.id != saved.id) foodDao.deleteFood(it.id) }
                saved
            }
        }
    }

    /** Past entries of [food] on the phone take its new name and nutrition, keeping their amounts. */
    private suspend fun rewriteLocalEntries(food: FoodItem) {
        foodEntryDao.getEntriesForFood(food.id).forEach { e ->
            foodEntryDao.updateEntry(
                e.copy(
                    foodName = food.name, brandName = food.brand,
                    calories = food.caloriesFor(e.quantity, e.unit), protein = food.proteinFor(e.quantity, e.unit),
                    carbs = food.carbsFor(e.quantity, e.unit), fat = food.fatFor(e.quantity, e.unit)
                )
            )
        }
    }

    /** Hides a food from search, or deletes it from the library (diary entries are kept either way). */
    suspend fun deleteFood(foodId: String, hideOnly: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        sparkyApiClient.deleteFood(foodId, hideOnly).onSuccess { foodDao.deleteFood(foodId) }
    }

    fun getRecentFoods(limit: Int = 25): Flow<List<FoodItem>> {
        return getRecentlyLoggedFoods(limit)
    }

    /** The amount last logged of each food (quantity, unit), keyed by food id. */
    suspend fun lastPortions(foodIds: List<String>): Map<String, Pair<Float, String>> = withContext(Dispatchers.IO) {
        if (foodIds.isEmpty()) return@withContext emptyMap()
        foodIds.distinct().chunked(500).flatMap { foodEntryDao.getLatestEntriesForFoods(it) }
            .filter { it.foodId != null && it.quantity > 0f }
            .associate { it.foodId!! to (it.quantity to it.unit) }
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

    /** Diary entries from the [MealPatterns.HISTORY_DAYS] days before [date] (and [date] itself when asked). */
    fun mealHistory(date: java.time.LocalDate, includeDay: Boolean = false): Flow<List<FoodEntry>> =
        foodEntryDao.getEntriesInRange(
            date.minusDays(MealPatterns.HISTORY_DAYS).toString(),
            (if (includeDay) date else date.minusDays(1)).toString()
        )

    /**
     * Foods for this meal, best first: what is usually logged at it (recent days and the same weekday weigh more),
     * then the most used foods overall.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getMealSuggestions(mealName: String, today: java.time.LocalDate = java.time.LocalDate.now()): Flow<List<FoodItem>> {
        val affinity = when (mealName.lowercase()) {
            "breakfast" -> "breakfast"
            "lunch" -> "lunch"
            "dinner" -> "dinner"
            else -> "snacks"
        }
        return kotlinx.coroutines.flow.combine(
            mealHistory(today, includeDay = true), foodDao.getSuggestedFoodsForMeal(affinity, 40)
        ) { history, popular -> history to popular }
            .mapLatest { (history, popular) -> suggestionsFrom(history, popular, mealName, today) }
    }

    private suspend fun suggestionsFrom(history: List<FoodEntry>, popular: List<FoodItem>, mealName: String, today: java.time.LocalDate): List<FoodItem> {
        val ranked = MealPatterns.rankFoods(history, mealName, today).take(SUGGESTION_LIMIT)
        val latest = history.groupBy(MealPatterns::foodKey).mapValues { (_, v) -> v.maxBy { it.createdAt } }
        val libraryIds = ranked.filterNot { it.startsWith("name:") }
        val library = withContext(Dispatchers.IO) { libraryIds.chunked(500).flatMap { foodDao.getFoodsByIds(it) } }.associateBy { it.id }
        val out = LinkedHashMap<String, FoodItem>()
        ranked.forEach { key ->
            val food = library[key] ?: latest[key]?.let(::foodFromEntry) ?: return@forEach
            out.putIfAbsent(food.id, food)
        }
        popular.forEach { if (out.size < SUGGESTION_LIMIT) out.putIfAbsent(it.id, it) }
        return out.values.take(SUGGESTION_LIMIT)
    }

    /** A food rebuilt from a diary entry (for quick adds and foods no longer in the library). */
    fun foodFromEntry(entry: FoodEntry) = FoodItem(
        id = entry.foodId ?: entry.id, name = entry.foodName, brand = entry.brandName,
        servingSize = entry.quantity, servingUnit = entry.unit,
        calories = entry.calories, protein = entry.protein, carbs = entry.carbs, fat = entry.fat, fiber = 0f
    )

    /** Sparky's AI estimate of a photographed meal, food by food. */
    suspend fun estimateMealPhoto(images: List<Pair<String, String>>, note: String?) =
        sparkyApiClient.estimateMealPhoto(images, note)

    /** True once anything has ever been logged on this phone. */
    suspend fun hasAnyEntries(): Boolean = withContext(Dispatchers.IO) { foodEntryDao.hasAnyEntries() }

    suspend fun getEntry(id: String): FoodEntry? = withContext(Dispatchers.IO) { foodEntryDao.getEntryById(id) }

    /** Foods just logged from search, waiting for the diary to offer an undo. */
    val pendingUndo = kotlinx.coroutines.flow.MutableStateFlow<LoggedBatch?>(null)

    /**
     * Takes back entries just logged. They may already have been sent to Sparky and given a new id, so they are
     * found again by day, name and creation time (which a re-key keeps).
     */
    suspend fun undoLogged(entries: List<FoodEntry>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        val current = entries.map { it.entryDate }.distinct().flatMap { foodEntryDao.getEntriesForDateOnce(it) }
        val wanted = entries.map { Triple(it.entryDate, it.foodName, it.createdAt) }.toSet()
        val found = current.filter { it.id in entries.map { e -> e.id } || Triple(it.entryDate, it.foodName, it.createdAt) in wanted }
            .filter { it.syncStatus != FoodEntry.SYNC_STATUS_PENDING_DELETE }
        deleteEntries(found)
    }

    suspend fun lookupBarcode(barcode: String): FoodItem? {
        val localMatch = foodDao.getFoodByBarcode(barcode)
        // Earlier versions saved unreadable lookups as a blank "Scanned Product"; those aren't real matches
        if (localMatch != null && localMatch.name == "Scanned Product" && localMatch.calories == 0f) {
            foodDao.deleteFood(localMatch.id)
        } else if (localMatch != null) return localMatch

        val remoteResult = sparkyApiClient.getFoodByBarcode(barcode)
        val remoteFood = remoteResult.getOrNull()
        if (remoteFood != null) {
            cacheFoods(listOf(remoteFood))
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

    /** Saved foods by id (those on the phone). */
    suspend fun foodsByIds(ids: List<String>): List<FoodItem> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) emptyList() else ids.chunked(500).flatMap { foodDao.getFoodsByIds(it) }
    }

    /** The id of a saved food with this name and brand, if you have one. */
    suspend fun savedFoodId(name: String, brand: String?): String? = withContext(Dispatchers.IO) {
        val key = FoodSearch.identity(name, brand)
        runCatching { foodDao.searchFoods(name).first() }.getOrDefault(emptyList())
            .firstOrNull { FoodSearch.identity(it.name, it.brand) == key }?.id
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

        // 1. Instant local persistence. Everything added here is new to Sparky (including undo restores).
        val entries = entries.map { it.copy(syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT) }
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

        // 3. Send to Sparky in the background; anything refused stays pending and is retried on the next sync
        appScope.launch(Dispatchers.IO) { pushPendingEntries() }
    }

    /**
     * Update an entry optimistically in local DB and sync changes to Sparky Fitness in background.
     */
    suspend fun updateEntry(entry: FoodEntry) = withContext(Dispatchers.IO) {
        // An entry Sparky has never seen stays a new entry; otherwise it is an edit.
        val status = if (entry.syncStatus == FoodEntry.SYNC_STATUS_PENDING_INSERT) FoodEntry.SYNC_STATUS_PENDING_INSERT else FoodEntry.SYNC_STATUS_PENDING_UPDATE
        foodEntryDao.updateEntry(entry.copy(syncStatus = status))
        prepBatchDao?.onEntryQuantityChanged(entry.id, entry.quantity)
        appScope.launch(Dispatchers.IO) { pushPendingEntries() }
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
    suspend fun deleteEntry(entry: FoodEntry) = withContext(Dispatchers.IO) { deleteEntries(listOf(entry)) }

    /**
     * Delete entries. Ones Sparky never received are simply removed; the rest are hidden and marked for deletion
     * until Sparky confirms, so a deletion made offline isn't undone by the next download.
     */
    suspend fun deleteEntries(entries: List<FoodEntry>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        val ids = entries.map { it.id }
        val (neverSent, onServer) = entries.partition { it.syncStatus == FoodEntry.SYNC_STATUS_PENDING_INSERT }
        if (neverSent.isNotEmpty()) foodEntryDao.deleteEntriesByIds(neverSent.map { it.id })
        onServer.forEach { foodEntryDao.updateSyncStatus(it.id, FoodEntry.SYNC_STATUS_PENDING_DELETE) }
        detachBatchLinks(ids)
        if (onServer.isNotEmpty()) appScope.launch(Dispatchers.IO) { pushPendingEntries() }
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
        foodEntryDao.insertEntries(updatedEntries.map { e ->
            // Entries Sparky hasn't received yet stay new entries
            if (entries.first { it.id == e.id }.syncStatus == FoodEntry.SYNC_STATUS_PENDING_INSERT) e.copy(syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT) else e
        })
        appScope.launch(Dispatchers.IO) { pushPendingEntries() }
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

        // 1. Send the phone's changes first (every date), so nothing made here is lost to the download below
        try { pushPendingEntries() } catch (_: Exception) {}

        // 2. Download Sparky's diary for the date and merge it in
        val fetchResult = sparkyApiClient.fetchEntriesForDate(cleanDate)
        if (fetchResult.isSuccess) {
            // Food Nibl wrote to Health Connect that Sparky's phone app imported back is already here: skip the copy
            val serverEntries = fetchResult.getOrNull().orEmpty()
                .filterNot { com.bytelog.app.health.HealthConnectEcho.isEcho(it.source, it.sourceId) }
            mergeServerEntries(cleanDate, serverEntries)

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

    /**
     * Merges Sparky's entries for a date without losing anything made on the phone:
     *  - entries Sparky has are stored as Sparky has them (unless the phone has an unsent change to them);
     *  - an entry that was downloaded before and is now gone from Sparky was deleted there, so it goes here too;
     *  - entries uploaded from the phone but not (yet) in Sparky's list, and unsent changes, are always kept.
     */
    private suspend fun mergeServerEntries(date: String, serverEntries: List<FoodEntry>) {
        val local = foodEntryDao.getEntriesForDateOnce(date)
        val serverIds = serverEntries.map { it.id }.toSet()
        val keepLocal = local.filter {
            it.syncStatus == FoodEntry.SYNC_STATUS_PENDING_INSERT ||
                it.syncStatus == FoodEntry.SYNC_STATUS_PENDING_UPDATE ||
                it.syncStatus == FoodEntry.SYNC_STATUS_PENDING_DELETE
        }.map { it.id }.toSet()
        val goneFromServer = local.filter { it.syncStatus == FoodEntry.SYNC_STATUS_SYNCED && it.id !in serverIds }.map { it.id }
        if (goneFromServer.isNotEmpty()) {
            foodEntryDao.deleteEntriesByIds(goneFromServer)
            detachBatchLinks(goneFromServer)
        }
        val incoming = serverEntries.filter { it.id !in keepLocal }
        if (incoming.isNotEmpty()) foodEntryDao.insertEntries(incoming)
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
        fiber: Float? = 0f,
        /** "2 cookies" when the serving is a count of pieces; kept on the phone and added to Sparky as a serving. */
        household: com.bytelog.app.recipes.HouseholdServing? = null
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
        val created = result.getOrNull()?.let { food ->
            if (household == null) food
            else {
                val r = household.perUnit * household.quantity
                sparkyApiClient.createFoodVariant(
                    food.id, household.quantity, com.bytelog.app.recipes.HouseholdServings.plural(household.unit, household.quantity),
                    food.calories * r, food.protein * r, food.carbs * r, food.fat * r
                )
                food.copy(householdServings = com.bytelog.app.recipes.HouseholdServings.encode(listOf(household)))
            }
        }
        if (created != null) foodDao.insertFood(created)
        if (created != null) Result.success(created) else result
    }

    /** Reads a nutrition label photo (base64 JPEG) with Sparky's AI. */
    suspend fun scanNutritionLabel(imageBase64: String) = sparkyApiClient.scanNutritionLabel(imageBase64)

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
            listOf(com.bytelog.app.data.remote.ChatMessageInput("user", prompt)),
            toolCategories = listOf("food")
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

    private companion object {
        const val SUGGESTION_LIMIT = 25
    }
}
