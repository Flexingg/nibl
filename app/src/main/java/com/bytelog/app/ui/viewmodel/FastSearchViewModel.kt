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
import kotlinx.coroutines.flow.distinctUntilChanged
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

    // Sub-16ms Instant Local Search
    val searchResults: StateFlow<List<FoodItem>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) {
                flowOf(emptyList())
            } else {
                foodRepository.searchLocalFoods(query)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedMealName = MutableStateFlow("Breakfast")
    val selectedMealName: StateFlow<String> = _selectedMealName.asStateFlow()

    val availableMealTypes: StateFlow<List<com.bytelog.app.data.local.model.MealType>> = foodRepository.getAllMealTypes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Zero-query context tabs
    val recentFoods: StateFlow<List<FoodItem>> = foodRepository.getRecentlyLoggedFoods(30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteFoods: StateFlow<List<FoodItem>> = foodRepository.getFavoriteFoods()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val suggestedFoods: StateFlow<List<FoodItem>> = foodRepository.getMealSuggestions(targetMealName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Remote search status for OpenFoodFacts & Sparky database
    private val _isSearchingRemote = MutableStateFlow(false)
    val isSearchingRemote: StateFlow<Boolean> = _isSearchingRemote.asStateFlow()

    // Remote search job for Sparky Fitness API
    private var remoteSearchJob: Job? = null

    init {
        viewModelScope.launch {
            _searchQuery
                .debounce(250)
                .distinctUntilChanged()
                .collect { query ->
                    val trimmed = query.trim()
                    if (trimmed.length >= 2) {
                        remoteSearchJob?.cancel()
                        remoteSearchJob = launch(Dispatchers.IO) {
                            _isSearchingRemote.value = true
                            try {
                                foodRepository.searchRemoteFoods(trimmed)
                            } finally {
                                _isSearchingRemote.value = false
                            }
                        }
                    } else {
                        remoteSearchJob?.cancel()
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
            _stagedEntries.value = emptyList()
            stagedFoods.clear()
            libraryBacked.clear()
            _activeStagedId.value = null
            onSuccess()
        }
    }

    /**
     * Continuous burst barcode scanner handler.
     */
    suspend fun handleScannedBarcode(barcode: String): FoodItem? {
        val food = foodRepository.lookupBarcode(barcode)
        if (food != null) {
            stageFood(food)
        }
        return food
    }

    fun toggleFavorite(food: FoodItem) {
        viewModelScope.launch {
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
