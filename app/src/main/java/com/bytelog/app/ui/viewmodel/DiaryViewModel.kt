package com.bytelog.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.local.model.DailySummary
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.WaterLogEntry
import com.bytelog.app.data.remote.WaterContainer
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.WaterRepository
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
    private val settingsRepository: SettingsRepository
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

    val waterTotalForDate: StateFlow<Float> = _selectedDate
        .flatMapLatest { date -> waterRepository.getTotalWaterForDate(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    val waterLogsForDate: StateFlow<List<WaterLogEntry>> = _selectedDate
        .flatMapLatest { date -> waterRepository.getLogsForDate(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val waterContainers: StateFlow<List<WaterContainer>> = waterRepository.containers

    val targetWater: StateFlow<Float> = settingsRepository.targetWater
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 2000f)

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

    // --- Hydration Actions ---

    fun quickAddWater(
        amountMl: Float,
        containerId: Int? = null,
        containerName: String? = null
    ) {
        waterRepository.quickAddWater(
            date = _selectedDate.value,
            amountMl = amountMl,
            containerId = containerId,
            containerName = containerName
        )
    }

    fun undoLastDrink() {
        waterRepository.undoLastDrink(_selectedDate.value)
    }

    fun deleteWaterLog(entry: WaterLogEntry) {
        waterRepository.deleteWaterLog(entry)
    }

    fun syncSelectedDate() {
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                foodRepository.syncDateWithServer(_selectedDate.value)
                waterRepository.syncWaterForDate(_selectedDate.value)
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
