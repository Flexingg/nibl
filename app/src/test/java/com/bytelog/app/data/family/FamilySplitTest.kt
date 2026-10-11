package com.bytelog.app.data.family

import com.bytelog.app.data.local.model.FoodEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilySplitTest {
    @Test fun evenSharesAlwaysAddUpToOneHundred() {
        assertEquals(listOf(100), FamilySplit.even(1))
        assertEquals(listOf(50, 50), FamilySplit.even(2))
        assertEquals(listOf(34, 33, 33), FamilySplit.even(3))
        for (n in 1..9) assertEquals(100, FamilySplit.even(n).sum())
        assertEquals(emptyList<Int>(), FamilySplit.even(0))
    }

    @Test fun changingOnePersonRebalancesTheOthersToExactlyOneHundred() {
        val three = FamilySplit.adjust(listOf(50, 30, 20), 0, 70)      // others had 30:20 → share 30 in that ratio
        assertEquals(listOf(70, 18, 12), three)
        assertEquals(100, three.sum())
        assertEquals(listOf(60, 40), FamilySplit.adjust(listOf(50, 50), 0, 60))
        assertEquals(listOf(0, 100), FamilySplit.adjust(listOf(50, 50), 0, 0))
        assertEquals("others that had nothing: the first takes it", listOf(40, 60, 0), FamilySplit.adjust(listOf(100, 0, 0), 0, 40))
        assertEquals("clamped", listOf(100, 0), FamilySplit.adjust(listOf(50, 50), 0, 140))
        for (v in 0..100) assertEquals(100, FamilySplit.adjust(listOf(34, 33, 33), 1, v).sum())
    }

    @Test fun anEntryScalesAmountAndNutritionTogether() {
        val e = FoodEntry(id = "1", foodName = "Lasagna", mealTypeId = "dinner", mealName = "Dinner", quantity = 300f, unit = "g",
            calories = 600f, protein = 30f, carbs = 55f, fat = 25f, entryDate = "2026-10-10")
        val forty = FamilySplit.scale(e, 40)
        assertEquals(120f, forty.quantity, 0.001f)
        assertEquals(240f, forty.calories, 0.001f)
        assertEquals(12f, forty.protein, 0.001f)
        assertEquals(22f, forty.carbs, 0.001f)
        assertEquals(10f, forty.fat, 0.001f)
        assertTrue(FamilySplit.scale(e, 100) == e)
    }
}
