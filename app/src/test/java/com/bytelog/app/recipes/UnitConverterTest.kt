package com.bytelog.app.recipes

import com.bytelog.app.data.local.model.FoodItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitConverterTest {

    @Test fun massConversions() {
        assertEquals(453.59, UnitConverter.convert(1.0, "lb", "g")!!, 0.01)
        assertEquals(28.35, UnitConverter.convert(1.0, "ounces", "g")!!, 0.01)
        assertEquals(2.0, UnitConverter.convert(2000.0, "g", "kg")!!, 1e-9)
    }

    @Test fun volumeNeedsDensityForMass() {
        assertNull(UnitConverter.convert(1.0, "cup", "g"))
        assertEquals(125.4, UnitConverter.toGrams(1.0, "cup", "all-purpose flour")!!, 0.1)
    }

    @Test fun countUnitsAreNotConvertible() {
        assertNull(UnitConverter.convert(1.0, "can", "g"))
    }

    @Test fun foodMathHonoursUnits() {
        val chicken = FoodItem(id = "1", name = "Chicken Breast", servingSize = 100f, servingUnit = "g", calories = 165f, protein = 31f)
        // Previously "1.5 lb" was treated as 1.5 g.
        assertEquals(1122.6f, chicken.caloriesFor(1.5f, "lb"), 0.5f)
        assertEquals(165f, chicken.caloriesFor(100f, "g"), 0.01f)
        // One "serving" means one reference serving.
        assertEquals(165f, chicken.caloriesFor(1f, "serving"), 0.01f)
        assertEquals(46.8f, chicken.caloriesFor(1f, "oz"), 0.1f)
    }

    @Test fun sameUnitKeepsHistoricBehaviour() {
        val egg = FoodItem(id = "2", name = "Egg", servingSize = 1f, servingUnit = "large", calories = 72f)
        assertEquals(144f, egg.caloriesFor(2f, "large"), 0.01f)
    }
}
