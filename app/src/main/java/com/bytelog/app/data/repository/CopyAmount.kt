package com.bytelog.app.data.repository

import com.bytelog.app.data.local.model.FoodEntry
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** How much to copy: a new amount for one food, or a multiplier for several. */
object CopyAmount {
    /** Multipliers offered for several foods (and a whole meal). */
    val PRESETS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f)

    /** [entry] at [scale] times its amount: quantity, calories and macros together. */
    fun scaled(entry: FoodEntry, scale: Float): FoodEntry {
        if (scale == 1f || scale <= 0f || !scale.isFinite()) return entry
        return entry.copy(
            quantity = entry.quantity * scale, calories = entry.calories * scale,
            protein = entry.protein * scale, carbs = entry.carbs * scale, fat = entry.fat * scale
        )
    }

    /** The multiplier that turns [entry]'s amount into [newQuantity]. */
    fun scaleFor(entry: FoodEntry, newQuantity: Float): Float =
        if (entry.quantity > 0f && newQuantity > 0f) newQuantity / entry.quantity else 1f

    /** One tap of − or + on an amount: 10 for grams and millilitres, a quarter for servings and the like. */
    fun step(unit: String): Float = if (unit.trim().lowercase() in setOf("g", "gram", "grams", "ml", "millilitre", "milliliter")) 10f else 0.25f

    /** "¼×", "1½×", "2×", or "1.3×" for anything else. */
    fun label(scale: Float): String {
        val whole = scale.toInt()
        val frac = scale - whole
        val part = when {
            abs(frac) < 0.01f -> ""
            abs(frac - 0.25f) < 0.01f -> "¼"
            abs(frac - 0.5f) < 0.01f -> "½"
            abs(frac - 0.75f) < 0.01f -> "¾"
            else -> return String.format(Locale.US, "%.2f", scale).trimEnd('0').trimEnd('.') + "×"
        }
        return (if (whole == 0 && part.isNotEmpty()) "" else whole.toString()) + part + "×"
    }

    /** An amount for display: whole numbers plain, otherwise up to two decimals. */
    fun amount(q: Float): String = if (abs(q - q.roundToInt()) < 0.005f) q.roundToInt().toString()
        else String.format(Locale.US, "%.2f", q).trimEnd('0').trimEnd('.')
}
