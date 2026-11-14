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

    /** Everything planned from [from] to [to], both included (for the shopping list). */
    fun between(from: String, to: String): Flow<List<PlannedEntry>> = dao.between(from, to)

    /** Plans [entries] on each of [dates] (one copy per day). Returns what was added. */
    suspend fun planOn(entries: List<FoodEntry>, dates: Collection<String>): List<PlannedEntry> {
        val copies = dates.distinct().sorted().flatMap { d ->
            entries.map { PlannedEntry.from(it).copy(id = java.util.UUID.randomUUID().toString(), entryDate = d, createdAt = System.currentTimeMillis()) }
        }
        dao.insert(copies)
        return copies
    }

    /** Copies planned food to other days, same meals and amounts. Returns the copies. */
    suspend fun copyTo(planned: List<PlannedEntry>, dates: Collection<String>): List<PlannedEntry> {
        val copies = dates.distinct().sorted().flatMap { d ->
            planned.filter { it.entryDate != d }.map { it.copy(id = java.util.UUID.randomUUID().toString(), entryDate = d, createdAt = System.currentTimeMillis()) }
        }
        dao.insert(copies)
        return copies
    }

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

    /** What's planned from yesterday to [PlanEdits.DAYS] days ahead: what the chat can see and change. */
    suspend fun upcoming(today: LocalDate = LocalDate.now()): List<PlannedEntry> =
        dao.between(today.minusDays(1).toString(), today.plusDays(PlanEdits.DAYS.toLong()).toString()).first()

    /** Applies changes the assistant proposed, against what's planned now. Returns what changed, for an undo. */
    suspend fun applyEdit(draft: PlanEditDraft, today: LocalDate = LocalDate.now()): AppliedPlanEdit {
        val planned = upcoming(today)
        val foodsById = foods.foodsByIds(planned.mapNotNull { it.foodId }.distinct()).associateBy { it.id }
        val types = foods.getAllMealTypes().first()
        val edit = PlanEdits.plan(draft, planned, types) { foodsById[it] }
        // New foods use your saved food where you have it
        val added = edit.added.map { e -> e.copy(foodId = foods.savedFoodId(e.foodName, e.brandName)) }
        if (edit.removed.isNotEmpty()) dao.delete(edit.removed.map { it.id })
        if (edit.after.isNotEmpty() || added.isNotEmpty()) dao.insert(edit.after + added)
        return edit.copy(added = added)
    }

    /** Puts the plan back as it was before [edit]. */
    suspend fun undoEdit(edit: AppliedPlanEdit) {
        if (edit.added.isNotEmpty()) dao.delete(edit.added.map { it.id })
        if (edit.removed.isNotEmpty() || edit.before.isNotEmpty()) dao.insert(edit.removed + edit.before)
    }

    /** Saves a change to planned food (amount, meal or day). */
    suspend fun update(entries: List<PlannedEntry>) = dao.insert(entries)

    suspend fun remove(planned: List<PlannedEntry>) = dao.delete(planned.map { it.id })

    /** Puts entries back on the plan (undoing a remove). */
    suspend fun restore(planned: List<PlannedEntry>) = dao.insert(planned)

    companion object {
        /** Food added to a day after today is planned, not logged. */
        fun isFuture(date: String, today: LocalDate = LocalDate.now()): Boolean =
            runCatching { LocalDate.parse(date.take(10)).isAfter(today) }.getOrDefault(false)
    }
}
