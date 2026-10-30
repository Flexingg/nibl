package com.bytelog.app.ui.logging

import com.bytelog.app.data.local.model.FoodItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortionsTest {
    private val chicken = FoodItem("c", "Chicken Breast", servingSize = 100f, servingUnit = "g", calories = 165f, protein = 31f, carbs = 0f, fat = 3.6f)
    private val milk = FoodItem("m", "Milk", servingSize = 240f, servingUnit = "ml", calories = 120f, protein = 8f, carbs = 12f, fat = 5f)
    private val bar = FoodItem("b", "Protein Bar", servingSize = 1f, servingUnit = "bar", calories = 200f, protein = 20f, carbs = 22f, fat = 7f)

    @Test fun massFoodsOfferServingGramsAndOunces() {
        val units = Portions.unitOptions(chicken)
        // A whole food described by weight offers its household measure first ("1 breast")
        assertEquals(listOf("breast", "serving", "g", "oz"), units.take(4))
        assertFalse("no volume without a density", "ml" in units)
    }

    @Test fun volumeFoodsOfferCupsAndSpoons() {
        val units = Portions.unitOptions(milk)
        assertTrue(units.containsAll(listOf("serving", "ml", "cup", "tbsp")))
    }

    @Test fun countedFoodsOfferServingAndTheirOwnUnit() {
        assertEquals(listOf("serving", "bar"), Portions.unitOptions(bar))
    }

    @Test fun switchingUnitKeepsTheSameFood() {
        val oz = Portions.convert(chicken, Portion(150f, "g"), "oz")
        assertEquals(5.3f, oz.quantity, 0.06f)
        val back = Portions.convert(chicken, oz, "g")
        assertEquals(150f, back.quantity, 2f)
        assertEquals(1.5f, Portions.convert(chicken, Portion(150f, "g"), "serving").quantity, 0.001f)
        assertEquals(1f, Portions.convert(milk, Portion(1f, "serving"), "cup").quantity, 0f) // 240 ml snaps to 1 cup
        // Same calories either side of the switch
        assertEquals(
            Portions.macros(chicken, Portion(150f, "g")).calories,
            Portions.macros(chicken, Portions.convert(chicken, Portion(150f, "g"), "serving")).calories, 0.5f
        )
    }

    @Test fun stepsAndQuickAmountsSuitTheUnit() {
        assertEquals(5f, Portions.step("g", 30f), 0f)
        assertEquals(10f, Portions.step("g", 150f), 0f)
        assertEquals(0.5f, Portions.step("cup", 1f), 0f)
        assertEquals(listOf(50f, 100f, 150f, 200f), Portions.quickAmounts(chicken, "g"))
        assertTrue(0.5f in Portions.quickAmounts(chicken, "serving"))
    }

    @Test fun amountsReadNaturally() {
        assertEquals(53f, Portions.tidy(52.6f, "g"), 0f)
        assertEquals(1.35f, Portions.tidy(1.3333f, "cup"), 0.001f)
        assertEquals("½", formatQuantity(0.5f))
        assertEquals("1½", formatQuantity(1.5f))
        assertEquals("150", formatQuantity(150f))
        assertEquals("2 servings", Portion(2f, "serving").label)
        assertEquals("1 serving", Portion(1f, "serving").label)
    }
}
