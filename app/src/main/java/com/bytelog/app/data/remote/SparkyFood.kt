package com.bytelog.app.data.remote

import com.bytelog.app.data.local.model.FoodItem
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Nutrition for one serving, as Sparky stores it on a food variant. Null means "not recorded". */
data class SparkyNutrients(
    val servingSize: Float = 100f,
    val servingUnit: String = "g",
    val calories: Float = 0f,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f,
    val fiber: Float? = null,
    val sugars: Float? = null,
    val saturatedFat: Float? = null,
    val sodium: Float? = null,
    val cholesterol: Float? = null,
    val potassium: Float? = null
)

/** A food as Sparky has it: library details plus its default serving. */
data class SparkyFood(
    val id: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val userId: String? = null,
    val isCustom: Boolean = false,
    val providerType: String? = null,
    val variantId: String? = null,
    val nutrients: SparkyNutrients = SparkyNutrients()
) {
    fun toFoodItem(base: FoodItem? = null): FoodItem = (base ?: FoodItem(id = id, name = name)).copy(
        id = id, name = name, brand = brand, barcode = barcode,
        servingSize = nutrients.servingSize, servingUnit = nutrients.servingUnit,
        calories = nutrients.calories, protein = nutrients.protein, carbs = nutrients.carbs, fat = nutrients.fat,
        fiber = nutrients.fiber ?: 0f
    )

    companion object {
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
        private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.floatOrNull ?: it.contentOrNull?.toFloatOrNull() }

        /** Reads GET /api/foods/:id (or a foods-paginated row): food fields plus `default_variant`. */
        fun parse(obj: JsonObject): SparkyFood {
            val v = (obj["default_variant"] as? JsonObject) ?: obj
            return SparkyFood(
                id = obj.str("id").orEmpty(),
                name = obj.str("name") ?: "Unnamed food",
                brand = obj.str("brand"),
                barcode = obj.str("barcode"),
                userId = obj.str("user_id"),
                isCustom = (obj["is_custom"] as? JsonPrimitive)?.booleanOrNull ?: false,
                providerType = obj.str("provider_type"),
                variantId = (obj["default_variant"] as? JsonObject)?.str("id"),
                nutrients = SparkyNutrients(
                    servingSize = v.num("serving_size") ?: 100f,
                    servingUnit = v.str("serving_unit") ?: "g",
                    calories = v.num("calories") ?: 0f,
                    protein = v.num("protein") ?: 0f,
                    carbs = v.num("carbs") ?: 0f,
                    fat = v.num("fat") ?: 0f,
                    fiber = v.num("dietary_fiber"),
                    sugars = v.num("sugars"),
                    saturatedFat = v.num("saturated_fat"),
                    sodium = v.num("sodium"),
                    cholesterol = v.num("cholesterol"),
                    potassium = v.num("potassium")
                )
            )
        }
    }
}
