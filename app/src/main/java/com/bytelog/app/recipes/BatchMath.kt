package com.bytelog.app.recipes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class Macros(
    val calories: Float = 0f,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f
) {
    operator fun plus(o: Macros) = Macros(calories + o.calories, protein + o.protein, carbs + o.carbs, fat + o.fat)
    operator fun times(f: Float) = Macros(calories * f, protein * f, carbs * f, fat * f)
    operator fun div(f: Float) = if (f > 0f) Macros(calories / f, protein / f, carbs / f, fat / f) else this
}

/**
 * Batch amounts. A batch has total nutrients and optionally a portion count and/or
 * a cooked weight. Logging is by portions or by grams; when both a weight and a
 * portion count exist, a portion is `cookedWeight / portions` grams.
 */
object BatchMath {

    /** Nutrients for eating [portions] portions or [grams] grams of a batch. */
    fun macrosFor(total: Macros, portions: Int?, cookedWeightG: Float?, eatPortions: Float?, eatGrams: Float?): Macros {
        val fraction = fractionFor(portions, cookedWeightG, eatPortions, eatGrams) ?: return Macros()
        return total * fraction
    }

    /** The fraction of the whole batch represented by the amount eaten, or null if it can't be determined. */
    fun fractionFor(portions: Int?, cookedWeightG: Float?, eatPortions: Float?, eatGrams: Float?): Float? = when {
        eatGrams != null && cookedWeightG != null && cookedWeightG > 0f -> eatGrams / cookedWeightG
        eatPortions != null && portions != null && portions > 0 -> eatPortions / portions
        eatPortions != null && cookedWeightG != null && cookedWeightG > 0f && portions != null && portions > 0 ->
            eatPortions / portions
        else -> null
    }

    /**
     * Remaining share of the batch (0..1) after the given consumptions, each expressed as a fraction.
     */
    fun remainingFraction(consumedFractions: List<Float>): Float =
        (1f - consumedFractions.sum()).coerceIn(0f, 1f)

    /** True when the batch should be treated as finished (allowing for scale rounding on weighed batches). */
    fun isFinished(remainingFraction: Float, weighed: Boolean): Boolean =
        if (weighed) remainingFraction <= 0.01f else remainingFraction <= 0.0001f

    /** Default portion count for a batch cooked at [scale] × a recipe that yields [recipeServings]. */
    fun defaultPortions(recipeServings: Float, scale: Float): Int =
        Math.round(recipeServings * scale).coerceAtLeast(1)
}

/** One ingredient row of a template as Sparky returns it in `/api/meals` → `foods`. */
data class TemplateFoodRow(
    val foodId: String?,
    val name: String,
    val quantity: Float,
    val unit: String,
    val servingSize: Float?,
    val servingUnit: String?,
    val per: Macros?
)

object TemplateNutrition {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseRows(foodsJson: String): List<TemplateFoodRow> = try {
        json.parseToJsonElement(foodsJson).jsonArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            fun f(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.floatOrNull ?: it.jsonPrimitive.contentOrNull?.toFloatOrNull() }.getOrNull() }
            val calories = f("calories")
            TemplateFoodRow(
                foodId = s("food_id") ?: s("foodId"),
                name = s("food_name") ?: s("foodName") ?: s("child_meal_name") ?: "Ingredient",
                quantity = f("quantity") ?: 1f,
                unit = s("unit") ?: "serving",
                servingSize = f("serving_size"),
                servingUnit = s("serving_unit"),
                per = if (calories != null) Macros(calories, f("protein") ?: 0f, f("carbs") ?: 0f, f("fat") ?: 0f) else null
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Whole-recipe totals from the template's ingredient rows. Each row's nutrients are
     * per `serving_size serving_unit`, so the row total is `per × quantity / serving_size`
     * (unit-converted where needed). Returns null when no row carries nutrients.
     */
    fun totals(foodsJson: String): Macros? {
        val rows = parseRows(foodsJson)
        if (rows.none { it.per != null }) return null
        return rows.fold(Macros()) { acc, row ->
            val per = row.per ?: return@fold acc
            val ss = row.servingSize ?: 1f
            val su = row.servingUnit ?: row.unit
            val ratio = UnitConverter.servingRatio(row.quantity, row.unit, ss, su, row.name)
            acc + per * ratio
        }
    }
}
