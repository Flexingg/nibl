package com.bytelog.app.data

import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.FoodPrice
import com.bytelog.app.data.repository.PlannerConfig
import com.bytelog.app.data.repository.PriceCodec
import com.bytelog.app.data.repository.ShoppingAmount
import com.bytelog.app.data.repository.ShoppingList
import com.bytelog.app.data.repository.SparkyPrice
import com.bytelog.app.ui.components.PriceDraft
import com.bytelog.app.ui.logging.Portions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlannerAndUnitsTest {
    private val yogurt = FoodItem("f-yog", "Greek Yogurt", servingSize = 170f, servingUnit = "g", calories = 150f)

    @Test fun pricesOnlyUseTheFoodsOwnUnits() {
        val units = Portions.unitOptions(yogurt)
        assertTrue(units.toString(), "g" in units && "oz" in units && "serving" in units)
        assertNull(PriceDraft(price = "4.99", quantity = "32", unit = "oz").problem(units))
        assertNull(PriceDraft(price = "4.99", quantity = "32", unit = "ounces").problem(units))
        assertNotNull("not one of its units", PriceDraft(price = "4.99", quantity = "1", unit = "tub").problem(units))
        assertNotNull("a price needs an amount", PriceDraft(price = "4.99").problem(units))
        assertNotNull("a package needs how much it holds", PriceDraft(price = "4.99", packageName = "tub").problem(units))
        // A new unit is a named package of a known amount
        val tub = PriceDraft(store = "Costco", price = "4.99", quantity = "32", unit = "oz", packageName = "tub")
        assertNull(tub.problem(units))
        assertEquals(SparkyPrice("Costco", 4.99f, 32f, "oz", "tub"), tub.toPrice())
        assertEquals(tub, PriceDraft.from(tub.toPrice()))
        assertNull("store alone is fine", PriceDraft(store = "Aldi").problem(units))
    }

    @Test fun thePackageNameIsStoredOnSparkyToo() {
        val written = PriceCodec.write(null, SparkyPrice("Costco", 4.99f, 32f, "oz", "tub"), "2026-11-14")
        assertEquals("tub", PriceCodec.read(written).packageName)
    }

    @Test fun costsConvertThroughTheFoodsServings() {
        val byOz = FoodPrice("f-yog", "f-yog", "Greek Yogurt", null, "Costco", 4.99f, 32f, "oz", "tub")
        // 2 servings of 170 g is 340 g, about 12 oz: one tub
        val (packs, cost) = ShoppingList.costByServings(yogurt, listOf(ShoppingAmount(2.0, "serving")), byOz)!!
        assertEquals(1, packs); assertEquals(4.99, cost!!, 0.001)
        // Priced by the serving: 300 g is 1.8 servings, so 2
        val perServing = byOz.copy(price = 1f, quantity = 1f, unit = "serving", packageName = null)
        assertEquals(2, ShoppingList.costByServings(yogurt, listOf(ShoppingAmount(300.0, "g")), perServing)!!.first)
        // A unit that isn't the food's can't be costed this way
        assertNull(ShoppingList.costByServings(yogurt, listOf(ShoppingAmount(2.0, "tub")), byOz))

        val item = ShoppingList.build(
            listOf(com.bytelog.app.data.local.model.PlannedEntry("p1", "f-yog", "Greek Yogurt", null, "breakfast", "Breakfast", 2f, "serving", 300f, 30f, 12f, 10f, "2026-11-15")),
            priceOf = { if (it == "f-yog") byOz else null }, foodOf = { if (it == "f-yog") yogurt else null }
        ).items.single()
        assertEquals("Buy 1 tub (32 oz each)", item.buyLabel)
        assertTrue(item.units.toString(), "oz" in item.units)
        assertEquals("$4.99 per tub (32 oz)", byOz.describe { "$" + String.format(java.util.Locale.US, "%.2f", it) })
    }

    @Test fun plannerConfigRoundTripsAndStartsWhereAsked() {
        val c = PlannerConfig(days = 14, startOnMonday = true, showMacros = false, showGoal = true)
        assertEquals(c, PlannerConfig.parse(c.encode()))
        assertEquals(PlannerConfig(), PlannerConfig.parse(null))
        assertEquals(PlannerConfig(), PlannerConfig.parse("junk;d=999"))
        val thursday = LocalDate.of(2026, 11, 19)
        assertEquals(LocalDate.of(2026, 11, 16), c.start(thursday))
        assertEquals(LocalDate.of(2026, 11, 30), c.start(thursday, page = 1))
        assertEquals(thursday.plusDays(7), PlannerConfig(days = 7).start(thursday, 1))
    }
}
