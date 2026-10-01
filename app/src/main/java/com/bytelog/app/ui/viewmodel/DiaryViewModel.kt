package com.bytelog.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.local.model.DailySummary
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.WaterLogEntry
import com.bytelog.app.data.remote.WaterContainer
import com.bytelog.app.data.local.model.MealTemplate
import com.bytelog.app.data.local.model.TemplateIngredient
import com.bytelog.app.data.local.model.WeightEntry
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.MealTemplateRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.WaterRepository
import com.bytelog.app.data.repository.WeightRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(
    private val foodRepository: FoodRepository,
    private val waterRepository: WaterRepository,
    private val settingsRepository: SettingsRepository,
    private val weightRepository: WeightRepository,
    private val mealTemplateRepository: MealTemplateRepository
) : ViewModel() {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val _selectedDate = MutableStateFlow(LocalDate.now().format(dateFormatter))
    val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableSharedFlow<String>()
    val syncMessage: SharedFlow<String> = _syncMessage.asSharedFlow()

    val mealTypes: StateFlow<List<MealType>> = foodRepository.getAllMealTypes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MealType.DEFAULT_MEAL_TYPES)

    val entriesForDate: StateFlow<List<FoodEntry>> = _selectedDate
        .flatMapLatest { date -> foodRepository.getEntriesForDate(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailySummary: StateFlow<DailySummary> = combine(
        entriesForDate,
        settingsRepository.targetCalories,
        settingsRepository.targetProtein,
        settingsRepository.targetCarbs,
        settingsRepository.targetFat
    ) { entries, targetCal, targetP, targetC, targetF ->
        val totalCal = entries.sumOf { it.calories.toDouble() }.toFloat()
        val totalP = entries.sumOf { it.protein.toDouble() }.toFloat()
        val totalC = entries.sumOf { it.carbs.toDouble() }.toFloat()
        val totalF = entries.sumOf { it.fat.toDouble() }.toFloat()

        DailySummary(
            date = _selectedDate.value,
            totalCalories = totalCal,
            targetCalories = targetCal,
            totalProtein = totalP,
            targetProtein = targetP,
            totalCarbs = totalC,
            targetCarbs = targetC,
            totalFat = totalF,
            targetFat = targetF
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DailySummary(date = _selectedDate.value))

    val waterTotalOz: StateFlow<Float> = _selectedDate
        .flatMapLatest { date -> waterRepository.getTotalWaterOzForDate(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    val waterLogsForDate: StateFlow<List<WaterLogEntry>> = _selectedDate
        .flatMapLatest { date -> waterRepository.getLogsForDate(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val waterContainers: StateFlow<List<WaterContainer>> = waterRepository.containers

    val targetWaterOz: StateFlow<Float> = waterRepository.targetWaterOz
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 64f)

    val defaultContainerOz: StateFlow<Float> = waterRepository.defaultContainerOz
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 8f)

    val defaultContainerName: StateFlow<String> = waterRepository.defaultContainerName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Glass")

    val currentWeight: StateFlow<WeightEntry?> = _selectedDate
        .flatMapLatest { date -> weightRepository.getWeightForDate(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val previousWeight: StateFlow<WeightEntry?> = _selectedDate
        .flatMapLatest { date -> weightRepository.getLatestWeightOnOrBefore(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val weightUnit: StateFlow<String> = weightRepository.weightUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "lbs")

    val mealTemplates: StateFlow<List<MealTemplate>> = mealTemplateRepository.templates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableFoods: StateFlow<List<FoodItem>> = foodRepository.getRecentFoods(30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        syncSelectedDate()
    }

    fun changeDay(offsetDays: Long) {
        val current = LocalDate.parse(_selectedDate.value, dateFormatter)
        val newDate = current.plusDays(offsetDays).format(dateFormatter)
        _selectedDate.value = newDate
        syncSelectedDate()
    }

    fun setDate(date: String) {
        _selectedDate.value = date
        syncSelectedDate()
    }

    fun deleteEntry(entry: FoodEntry) {
        viewModelScope.launch {
            foodRepository.deleteEntry(entry)
        }
    }

    fun updateEntry(entry: FoodEntry) {
        viewModelScope.launch {
            foodRepository.updateEntry(entry)
        }
    }

    fun duplicateEntryToNow(entry: FoodEntry) {
        viewModelScope.launch {
            foodRepository.duplicateEntry(
                source = entry,
                targetDate = _selectedDate.value,
                targetMealTypeId = entry.mealTypeId,
                targetMealName = entry.mealName
            )
        }
    }

    // --- Hydration Actions (Always in oz) ---

    fun quickAddWaterOz(
        amountOz: Float,
        containerId: Int? = null,
        containerName: String? = null
    ) {
        waterRepository.quickAddWaterOz(
            date = _selectedDate.value,
            amountOz = amountOz,
            containerId = containerId,
            containerName = containerName
        )
    }

    fun logDefaultDrink() {
        quickAddWaterOz(
            amountOz = defaultContainerOz.value,
            containerId = null,
            containerName = defaultContainerName.value
        )
    }

    fun setDefaultContainer(oz: Float, name: String) {
        viewModelScope.launch {
            waterRepository.setDefaultContainer(oz, name)
        }
    }

    fun undoLastDrink() {
        waterRepository.undoLastDrink(_selectedDate.value)
    }

    fun deleteWaterLog(entry: WaterLogEntry) {
        waterRepository.deleteWaterLog(entry)
    }

    // --- Bodyweight Actions ---

    fun logWeight(weight: Float, unit: String? = null, bodyFat: Float? = null) {
        weightRepository.logWeight(_selectedDate.value, weight, unit, bodyFat)
    }

    fun saveWeightUnit(unit: String) {
        viewModelScope.launch {
            weightRepository.saveWeightUnit(unit)
        }
    }

    // --- Copy Meals Actions ---

    fun copyMealFromYesterday(mealType: String) {
        viewModelScope.launch {
            _isSyncing.value = true
            val result = foodRepository.copyMealFromYesterday(mealType, _selectedDate.value)
            _isSyncing.value = false
            if (result.isSuccess) {
                _syncMessage.emit("Copied yesterday's $mealType!")
            } else {
                _syncMessage.emit("Failed to copy $mealType: ${result.exceptionOrNull()?.message ?: "Error"}")
            }
        }
    }

    fun copyMeal(sourceDate: String, sourceMeal: String, targetDate: String, targetMeal: String) {
        viewModelScope.launch {
            _isSyncing.value = true
            val result = foodRepository.copyMeal(sourceDate, sourceMeal, targetDate, targetMeal)
            _isSyncing.value = false
            if (result.isSuccess) {
                _syncMessage.emit("Copied $sourceMeal into $targetMeal!")
            } else {
                _syncMessage.emit("Failed to copy meal: ${result.exceptionOrNull()?.message ?: "Error"}")
            }
        }
    }

    fun copyAllFromYesterday() {
        viewModelScope.launch {
            _isSyncing.value = true
            val result = foodRepository.copyAllFromYesterday(_selectedDate.value)
            _isSyncing.value = false
            if (result.isSuccess) {
                _syncMessage.emit("Copied all yesterday's meals!")
            } else {
                _syncMessage.emit("Failed to copy yesterday: ${result.exceptionOrNull()?.message ?: "Error"}")
            }
        }
    }

    // --- Meal Template & Recipe Actions ---

    fun saveMealAsTemplate(mealName: String, description: String?) {
        viewModelScope.launch {
            val result = mealTemplateRepository.createTemplateFromDiary(
                date = _selectedDate.value,
                mealType = mealName,
                mealName = "$mealName Template",
                description = description
            )
            if (result.isSuccess) {
                _syncMessage.emit("Saved $mealName as template to Sparky!")
            } else {
                _syncMessage.emit("Failed to save template: ${result.exceptionOrNull()?.message ?: "Error"}")
            }
        }
    }

    fun createRecipeTemplate(
        name: String,
        description: String?,
        servingSize: Float,
        servingUnit: String,
        totalServings: Float,
        foods: List<TemplateIngredient>
    ) {
        viewModelScope.launch {
            val result = mealTemplateRepository.createRecipeTemplate(
                name = name,
                description = description,
                servingSize = servingSize,
                servingUnit = servingUnit,
                totalServings = totalServings,
                foods = foods
            )
            if (result.isSuccess) {
                _syncMessage.emit("Recipe '$name' created on Sparky!")
            } else {
                _syncMessage.emit("Failed to create recipe: ${result.exceptionOrNull()?.message ?: "Error"}")
            }
        }
    }

    fun logTemplateToDiary(template: MealTemplate, mealName: String, mealId: String?) {
        viewModelScope.launch {
            _isSyncing.value = true
            val result = mealTemplateRepository.logTemplateToDiary(
                templateId = template.id,
                mealType = mealName,
                mealTypeId = mealId,
                date = _selectedDate.value
            )
            if (result.isSuccess) {
                foodRepository.syncDateWithServer(_selectedDate.value)
                _syncMessage.emit("Logged ${template.name} to $mealName!")
            } else {
                _syncMessage.emit("Failed to log template: ${result.exceptionOrNull()?.message ?: "Error"}")
            }
            _isSyncing.value = false
        }
    }

    fun deleteTemplate(templateId: String) {
        viewModelScope.launch {
            mealTemplateRepository.deleteTemplate(templateId)
        }
    }

    fun syncSelectedDate() {
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                foodRepository.syncDateWithServer(_selectedDate.value)
                waterRepository.syncWaterForDate(_selectedDate.value)
                weightRepository.syncWeightForDate(_selectedDate.value)
            } catch (_: Exception) {
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun manualSync() {
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                val foodRes = foodRepository.syncDateWithServer(_selectedDate.value)
                val waterRes = waterRepository.syncWaterForDate(_selectedDate.value)
                weightRepository.syncWeightForDate(_selectedDate.value)
                mealTemplateRepository.refreshTemplates()

                foodRes.fold(
                    onSuccess = { count ->
                        val waterInfo = if (waterRes.isSuccess) ", ${waterRes.getOrNull()?.toInt() ?: 0} ml water" else ""
                        _syncMessage.emit(
                            if (count > 0) "Synced $count food entries$waterInfo from Sparky Fitness"
                            else "Synced with Sparky (0 foods$waterInfo)"
                        )
                    },
                    onFailure = { err ->
                        _syncMessage.emit("Sync failed: ${err.message ?: "Connection error"}")
                    }
                )
            } catch (e: Exception) {
                _syncMessage.emit("Sync error: ${e.message ?: "Unknown error"}")
            } finally {
                _isSyncing.value = false
            }
        }
    }
}
