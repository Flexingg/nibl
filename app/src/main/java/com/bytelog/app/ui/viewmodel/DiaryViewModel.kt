package com.bytelog.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.local.model.DailySummary
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val _selectedDate = MutableStateFlow(LocalDate.now().format(dateFormatter))
    val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

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

    fun syncSelectedDate() {
        viewModelScope.launch {
            foodRepository.syncDateWithServer(_selectedDate.value)
        }
    }
}
