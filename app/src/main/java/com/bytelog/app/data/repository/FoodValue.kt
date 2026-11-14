package com.bytelog.app.data.repository

import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.FoodPrice
import com.bytelog.app.data.local.model.PlannedEntry
import java.util.Locale
import kotlin.math.roundToInt

/** What a food gives you for your money, measured by. */
enum class ValueMetric(val label: String, val unit: String, val amount: (FoodItem) -> Float) {
    PROTEIN("Protein", "g", { it.protein }),
    CALORIES("Calories", "kcal", { it.calories }),
    FIBER("Fiber", "g", { it.fiber ?: 0f }),
    CARBS("Carbs", "g", { it.carbs }),
    FAT("Fat", "g", { it.fat });

    /** "24 g protein", "410 kcal". */
    fun describe(v: Float): String {
        val n = if (v >= 10f || v == 0f) v.roundToInt().toString() else String.format(Locale.getDefault(), "%.1f", v)
        return if (this == CALORIES) "$n kcal" else "$n $unit ${label.lowercase()}"
    }
}

/** A food's cost per serving, from its price, and so what each dollar buys of it. */
data class FoodValue(val food: FoodItem, val price: FoodPrice, val costPerServing: Double) {
    /** How much of [m] a dollar buys. */
    fun perDollar(m: ValueMetric): Float = if (costPerServing <= 0.0) 0f else (m.amount(food) / costPerServing).toFloat()

    /** What [quantity] [unit] of the food costs (null if the unit isn't one of its own). */
    fun costOf(quantity: Float, unit: String): Double? {
        if (!ShoppingList.isFoodUnit(food, unit)) return null
        return costPerServing * food.servingRatio(quantity, unit)
    }

    /** "24 g protein · 410 kcal per $1". */
    fun summary(primary: ValueMetric = ValueMetric.PROTEIN): String {
        val second = if (primary == ValueMetric.CALORIES) ValueMetric.PROTEIN else ValueMetric.CALORIES
        return "${primary.describe(perDollar(primary))} · ${second.describe(perDollar(second))} per ${ShoppingList.money(1.0).replace(Regex("[.,]00$"), "")}"
    }

    companion object {
        /**
         * The value of [food] at [price]: the price's amount, in one of the food's own units, gives how many servings
         * it buys. Null without a price, an amount, or a food with any nutrition.
         */
        fun of(food: FoodItem, price: FoodPrice?): FoodValue? {
            val p = price?.price ?: return null
            val q = price.quantity ?: return null
            val u = price.unit ?: return null
            if (p <= 0f || q <= 0f || (food.calories <= 0f && food.protein <= 0f) || !ShoppingList.isFoodUnit(food, u)) return null
            val servings = food.servingRatio(q, u)
            if (servings <= 0f) return null
            return FoodValue(food, price, p.toDouble() / servings)
        }

        /** A food described by what's planned of it: its amount as the serving (for foods the phone doesn't have). */
        fun foodOf(p: PlannedEntry): FoodItem = FoodItem(
            id = p.foodId ?: ShoppingList.keyOf(null, p.foodName, p.brandName), name = p.foodName, brand = p.brandName,
            servingSize = p.quantity, servingUnit = p.unit, calories = p.calories, protein = p.protein, carbs = p.carbs, fat = p.fat
        )
    }
}
