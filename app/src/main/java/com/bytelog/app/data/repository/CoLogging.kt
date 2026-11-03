package com.bytelog.app.data.repository

import com.bytelog.app.data.local.model.FoodEntry

/** A food that's often logged with the foods in the tray: [together] meals out of the [ofSeed] it was eaten in. */
data class CoFood(val entry: FoodEntry, val together: Int, val ofSeed: Int, val score: Float)

/**
 * "Often logged together": foods that turn up in the same meal on the same day as a food, from your history. Used to
 * suggest the next food once one is picked.
 */
object CoLogging {
    /** A food's identity across entries: its saved id isn't reliable (re-saved, logged elsewhere), so it's its name and brand. */
    fun identity(e: FoodEntry): String = FoodSearch.identity(e.foodName, e.brandName)

    private fun mealOf(e: FoodEntry) = e.entryDate.take(10) to MealPatterns.mealKey(e.mealName)

    /**
     * The foods most often eaten in the same meal as the [seeds] (what's in the tray), best first. [entries] is the
     * history to look through (at least the days the seeds were eaten on).
     *
     * A food qualifies for a seed when it was in the same meal at least [minTogether] times and in at least
     * [minShare] of the meals that seed was in, so a coffee that goes with everything doesn't suggest everything.
     * With several foods in the tray, one that goes with several of them ranks higher. Foods already in the tray
     * aren't suggested.
     */
    fun suggest(
        entries: List<FoodEntry>,
        seeds: List<FoodEntry>,
        /** Foods already logged in the meal being added to, which aren't suggested again. */
        alreadyIn: List<FoodEntry> = emptyList(),
        limit: Int = 8,
        minTogether: Int = 2,
        minShare: Float = 0.3f
    ): List<CoFood> {
        if (seeds.isEmpty()) return emptyList()
        val seedKeys = seeds.map(::identity).toSet()
        val seedIds = seeds.mapNotNull { it.foodId }.toSet()
        val leaveOut = seedKeys + alreadyIn.map(::identity)
        val leaveOutIds = seedIds + alreadyIn.mapNotNull { it.foodId }
        fun isSeed(e: FoodEntry) = identity(e) in leaveOut || (e.foodId != null && e.foodId in leaveOutIds)

        val meals = entries.filter { it.syncStatus != FoodEntry.SYNC_STATUS_PENDING_DELETE }.groupBy(::mealOf).values
        val score = HashMap<String, Float>()
        val together = HashMap<String, Int>()
        val ofSeed = HashMap<String, Int>()
        val latest = HashMap<String, FoodEntry>()

        for (seed in seeds.distinctBy(::identity)) {
            val key = identity(seed)
            val withSeed = meals.filter { m -> m.any { it.foodId != null && it.foodId == seed.foodId || identity(it) == key } }
            if (withSeed.isEmpty()) continue
            val counts = HashMap<String, Int>()
            for (meal in withSeed) {
                // Each food counts once per meal, however many times it was logged in it
                for (e in meal.distinctBy(::identity)) {
                    if (isSeed(e)) continue
                    val k = identity(e)
                    counts[k] = (counts[k] ?: 0) + 1
                    if ((latest[k]?.createdAt ?: Long.MIN_VALUE) < e.createdAt) latest[k] = e
                }
            }
            for ((k, n) in counts) {
                val share = n.toFloat() / withSeed.size
                if (n < minTogether || share < minShare) continue
                score[k] = (score[k] ?: 0f) + share
                if (n > (together[k] ?: 0)) { together[k] = n; ofSeed[k] = withSeed.size }
            }
        }
        return score.keys
            .sortedWith(compareByDescending<String> { score.getValue(it) }.thenByDescending { together.getValue(it) }.thenByDescending { latest.getValue(it).createdAt })
            .take(limit)
            .map { CoFood(latest.getValue(it), together.getValue(it), ofSeed.getValue(it), score.getValue(it)) }
    }
}
