package com.bytelog.app.data.repository

import com.bytelog.app.data.local.model.FoodEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CopyAmountTest {
    private val rice = FoodEntry(
        id = "1", foodName = "Rice", mealTypeId = "lunch", mealName = "Lunch", quantity = 150f, unit = "g",
        calories = 200f, protein = 4f, carbs = 44f, fat = 0.4f, entryDate = "2026-11-24"
    )

    @Test fun scalingChangesTheAmountAndEveryNutrientTogether() {
        val half = CopyAmount.scaled(rice, 0.5f)
        assertEquals(75f, half.quantity, 0.001f)
        assertEquals(100f, half.calories, 0.001f)
        assertEquals(2f, half.protein, 0.001f)
        assertEquals(22f, half.carbs, 0.001f)
        assertEquals(0.2f, half.fat, 0.001f)
        assertEquals("Rice", half.foodName)
    }

    @Test fun oneTimesOrANonsenseScaleLeavesTheFoodAlone() {
        assertSame(rice, CopyAmount.scaled(rice, 1f))
        assertSame(rice, CopyAmount.scaled(rice, 0f))
        assertSame(rice, CopyAmount.scaled(rice, Float.NaN))
    }

    @Test fun aNewAmountBecomesAMultiplier() {
        assertEquals(2f, CopyAmount.scaleFor(rice, 300f), 0.001f)
        assertEquals(1f, CopyAmount.scaleFor(rice.copy(quantity = 0f), 300f), 0.001f)
    }

    @Test fun gramsStepByTenAndServingsByAQuarter() {
        assertEquals(10f, CopyAmount.step("g"), 0f)
        assertEquals(10f, CopyAmount.step("ml"), 0f)
        assertEquals(0.25f, CopyAmount.step("serving"), 0f)
    }

    @Test fun multipliersReadAsFractions() {
        assertEquals("¼×", CopyAmount.label(0.25f))
        assertEquals("½×", CopyAmount.label(0.5f))
        assertEquals("1×", CopyAmount.label(1f))
        assertEquals("1½×", CopyAmount.label(1.5f))
        assertEquals("2×", CopyAmount.label(2f))
        assertEquals("1.3×", CopyAmount.label(1.3f))
        assertEquals("1.5", CopyAmount.amount(1.5f))
        assertEquals("150", CopyAmount.amount(150f))
    }
}
