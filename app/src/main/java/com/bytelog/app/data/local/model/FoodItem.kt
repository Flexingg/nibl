package com.bytelog.app.data.local.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
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
     * Calculates scaled calories for a given quantity in the given unit.
     */
    fun caloriesFor(quantity: Float, unit: String): Float {
        val ratio = if (servingSize > 0f) quantity / servingSize else 1f
        return calories * ratio
    }

    fun proteinFor(quantity: Float, unit: String): Float {
        val ratio = if (servingSize > 0f) quantity / servingSize else 1f
        return protein * ratio
    }

    fun carbsFor(quantity: Float, unit: String): Float {
        val ratio = if (servingSize > 0f) quantity / servingSize else 1f
        return carbs * ratio
    }

    fun fatFor(quantity: Float, unit: String): Float {
        val ratio = if (servingSize > 0f) quantity / servingSize else 1f
        return fat * ratio
    }
}
