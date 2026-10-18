package com.bytelog.app.data.repository

import com.bytelog.app.data.local.model.FoodEntry
import java.time.LocalDate
import java.util.UUID
import kotlin.math.exp

/**
 * What someone tends to eat at a meal, worked out from their diary: the foods to suggest first, what they had at
 * this meal yesterday, and their "usual" (the same set of foods logged together on several days).
 */
object MealPatterns {
    /** How far back the diary is read. */
    const val HISTORY_DAYS = 42L

    /** How many days a set of foods must recur on to count as the usual. */
    const val USUAL_MIN_DAYS = 3

    fun mealKey(name: String): String = name.trim().lowercase().removeSuffix("s")

    fun sameMeal(a: String, b: String): Boolean = mealKey(a) == mealKey(b)

    /** One food across entries: its library id, or its name for quick adds. */
    fun foodKey(e: FoodEntry): String = e.foodId ?: ("name:" + e.foodName.trim().lowercase())

    /**
     * Foods for [mealName], best first: how often they were logged at this meal, recent days counting more and
     * the same weekday a little more again. Foods only logged at other meals come after, ranked the same way.
     */
    fun rankFoods(history: List<FoodEntry>, mealName: String, today: LocalDate): List<String> {
        val scores = HashMap<String, Double>()
        val other = HashMap<String, Double>()
        history.forEach { e ->
            val day = runCatching { LocalDate.parse(e.entryDate.take(10)) }.getOrNull() ?: return@forEach
            val age = (today.toEpochDay() - day.toEpochDay()).coerceAtLeast(0L).toDouble()
            var w = exp(-age / 14.0)
            if (day.dayOfWeek == today.dayOfWeek) w *= 1.35
            val target = if (sameMeal(e.mealName, mealName)) scores else other
            target[foodKey(e)] = (target[foodKey(e)] ?: 0.0) + w
        }
        val first = scores.entries.sortedByDescending { it.value }.map { it.key }
        val rest = other.entries.filter { it.key !in scores }.sortedByDescending { it.value }.map { it.key }
        return first + rest
    }

    /** What was logged at [mealName] on the day before [date]. */
    fun yesterday(history: List<FoodEntry>, mealName: String, date: LocalDate): List<FoodEntry> {
        val y = date.minusDays(1).toString()
        return history.filter { it.entryDate.take(10) == y && sameMeal(it.mealName, mealName) }
    }

    /**
     * The usual for [mealName]: the set of foods logged together at it on the most days before [date] (at least
     * [USUAL_MIN_DAYS]), at the amounts of the latest of those days. Null when nothing recurs that often.
     */
    fun usual(history: List<FoodEntry>, mealName: String, date: LocalDate): List<FoodEntry>? {
        val before = date.toString()
        val days = history
            .filter { sameMeal(it.mealName, mealName) && it.entryDate.take(10) < before }
            .groupBy { it.entryDate.take(10) }
        if (days.size < USUAL_MIN_DAYS) return null
        val bySet = days.entries.groupBy { (_, entries) -> entries.map(::foodKey).toSortedSet() }
        val best = bySet.entries
            .filter { it.value.size >= USUAL_MIN_DAYS }
            .maxWithOrNull(compareBy<Map.Entry<Set<String>, List<Map.Entry<String, List<FoodEntry>>>>> { it.value.size }
                .thenBy { e -> e.value.maxOf { it.key } })
            ?: return null
        return best.value.maxByOrNull { it.key }!!.value
    }

    /** Fresh copies of [entries] for another day and meal, ready to log. */
    fun copiesFor(entries: List<FoodEntry>, date: String, mealTypeId: String, mealName: String, now: Long = System.currentTimeMillis()): List<FoodEntry> =
        entries.mapIndexed { i, e ->
            e.copy(
                id = UUID.randomUUID().toString(), entryDate = date, mealTypeId = mealTypeId, mealName = mealName,
                entryTime = null, syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT, createdAt = now + i
            )
        }

    /** True when both lists are the same foods (ignoring amounts and order). */
    fun sameFoods(a: List<FoodEntry>, b: List<FoodEntry>): Boolean = a.map(::foodKey).toSortedSet() == b.map(::foodKey).toSortedSet()
}

/** The one-tap shortcuts offered for an empty meal. */
data class MealShortcuts(val yesterday: List<FoodEntry>, val usual: List<FoodEntry>?) {
    /** The usual is only shown when it isn't just yesterday again. */
    val showUsual: Boolean get() = usual != null && (yesterday.isEmpty() || !MealPatterns.sameFoods(usual, yesterday))
    val isEmpty: Boolean get() = yesterday.isEmpty() && !showUsual

    companion object {
        fun of(history: List<FoodEntry>, mealName: String, date: LocalDate) =
            MealShortcuts(MealPatterns.yesterday(history, mealName, date), MealPatterns.usual(history, mealName, date))
    }
}

/** Foods just logged somewhere in the app, so the diary can offer to undo them. */
data class LoggedBatch(val entries: List<FoodEntry>, val mealName: String) {
    val label: String get() = if (entries.size == 1) "Logged ${entries[0].foodName} to $mealName" else "Logged ${entries.size} to $mealName"
}
