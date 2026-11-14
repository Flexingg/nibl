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
import com.bytelog.app.data.repository.LoggedBatch
import com.bytelog.app.data.repository.MealPatterns
import com.bytelog.app.data.repository.MealShortcuts
import com.bytelog.app.data.repository.FitOption
import com.bytelog.app.data.repository.Fasting
import com.bytelog.app.data.repository.Remaining
import com.bytelog.app.data.repository.WeeklyReview
import com.bytelog.app.data.repository.WhatFits
import com.bytelog.app.data.repository.asDayWeights
import com.bytelog.app.data.repository.MealTemplateRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.WaterRepository
import com.bytelog.app.data.repository.WeightRepository
import com.bytelog.app.data.analytics.AdaptiveTdeeCalculation
import com.bytelog.app.data.analytics.AdaptiveTdeeEngine
import com.bytelog.app.data.analytics.DayChartPoint
import com.bytelog.app.data.remote.AdaptiveTdeeResult
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.ui.components.RecentMealItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private data class GoalSettingsData(
    val autoAdjust: Boolean,
    val goalMode: String,
    val goalOffset: Float,
    val fallbackCalories: Float,
    val weightUnit: String
)

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(
    private val foodRepository: FoodRepository,
    private val waterRepository: WaterRepository,
    private val settingsRepository: SettingsRepository,
    private val weightRepository: WeightRepository,
    private val mealTemplateRepository: MealTemplateRepository,
    private val sparkyApiClient: SparkyApiClient? = null,
    private val plans: com.bytelog.app.data.repository.PlanRepository? = null
) : ViewModel() {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val _selectedDate = MutableStateFlow(LocalDate.now().format(dateFormatter))
    val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableSharedFlow<String>()
    val syncMessage: SharedFlow<String> = _syncMessage.asSharedFlow()

    private val _serverAdaptiveTdee = MutableStateFlow<AdaptiveTdeeResult?>(null)

    private val _selectedChartRangeDays = MutableStateFlow(30)
    val selectedChartRangeDays: StateFlow<Int> = _selectedChartRangeDays.asStateFlow()

    val autoAdjustCalories: StateFlow<Boolean> = settingsRepository.autoAdjustCalories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val calorieGoalMode: StateFlow<String> = settingsRepository.calorieGoalMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "cut")

    val calorieGoalOffset: StateFlow<Float> = settingsRepository.calorieGoalOffset
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -500f)

    val mealTypes: StateFlow<List<MealType>> = foodRepository.getAllMealTypes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MealType.DEFAULT_MEAL_TYPES)

    val entriesForDate: StateFlow<List<FoodEntry>> = _selectedDate
        .flatMapLatest { date -> foodRepository.getEntriesForDate(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allEntries: StateFlow<List<FoodEntry>> = foodRepository.getAllEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allWeights: StateFlow<List<WeightEntry>> = weightRepository.getAllWeights()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weightUnit: StateFlow<String> = weightRepository.weightUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "lbs")

    private val goalSettingsFlow = combine(
        settingsRepository.autoAdjustCalories,
        settingsRepository.calorieGoalMode,
        settingsRepository.calorieGoalOffset,
        settingsRepository.targetCalories,
        weightUnit
    ) { autoAdjust, mode, offset, fallbackCal, unit ->
        GoalSettingsData(autoAdjust, mode, offset, fallbackCal, unit)
    }

    val adaptiveTdeeCalculation: StateFlow<AdaptiveTdeeCalculation> = combine(
        allEntries,
        allWeights,
        _selectedDate,
        goalSettingsFlow,
        _serverAdaptiveTdee
    ) { entries, weights, date, settings, serverRes ->
        if (serverRes != null && !serverRes.isFallback && serverRes.tdee > 0f) {
            val recommended = (serverRes.tdee + settings.goalOffset).coerceAtLeast(1200f)
            val isLbs = settings.weightUnit.equals("lbs", ignoreCase = true)
            val weeklyDelta = if (serverRes.weightChangeKg != null) {
                val change = if (isLbs) serverRes.weightChangeKg * 2.20462f else serverRes.weightChangeKg
                change * (7f / 28f)
            } else null

            val trend = if (isLbs && serverRes.weightTrend != null) {
                (serverRes.weightTrend * 2.20462f * 10).toInt() / 10f
            } else {
                serverRes.weightTrend
            }

            AdaptiveTdeeCalculation(
                tdee = serverRes.tdee,
                confidence = serverRes.confidence,
                weightTrend = trend,
                weightChangeRatePerWeek = weeklyDelta,
                avgDailyIntake = serverRes.avgIntake,
                recommendedCalories = recommended,
                goalMode = settings.goalMode,
                goalOffset = settings.goalOffset,
                daysOfData = serverRes.daysOfData,
                isFallback = false,
                explanation = "Synced with Sparky Adaptive Engine (${serverRes.confidence.lowercase().replaceFirstChar { it.uppercase() }} confidence)"
            )
        } else {
            AdaptiveTdeeEngine.calculate(
                allEntries = entries,
                allWeights = weights,
                targetDateStr = date,
                goalOffset = settings.goalOffset,
                goalMode = settings.goalMode,
                weightUnit = settings.weightUnit,
                fallbackTdee = settings.fallbackCalories
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AdaptiveTdeeEngine.calculate(emptyList(), emptyList(), LocalDate.now().format(dateFormatter))
    )

    val dailySummary: StateFlow<DailySummary> = combine(
        entriesForDate,
        adaptiveTdeeCalculation,
        goalSettingsFlow,
        combine(settingsRepository.targetProtein, settingsRepository.targetCarbs, settingsRepository.targetFat) { p, c, f -> Triple(p, c, f) }
    ) { entries, adaptiveCalc, settings, macroTargets ->
        val (targetP, targetC, targetF) = macroTargets
        val totalCal = entries.sumOf { it.calories.toDouble() }.toFloat()
        val totalP = entries.sumOf { it.protein.toDouble() }.toFloat()
        val totalC = entries.sumOf { it.carbs.toDouble() }.toFloat()
        val totalF = entries.sumOf { it.fat.toDouble() }.toFloat()

        val targetCal = if (settings.autoAdjust) adaptiveCalc.recommendedCalories else settings.fallbackCalories
        val autoMacros = if (settings.autoAdjust) {
            val isLbs = settings.weightUnit.equals("lbs", ignoreCase = true)
            val currentKg = if (adaptiveCalc.weightTrend != null) {
                if (isLbs) adaptiveCalc.weightTrend * 0.45359237f else adaptiveCalc.weightTrend
            } else 75f
            AdaptiveTdeeEngine.computeMacros(targetCal, currentKg)
        } else null

        DailySummary(
            date = _selectedDate.value,
            totalCalories = totalCal,
            targetCalories = targetCal,
            totalProtein = totalP,
            targetProtein = autoMacros?.protein ?: targetP,
            totalCarbs = totalC,
            targetCarbs = autoMacros?.carbs ?: targetC,
            totalFat = totalF,
            targetFat = autoMacros?.fat ?: targetF
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DailySummary(date = _selectedDate.value))

    val weightVsCaloriesChartData: StateFlow<List<DayChartPoint>> = combine(
        allEntries,
        allWeights,
        _selectedChartRangeDays,
        dailySummary,
        weightUnit
    ) { entries, weights, rangeDays, summary, unit ->
        AdaptiveTdeeEngine.buildChartPoints(
            allEntries = entries,
            allWeights = weights,
            rangeDays = rangeDays,
            targetDateStr = summary.date,
            targetCalories = summary.targetCalories,
            weightUnit = unit
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentMealsHistory: StateFlow<List<RecentMealItem>> = combine(
        allEntries,
        _selectedDate,
        mealTypes
    ) { entries, _, mTypes ->
        val groups = entries
            .filter { it.entryDate.isNotBlank() }
            .groupBy { Pair(it.entryDate, it.mealName.trim().lowercase()) }

        val todayStr = LocalDate.now().format(dateFormatter)
        val yesterdayStr = LocalDate.now().minusDays(1).format(dateFormatter)

        groups.map { (key, groupEntries) ->
            val (date, lowerMeal) = key
            val matchedType = mTypes.find { it.name.trim().equals(lowerMeal, ignoreCase = true) }
            val displayName = matchedType?.name?.trim()?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                ?: groupEntries.first().mealName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val mealId = matchedType?.id ?: groupEntries.first().mealTypeId

            val displayDate = when (date) {
                todayStr -> "Today"
                yesterdayStr -> "Yesterday"
                else -> try {
                    LocalDate.parse(date, dateFormatter).format(DateTimeFormatter.ofPattern("MMM d"))
                } catch (_: Exception) {
                    date
                }
            }

            val cal = groupEntries.sumOf { it.calories.toDouble() }.toFloat()
            val p = groupEntries.sumOf { it.protein.toDouble() }.toFloat()
            val c = groupEntries.sumOf { it.carbs.toDouble() }.toFloat()
            val f = groupEntries.sumOf { it.fat.toDouble() }.toFloat()

            val foodSummary = groupEntries.map { it.foodName }.distinct().take(3).joinToString(", ") +
                if (groupEntries.map { it.foodName }.distinct().size > 3) " +${groupEntries.size - 3} more" else ""

            RecentMealItem(
                date = date,
                displayDate = displayDate,
                mealName = displayName,
                mealTypeId = mealId,
                calories = cal,
                protein = p,
                carbs = c,
                fat = f,
                foodSummary = foodSummary,
                entries = groupEntries
            )
        }.sortedWith(compareByDescending<RecentMealItem> { it.date }.thenBy { it.mealName })
        .take(15)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    val mealTemplates: StateFlow<List<MealTemplate>> = mealTemplateRepository.templates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableFoods: StateFlow<List<FoodItem>> = foodRepository.getRecentFoods(30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        syncSelectedDate()
        viewModelScope.launch {
            weightRepository.repairKgStoredAsLbs()
            weightRepository.syncWeightHistory()
        }
    }

    // ----- One-tap logging: shortcuts for empty meals, undo, amounts changed in place -----

    /** For each meal (by [MealPatterns.mealKey]): yesterday's foods and the usual, for the day being shown. */
    val mealShortcuts: StateFlow<Map<String, MealShortcuts>> = combine(
        _selectedDate.flatMapLatest { d ->
            val day = runCatching { LocalDate.parse(d) }.getOrElse { LocalDate.now() }
            foodRepository.mealHistory(day).map { day to it }
        },
        mealTypes
    ) { (day, history), types ->
        (if (types.isNotEmpty()) types else MealType.DEFAULT_MEAL_TYPES)
            .associate { MealPatterns.mealKey(it.name) to MealShortcuts.of(history, it.name, day) }
            .filterValues { !it.isEmpty }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // ----- Insights: what fits, the weekly review, the fast -----

    private fun todayIso() = LocalDate.now().format(dateFormatter)

    /** The day shown with the diary history before it (and the day itself). */
    private val historyWithDay = _selectedDate.flatMapLatest { d ->
        val day = runCatching { LocalDate.parse(d) }.getOrElse { LocalDate.now() }
        foodRepository.mealHistory(day, includeDay = true).map { day to it }
    }

    /** Foods you eat that fit what's left of today's goals (only for today). */
    val whatFits: StateFlow<List<FitOption>> = combine(historyWithDay, dailySummary) { (day, history), s ->
        if (day.toString() != todayIso()) return@combine emptyList()
        val left = Remaining(s.targetCalories - s.totalCalories, s.targetProtein - s.totalProtein, s.targetCarbs - s.totalCarbs, s.targetFat - s.totalFat)
        WhatFits.suggest(left, WhatFits.candidates(history.filter { it.entryDate != day.toString() }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun logFit(option: FitOption, mealTypeId: String, mealName: String, onLogged: (LoggedBatch) -> Unit) =
        logShortcut(option.foods, mealTypeId, mealName, onLogged)

    /** The last seven days, shown on Sunday and Monday until closed. */
    val weeklyReview: StateFlow<WeeklyReview?> = combine(
        historyWithDay, dailySummary, allWeights, weightUnit, settingsRepository.weeklyReviewDismissed
    ) { (day, history), s, weights, unit, dismissed ->
        if (day.toString() != todayIso() || !WeeklyReview.isReviewDay(day)) return@combine null
        val review = WeeklyReview.of(history, day, s.targetCalories, s.targetProtein,
            weights.asDayWeights { com.bytelog.app.ui.components.displayWeight(it, unit) })
        review.takeIf { it.end.toString() != dismissed && it.daysLogged > 0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Food planned for the day shown, not yet eaten. */
    val plannedEntries: StateFlow<List<com.bytelog.app.data.local.model.PlannedEntry>> = selectedDate
        .flatMapLatest { d -> plans?.forDate(d) ?: kotlinx.coroutines.flow.flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Ticks planned food off: logged on its day. */
    fun eatPlanned(planned: List<com.bytelog.app.data.local.model.PlannedEntry>) {
        viewModelScope.launch { plans?.eat(planned) }
    }

    /** Copies a day's planned food onto other days. */
    fun copyPlanned(planned: List<com.bytelog.app.data.local.model.PlannedEntry>, dates: Set<String>, done: (Int) -> Unit = {}) {
        val repo = plans ?: return
        viewModelScope.launch { done(repo.copyTo(planned, dates).size) }
    }

    fun removePlanned(planned: List<com.bytelog.app.data.local.model.PlannedEntry>) {
        viewModelScope.launch { plans?.remove(planned) }
    }

    fun restorePlanned(planned: List<com.bytelog.app.data.local.model.PlannedEntry>) {
        viewModelScope.launch { plans?.restore(planned) }
    }

    /** The order of the home screen sections. */
    val homeLayout: StateFlow<com.bytelog.app.data.repository.HomeLayout> = settingsRepository.homeLayout
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.bytelog.app.data.repository.HomeLayout.DEFAULT)

    fun saveHomeLayout(layout: com.bytelog.app.data.repository.HomeLayout) {
        viewModelScope.launch { settingsRepository.saveHomeLayout(layout) }
    }

    fun dismissWeeklyReview(review: WeeklyReview) {
        viewModelScope.launch { settingsRepository.dismissWeeklyReview(review.end.toString()) }
    }

    /** When food was last eaten (today or yesterday), for the fasting timer. */
    val lastMealAt: StateFlow<Long?> = kotlinx.coroutines.flow.flow {
        while (true) { emit(LocalDate.now()); kotlinx.coroutines.delay(60_000) }
    }.distinctUntilChanged().flatMapLatest { today ->
        foodRepository.getEntriesInRange(today.minusDays(1).toString(), today.toString()).map { Fasting.lastMealAt(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Foods logged from search that the diary should offer to undo. */
    val pendingUndo: StateFlow<LoggedBatch?> = foodRepository.pendingUndo.asStateFlow()

    fun consumePendingUndo(batch: LoggedBatch) { foodRepository.pendingUndo.compareAndSet(batch, null) }

    fun undoLogged(entries: List<FoodEntry>) {
        viewModelScope.launch { foodRepository.undoLogged(entries) }
    }

    /** Logs a shortcut's foods to [mealName] on the day shown, at the same amounts. */
    fun logShortcut(source: List<FoodEntry>, mealTypeId: String, mealName: String, onLogged: (LoggedBatch) -> Unit) {
        if (source.isEmpty()) return
        viewModelScope.launch {
            val copies = MealPatterns.copiesFor(source, _selectedDate.value, mealTypeId, mealName)
            foodRepository.commitMultiAdd(copies)
            onLogged(LoggedBatch(copies, mealName))
        }
    }

    /** The food behind an entry, for changing its amount; rebuilt from the entry when it isn't in the library. */
    suspend fun foodForEntry(entry: FoodEntry): FoodItem =
        entry.foodId?.let { foodRepository.getLocalFood(it) }?.takeIf { it.servingSize > 0f } ?: foodRepository.foodFromEntry(entry)

    /** A new amount or unit for a logged entry, with nutrition worked out again from its food. */
    fun setEntryPortion(entry: FoodEntry, food: FoodItem, portion: com.bytelog.app.ui.logging.Portion) {
        viewModelScope.launch {
            // The stored entry, since it may have been sent to Sparky (and re-keyed) since it was shown
            val current = foodRepository.getEntry(entry.id)
                ?: foodRepository.getEntriesForDate(entry.entryDate).first().firstOrNull { it.createdAt == entry.createdAt && it.foodName == entry.foodName }
                ?: return@launch
            if (current.quantity == portion.quantity && current.unit == portion.unit) return@launch
            val m = com.bytelog.app.ui.logging.Portions.macros(food, portion)
            foodRepository.updateEntry(current.copy(quantity = portion.quantity, unit = portion.unit, calories = m.calories, protein = m.protein, carbs = m.carbs, fat = m.fat))
        }
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

    fun moveMeal(sourceDate: String, sourceMeal: String, targetDate: String, targetMeal: String) {
        viewModelScope.launch {
            _isSyncing.value = true
            val res = foodRepository.moveMeal(sourceDate, sourceMeal, targetDate, targetMeal)
            _isSyncing.value = false
            if (res.isSuccess) {
                val count = res.getOrNull() ?: 0
                _syncMessage.emit("Moved $sourceMeal ($count foods) to $targetMeal on $targetDate")
            } else {
                _syncMessage.emit("Failed to move $sourceMeal: ${res.exceptionOrNull()?.message ?: "Error"}")
            }
        }
    }

    fun deleteMeal(date: String, mealName: String, onDeleted: ((List<FoodEntry>) -> Unit)? = null) {
        viewModelScope.launch {
            val deleted = foodRepository.deleteMeal(date, mealName)
            if (deleted.isNotEmpty()) {
                _syncMessage.emit("Cleared $mealName (${deleted.size} items)")
                onDeleted?.invoke(deleted)
            }
        }
    }

    fun moveEntries(entries: List<FoodEntry>, targetDate: String, targetMealTypeId: String, targetMealName: String) {
        viewModelScope.launch {
            foodRepository.moveEntries(entries, targetDate, targetMealTypeId, targetMealName)
            _syncMessage.emit("Moved ${entries.size} item(s) to $targetMealName")
        }
    }

    fun copyEntries(entries: List<FoodEntry>, targetDate: String, targetMealTypeId: String, targetMealName: String) {
        viewModelScope.launch {
            foodRepository.copyEntries(entries, targetDate, targetMealTypeId, targetMealName)
            _syncMessage.emit("Copied ${entries.size} item(s) to $targetMealName")
        }
    }

    fun deleteEntries(entries: List<FoodEntry>, onDeleted: ((List<FoodEntry>) -> Unit)? = null) {
        viewModelScope.launch {
            foodRepository.deleteEntries(entries)
            _syncMessage.emit("Deleted ${entries.size} item(s)")
            onDeleted?.invoke(entries)
        }
    }

    fun restoreEntries(entries: List<FoodEntry>) {
        viewModelScope.launch {
            foodRepository.commitMultiAdd(entries)
            _syncMessage.emit("Restored ${entries.size} item(s)")
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

    fun copyMultipleMeals(meals: List<RecentMealItem>, targetDate: String) {
        viewModelScope.launch {
            _isSyncing.value = true
            val pairs = meals.map { Pair(it.mealName, it.entries) }
            val res = foodRepository.copyMultipleMeals(pairs, targetDate)
            _isSyncing.value = false
            if (res.isSuccess) {
                val count = res.getOrNull() ?: 0
                _syncMessage.emit("Copied ${meals.size} meal(s) ($count foods) to $targetDate!")
            } else {
                _syncMessage.emit("Failed to copy meals: ${res.exceptionOrNull()?.message ?: "Error"}")
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

    fun setChartRange(days: Int) {
        _selectedChartRangeDays.value = days
    }

    fun saveGoalSettings(enabled: Boolean, mode: String, offset: Float) {
        viewModelScope.launch {
            settingsRepository.saveAutoAdjustCalories(enabled)
            settingsRepository.saveCalorieGoal(mode, offset)
            _syncMessage.emit("Goal updated: $mode (${if (offset > 0) "+${offset.toInt()}" else offset.toInt()} kcal)")
        }
    }

    fun quickIncrementServing(entry: FoodEntry) {
        viewModelScope.launch {
            val addQty = if (entry.unit.contains("serving", ignoreCase = true)) 1f else entry.quantity
            val newQty = entry.quantity + addQty
            val ratio = if (entry.quantity > 0f) newQty / entry.quantity else 2f
            val updated = entry.copy(
                quantity = newQty,
                calories = entry.calories * ratio,
                protein = entry.protein * ratio,
                carbs = entry.carbs * ratio,
                fat = entry.fat * ratio,
                syncStatus = FoodEntry.SYNC_STATUS_PENDING_UPDATE
            )
            foodRepository.updateEntry(updated)
            _syncMessage.emit("+1 serving (${newQty.toInt()}${entry.unit}) to ${entry.foodName}")
        }
    }

    fun syncSelectedDate() {
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                foodRepository.syncDateWithServer(_selectedDate.value)
                waterRepository.syncWaterForDate(_selectedDate.value)
                weightRepository.syncWeightForDate(_selectedDate.value)
                weightRepository.repairKgStoredAsLbs()
                weightRepository.syncWeightHistory()

                sparkyApiClient?.let { client ->
                    val tdeeRes = client.fetchAdaptiveTdee(_selectedDate.value)
                    if (tdeeRes.isSuccess) {
                        _serverAdaptiveTdee.value = tdeeRes.getOrNull()
                    }
                }
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

                val tdeeRes = sparkyApiClient?.fetchAdaptiveTdee(_selectedDate.value)
                if (tdeeRes?.isSuccess == true) {
                    _serverAdaptiveTdee.value = tdeeRes.getOrNull()
                }

                // Anything Sparky still hasn't accepted stays on the phone and is sent again next time
                val waiting = foodRepository.countPendingSync() + waterRepository.countPending() + weightRepository.countPending()
                val reason = foodRepository.lastPushError?.let { " Sparky said: ${it.take(120)}" } ?: ""
                foodRes.fold(
                    onSuccess = { count ->
                        val waterInfo = if (waterRes.isSuccess) ", ${waterRes.getOrNull()?.toInt() ?: 0} oz water" else ""
                        _syncMessage.emit(
                            if (waiting > 0) "Synced $count foods$waterInfo. $waiting change(s) are kept on this phone and will be sent next sync.$reason"
                            else "Synced with Sparky: $count foods$waterInfo, everything from this phone is uploaded"
                        )
                    },
                    onFailure = { err ->
                        _syncMessage.emit(
                            "Couldn't reach Sparky (${err.message ?: "connection error"}). Nothing was removed; " +
                                (if (waiting > 0) "$waiting change(s) are waiting on this phone." else "your diary is unchanged.")
                        )
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
