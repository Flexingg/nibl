package com.bytelog.app.recipes

import com.bytelog.app.data.local.model.FoodItem
import java.util.UUID

/** Where a matched food came from, shown on the review screen. */
enum class MatchSource { ALIAS, LIBRARY, SEARCH, MANUAL, AI, NONE }

/** One reviewed ingredient: the parsed line plus the food and amount it maps to. */
data class IngredientMatch(
    val key: String = UUID.randomUUID().toString(),
    val parsed: ParsedIngredient,
    val food: FoodItem? = null,
    val quantity: Float = 0f,
    val unit: String = "g",
    val source: MatchSource = MatchSource.NONE,
    val include: Boolean = true,
    val needsReview: Boolean = true,
    val candidates: List<FoodItem> = emptyList()
) {
    val macros: Macros
        get() = food?.let {
            Macros(it.caloriesFor(quantity, unit), it.proteinFor(quantity, unit), it.carbsFor(quantity, unit), it.fatFor(quantity, unit))
        } ?: Macros()

    /** Approximate weight in grams for display, when it can be worked out. */
    val grams: Float?
        get() {
            UnitConverter.toGrams(quantity.toDouble(), unit, food?.name ?: parsed.name)?.let { return it.toFloat() }
            val f = food ?: return null
            val ratio = f.servingRatio(quantity, unit)
            return UnitConverter.toGrams((f.servingSize * ratio).toDouble(), f.servingUnit, f.name)?.toFloat()
        }
}

object IngredientMatching {

    private val stopWords = setOf(
        "of", "and", "or", "the", "a", "an", "with", "for", "to", "in", "large", "medium", "small",
        "fresh", "organic", "raw", "boneless", "skinless", "extra", "virgin", "pure", "plain", "unsalted",
        "salted", "low", "sodium", "reduced", "fat", "free", "light", "lean", "whole", "ground", "dried", "frozen"
    )

    /** Key used for the alias table: lowercase, letters only, crude singular form. */
    fun normalize(name: String): String = name.lowercase()
        .replace(Regex("""[^a-z\s]"""), " ")
        .split(Regex("""\s+"""))
        .filter { it.isNotBlank() }
        .joinToString(" ") { singular(it) }

    private fun singular(w: String): String = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && (w.endsWith("oes") || w.endsWith("ches") || w.endsWith("shes")) -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    private fun tokens(s: String): Set<String> = normalize(s).split(' ').filter { it.isNotBlank() }.toSet()

    private fun coreTokens(s: String): Set<String> = tokens(s).filter { it !in stopWords }.toSet().ifEmpty { tokens(s) }

    /** 0..1 similarity between an ingredient name and a food name. */
    fun score(ingredientName: String, food: FoodItem): Float {
        val want = coreTokens(ingredientName)
        val have = tokens(food.name + " " + (food.brand ?: ""))
        if (want.isEmpty() || have.isEmpty()) return 0f
        val hit = want.count { it in have }
        val coverage = hit.toFloat() / want.size
        // Penalise long food names that only share one generic word ("Chicken Alfredo Pizza" for "chicken").
        val foodCore = coreTokens(food.name)
        val precision = if (foodCore.isEmpty()) 0f else foodCore.count { it in want }.toFloat() / foodCore.size
        return 0.7f * coverage + 0.3f * precision
    }

    /** Search terms from most to least specific: "boneless skinless chicken breast" → [.., "chicken breast", "breast"]. */
    fun searchQueries(name: String): List<String> {
        val words = name.lowercase().replace(Regex("""[^a-z\s]"""), " ").split(Regex("""\s+""")).filter { it.isNotBlank() }
        val core = words.filter { it !in stopWords }
        val out = linkedSetOf<String>()
        if (words.isNotEmpty()) out += words.joinToString(" ")
        if (core.isNotEmpty()) out += core.joinToString(" ")
        if (core.size > 2) out += core.takeLast(2).joinToString(" ")
        if (core.isNotEmpty()) out += core.last()
        return out.toList()
    }

    /**
     * The amount to store for an ingredient, expressed so that [FoodItem.servingRatio] gives
     * the right multiple of the food's reference serving. Returns (quantity, unit, confident).
     */
    fun amountFor(parsed: ParsedIngredient, food: FoodItem): Triple<Float, String, Boolean> {
        val foodDim = UnitConverter.dimension(food.servingUnit)
        val qty = parsed.quantity?.let { q -> parsed.quantityMax?.let { (q + it) / 2 } ?: q }
        val grams = parsed.grams
        return when {
            grams != null && (foodDim == UnitConverter.Dimension.MASS || foodDim == UnitConverter.Dimension.VOLUME) ->
                Triple(grams.toFloat(), "g", true)
            qty != null && parsed.unit != null &&
                UnitConverter.convert(qty, parsed.unit, food.servingUnit, IngredientDensities.lookup(food.name)) != null ->
                Triple(qty.toFloat(), parsed.unit, true)
            qty != null && (parsed.unit == null || UnitConverter.dimension(parsed.unit) == UnitConverter.Dimension.COUNT) &&
                (foodDim == UnitConverter.Dimension.COUNT || foodDim == null) ->
                Triple((qty * food.servingSize).toFloat(), food.servingUnit, parsed.unit == null || parsed.unit.equals(food.servingUnit, true))
            qty != null && (parsed.unit == null || UnitConverter.dimension(parsed.unit) == UnitConverter.Dimension.COUNT) ->
                // "2 eggs" against a food measured in grams: count each as one reference serving.
                Triple(qty.toFloat(), "serving", false)
            grams != null -> Triple(grams.toFloat(), "g", false)
            else -> Triple(food.servingSize, food.servingUnit, false)
        }
    }

    /** Builds a match for [parsed] against [food] with the computed amount. */
    fun matchWith(parsed: ParsedIngredient, food: FoodItem, source: MatchSource, candidates: List<FoodItem> = emptyList(), key: String? = null): IngredientMatch {
        val (q, u, confident) = amountFor(parsed, food)
        val strongName = source == MatchSource.ALIAS || source == MatchSource.MANUAL || score(parsed.name, food) >= 0.75f
        return IngredientMatch(
            key = key ?: UUID.randomUUID().toString(),
            parsed = parsed,
            food = food,
            quantity = q,
            unit = u,
            source = source,
            include = true,
            needsReview = !(confident && strongName),
            candidates = candidates
        )
    }
}
