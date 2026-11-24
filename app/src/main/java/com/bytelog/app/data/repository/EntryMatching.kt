package com.bytelog.app.data.repository

import com.bytelog.app.data.local.model.FoodEntry
import kotlin.math.abs

/**
 * Finds a diary entry's copy on Sparky when the ids don't line up, so the phone and Sparky never both keep one.
 *
 * Two entries are the same when they're the same food, amount, unit and meal on the same day. That alone would
 * also match two cups of coffee logged on purpose, so it's only used where the phone knows a copy must exist:
 * an entry Sparky accepted under an id the phone didn't learn, or one whose upload got no answer. And a Sparky
 * entry the phone already has (by id) is never matched again, so the second coffee stays.
 */
object EntryMatching {

    fun same(a: FoodEntry, b: FoodEntry): Boolean =
        a.entryDate.take(10) == b.entryDate.take(10) &&
            norm(a.foodName) == norm(b.foodName) &&
            norm(a.mealName) == norm(b.mealName) &&
            abs(a.quantity - b.quantity) < 0.01f &&
            norm(a.unit) == norm(b.unit)

    /**
     * Pairs each of [strays] (phone entries whose id Sparky doesn't list) with the matching Sparky entry from
     * [candidates] (Sparky entries the phone doesn't have), one to one, nearest in time first.
     * Returns phone id → Sparky id.
     */
    fun pair(strays: List<FoodEntry>, candidates: List<FoodEntry>): Map<String, String> {
        val free = candidates.toMutableList()
        val out = LinkedHashMap<String, String>()
        for (stray in strays.sortedBy { it.createdAt }) {
            val match = free.filter { same(stray, it) }.minByOrNull { gap(stray, it) } ?: continue
            free.remove(match)
            out[stray.id] = match.id
        }
        return out
    }

    /**
     * The Sparky entry [entry] most likely became when its upload got no answer at [attemptAt]: a match the phone
     * doesn't have yet, made no earlier than shortly before the attempt (clocks differ a little).
     */
    fun findUnanswered(entry: FoodEntry, attemptAt: Long, candidates: List<FoodEntry>): FoodEntry? =
        candidates.filter { same(entry, it) && (it.createdAt <= 0L || it.createdAt >= attemptAt - CLOCK_SLACK_MS) }
            .minByOrNull { if (it.createdAt <= 0L) Long.MAX_VALUE else abs(it.createdAt - attemptAt) }

    private fun gap(a: FoodEntry, b: FoodEntry) = if (a.createdAt <= 0L || b.createdAt <= 0L) Long.MAX_VALUE else abs(a.createdAt - b.createdAt)

    private fun norm(s: String?) = s.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")

    private const val CLOCK_SLACK_MS = 5 * 60 * 1000L
}
