package com.bytelog.app.ui.viewmodel

import com.bytelog.app.ui.logging.Portion
import com.bytelog.app.ui.logging.Portions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.repository.FoodRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class FastSearchViewModel(
    private val foodRepository: FoodRepository
) : ViewModel() {

    var targetMealTypeId: String = "breakfast"
    var targetMealName: String = "Breakfast"
    var targetDate: String = ""

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Multi-Add Staging Tray (Active Basket)
    private val _stagedEntries = MutableStateFlow<List<FoodEntry>>(emptyList())
    val stagedEntries: StateFlow<List<FoodEntry>> = _stagedEntries.asStateFlow()

    /** Server results and the query they answer; kept while the next query loads, filtered to what still matches. */
    private val _remote = MutableStateFlow<Pair<String, List<FoodItem>>>("" to emptyList())

    /**
     * Search results as you type: foods you've logged first (instantly, from the phone), then saved foods, then new
     * ones from Sparky and Open Food Facts as they arrive.
     */
    val searchResults: StateFlow<com.bytelog.app.data.repository.FoodSearchResults> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) flowOf(com.bytelog.app.data.repository.FoodSearchResults())
            else kotlinx.coroutines.flow.combine(foodRepository.searchOnDevice(query), _remote) { (logged, saved), (_, remote) ->
                com.bytelog.app.data.repository.FoodSearch.combine(logged, saved, remote, com.bytelog.app.data.repository.FoodSearch.tokens(query))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.bytelog.app.data.repository.FoodSearchResults())

    private val _selectedMealName = MutableStateFlow("Breakfast")
    val selectedMealName: StateFlow<String> = _selectedMealName.asStateFlow()

    val availableMealTypes: StateFlow<List<com.bytelog.app.data.local.model.MealType>> = foodRepository.getAllMealTypes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Zero-query context tabs
    val recentFoods: StateFlow<List<FoodItem>> = foodRepository.getRecentlyLoggedFoods(30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteFoods: StateFlow<List<FoodItem>> = foodRepository.getFavoriteFoods()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // What is usually logged at the meal being logged to; follows the meal picker
    val suggestedFoods: StateFlow<List<FoodItem>> = _selectedMealName
        .flatMapLatest { foodRepository.getMealSuggestions(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Opening the keyboard straight away only helps when there are no suggestions to tap instead. */
    suspend fun shouldFocusSearchOnOpen(): Boolean = !foodRepository.hasAnyEntries()

    // Remote search status for OpenFoodFacts & Sparky database
    private val _isSearchingRemote = MutableStateFlow(false)
    val isSearchingRemote: StateFlow<Boolean> = _isSearchingRemote.asStateFlow()

    init {
        viewModelScope.launch {
            _searchQuery
                .map { it.trim() }
                .distinctUntilChanged()
                .onEach { q ->
                    // A query seen recently shows its results at once; the request below refreshes them
                    if (q.length < 2) _remote.value = "" to emptyList()
                    else foodRepository.cachedRemoteResults(q)?.let { _remote.value = q to it }
                }
                .debounce(180)
                .collectLatest { q ->
                    if (q.length < 2) { _isSearchingRemote.value = false; return@collectLatest }
                    _isSearchingRemote.value = true
                    try {
                        foodRepository.searchRemote(q).collect { _remote.value = q to it }
                    } finally {
                        _isSearchingRemote.value = false
                    }
                }
        }
    }

    fun initContext(mealTypeId: String, mealName: String, date: String) {
        val cleanName = mealName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        this.targetMealTypeId = mealTypeId
        this.targetMealName = cleanName
        this.targetDate = date
        this._selectedMealName.value = cleanName
    }

    fun setTargetMeal(mealId: String, mealName: String) {
        val cleanName = mealName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        this.targetMealTypeId = mealId
        this.targetMealName = cleanName
        this._selectedMealName.value = cleanName
        // The tray logs to the meal shown on its button, including foods added before the meal was picked
        _stagedEntries.value = _stagedEntries.value.map { it.copy(mealTypeId = mealId, mealName = cleanName) }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // ----- Staging: one tap adds a food at a sensible amount; the amount and unit stay easy to change -----

    /** The food behind each staged entry, so a new amount or unit is recalculated from the food itself. */
    private val stagedFoods = java.util.concurrent.ConcurrentHashMap<String, FoodItem>()

    /** The staged entry whose amount is being adjusted (the one added last, unless another is picked). */
    private val _activeStagedId = MutableStateFlow<String?>(null)
    val activeStagedId: StateFlow<String?> = _activeStagedId.asStateFlow()

    /** Amount last logged of each food shown, so a tap repeats it. */
    private val _lastPortions = MutableStateFlow<Map<String, Portion>>(emptyMap())
    val lastPortions: StateFlow<Map<String, Portion>> = _lastPortions.asStateFlow()

    fun prefetchPortions(foods: List<FoodItem>) {
        val missing = foods.map { it.id }.filter { it !in _lastPortions.value }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            val found = foodRepository.lastPortions(missing)
            if (found.isNotEmpty()) _lastPortions.value = _lastPortions.value + found.mapValues { (_, v) -> Portion(v.first, v.second) }
        }
    }

    /** What a tap logs: the amount last logged of this food when its unit still fits, else one serving. */
    fun portionFor(food: FoodItem): Portion {
        val last = _lastPortions.value[food.id] ?: return Portions.defaultFor(food)
        val fits = Portions.unitOptions(food).any { it.equals(last.unit, ignoreCase = true) } || last.unit.equals(food.servingUnit, ignoreCase = true)
        return if (fits && last.quantity > 0f) last else Portions.defaultFor(food)
    }

    /** Staged entries backed by a library food (not quick add or voice), which can open the food editor. */
    private val libraryBacked = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun editableFoodId(entryId: String): String? = if (entryId in libraryBacked) stagedFoods[entryId]?.id else null

    /**
     * A food was edited (or saved as the user's copy, or deleted) in the food editor. Staged entries of it take the
     * new food and are recalculated at their current amount.
     */
    fun onFoodEdited(oldId: String, newId: String?) {
        viewModelScope.launch {
            val updated = newId?.let { foodRepository.getLocalFood(it) }
            val ids = stagedFoods.filterValues { it.id == oldId }.keys
            if (updated == null) return@launch
            ids.forEach { entryId ->
                stagedFoods[entryId] = updated
                val e = _stagedEntries.value.firstOrNull { it.id == entryId } ?: return@forEach
                _stagedEntries.value = _stagedEntries.value.map {
                    if (it.id == entryId) it.copy(foodId = updated.id, foodName = updated.name, brandName = updated.brand) else it
                }
                updateStagedPortion(entryId, Portion(e.quantity, e.unit))
            }
            _lastPortions.value = _lastPortions.value - oldId
        }
    }

    fun unitOptionsFor(entryId: String): List<String> = stagedFoods[entryId]?.let { Portions.unitOptions(it) } ?: emptyList()
    fun foodFor(entryId: String): FoodItem? = stagedFoods[entryId]

    fun stageFood(food: FoodItem, portion: Portion? = null): FoodEntry {
        // A server result becomes part of your foods once you use it
        viewModelScope.launch { foodRepository.rememberFood(food) }
        val p = portion ?: portionFor(food)
        val m = Portions.macros(food, p)
        val entry = FoodEntry(
            id = UUID.randomUUID().toString(),
            foodId = food.id,
            foodName = food.name,
            brandName = food.brand,
            mealTypeId = targetMealTypeId,
            mealName = targetMealName,
            quantity = p.quantity,
            unit = p.unit,
            calories = m.calories,
            protein = m.protein,
            carbs = m.carbs,
            fat = m.fat,
            entryDate = targetDate,
            syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT
        )
        stagedFoods[entry.id] = food
        libraryBacked += entry.id
        _stagedEntries.value = _stagedEntries.value + entry
        _activeStagedId.value = entry.id
        return entry
    }

    fun stageFood(food: FoodItem, quantity: Float, unit: String = food.servingUnit) = stageFood(food, Portion(quantity, unit))

    /**
     * One tap on a result. A food not yet in the tray is added at [portionFor]; tapping a food that is already
     * there adds another portion of it to that entry (in the entry's unit) instead of a duplicate line.
     */
    fun tapFood(food: FoodItem) {
        val existing = _stagedEntries.value.lastOrNull { it.foodId == food.id && stagedFoods[it.id]?.id == food.id }
        if (existing == null) { stageFood(food); return }
        val more = Portions.convert(food, portionFor(food), existing.unit).quantity
        updateStagedPortion(existing.id, Portion(Portions.tidy(existing.quantity + more, existing.unit), existing.unit))
        _activeStagedId.value = existing.id
    }

    /** Entries made elsewhere (quick add, voice): their own amount becomes "1 serving" for later changes. */
    fun stageEntries(entries: List<FoodEntry>) {
        entries.forEach { e ->
            stagedFoods[e.id] = FoodItem(
                id = e.foodId ?: e.id, name = e.foodName, brand = e.brandName,
                servingSize = e.quantity, servingUnit = e.unit,
                calories = e.calories, protein = e.protein, carbs = e.carbs, fat = e.fat
            )
        }
        _stagedEntries.value = _stagedEntries.value + entries
        entries.lastOrNull()?.let { _activeStagedId.value = it.id }
    }

    fun selectStagedEntry(entryId: String) { _activeStagedId.value = entryId }

    fun removeStagedEntry(entry: FoodEntry) {
        stagedFoods.remove(entry.id)
        libraryBacked -= entry.id
        _stagedEntries.value = _stagedEntries.value.filter { it.id != entry.id }
        if (_activeStagedId.value == entry.id) _activeStagedId.value = _stagedEntries.value.lastOrNull()?.id
    }

    /** New amount and/or unit for a staged entry; nutrition is recalculated from its food, converting units. */
    fun updateStagedPortion(entryId: String, portion: Portion) {
        val food = stagedFoods[entryId] ?: return
        val m = Portions.macros(food, portion)
        _stagedEntries.value = _stagedEntries.value.map { current ->
            if (current.id == entryId) current.copy(
                quantity = portion.quantity, unit = portion.unit,
                calories = m.calories, protein = m.protein, carbs = m.carbs, fat = m.fat
            ) else current
        }
    }

    /** Kept for callers that edit by entry: same as [updateStagedPortion]. */
    fun updateStagedEntry(entry: FoodEntry, newQuantity: Float, newUnit: String) = updateStagedPortion(entry.id, Portion(newQuantity, newUnit))

    /**
     * Commits all staged items in 1 frame and clears tray.
     */
    fun commitStagedTray(onSuccess: () -> Unit) {
        // Always the meal and day this screen is logging to (the ones on the Log button)
        val entriesToCommit = _stagedEntries.value.map {
            it.copy(mealTypeId = targetMealTypeId, mealName = targetMealName, entryDate = targetDate.ifBlank { it.entryDate })
        }
        if (entriesToCommit.isEmpty()) return

        viewModelScope.launch {
            foodRepository.commitMultiAdd(entriesToCommit)
            // The diary offers to undo this once it is back on screen
            foodRepository.pendingUndo.value = com.bytelog.app.data.repository.LoggedBatch(entriesToCommit, targetMealName)
            _stagedEntries.value = emptyList()
            stagedFoods.clear()
            libraryBacked.clear()
            _activeStagedId.value = null
            onSuccess()
        }
    }

    /**
     * Swipe on a result: logged straight away at its usual amount to the meal being logged to, skipping the tray.
     * Returns what was logged, for an undo.
     */
    fun logNow(food: FoodItem, onLogged: (List<FoodEntry>) -> Unit) {
        val p = portionFor(food)
        val m = Portions.macros(food, p)
        val entry = FoodEntry(
            id = UUID.randomUUID().toString(), foodId = food.id, foodName = food.name, brandName = food.brand,
            mealTypeId = targetMealTypeId, mealName = targetMealName, quantity = p.quantity, unit = p.unit,
            calories = m.calories, protein = m.protein, carbs = m.carbs, fat = m.fat,
            entryDate = targetDate.ifBlank { java.time.LocalDate.now().toString() },
            syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT
        )
        viewModelScope.launch {
            foodRepository.rememberFood(food)
            foodRepository.commitMultiAdd(listOf(entry))
            _lastPortions.value = _lastPortions.value + (food.id to p)
            onLogged(listOf(entry))
        }
    }

    fun undoLogged(entries: List<FoodEntry>) {
        viewModelScope.launch { foodRepository.undoLogged(entries) }
    }

    // ----- Photo logging: snap the plate, Sparky's AI lists each food, the chosen ones go in the tray -----

    sealed interface PhotoState {
        data object Idle : PhotoState
        data class Estimating(val photo: android.graphics.Bitmap?) : PhotoState
        data class Ready(val photo: android.graphics.Bitmap?, val estimate: com.bytelog.app.data.remote.PhotoMealEstimate) : PhotoState
        data class Failed(val photo: android.graphics.Bitmap?, val message: String) : PhotoState
    }

    private val _photoState = MutableStateFlow<PhotoState>(PhotoState.Idle)
    val photoState: StateFlow<PhotoState> = _photoState.asStateFlow()
    private var photoBase64: String? = null
    private var photoBitmap: android.graphics.Bitmap? = null
    private var photoJob: Job? = null

    /** Sends a photo for an estimate; [note] is anything the photo can't show ("cooked in butter"). */
    fun estimatePhoto(photo: android.graphics.Bitmap, note: String = "") {
        photoBitmap = photo
        photoBase64 = null
        reestimatePhoto(note)
    }

    fun reestimatePhoto(note: String = "") {
        val bmp = photoBitmap ?: return
        photoJob?.cancel()
        _photoState.value = PhotoState.Estimating(bmp)
        photoJob = viewModelScope.launch {
            val b64 = photoBase64 ?: kotlinx.coroutines.withContext(Dispatchers.Default) {
                com.bytelog.app.util.ImageUtils.convertBitmapToBase64(bmp, maxDim = 1280, quality = 85)
            }.also { photoBase64 = it }
            foodRepository.estimateMealPhoto(listOf(b64 to "image/jpeg"), note.trim().ifBlank { null })
                .onSuccess { est ->
                    _photoState.value = if (est.items.isEmpty()) PhotoState.Failed(bmp, "No food found in that photo. Try again closer up, or in better light.")
                    else PhotoState.Ready(bmp, est)
                }
                .onFailure { _photoState.value = PhotoState.Failed(bmp, it.message ?: "Couldn't estimate that photo") }
        }
    }

    fun dismissPhoto() {
        photoJob?.cancel()
        _photoState.value = PhotoState.Idle
        photoBitmap = null
        photoBase64 = null
    }

    /** The picked foods from a photo go in the tray at the estimated weight, ready to adjust and log. */
    fun stagePhotoItems(items: List<com.bytelog.app.data.remote.PhotoMealItem>) {
        if (items.isEmpty()) return
        stageEntries(items.map { photoEntry(it) })
        dismissPhoto()
    }

    private fun photoEntry(item: com.bytelog.app.data.remote.PhotoMealItem): FoodEntry {
        val grams = item.grams.takeIf { it > 0f }
        return FoodEntry(
            id = UUID.randomUUID().toString(),
            foodId = null,
            foodName = item.name.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
            brandName = "Photo estimate",
            mealTypeId = targetMealTypeId,
            mealName = targetMealName,
            quantity = grams ?: 1f,
            unit = if (grams != null) "g" else "serving",
            calories = item.calories, protein = item.protein, carbs = item.carbs, fat = item.fat,
            entryDate = targetDate.ifBlank { java.time.LocalDate.now().toString() },
            syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT
        )
    }

    /**
     * Continuous burst barcode scanner handler.
     */
    suspend fun handleScannedBarcode(barcode: String): FoodItem? {
        val food = foodRepository.lookupBarcode(barcode)
        if (food != null) {
            // A code scanned again after leaving the frame adds another portion rather than a duplicate line
            tapFood(food)
        }
        return food
    }

    fun toggleFavorite(food: FoodItem) {
        viewModelScope.launch {
            foodRepository.rememberFood(food)
            foodRepository.toggleFavorite(food.id, food.isFavorite)
        }
    }

    fun createCustomFood(
        name: String,
        brand: String?,
        servingSize: Float,
        servingUnit: String,
        calories: Float,
        protein: Float,
        carbs: Float,
        fat: Float,
        fiber: Float?,
        autoStage: Boolean = true
    ) {
        viewModelScope.launch {
            val result = foodRepository.createCustomFood(
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
            if (result.isSuccess && autoStage) {
                val item = result.getOrNull()
                if (item != null) {
                    stageFood(item, Portion(servingSize, servingUnit))
                }
            }
        }
    }

    private val _isGeneratingAi = MutableStateFlow(false)
    val isGeneratingAi: StateFlow<Boolean> = _isGeneratingAi.asStateFlow()

    private val _aiGenerationError = MutableStateFlow<String?>(null)
    val aiGenerationError: StateFlow<String?> = _aiGenerationError.asStateFlow()

    fun generateFoodWithAi(
        name: String,
        description: String = "",
        imageBase64: String? = null,
        imageMimeType: String = "image/jpeg",
        autoStage: Boolean = true,
        onSuccess: (FoodItem) -> Unit
    ) {
        viewModelScope.launch {
            _isGeneratingAi.value = true
            _aiGenerationError.value = null
            try {
                val result = foodRepository.generateFoodWithAi(
                    name = name,
                    description = description,
                    imageBase64 = imageBase64,
                    imageMimeType = imageMimeType
                )
                if (result.isSuccess) {
                    val item = result.getOrNull()
                    if (item != null) {
                        if (autoStage) {
                            stageFood(item)
                        }
                        onSuccess(item)
                    }
                } else {
                    _aiGenerationError.value = result.exceptionOrNull()?.message ?: "AI food generation failed"
                }
            } finally {
                _isGeneratingAi.value = false
            }
        }
    }
}
