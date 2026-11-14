package com.bytelog.app.data

import com.bytelog.app.data.local.model.FoodPrice
import com.bytelog.app.data.local.model.PlannedEntry
import com.bytelog.app.data.repository.PriceCodec
import com.bytelog.app.data.repository.ShoppingList
import com.bytelog.app.data.repository.SparkyPrice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingListTest {
    private var n = 0
    private fun p(food: String, qty: Float, unit: String, date: String = "2026-11-15", id: String? = null, brand: String? = null, kcal: Float = 100f) = PlannedEntry(
        id = "p${n++}", foodId = id, foodName = food, brandName = brand, mealTypeId = "lunch", mealName = "Lunch",
        quantity = qty, unit = unit, calories = kcal, protein = 0f, carbs = 0f, fat = 0f, entryDate = date
    )

    @Test fun amountsAddUpAcrossDaysAndConvert() {
        val list = ShoppingList.build(listOf(
            p("Greek Yogurt", 1f, "cup", "2026-11-15", "f-yog"),
            p("Greek Yogurt", 8f, "tbsp", "2026-11-16", "f-yog"),
            p("Chicken Breast", 200f, "g", "2026-11-15"),
            p("chicken breast", 4f, "oz", "2026-11-17"),
            p("Banana", 1f, "piece"), p("Banana", 2f, "pieces", "2026-11-16")
        )).items.associateBy { it.name.lowercase() }

        val yogurt = list.getValue("greek yogurt")
        assertEquals(1.5, yogurt.amounts.single().quantity, 0.01)
        assertEquals("cup", yogurt.amounts.single().unit)
        assertEquals(listOf("2026-11-15", "2026-11-16"), yogurt.days)

        val chicken = list.getValue("chicken breast")
        assertEquals("g", chicken.amounts.single().unit)
        assertEquals(200 + 4 * 28.35, chicken.amounts.single().quantity, 0.5)

        assertEquals(3.0, list.getValue("banana").amounts.single().quantity, 0.0)
        assertEquals("3 pieces", list.getValue("banana").amountLabel)
        assertEquals("1.5 cups", yogurt.amountLabel)
        assertEquals("1 cup", com.bytelog.app.data.repository.ShoppingAmount(1.0, "cup").label)
        assertEquals("2 each", com.bytelog.app.data.repository.ShoppingAmount(2.0, "each").label)
    }

    @Test fun theSameFoodUnderAnotherIdIsOneLine() {
        val items = ShoppingList.build(listOf(p("Oats", 40f, "g", id = "f-1"), p("Oats", 40f, "g", id = null))).items
        assertEquals(1, items.size)
        assertEquals(80.0, items.single().amounts.single().quantity, 0.0)
        assertEquals("f-1", items.single().foodId)
    }

    @Test fun servingsBecomeWeightsWhenTheFoodsServingIsKnown() {
        val items = ShoppingList.build(listOf(p("Rice", 2f, "serving", id = "f-rice"), p("Rice", 50f, "g", id = "f-rice")), servingOf = { if (it == "f-rice") 75f to "g" else null }).items
        assertEquals(200.0, items.single().amounts.single().quantity, 0.0)
        // Without a known serving it stays in servings, separate from grams
        val other = ShoppingList.build(listOf(p("Soup", 2f, "serving", id = "x"), p("Soup", 100f, "g", id = "x"))).items.single()
        assertEquals(listOf("2 servings", "100 g"), other.amounts.map { it.label })
    }

    private fun price(key: String, store: String?, price: Float?, qty: Float?, unit: String?) =
        FoodPrice(key, null, "x", null, store, price, qty, unit)

    @Test fun costIsWholePackagesWhereTheUnitsConvert() {
        val prices = mapOf(
            "f-yog" to price("f-yog", "Costco", 4.99f, 32f, "oz"),
            "n:${com.bytelog.app.data.repository.FoodSearch.identity("Eggs", null)}" to price("eggs", "Aldi", 3.6f, 1f, "dozen"),
            "f-salmon" to price("f-salmon", null, 12f, 1f, "lb"),
            "f-soup" to price("f-soup", "Aldi", 2f, 1f, "can")
        )
        val list = ShoppingList.build(listOf(
            // 1.5 cups of yogurt is about 13 oz by weight: one 32 oz tub
            p("Greek Yogurt", 1.5f, "cup", id = "f-yog"),
            // 14 eggs: two dozen
            p("Eggs", 14f, "each"),
            // 600 g of salmon: 1.3 lb, so 2 × 1 lb
            p("Salmon", 600f, "g", id = "f-salmon"),
            // 2 servings of a soup priced per can: won't convert
            p("Soup", 2f, "serving", id = "f-soup"),
            p("Apples", 3f, "piece")
        ), priceOf = { prices[it] })
        val byName = list.items.associateBy { it.name }
        assertEquals(1, byName.getValue("Greek Yogurt").packages)
        assertEquals(4.99, byName.getValue("Greek Yogurt").cost!!, 0.001)
        assertEquals(2, byName.getValue("Eggs").packages)
        assertEquals(7.2, byName.getValue("Eggs").cost!!, 0.001)
        assertEquals(2, byName.getValue("Salmon").packages)
        assertNull(byName.getValue("Soup").cost)
        assertNull(byName.getValue("Apples").price)
        assertEquals(4.99 + 7.2 + 24.0, list.total, 0.001)
        assertEquals(3, list.priced)
        // Stores A to Z, then the ones with none
        assertEquals(listOf("Aldi", "Costco", null), list.byStore.map { it.first })

        val text = ShoppingList.asText(list, checked = setOf("f-yog"))
        assertTrue(text, text.contains("☑ Greek Yogurt"))
        assertTrue(text, text.contains("☐ Eggs — 14 each · buy 2 × 1 dozen"))
        assertTrue(text, text.contains("Anywhere"))
    }

    @Test fun storeAndPriceSitBesideOtherCustomNutrients() {
        val existing = Json.parseToJsonElement("""{"Omega 3":"1.2","nibl_store":"Old","nibl_price":"9"}""") as JsonObject
        val written = PriceCodec.write(existing, SparkyPrice("Costco", 4.99f, 32f, "oz"), "2026-11-14")
        assertEquals(JsonPrimitive("1.2"), written["Omega 3"])
        assertEquals(SparkyPrice("Costco", 4.99f, 32f, "oz"), PriceCodec.read(written))
        assertEquals(JsonPrimitive("2026-11-14"), written[PriceCodec.UPDATED])

        // Clearing takes Nibl's keys off and leaves the rest
        val cleared = PriceCodec.write(written, SparkyPrice(), "2026-11-14")
        assertEquals(setOf("Omega 3"), cleared.keys)
        assertTrue(PriceCodec.read(cleared).isEmpty)

        // Absent, null, blank and junk read as not set
        assertTrue(PriceCodec.read(null).isEmpty)
        val junk = Json.parseToJsonElement("""{"nibl_store":"  ","nibl_price":"abc","nibl_price_qty":0,"nibl_price_unit":null}""") as JsonObject
        assertTrue(PriceCodec.read(junk).isEmpty)
        assertFalse(PriceCodec.read(Json.parseToJsonElement("""{"nibl_price":"3.5"}""") as JsonObject).isEmpty)
    }
}
