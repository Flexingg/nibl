package com.bytelog.app.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchMathTest {
    private val total = Macros(2000f, 150f, 200f, 60f)

    @Test fun weighedBatchScalesByGrams() {
        val m = BatchMath.macrosFor(total, portions = null, cookedWeightG = 1450f, eatPortions = null, eatGrams = 320f)
        assertEquals(2000f * 320f / 1450f, m.calories, 0.01f)
    }

    @Test fun portionBatchDividesEvenly() {
        assertEquals(400f, BatchMath.macrosFor(total, 5, null, 1f, null).calories, 0.01f)
        assertEquals(200f, BatchMath.macrosFor(total, 5, null, 0.5f, null).calories, 0.01f)
    }

    @Test fun gramsWithoutWeightIsUnknown() {
        assertNull(BatchMath.fractionFor(5, null, null, 300f))
    }

    @Test fun portionsOnWeighedBatch() {
        assertEquals(0.25f, BatchMath.fractionFor(4, 1200f, 1f, null)!!, 1e-6f)
    }

    @Test fun remainingAndFinished() {
        val r = BatchMath.remainingFraction(listOf(0.2f, 0.2f, 0.59f))
        assertEquals(0.01f, r, 1e-6f)
        assertTrue(BatchMath.isFinished(r, weighed = true))
        assertFalse(BatchMath.isFinished(r, weighed = false))
        assertEquals(0f, BatchMath.remainingFraction(listOf(0.7f, 0.7f)), 0f)
    }

    @Test fun scaledRecipeYieldsMoreServings() {
        // 3x a recipe that makes 10 servings = 30 servings.
        assertEquals(30, BatchMath.defaultPortions(10f, 3f))
        assertEquals(5, BatchMath.defaultPortions(10f, 0.5f))
    }

    @Test fun templateTotalsFromSparkyRows() {
        val foods = """[
          {"food_id":"a","food_name":"Chicken Breast","quantity":680,"unit":"g","serving_size":100,"serving_unit":"g","calories":165,"protein":31,"carbs":0,"fat":3.6},
          {"food_id":"b","food_name":"Rice","quantity":2,"unit":"serving","serving_size":45,"serving_unit":"g","calories":160,"protein":3,"carbs":35,"fat":0.5},
          {"food_id":"c","food_name":"Olive Oil","quantity":2,"unit":"tbsp","serving_size":1,"serving_unit":"tbsp","calories":119,"protein":0,"carbs":0,"fat":13.5}
        ]"""
        val t = TemplateNutrition.totals(foods)!!
        assertEquals(165f * 6.8f + 160f * 2 + 119f * 2, t.calories, 0.5f)
    }

    @Test fun templateWithoutNutritionReturnsNull() {
        assertNull(TemplateNutrition.totals("""[{"food_id":"a","quantity":1,"unit":"g"}]"""))
    }
}
