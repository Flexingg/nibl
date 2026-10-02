package com.bytelog.app.ui.viewmodel

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
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Instantly stage food to multi-add tray with default or custom portion.
     */
    fun stageFood(food: FoodItem, quantity: Float = food.servingSize, unit: String = food.servingUnit) {
        val entry = FoodEntry(
            id = UUID.randomUUID().toString(),
            foodId = food.id,
            foodName = food.name,
            brandName = food.brand,
            mealTypeId = targetMealTypeId,
            mealName = targetMealName,
            quantity = quantity,
            unit = unit,
            calories = food.caloriesFor(quantity, unit),
            protein = food.proteinFor(quantity, unit),
            carbs = food.carbsFor(quantity, unit),
            fat = food.fatFor(quantity, unit),
            entryDate = targetDate,
            syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT
        )
        _stagedEntries.value = _stagedEntries.value + entry
    }

    fun stageEntries(entries: List<FoodEntry>) {
        _stagedEntries.value = _stagedEntries.value + entries
    }

    fun removeStagedEntry(entry: FoodEntry) {
        _stagedEntries.value = _stagedEntries.value.filter { it.id != entry.id }
    }

    fun updateStagedEntry(entry: FoodEntry, newQuantity: Float, newUnit: String) {
        val updatedList = _stagedEntries.value.map { current ->
            if (current.id == entry.id) {
                val ratio = if (current.quantity > 0f) newQuantity / current.quantity else 1f
                current.copy(
                    quantity = newQuantity,
                    unit = newUnit,
                    calories = current.calories * ratio,
                    protein = current.protein * ratio,
                    carbs = current.carbs * ratio,
                    fat = current.fat * ratio
                )
            } else current
        }
        _stagedEntries.value = updatedList
    }

    /**
     * Commits all staged items in 1 frame and clears tray.
     */
    fun commitStagedTray(onSuccess: () -> Unit) {
        val entriesToCommit = _stagedEntries.value
        if (entriesToCommit.isEmpty()) return

        viewModelScope.launch {
            foodRepository.commitMultiAdd(entriesToCommit)
            _stagedEntries.value = emptyList()
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
                    stageFood(item, servingSize, servingUnit)
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
