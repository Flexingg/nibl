package com.bytelog.app.data.repository

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.WeightEntry
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// ======================================================================
// Fasting: time since the last thing eaten
// ======================================================================

object Fasting {
    /** Shown once the fast is at least this long, so a snack ten minutes ago doesn't read as a fast. */
    const val SHOW_AFTER_MS = 60 * 60 * 1000L

    /**
     * When food was last eaten, from diary entries (with calories). An entry's own time is used when it has one;
     * otherwise when it was logged, but never later than the end of its day, so logging yesterday's dinner this
     * morning doesn't count as eating now. Entries in the future (planned) are ignored.
     */
    fun lastMealAt(entries: List<FoodEntry>, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? =
        entries.asSequence()
            .filter { it.calories > 0f && it.syncStatus != FoodEntry.SYNC_STATUS_PENDING_DELETE }
            .mapNotNull { eatenAt(it, zone) }
            .filter { it <= now }
            .maxOrNull()

    fun eatenAt(e: FoodEntry, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val day = runCatching { LocalDate.parse(e.entryDate.take(10)) }.getOrNull() ?: return null
        e.entryTime?.let { t ->
            runCatching { LocalTime.parse(t.take(8).let { if (it.length == 5) "$it:00" else it }) }.getOrNull()?.let {
                return LocalDateTime.of(day, it).atZone(zone).toInstant().toEpochMilli()
            }
        }
        val endOfDay = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return min(e.createdAt, endOfDay)
    }

    /** "14h 20m", or "45m". */
    fun format(ms: Long): String {
        val mins = (ms / 60_000L).coerceAtLeast(0L)
        val h = mins / 60; val m = mins % 60
        return if (h == 0L) "${m}m" else "${h}h ${m}m"
    }
}

// ======================================================================
// What fits: foods you eat that fit what is left of today's goals
// ======================================================================

/** One suggestion: one or two foods at the amounts you usually have them. */
data class FitOption(val foods: List<FoodEntry>) {
    val calories: Float get() = foods.sumOf { it.calories.toDouble() }.toFloat()
    val protein: Float get() = foods.sumOf { it.protein.toDouble() }.toFloat()
    val carbs: Float get() = foods.sumOf { it.carbs.toDouble() }.toFloat()
    val fat: Float get() = foods.sumOf { it.fat.toDouble() }.toFloat()
    val label: String get() = foods.joinToString(" + ") { it.foodName }
}

data class Remaining(val calories: Float, val protein: Float, val carbs: Float, val fat: Float)

object WhatFits {
    /** Below this there's nothing worth suggesting. */
    const val MIN_CALORIES = 120f

    /** Foods from the diary history, each at the amount last logged, the most often eaten first. */
    fun candidates(history: List<FoodEntry>, limit: Int = 40): List<FoodEntry> =
        history.filter { it.calories > 0f }
            .groupBy(MealPatterns::foodKey)
            .values
            .sortedByDescending { it.size }
            .take(limit)
            .map { group -> group.maxBy { it.createdAt } }

    /**
     * Up to [limit] options that fit [left]: single foods and pairs, none over the calories left (5% leeway).
     * They are scored on how much of the remaining protein they cover (when protein is still needed), how well
     * they use the calories left, not overshooting carbs or fat, and how often you eat them.
     */
    fun suggest(left: Remaining, candidates: List<FoodEntry>, limit: Int = 3): List<FitOption> {
        if (left.calories < MIN_CALORIES || candidates.isEmpty()) return emptyList()
        val counts = candidates.withIndex().associate { (i, e) -> MealPatterns.foodKey(e) to (1f - i.toFloat() / candidates.size) }
        fun score(o: FitOption): Float? {
            val kcal = o.calories
            if (kcal < 40f || kcal > left.calories * 1.05f) return null
            val fill = kcal / left.calories
            val needProtein = left.protein >= 10f
            val proteinCover = if (needProtein) min(o.protein, left.protein) / left.protein else 0f
            val over = (o.carbs - left.carbs).coerceAtLeast(0f) / 50f + (o.fat - left.fat).coerceAtLeast(0f) / 20f
            val familiar = o.foods.map { counts[MealPatterns.foodKey(it)] ?: 0f }.average().toFloat()
            return if (needProtein) 0.5f * proteinCover + 0.3f * fill + 0.2f * familiar - over
            else 0.6f * fill + 0.25f * familiar - over
        }
        val singles = candidates.map { FitOption(listOf(it)) }.mapNotNull { o -> score(o)?.let { o to it } }
        val top = singles.sortedByDescending { it.second }.take(12).map { it.first.foods.single() }
        val pairs = top.indices.flatMap { i -> (i + 1 until top.size).map { j -> FitOption(listOf(top[i], top[j])) } }
            .mapNotNull { o -> score(o)?.let { o to it } }
        val ranked = (singles + pairs).sortedByDescending { it.second }.map { it.first }
        // Variety: each food appears in at most one suggestion
        val used = HashSet<String>()
        val out = ArrayList<FitOption>()
        for (o in ranked) {
            val keys = o.foods.map(MealPatterns::foodKey)
            if (keys.any { it in used }) continue
            out += o; used += keys
            if (out.size == limit) break
        }
        return out
    }
}

// ======================================================================
// Weekly review: the last seven full days
// ======================================================================

data class DayTotal(val date: LocalDate, val calories: Float, val protein: Float, val logged: Boolean)

data class WeeklyReview(
    val start: LocalDate,
    val end: LocalDate,
    val days: List<DayTotal>,
    val calorieGoal: Float,
    val proteinGoal: Float,
    val avgCalories: Int,
    val avgProtein: Int,
    val avgCarbs: Int,
    val avgFat: Int,
    val daysLogged: Int,
    val daysOnTarget: Int,
    val proteinDays: Int,
    /** Days in a row with something logged, up to today. */
    val streak: Int,
    /** Change across the week, in the weight unit used; null without two weigh-ins. */
    val weightChange: Float?
) {
    companion object {
        /** Within this share of the calorie goal counts as on target. */
        const val ON_TARGET = 0.10f

        /**
         * The seven days before [today]. [entries] should cover at least those days (and further back for the
         * streak); [weights] are weigh-ins in the user's unit.
         */
        fun of(
            entries: List<FoodEntry>, today: LocalDate, calorieGoal: Float, proteinGoal: Float,
            weights: List<Pair<LocalDate, Float>> = emptyList()
        ): WeeklyReview {
            val end = today.minusDays(1)
            val start = end.minusDays(6)
            val byDay = entries.filter { it.syncStatus != FoodEntry.SYNC_STATUS_PENDING_DELETE }
                .groupBy { runCatching { LocalDate.parse(it.entryDate.take(10)) }.getOrNull() }
            val days = (0L..6L).map { start.plusDays(it) }.map { d ->
                val list = byDay[d].orEmpty()
                DayTotal(d, list.sumOf { it.calories.toDouble() }.toFloat(), list.sumOf { it.protein.toDouble() }.toFloat(), list.isNotEmpty())
            }
            val logged = days.filter { it.logged }
            val loggedEntries = logged.flatMap { byDay[it.date].orEmpty() }
            fun avg(f: (FoodEntry) -> Float) = if (logged.isEmpty()) 0 else (loggedEntries.sumOf { f(it).toDouble() } / logged.size).roundToInt()
            val onTarget = if (calorieGoal > 0f) logged.count { abs(it.calories - calorieGoal) <= calorieGoal * ON_TARGET } else 0
            val proteinDays = if (proteinGoal > 0f) logged.count { it.protein >= proteinGoal * 0.9f } else 0
            var streak = 0
            var d = if (byDay[today].isNullOrEmpty()) end else today
            while (!byDay[d].isNullOrEmpty()) { streak++; d = d.minusDays(1) }
            val inWeek = weights.filter { (wd, _) -> !wd.isBefore(start.minusDays(1)) && !wd.isAfter(end) }.sortedBy { it.first }
            val change = if (inWeek.size >= 2) inWeek.last().second - inWeek.first().second else null
            return WeeklyReview(
                start, end, days, calorieGoal, proteinGoal,
                avg { it.calories }, avg { it.protein }, avg { it.carbs }, avg { it.fat },
                logged.size, onTarget, proteinDays, streak, change
            )
        }

        /** The review is offered on Sunday and Monday. */
        fun isReviewDay(day: LocalDate) = day.dayOfWeek == java.time.DayOfWeek.SUNDAY || day.dayOfWeek == java.time.DayOfWeek.MONDAY

        /** One line for the Sunday reminder. */
        fun summaryLine(r: WeeklyReview): String = buildString {
            append("Your week: ${r.daysOnTarget}/7 days on target")
            if (r.daysLogged > 0) append(", ${"%,d".format(r.avgCalories)} kcal and ${r.avgProtein} g protein a day")
            if (r.streak >= 2) append(". ${r.streak}-day logging streak")
            append(".")
        }
    }
}

/** Weigh-ins as (day, weight) pairs for [WeeklyReview]. */
fun List<WeightEntry>.asDayWeights(convert: (WeightEntry) -> Float): List<Pair<LocalDate, Float>> =
    mapNotNull { w -> runCatching { LocalDate.parse(w.entryDate.take(10)) }.getOrNull()?.let { it to convert(w) } }

/** Parses a server timestamp ("2026-10-18T12:34:56.789Z") to epoch millis. */
fun parseServerTime(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
    ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
