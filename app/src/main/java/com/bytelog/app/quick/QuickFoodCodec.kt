package com.bytelog.app.quick

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A search result carried on a notification's Log button. Results from Sparky and Open Food Facts aren't saved on
 * the phone until they're used, so the button carries the food itself rather than an id to look up.
 */
@Serializable
internal data class CarriedFood(
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
    val householdServings: String? = null
) {
    fun toFood() = FoodItem(
        id = id, name = name, brand = brand, barcode = barcode, servingSize = servingSize, servingUnit = servingUnit,
        calories = calories, protein = protein, carbs = carbs, fat = fat, fiber = fiber, householdServings = householdServings
    )

    companion object {
        fun of(f: FoodItem) = CarriedFood(
            f.id, f.name, f.brand, f.barcode, f.servingSize, f.servingUnit, f.calories, f.protein, f.carbs, f.fat, f.fiber,
            f.householdServings
        )
    }
}

/** What an Undo button needs to find an entry again (see FoodRepository.undoLogged). */
@Serializable
internal data class QuickLogged(val id: String, val foodName: String, val mealTypeId: String, val mealName: String, val entryDate: String, val createdAt: Long) {
    fun toEntry() = FoodEntry(
        id = id, foodName = foodName, mealTypeId = mealTypeId, mealName = mealName, quantity = 0f,
        calories = 0f, protein = 0f, carbs = 0f, fat = 0f, entryDate = entryDate, createdAt = createdAt
    )

    companion object {
        fun of(e: FoodEntry) = QuickLogged(e.id, e.foodName, e.mealTypeId, e.mealName, e.entryDate, e.createdAt)
    }
}

internal object QuickFoodCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(food: FoodItem): String = json.encodeToString(CarriedFood.of(food))
    fun decode(raw: String?): FoodItem? = raw?.let { runCatching { json.decodeFromString<CarriedFood>(it).toFood() }.getOrNull() }

    fun encodeLogged(e: FoodEntry): String = json.encodeToString(QuickLogged.of(e))
    fun decodeLogged(raw: String?): FoodEntry? = raw?.let { runCatching { json.decodeFromString<QuickLogged>(it).toEntry() }.getOrNull() }
}
