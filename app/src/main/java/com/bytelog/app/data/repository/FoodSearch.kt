package com.bytelog.app.data.repository

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import java.text.Normalizer

/** A search result: the food, and how often (and when last) you've logged it; 0 times = never logged. */
data class SearchHit(val food: FoodItem, val timesLogged: Int = 0, val lastLogged: Long = 0L)

/** Search results in the order they're shown: foods you've logged, then everything else. */
data class FoodSearchResults(val logged: List<SearchHit> = emptyList(), val other: List<SearchHit> = emptyList()) {
    val all: List<SearchHit> get() = logged + other
    val isEmpty: Boolean get() = logged.isEmpty() && other.isEmpty()
}

/**
 * Matching and ranking for food search. Every word typed has to appear in the name or brand, in any order
 * ("breast chicken" finds "Chicken Breast"), ignoring case and accents. Foods you've logged always come first.
 */
object FoodSearch {
    /** How well a food matches: lower is better. */
    const val NAME_STARTS = 0
    const val WORD_STARTS = 1
    const val CONTAINS = 2

    fun normalize(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val plain = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return plain.lowercase().replace(Regex("[^a-z0-9%]+"), " ").trim()
    }

    fun tokens(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }.distinct()

    /** The word to pre-filter with in the database: the longest, which narrows things most. */
    fun prefilterToken(query: String): String = tokens(query).maxByOrNull { it.length } ?: query.trim()

    /** How well [name] and [brand] match the query's [tokens], or null when a word is missing. */
    fun match(name: String, brand: String?, tokens: List<String>): Int? {
        if (tokens.isEmpty()) return null
        val n = normalize(name)
        val haystack = if (brand.isNullOrBlank()) n else "$n ${normalize(brand)}"
        if (tokens.any { it !in haystack }) return null
        val words = haystack.split(' ')
        return when {
            n.startsWith(tokens.joinToString(" ")) || n.startsWith(tokens.first()) -> NAME_STARTS
            tokens.all { t -> words.any { it.startsWith(t) } } -> WORD_STARTS
            else -> CONTAINS
        }
    }

    /** The same food logged or saved twice (by id, or else by name and brand) counts once. */
    fun identity(name: String, brand: String?): String = normalize(name) + "|" + normalize(brand)

    /**
     * Foods you've logged that match, one per food, best match first, then most logged, then most recent. [foods]
     * are the saved foods by id: a logged food uses its saved nutrition where there is one, else the amounts from
     * its latest entry.
     */
    fun rankLogged(entries: List<FoodEntry>, foods: Map<String, FoodItem>, tokens: List<String>, limit: Int = 40): List<SearchHit> {
        data class Group(val latest: FoodEntry, var count: Int, val quality: Int)
        val groups = LinkedHashMap<String, Group>()
        // Newest first, so the first entry seen for a food is its latest
        for (e in entries.sortedByDescending { it.createdAt }) {
            if (e.syncStatus == FoodEntry.SYNC_STATUS_PENDING_DELETE) continue
            val key = e.foodId ?: identity(e.foodName, e.brandName)
            val g = groups[key]
            if (g != null) { g.count++; continue }
            val q = match(e.foodName, e.brandName, tokens) ?: continue
            groups[key] = Group(e, 1, q)
        }
        // The same food under a different id (re-saved, or logged on another device) merges by name
        val byName = LinkedHashMap<String, Group>()
        for (g in groups.values) {
            val k = identity(g.latest.foodName, g.latest.brandName)
            val other = byName[k]
            // Shown as the saved food when one of them is, else as most recently logged
            fun better(a: Group, b: Group) = when {
                (a.latest.foodId != null) != (b.latest.foodId != null) -> a.latest.foodId != null
                else -> a.latest.createdAt > b.latest.createdAt
            }
            if (other == null) byName[k] = g
            else if (better(g, other)) byName[k] = g.copy(count = g.count + other.count)
            else other.count += g.count
        }
        return byName.values
            .sortedWith(compareBy<Group> { it.quality }.thenByDescending { it.count }.thenByDescending { it.latest.createdAt })
            .take(limit)
            .map { g ->
                val e = g.latest
                val food = e.foodId?.let { foods[it] } ?: FoodItem(
                    id = e.foodId ?: e.id, name = e.foodName, brand = e.brandName,
                    servingSize = e.quantity, servingUnit = e.unit,
                    calories = e.calories, protein = e.protein, carbs = e.carbs, fat = e.fat, fiber = 0f
                )
                SearchHit(food, g.count, e.createdAt)
            }
    }

    /** Saved foods you haven't logged: favourites first, then best match, most used, shortest name. */
    fun rankSaved(foods: List<FoodItem>, tokens: List<String>, exclude: Set<String>, limit: Int = 30): List<SearchHit> =
        foods.asSequence()
            .filter { it.id !in exclude && identity(it.name, it.brand) !in exclude }
            .mapNotNull { f -> match(f.name, f.brand, tokens)?.let { f to it } }
            .sortedWith(compareByDescending<Pair<FoodItem, Int>> { it.first.isFavorite }.thenBy { it.second }
                .thenByDescending { it.first.useCount }.thenBy { it.first.name.length })
            .distinctBy { identity(it.first.name, it.first.brand) }
            .take(limit)
            .map { SearchHit(it.first) }
            .toList()

    /** New foods from Sparky or Open Food Facts that aren't already listed, best match first (order kept otherwise). */
    fun rankRemote(foods: List<FoodItem>, tokens: List<String>, exclude: Set<String>): List<SearchHit> =
        foods.withIndex()
            .filter { (_, f) -> f.id !in exclude && identity(f.name, f.brand) !in exclude }
            .mapNotNull { (i, f) -> match(f.name, f.brand, tokens)?.let { Triple(f, it, i) } }
            .sortedWith(compareBy<Triple<FoodItem, Int, Int>> { it.second }.thenBy { it.third })
            .distinctBy { identity(it.first.name, it.first.brand) }
            .map { SearchHit(it.first) }

    /** Everything in display order: logged, then saved, then new from the servers. */
    fun combine(logged: List<SearchHit>, saved: List<SearchHit>, remote: List<FoodItem>, tokens: List<String>): FoodSearchResults {
        val seen = HashSet<String>()
        (logged + saved).forEach { seen += it.food.id; seen += identity(it.food.name, it.food.brand) }
        val loggedIds = logged.map { it.food.id }.toSet()
        // List keys are food ids, so each id appears once
        return FoodSearchResults(logged.distinctBy { it.food.id }, (saved + rankRemote(remote, tokens, seen)).filter { it.food.id !in loggedIds }.distinctBy { it.food.id })
    }
}
