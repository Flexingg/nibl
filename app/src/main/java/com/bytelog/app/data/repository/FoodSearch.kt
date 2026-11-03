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
 * ("breast chicken" finds "Chicken Breast"), ignoring case and accents. Plurals, common spelling variants
 * ("yoghurt", "chilli") and small typos ("bannana") still match, ranked below exact matches. Foods you've
 * logged always come first.
 */
object FoodSearch {
    /** How well a food matches: lower is better. */
    const val NAME_STARTS = 0
    const val WORD_STARTS = 1
    const val CONTAINS = 2
    /** Only after allowing for a typo. */
    const val FUZZY = 3

    fun normalize(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val plain = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return plain.lowercase().replace(Regex("[^a-z0-9%]+"), " ").trim()
    }

    fun tokens(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }.distinct()

    /**
     * What to pre-filter with in the database: the start of the longest word, as typed, as its singular and as its
     * other spellings (so "aubergine" also finds "eggplant"), always three of them. Plurals and typos later in the
     * word still reach the matching below.
     */
    fun prefilterTokens(query: String): List<String> {
        val longest = tokens(query).maxByOrNull { it.length } ?: return List(3) { query.trim() }
        val canon = canonical(longest)
        val forms = listOf(longest, canon) + variants.filterValues { it == canon }.keys
        val starts = forms.map { if (it.length > 3) it.take(3) else it }.distinct().take(3)
        return starts + List(3 - starts.size) { starts.first() }
    }

    /** British and American spellings, and other names for the same food, written one way. */
    private val variants = mapOf(
        "yoghurt" to "yogurt", "yoghourt" to "yogurt", "chilli" to "chili", "chile" to "chili",
        "doughnut" to "donut", "aubergine" to "eggplant", "courgette" to "zucchini", "coriander" to "cilantro",
        "prawn" to "shrimp", "rocket" to "arugula", "mince" to "ground", "minced" to "ground",
        "porridge" to "oatmeal", "flavour" to "flavor", "flavoured" to "flavored", "savoury" to "savory",
        "fibre" to "fiber", "omelette" to "omelet", "ketchup" to "catsup", "colour" to "color"
    )

    /** A word as its singular, in one spelling: "berries" -> "berry", "tomatoes" -> "tomato", "yoghurts" -> "yogurt". */
    fun canonical(word: String): String {
        variants[word]?.let { return it }
        val single = when {
            word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
            word.length > 4 && word.endsWith("oes") -> word.dropLast(2)
            word.length > 4 && (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes") || word.endsWith("sses")) -> word.dropLast(2)
            word.length > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") -> word.dropLast(1)
            else -> word
        }
        return variants[single] ?: single
    }

    /** Typos allowed in a word of this length: none up to 4 letters (beef is not beer), one from 5, two from 8. */
    private fun allowedTypos(length: Int) = when { length >= 8 -> 2; length >= 5 -> 1; else -> 0 }

    /** Whether [typed] is [word] (or its start, while still typing) give or take a typo or two. */
    private fun closeTo(typed: String, word: String): Boolean {
        val k = allowedTypos(typed.length)
        if (k == 0 || word.length < 3) return false
        if (kotlin.math.abs(word.length - typed.length) <= k && distance(typed, word, k) <= k) return true
        // A partly typed word: compare with the start of the word
        if (word.length > typed.length) {
            for (len in (typed.length - k).coerceAtLeast(3)..(typed.length + k).coerceAtMost(word.length)) {
                if (distance(typed, word.take(len), k) <= k) return true
            }
        }
        return false
    }

    /** Edit distance (insertions, deletions, substitutions, swapped neighbours), giving up past [max]. */
    private fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev2 = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** How well [name] and [brand] match the query's [tokens], or null when a word is missing. */
    fun match(name: String, brand: String?, tokens: List<String>): Int? {
        if (tokens.isEmpty()) return null
        val n = normalize(name)
        val haystack = if (brand.isNullOrBlank()) n else "$n ${normalize(brand)}"
        val words = haystack.split(' ')
        if (tokens.all { it in haystack }) {
            return when {
                n.startsWith(tokens.joinToString(" ")) || n.startsWith(tokens.first()) -> NAME_STARTS
                tokens.all { t -> words.any { it.startsWith(t) } } -> WORD_STARTS
                else -> CONTAINS
            }
        }
        // Allowing for plurals, spelling variants and typos, word by word
        val canonWords = words.map { canonical(it) }
        val canonHay = canonWords.joinToString(" ")
        val ok = tokens.all { t ->
            val c = canonical(t)
            t in haystack || c in canonHay || canonWords.any { it.startsWith(c) || closeTo(c, it) }
        }
        if (!ok) return null
        // Variants and plurals rank like the spelling you typed; only typos rank lower
        val exactish = tokens.all { t -> val c = canonical(t); t in haystack || c in canonHay || canonWords.any { it.startsWith(c) } }
        return if (!exactish) FUZZY else when {
            canonWords.firstOrNull()?.startsWith(canonical(tokens.first())) == true -> NAME_STARTS
            else -> WORD_STARTS
        }
    }

    /** A food described by what was logged of it: its own amount and nutrition as the serving. */
    fun foodOf(e: FoodEntry): FoodItem = FoodItem(
        id = e.foodId ?: e.id, name = e.foodName, brand = e.brandName,
        servingSize = e.quantity, servingUnit = e.unit,
        calories = e.calories, protein = e.protein, carbs = e.carbs, fat = e.fat, fiber = 0f
    )

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
                SearchHit(e.foodId?.let { foods[it] } ?: foodOf(e), g.count, e.createdAt)
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
