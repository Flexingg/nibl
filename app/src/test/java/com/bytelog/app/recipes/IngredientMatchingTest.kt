package com.bytelog.app.recipes

import com.bytelog.app.data.local.model.FoodItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientMatchingTest {
    private val chicken = FoodItem(id = "c", name = "Chicken Breast", servingSize = 100f, servingUnit = "g", calories = 165f)
    private val pizza = FoodItem(id = "p", name = "Chicken Alfredo Pizza", servingSize = 1f, servingUnit = "slice", calories = 300f)
    private val egg = FoodItem(id = "e", name = "Egg", servingSize = 50f, servingUnit = "g", calories = 72f)

    @Test fun scorePrefersSpecificFood() {
        val want = "boneless skinless chicken breast"
        assertTrue(IngredientMatching.score(want, chicken) > IngredientMatching.score(want, pizza))
        assertTrue(IngredientMatching.score(want, chicken) >= 0.75f)
    }

    @Test fun gramsUsedForMassFoods() {
        val parsed = IngredientLineParser.parse("1 1/2 lbs chicken breast")
        val m = IngredientMatching.matchWith(parsed, chicken, MatchSource.SEARCH)
        assertEquals("g", m.unit)
        assertEquals(680.4f, m.quantity, 0.1f)
        assertEquals(1122.6f, m.macros.calories, 0.5f)
    }

    @Test fun countsBecomeServings() {
        val parsed = IngredientLineParser.parse("3 large eggs")
        val m = IngredientMatching.matchWith(parsed, egg, MatchSource.SEARCH)
        assertEquals(216f, m.macros.calories, 0.1f)
    }

    @Test fun normalizeForAliases() {
        assertEquals(IngredientMatching.normalize("Chicken Breasts"), IngredientMatching.normalize("chicken breast"))
        assertEquals("tomato", IngredientMatching.normalize("Tomatoes"))
    }

    @Test fun searchQueriesGoFromSpecificToGeneral() {
        val q = IngredientMatching.searchQueries("boneless skinless chicken breast")
        assertEquals("boneless skinless chicken breast", q.first())
        assertTrue(q.contains("chicken breast"))
        assertEquals("breast", q.last())
    }
}
