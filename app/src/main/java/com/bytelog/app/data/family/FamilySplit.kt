package com.bytelog.app.data.family

import com.bytelog.app.data.local.model.FoodEntry
import kotlin.math.roundToInt

/** Percent maths for splitting a meal between people, and scaling an entry to someone's share. */
object FamilySplit {
    /** [people] equal shares in whole percents that add up to exactly 100 (3 → 34, 33, 33). */
    fun even(people: Int): List<Int> {
        if (people <= 0) return emptyList()
        val base = 100 / people
        val extra = 100 - base * people
        return List(people) { if (it < extra) base + 1 else base }
    }

    /**
     * Sets [index] to [value] (0–100) and spreads the rest over the others in proportion to what they had, still
     * adding up to exactly 100. If the others had nothing, the first of them takes the remainder.
     */
    fun adjust(percents: List<Int>, index: Int, value: Int): List<Int> {
        if (percents.size <= 1) return percents.map { 100 }
        val v = value.coerceIn(0, 100)
        val others = percents.indices.filter { it != index }
        val rest = 100 - v
        val oldTotal = others.sumOf { percents[it] }
        val raw = others.map { if (oldTotal > 0) rest * percents[it].toDouble() / oldTotal else if (it == others.first()) rest.toDouble() else 0.0 }
        // largest-remainder rounding so the parts add up to exactly `rest`
        val floors = raw.map { it.toInt() }.toMutableList()
        var short = rest - floors.sum()
        raw.indices.sortedByDescending { raw[it] - floors[it] }.forEach { if (short > 0) { floors[it]++; short-- } }
        val out = percents.toMutableList()
        out[index] = v
        others.forEachIndexed { i, o -> out[o] = floors[i] }
        return out
    }

    /** [entry] reduced to [percent] of itself: amount and nutrition scaled together (a new id is up to the caller). */
    fun scale(entry: FoodEntry, percent: Int): FoodEntry {
        val f = percent / 100f
        fun r1(x: Float) = (x * f * 10f).roundToInt() / 10f
        return entry.copy(
            quantity = ((entry.quantity * f) * 100f).roundToInt() / 100f,
            calories = r1(entry.calories), protein = r1(entry.protein), carbs = r1(entry.carbs), fat = r1(entry.fat)
        )
    }
}
