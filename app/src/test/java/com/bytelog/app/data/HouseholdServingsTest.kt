package com.bytelog.app.data

import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.remote.SparkyFood
import com.bytelog.app.recipes.HouseholdServing
import com.bytelog.app.recipes.HouseholdServings
import com.bytelog.app.ui.logging.Portion
import com.bytelog.app.ui.logging.Portions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Household servings: "1 medium", "2 cookies", "1 cup", "4 oz" instead of 100 g. */
class HouseholdServingsTest {
    private fun per100(name: String, unit: String = "g", kcal: Float = 100f) = FoodItem("id-$name", name, servingSize = 100f, servingUnit = unit, calories = kcal)
    private fun usual(name: String, unit: String = "g") = Portions.defaultFor(per100(name, unit)).label

    @Test fun genericPer100FoodsGetAUsualPortion() {
        assertEquals("1 medium", usual("Bananas, raw"))
        assertEquals("1 large", usual("Egg, whole, cooked, hard-boiled"))
        assertEquals("1 slice", usual("Bread, whole-wheat, commercially prepared"))
        assertEquals("1 slice", usual("Banana bread"))                  // the bread, not the banana
        assertEquals("1 breast", usual("Chicken breast, roasted"))
        assertEquals("4 oz", usual("Beef, ground, 85% lean, cooked"))
        assertEquals("1 oz", usual("Cheddar cheese"))
        assertEquals("1 cup", usual("Rice, white, cooked"))
        assertEquals("1 cup", usual("Cottage cheese"))
        assertEquals("1 tbsp", usual("Olive oil"))
        assertEquals("1 tbsp", usual("Peanut butter, smooth"))
        assertEquals("1 cup", usual("Milk, 2%", unit = "ml"))
        // No good guess: keeps 100 g rather than inventing one
        assertEquals("100 g", usual("Cinnamon, ground"))
        assertEquals("100 g", usual("Taco seasoning mix"))
        assertEquals("1 cup", usual("Apple juice"))
    }

    @Test fun aFoodWithARealServingKeepsIt() {
        val bar = FoodItem("b", "Protein bar", servingSize = 60f, servingUnit = "g", calories = 220f)
        assertEquals(Portion(60f, "g"), Portions.defaultFor(bar))
        val sandwich = FoodItem("s", "Turkey club", servingSize = 1f, servingUnit = "sandwich", calories = 540f)
        assertEquals("1 sandwich", Portions.defaultFor(sandwich).label)
    }

    @Test fun householdPortionsCarryTheRightNutrition() {
        val banana = per100("Banana, raw", kcal = 89f)
        val p = Portions.defaultFor(banana)
        assertEquals(105f, Portions.macros(banana, p).calories, 0.5f)     // 118 g
        assertEquals("1 medium (118 g)", Portions.describe(banana, p))
        assertEquals("2 medium (236 g)", Portions.describe(banana, Portion(2f, "medium")))
        assertEquals("medium", Portions.unitOptions(banana).first())

        val rice = per100("Rice, white, cooked", kcal = 130f)
        assertEquals(246f, Portions.macros(rice, Portion(1f, "cup")).calories, 2f)   // 1 cup ≈ 189 g
    }

    @Test fun storedServingsWinAndPlural() {
        val cookie = FoodItem("c", "Chocolate chip cookies", servingSize = 28f, servingUnit = "g", calories = 140f,
            householdServings = HouseholdServings.encode(listOf(HouseholdServing("cookie", 0.5f, 2f))))
        assertEquals("2 cookies (28 g)", Portions.describe(cookie, Portions.defaultFor(cookie)))
        assertEquals(70f, Portions.macros(cookie, Portion(1f, "cookie")).calories, 0.01f)
        assertEquals(70f, Portions.macros(cookie, Portion(1f, "cookies")).calories, 0.01f)
        assertEquals(listOf(HouseholdServing("cookie", 0.5f, 2f)), HouseholdServings.decode(cookie.householdServings))
        assertEquals("sandwiches", HouseholdServings.plural("sandwich", 2f))
        assertEquals("berry", HouseholdServings.singular("berries"))
        assertEquals("2 oz", Portion(2f, "oz").label)
    }

    @Test fun sparkysV2SearchGivesTheLabelAndHouseholdServing() {
        val foods = SparkyApiClient.parseNormalizedFoods(
            """{"foods":[{"name":"Oreo","brand":"Nabisco","barcode":"044000032029","provider_type":"openfoodfacts",
                "default_variant":{"serving_size":34,"serving_unit":"g","calories":160,"protein":1,"carbs":25,"fat":7},
                "variants":[{"serving_size":34,"serving_unit":"g","calories":160,"protein":1,"carbs":25,"fat":7,"is_default":true},
                            {"serving_size":3,"serving_unit":"cookies","calories":160,"protein":1,"carbs":25,"fat":7,"is_default":false}]}],
               "pagination":{"page":1,"pageSize":20,"totalCount":1,"hasMore":false}}"""
        )
        val oreo = foods.single()
        assertEquals("off_044000032029", oreo.id)
        assertEquals(34f, oreo.servingSize, 0f)
        assertEquals("3 cookies (34 g)", Portions.describe(oreo, Portions.defaultFor(oreo)))
        assertEquals(53.3f, Portions.macros(oreo, Portion(1f, "cookie")).calories, 0.1f)
    }

    @Test fun theOriginalSearchNoLongerMixesUpPer100AndPerServing() {
        val foods = SparkyApiClient.parseOffProducts(
            """{"products":[
                {"code":"1","product_name":"Granola","serving_quantity":"30","serving_size":"1/2 cup (30 g)","nutriments":{"energy-kcal_100g":450,"proteins_100g":10}},
                {"code":"2","product_name":"Cookies","serving_quantity":28,"serving_size":"2 cookies (28 g)","nutriments":{"energy-kcal_100g":500}},
                {"code":"3","product_name":"Plain thing","nutriments":{"energy-kcal_100g":200}}
            ]}"""
        )
        val granola = foods[0]
        assertEquals(30f, granola.servingSize, 0f)
        assertEquals(135f, granola.calories, 0.01f)       // was 450 kcal labelled as 30 g
        assertEquals(3f, granola.protein, 0.01f)
        val cookies = foods[1]
        assertEquals("2 cookies (28 g)", Portions.describe(cookies, Portions.defaultFor(cookies)))
        assertEquals(70f, Portions.macros(cookies, Portion(1f, "cookie")).calories, 0.01f)
        assertEquals(100f, foods[2].servingSize, 0f)
        assertEquals(200f, foods[2].calories, 0f)
    }

    @Test fun sparkyLibraryVariantsBecomeHouseholdServings() {
        val json = Json { ignoreUnknownKeys = true }
        val default = json.parseToJsonElement("""{"serving_size":100,"serving_unit":"g","calories":389,"is_default":true}""").jsonObject
        val cup = json.parseToJsonElement("""{"serving_size":1,"serving_unit":"cup","calories":307,"is_default":false}""").jsonObject
        val oz = json.parseToJsonElement("""{"serving_size":1,"serving_unit":"oz","calories":110}""").jsonObject
        val h = SparkyFood.householdsFrom(default, listOf(default, cup, oz), "Mystery cereal")
        // The cup is new information; an ounce is just a conversion of grams
        assertEquals(1, h.size)
        assertEquals("cup", h[0].unit)
        assertEquals(307f / 389f, h[0].perUnit, 0.001f)
        assertNull(SparkyFood.householdsFrom(default, emptyList(), "x").firstOrNull())
        assertTrue(HouseholdServings.isPer100(FoodItem("x", "x", servingSize = 100f, servingUnit = "g")))
    }
}
