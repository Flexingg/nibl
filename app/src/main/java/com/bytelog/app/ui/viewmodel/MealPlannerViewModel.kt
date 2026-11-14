package com.bytelog.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.PlannedEntry
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.PlanRepository
import com.bytelog.app.data.repository.PlannerConfig
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.ui.logging.Portion
import com.bytelog.app.ui.logging.Portions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** One day in the planner: its planned food by meal, and what it adds up to. */
data class PlannerDay(
    val date: LocalDate,
    val meals: List<Pair<String, List<PlannedEntry>>>,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    /** What the day's food costs, from the foods with prices; [priced] of its foods have one. */
    val cost: Double = 0.0,
    val priced: Int = 0
) {
    val entries: List<PlannedEntry> get() = meals.flatMap { it.second }
    val isEmpty: Boolean get() = meals.isEmpty()
}

/**
 * The meal planner: the days ahead with what's planned for each, every item editable (amount, unit, meal, day),
 * days copied or cleared, and how it's shown set to taste.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MealPlannerViewModel(
    private val plans: PlanRepository,
    private val foods: FoodRepository,
    private val settings: SettingsRepository?,
    private val today: () -> LocalDate = { LocalDate.now() },
    /** Saved prices, for what each food and day costs. */
    private val prices: com.bytelog.app.data.repository.PriceRepository? = null
) : ViewModel() {

    data class State(
        val config: PlannerConfig = PlannerConfig(),
        val page: Int = 0,
        val from: LocalDate = LocalDate.now(),
        val days: List<PlannerDay> = emptyList(),
        val goal: Float = 0f,
        val mealTypes: List<MealType> = MealType.DEFAULT_MEAL_TYPES,
        val foods: Map<String, FoodItem> = emptyMap(),
        /** Cost per entry id, where its food has a price. */
        val costs: Map<String, Double> = emptyMap(),
        val loading: Boolean = true
    ) {
        val to: LocalDate get() = from.plusDays((config.days - 1).toLong())
        val planned: Int get() = days.sumOf { it.entries.size }
    }

    private val _page = MutableStateFlow(0)
    private val localConfig = MutableStateFlow<PlannerConfig?>(null)
    private val config: Flow<PlannerConfig> = (settings?.plannerConfig ?: flowOf(PlannerConfig()))
        .combine(localConfig) { saved, local -> local ?: saved }

    private val range = combine(config, _page) { c, p -> Triple(c, p, c.start(today(), p)) }

    private val planned = range.flatMapLatest { (c, _, start) ->
        plans.between(start.toString(), start.plusDays((c.days - 1).toLong()).toString())
    }

    private val foodMap = planned.mapLatest { list ->
        runCatching { foods.foodsByIds(list.mapNotNull { it.foodId }.distinct()) }.getOrDefault(emptyList()).associateBy { it.id }
    }

    private val pricedFoods = combine(foodMap, prices?.prices ?: flowOf(emptyList())) { fm, pr -> fm to pr.associateBy { it.key } }

    private val mealTypes = foods.getAllMealTypes()
    private val goal: Flow<Float> = settings?.targetCalories ?: flowOf(0f)

    val state: StateFlow<State> = combine(range, planned, pricedFoods, mealTypes, goal) { (c, page, start), p, (fm, byKey), mt, g ->
        // What each planned food costs: its food's value at its price, for the amount planned
        val costs = HashMap<String, Double>()
        for (e in p) {
            val key = com.bytelog.app.data.repository.ShoppingList.keyOf(e.foodId, e.foodName, e.brandName)
            val price = byKey[key] ?: byKey[com.bytelog.app.data.repository.ShoppingList.keyOf(null, e.foodName, e.brandName)] ?: continue
            val food = e.foodId?.let { fm[it] } ?: com.bytelog.app.data.repository.FoodValue.foodOf(e)
            com.bytelog.app.data.repository.FoodValue.of(food, price)?.costOf(e.quantity, e.unit)?.let { costs[e.id] = it }
        }
        val types = mt.ifEmpty { MealType.DEFAULT_MEAL_TYPES }
        val order = types.mapIndexed { i, t -> t.name.lowercase() to i }.toMap()
        val byDate = p.groupBy { it.entryDate }
        val days = (0 until c.days).map { i ->
            val d = start.plusDays(i.toLong())
            val items = byDate[d.toString()].orEmpty()
            PlannerDay(
                d,
                items.groupBy { it.mealName }.toList().sortedBy { order[it.first.lowercase()] ?: 99 },
                items.sumOf { it.calories.toDouble() }.toFloat(), items.sumOf { it.protein.toDouble() }.toFloat(),
                items.sumOf { it.carbs.toDouble() }.toFloat(), items.sumOf { it.fat.toDouble() }.toFloat(),
                cost = items.sumOf { costs[it.id] ?: 0.0 }, priced = items.count { it.id in costs }
            )
        }
        State(c, page, start, days, g, types, fm, costs, loading = false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State())

    // ----- Showing -----

    fun setConfig(c: PlannerConfig) {
        localConfig.value = c
        _page.value = 0
        viewModelScope.launch { settings?.savePlannerConfig(c) }
    }

    fun nextPage() { _page.value += 1 }
    fun previousPage() { _page.value -= 1 }
    fun thisPage() { _page.value = 0 }

    // ----- Editing -----

    /** Units an item can be planned in: its food's own, or just the one it has when the food isn't on the phone. */
    fun unitsFor(e: PlannedEntry): List<String> =
        e.foodId?.let { state.value.foods[it] }?.let { Portions.unitOptions(it) }?.let { units ->
            if (units.any { it.equals(e.unit, true) }) units else listOf(e.unit) + units
        } ?: listOf(e.unit)

    /**
     * [e] at a new amount: recalculated from its food when the phone has it (so any of its units work), else scaled
     * from what it was (same unit only).
     */
    fun withAmount(e: PlannedEntry, quantity: Float, unit: String): PlannedEntry {
        if (quantity <= 0f) return e
        val food = e.foodId?.let { state.value.foods[it] }
        if (food != null) {
            val m = Portions.macros(food, Portion(quantity, unit))
            return e.copy(quantity = quantity, unit = unit, calories = m.calories, protein = m.protein, carbs = m.carbs, fat = m.fat)
        }
        if (!unit.equals(e.unit, true) || e.quantity <= 0f) return e
        val r = quantity / e.quantity
        return e.copy(quantity = quantity, calories = e.calories * r, protein = e.protein * r, carbs = e.carbs * r, fat = e.fat * r)
    }

    /** The same amount of [e]'s food in another of its units (150 g → 5.3 oz). */
    fun inUnit(e: PlannedEntry, unit: String): PlannedEntry {
        val food = e.foodId?.let { state.value.foods[it] } ?: return e
        val p = Portions.convert(food, Portion(e.quantity, e.unit), unit)
        return withAmount(e, p.quantity, p.unit)
    }

    fun save(e: PlannedEntry) { viewModelScope.launch { plans.update(listOf(e)) } }

    fun moveTo(e: PlannedEntry, date: LocalDate) = save(e.copy(entryDate = date.toString()))

    fun moveToMeal(e: PlannedEntry, meal: MealType) = save(e.copy(mealTypeId = meal.id, mealName = meal.name))

    fun remove(entries: List<PlannedEntry>) { viewModelScope.launch { plans.remove(entries) } }

    fun restore(entries: List<PlannedEntry>) { viewModelScope.launch { plans.restore(entries) } }

    /** Copies a day's food to other days. */
    fun copyDay(day: PlannerDay, dates: Set<String>, done: (Int) -> Unit = {}) {
        viewModelScope.launch { done(plans.copyTo(day.entries, dates).size) }
    }

    /** Ticks a planned food off as eaten (logged on its day). */
    fun eat(entries: List<PlannedEntry>) { viewModelScope.launch { plans.eat(entries) } }
}
