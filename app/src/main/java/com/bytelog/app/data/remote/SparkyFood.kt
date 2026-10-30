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
    val nutrients: SparkyNutrients = SparkyNutrients(),
    /** The food's other servings on Sparky ("2 cookies", "1 cup"), relative to [nutrients]' serving. */
    val households: List<com.bytelog.app.recipes.HouseholdServing> = emptyList()
) {
    fun toFoodItem(base: FoodItem? = null): FoodItem = (base ?: FoodItem(id = id, name = name)).copy(
        id = id, name = name, brand = brand, barcode = barcode,
        servingSize = nutrients.servingSize, servingUnit = nutrients.servingUnit,
        calories = nutrients.calories, protein = nutrients.protein, carbs = nutrients.carbs, fat = nutrients.fat,
        fiber = nutrients.fiber ?: 0f,
        householdServings = com.bytelog.app.recipes.HouseholdServings.encode(households) ?: base?.householdServings
    )

    companion object {
        const val UNNAMED = "Unnamed food"
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
        private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.floatOrNull ?: it.contentOrNull?.toFloatOrNull() }

        /**
         * Household servings from a food's variants: each serving that isn't the default and can't already be
         * worked out from it by unit conversion ("2 cookies", "1 cup" of a food described in grams), sized by
         * its calories against the default's.
         */
        fun householdsFrom(default: JsonObject, variants: List<JsonObject>, foodName: String): List<com.bytelog.app.recipes.HouseholdServing> {
            val refSize = default.num("serving_size") ?: return emptyList()
            val refUnit = default.str("serving_unit") ?: return emptyList()
            val refCal = default.num("calories") ?: 0f
            if (refSize <= 0f || refCal <= 0f) return emptyList()
            val density = com.bytelog.app.recipes.IngredientDensities.lookup(foodName)
            return variants.mapNotNull { v ->
                val size = v.num("serving_size")?.takeIf { it > 0f } ?: return@mapNotNull null
                val unit = v.str("serving_unit")?.trim() ?: return@mapNotNull null
                val cal = v.num("calories")?.takeIf { it > 0f } ?: return@mapNotNull null
                if (unit.equals(refUnit, ignoreCase = true) && kotlin.math.abs(size - refSize) < 0.01f) return@mapNotNull null
                if (com.bytelog.app.recipes.UnitConverter.convert(1.0, unit, refUnit, density) != null) return@mapNotNull null
                com.bytelog.app.recipes.HouseholdServing(
                    com.bytelog.app.recipes.HouseholdServings.singular(unit), (cal / size) / refCal, size
                )
            }.distinctBy { it.unit }
        }

        /** Reads GET /api/foods/:id (or a foods-paginated row): food fields plus `default_variant` (and `variants`). */
        fun parse(obj: JsonObject): SparkyFood {
            val v = (obj["default_variant"] as? JsonObject) ?: obj
            val variants = (obj["variants"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            return SparkyFood(
                households = householdsFrom(v, variants, obj.str("name").orEmpty()),
                id = obj.str("id").orEmpty(),
                name = obj.str("name") ?: UNNAMED,
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
