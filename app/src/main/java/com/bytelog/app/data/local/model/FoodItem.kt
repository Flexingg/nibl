package com.bytelog.app.data.local.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bytelog.app.recipes.UnitConverter
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "foods",
    indices = [
        Index(value = ["name"]),
        Index(value = ["barcode"]),
        Index(value = ["isFavorite"]),
        Index(value = ["lastUsedAt"])
    ]
)
data class FoodItem(
    @PrimaryKey
    val id: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val servingSize: Float = 100f,
    val servingUnit: String = "g",
    val calories: Float = 0f,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f,
    val fiber: Float? = 0f,
    val isFavorite: Boolean = false,
    val useCount: Int = 0,
    val lastUsedAt: Long = System.currentTimeMillis(),
    val mealTypeAffinity: String? = null // e.g. "breakfast", "dinner"
) {
    /**
     * Ratio of [quantity] [unit] to this food's reference serving, converting units
     * (oz → g, cup → ml, …) where possible. "1 serving" means one reference serving.
     */
    fun servingRatio(quantity: Float, unit: String): Float =
        UnitConverter.servingRatio(quantity, unit, servingSize, servingUnit, name)

    fun caloriesFor(quantity: Float, unit: String): Float = calories * servingRatio(quantity, unit)

    fun proteinFor(quantity: Float, unit: String): Float = protein * servingRatio(quantity, unit)

    fun carbsFor(quantity: Float, unit: String): Float = carbs * servingRatio(quantity, unit)

    fun fatFor(quantity: Float, unit: String): Float = fat * servingRatio(quantity, unit)
}
