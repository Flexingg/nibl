package com.bytelog.app.data.repository

import com.bytelog.app.data.local.db.PlannedEntryDao
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.PlannedEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Food planned ahead: added to a future day it's planned rather than logged, shown faded in the diary, and ticked
 * off (logged for real) when it's eaten. Planned food stays on the phone until then.
 */
class PlanRepository(private val dao: PlannedEntryDao, private val foods: FoodRepository) {
    fun forDate(date: String): Flow<List<PlannedEntry>> = dao.forDate(date)

    suspend fun plan(entries: List<FoodEntry>) = dao.insert(entries.map { PlannedEntry.from(it) })

    /** Logs planned food as eaten (on the day it was planned for) and takes it off the plan. Returns the entries. */
    suspend fun eat(planned: List<PlannedEntry>): List<FoodEntry> {
        if (planned.isEmpty()) return emptyList()
        val entries = planned.map { it.toEntry() }
        foods.commitMultiAdd(entries)
        dao.delete(planned.map { it.id })
        return entries
    }

    /**
     * Puts a meal plan on the Planned list, each food on its day, using your saved food where you have it. Returns
     * the entries added.
     */
    suspend fun addPlan(plan: MealPlanDraft): List<PlannedEntry> {
        val types = foods.getAllMealTypes().first()
        val ids = HashMap<String, String?>()
        val entries = MealPlan.toPlanned(plan, types).map { e ->
            val key = FoodSearch.identity(e.foodName, e.brandName)
            val id = if (key in ids) ids[key] else foods.savedFoodId(e.foodName, e.brandName).also { ids[key] = it }
            e.copy(foodId = id)
        }
        dao.insert(entries)
        return entries
    }

    suspend fun remove(planned: List<PlannedEntry>) = dao.delete(planned.map { it.id })

    /** Puts entries back on the plan (undoing a remove). */
    suspend fun restore(planned: List<PlannedEntry>) = dao.insert(planned)

    companion object {
        /** Food added to a day after today is planned, not logged. */
        fun isFuture(date: String, today: LocalDate = LocalDate.now()): Boolean =
            runCatching { LocalDate.parse(date.take(10)).isAfter(today) }.getOrDefault(false)
    }
}
