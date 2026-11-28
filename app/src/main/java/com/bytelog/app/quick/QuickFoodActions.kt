package com.bytelog.app.quick

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.FoodSearch
import com.bytelog.app.ui.logging.Portion
import com.bytelog.app.ui.logging.Portions
import com.bytelog.app.ui.viewmodel.RecipesViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Finding and logging a food from a launcher command, without the search screen. */
internal object QuickFoodActions {
    /** At most this many matches: a notification shows three buttons. */
    const val MAX_RESULTS = 3
    private const val REMOTE_WAIT_MS = 8_000L

    /** The meal it's time for. */
    suspend fun mealNow(repo: FoodRepository, now: LocalTime = LocalTime.now()): MealType {
        val types = repo.getAllMealTypes().first().ifEmpty { MealType.DEFAULT_MEAL_TYPES }
        return RecipesViewModel.mealForNow(types, now) ?: types.first()
    }

    /**
     * The best matches for [query], each with the amount a tap would log: foods you've logged first, then your
     * saved foods, then Sparky and Open Food Facts (asked only when the phone has fewer than [MAX_RESULTS]).
     */
    suspend fun find(repo: FoodRepository, query: String): Pair<MealType, List<Pair<FoodItem, Portion>>> {
        val meal = mealNow(repo)
        val tokens = FoodSearch.tokens(query)
        val (logged, saved) = repo.searchOnDevice(query).first()
        var remote: List<FoodItem> = emptyList()
        if (logged.size + saved.size < MAX_RESULTS) {
            // Keep whatever arrived if one of the two services is slow
            withTimeoutOrNull(REMOTE_WAIT_MS) { repo.searchRemote(query).collect { remote = it } }
        }
        val foods = FoodSearch.combine(logged, saved, remote, tokens).all.map { it.food }.take(MAX_RESULTS)
        val last = runCatching { repo.lastPortions(foods.map { it.id }) }.getOrDefault(emptyMap())
        return meal to foods.map { f -> f to Portions.repeatOrDefault(f, last[f.id]?.let { Portion(it.first, it.second) }) }
    }

    /** Logs [food] to today's [mealName] at the amount a tap in search would; returns the entry and its portion. */
    suspend fun log(repo: FoodRepository, food: FoodItem, mealId: String, mealName: String, today: LocalDate = LocalDate.now()): Pair<FoodEntry, Portion> {
        val last = runCatching { repo.lastPortions(listOf(food.id)) }.getOrDefault(emptyMap())[food.id]
        val p = Portions.repeatOrDefault(food, last?.let { Portion(it.first, it.second) })
        val m = Portions.macros(food, p)
        // Saves the food on the phone (a result from the server isn't until it's used); its serving lookup is a bonus
        withTimeoutOrNull(4_000) { runCatching { repo.rememberFood(food) } }
        val entry = FoodEntry(
            id = UUID.randomUUID().toString(), foodId = food.id, foodName = food.name, brandName = food.brand,
            mealTypeId = mealId, mealName = mealName, quantity = p.quantity, unit = p.unit,
            calories = m.calories, protein = m.protein, carbs = m.carbs, fat = m.fat,
            entryDate = today.toString(), syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT
        )
        repo.commitMultiAdd(listOf(entry))
        return entry to p
    }
}
