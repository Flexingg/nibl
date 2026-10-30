package com.bytelog.app.ui.logging

import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.recipes.HouseholdServings
import com.bytelog.app.recipes.IngredientDensities
import com.bytelog.app.recipes.UnitConverter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** An amount of a food: "1.5 cup", "150 g", "2 serving". */
data class Portion(val quantity: Float, val unit: String) {
    val label: String get() = "${formatQuantity(quantity)} ${unitLabel(unit, quantity)}"
}

/** Calories and macros for a portion. */
data class PortionMacros(val calories: Float, val protein: Float, val carbs: Float, val fat: Float)

/**
 * Portion maths for logging: which units make sense for a food, converting an amount between them so the
 * food stays the same, step sizes and quick amounts.
 */
object Portions {
    const val SERVING = "serving"

    /**
     * What one tap adds of a food you haven't logged before: a household serving ("1 medium", "2 cookies",
     * "1 cup", "4 oz") where there is one, else the food's own serving.
     */
    fun defaultFor(food: FoodItem): Portion =
        HouseholdServings.usualPortion(food)?.let { (q, u) -> Portion(q, u) }
            ?: Portion(food.servingSize, food.servingUnit.ifBlank { "g" })

    /** The portion with its weight when that helps: "2 cookies (28 g)", "1 medium (118 g)"; plain otherwise. */
    fun describe(food: FoodItem, p: Portion): String {
        if (UnitConverter.canonical(p.unit) != null || p.unit.equals(SERVING, ignoreCase = true)) return p.label
        val refUnit = UnitConverter.canonical(food.servingUnit) ?: return p.label
        if (refUnit !in setOf("g", "ml", "oz")) return p.label
        val amount = food.servingRatio(p.quantity, p.unit) * food.servingSize
        if (amount <= 0f) return p.label
        return "${p.label} (${formatQuantity(tidy(amount, refUnit))} $refUnit)"
    }

    /**
     * Units offered for a food: "serving" first, then the food's own unit, then the ones it converts to.
     * Mass ↔ volume is only offered when a density is known for the food's name.
     */
    fun unitOptions(food: FoodItem): List<String> {
        val own = food.servingUnit.trim().ifBlank { "g" }
        val ownCanon = UnitConverter.canonical(own) ?: own.lowercase()
        val dim = UnitConverter.dimension(own)
        val dense = IngredientDensities.lookup(food.name) != null
        val out = mutableListOf<String>()
        fun add(u: String) { if (out.none { (UnitConverter.canonical(it) ?: it.lowercase()) == (UnitConverter.canonical(u) ?: u.lowercase()) }) out += u }
        // Household measures first ("cookie", "medium", "slice"), then the usual portion's unit (cup, oz)
        HouseholdServings.forFood(food).forEach { add(it.unit) }
        HouseholdServings.usualPortion(food)?.let { add(it.second) }
        if (ownCanon != SERVING) add(SERVING)
        add(ownCanon)
        when (dim) {
            UnitConverter.Dimension.MASS -> { add("g"); add("oz"); if (dense) { add("cup"); add("tbsp"); add("tsp") } }
            UnitConverter.Dimension.VOLUME -> { add("ml"); add("cup"); add("tbsp"); add("tsp"); add("fl oz"); if (dense) { add("g"); add("oz") } }
            else -> {}
        }
        return out
    }

    fun macros(food: FoodItem, p: Portion) = PortionMacros(
        food.caloriesFor(p.quantity, p.unit), food.proteinFor(p.quantity, p.unit),
        food.carbsFor(p.quantity, p.unit), food.fatFor(p.quantity, p.unit)
    )

    /** The same amount of food in [toUnit] (150 g → 5.3 oz, 1 serving of 240 ml → 1 cup). */
    fun convert(food: FoodItem, p: Portion, toUnit: String): Portion {
        if (p.unit.equals(toUnit, ignoreCase = true)) return p
        val perUnit = food.servingRatio(1f, toUnit)
        if (perUnit <= 0f) return Portion(1f, toUnit)
        val q = food.servingRatio(p.quantity, p.unit) / perUnit
        // Whole grams/ml; finer than tidy() for other units so a switch doesn't change how much food it is
        val rounded = when (UnitConverter.canonical(toUnit)) {
            "g", "ml", "mg" -> tidy(q, toUnit)
            else -> {
                // 240 ml is "1 cup", not "1.01 cup": within 2% of a quarter, take the quarter
                val quarter = (q * 4f).roundToInt() / 4f
                if (quarter > 0f && abs(q - quarter) <= quarter * 0.02f) quarter
                else ((q * 100f).roundToInt() / 100f).coerceAtLeast(0.01f)
            }
        }
        return Portion(rounded, toUnit)
    }

    /** Fine units (g, ml) step by 10 (5 below 50); everything else by halves. */
    fun step(unit: String, quantity: Float): Float = when (UnitConverter.canonical(unit)) {
        "g", "ml" -> if (quantity < 50f) 5f else 10f
        "mg" -> 50f
        else -> 0.5f
    }

    /** Quick amounts: fractions of a unit for coarse units, multiples of a serving for g/ml. */
    fun quickAmounts(food: FoodItem, unit: String): List<Float> = when (UnitConverter.canonical(unit)) {
        "g", "ml" -> {
            val one = convert(food, Portion(1f, SERVING), unit).quantity.takeIf { it > 0f } ?: 100f
            listOf(0.5f, 1f, 1.5f, 2f).map { tidy(one * it, unit) }.distinct()
        }
        else -> listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f)
    }

    /** Rounds to what people type: whole grams/ml, otherwise to the nearest 0.05. */
    fun tidy(q: Float, unit: String): Float = when (UnitConverter.canonical(unit)) {
        "g", "ml", "mg" -> q.roundToInt().toFloat().coerceAtLeast(if (q > 0f) 1f else 0f)
        else -> ((q * 20f).roundToInt() / 20f)
    }

    /** Scales logged nutrition from one portion to another of the same food when the food itself isn't known. */
    fun rescale(name: String, from: Portion, to: Portion): Float? {
        if (from.quantity <= 0f) return null
        if (from.unit.equals(to.unit, ignoreCase = true)) return to.quantity / from.quantity
        val converted = UnitConverter.convert(to.quantity.toDouble(), to.unit, from.unit, IngredientDensities.lookup(name)) ?: return null
        return (converted / from.quantity).toFloat()
    }
}

fun formatQuantity(q: Float): String {
    val r = (q * 100f).roundToInt() / 100f
    return when {
        abs(r - r.roundToInt()) < 0.001f -> r.roundToInt().toString()
        abs(r - 0.25f) < 0.001f -> "¼"
        abs(r - 0.5f) < 0.001f -> "½"
        abs(r - 0.75f) < 0.001f -> "¾"
        abs(r % 1f - 0.5f) < 0.001f -> "${r.toInt()}½"
        else -> String.format(Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')
    }
}

/** "serving" → "servings", "cookie" → "cookies" for amounts above one; measures and sizes stay as written. */
fun unitLabel(unit: String, quantity: Float): String =
    if (unit.equals(Portions.SERVING, ignoreCase = true) && quantity > 1.001f) "servings" else HouseholdServings.plural(unit, quantity)
