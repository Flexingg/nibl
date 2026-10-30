package com.bytelog.app.data

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.repository.FoodSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodSearchTest {
    private var t = 1_000L
    private fun entry(name: String, foodId: String? = null, brand: String? = null, kcal: Float = 100f) = FoodEntry(
        id = "e${t}", foodId = foodId, foodName = name, brandName = brand, mealTypeId = "lunch", mealName = "Lunch",
        quantity = 1f, unit = "serving", calories = kcal, protein = 1f, carbs = 1f, fat = 1f, entryDate = "2026-10-01",
        syncStatus = FoodEntry.SYNC_STATUS_SYNCED, createdAt = t++
    )

    @Test fun everyWordMustMatchInAnyOrderIgnoringCaseAndAccents() {
        val q = FoodSearch.tokens("breast CHICKEN")
        assertNotNull(FoodSearch.match("Chicken Breast, grilled", null, q))
        assertNull(FoodSearch.match("Chicken thigh", null, q))
        assertNotNull(FoodSearch.match("Crème brûlée", null, FoodSearch.tokens("creme brulee")))
        assertNotNull("brand counts", FoodSearch.match("Greek Yogurt", "Fage", FoodSearch.tokens("fage yog")))
        assertEquals(FoodSearch.NAME_STARTS, FoodSearch.match("Banana", null, FoodSearch.tokens("ban")))
        assertEquals(FoodSearch.WORD_STARTS, FoodSearch.match("Ripe banana", null, FoodSearch.tokens("ban")))
        assertEquals(FoodSearch.CONTAINS, FoodSearch.match("Urban salad", null, FoodSearch.tokens("ban")))
    }

    @Test fun loggedFoodsComeFirstMostLoggedFirstAndOncePerFood() {
        val entries = listOf(
            entry("Banana bread", "f-bread"),
            entry("Banana", "f-banana"), entry("Banana", "f-banana"), entry("Banana", "f-banana"),
            entry("banana ", null),                     // the same food logged without an id
            entry("Apple")
        )
        val logged = FoodSearch.rankLogged(entries, emptyMap(), FoodSearch.tokens("banana"))
        assertEquals(listOf("Banana", "Banana bread"), logged.map { it.food.name.trim() })
        assertEquals(4, logged[0].timesLogged)

        // A saved food that matches better is still listed after the ones you've logged
        val saved = listOf(FoodItem("f-new", "Banana", brand = "Chiquita", isFavorite = true), FoodItem("f-bread", "Banana bread"))
        val results = FoodSearch.combine(
            logged,
            FoodSearch.rankSaved(saved, FoodSearch.tokens("banana"), logged.map { it.food.id }.toSet()),
            listOf(FoodItem("off_1", "Banana chips"), FoodItem("off_2", "Banana", brand = "Chiquita")),
            FoodSearch.tokens("banana")
        )
        assertEquals(listOf("Banana", "Banana bread"), results.logged.map { it.food.name.trim() })
        assertEquals(listOf("f-new", "off_1"), results.other.map { it.food.id })   // the remote Chiquita banana is the saved one
    }

    @Test fun aLoggedFoodUsesItsSavedNutritionWhenThereIsOne() {
        val saved = FoodItem("f-oats", "Rolled Oats", servingSize = 40f, servingUnit = "g", calories = 150f)
        val logged = FoodSearch.rankLogged(listOf(entry("Rolled Oats", "f-oats", kcal = 300f), entry("Mystery stew", kcal = 420f)), mapOf(saved.id to saved), FoodSearch.tokens("s"))
        assertEquals(saved, logged.first { it.food.id == "f-oats" }.food)
        val stew = logged.first { it.food.name == "Mystery stew" }.food
        assertEquals(420f, stew.calories, 0f)                    // from the entry itself
        assertEquals("serving", stew.servingUnit)
    }

    @Test fun pluralsSpellingsAndTyposStillMatchButRankBelowExactMatches() {
        fun m(name: String, q: String) = FoodSearch.match(name, null, FoodSearch.tokens(q))
        assertNotNull("plural", m("Egg, whole", "eggs"))
        assertNotNull("plural -ies", m("Mixed berry blend", "berries"))
        assertNotNull("plural -oes", m("Cherry tomato", "tomatoes"))
        assertNotNull("spelling", m("Greek Yogurt", "yoghurt"))
        assertNotNull("spelling, other way", m("Greek Yoghurt", "yogurt"))
        assertNotNull("other name", m("Grilled eggplant", "aubergine"))
        assertEquals("typo", FoodSearch.FUZZY, m("Banana", "bannana"))
        assertEquals("swapped letters", FoodSearch.FUZZY, m("Chicken breast", "chikcen"))
        assertEquals("typo while still typing", FoodSearch.FUZZY, m("Avocado toast", "avacad"))
        assertNull("short words must be exact", m("Beef", "beer"))
        assertNull("too many typos", m("Banana", "bnanaa xx"))
        assertNull(m("Apple", "banana"))
        // A typo never outranks an exact match
        assertTrue(m("Banana bread", "banana")!! < m("Banana", "bannana")!!)
    }

    @Test fun thePrefilterCoversOtherSpellings() {
        assertEquals(3, FoodSearch.prefilterTokens("aubergine").size)
        assertTrue("egg" in FoodSearch.prefilterTokens("aubergine"))
        assertTrue("aub" in FoodSearch.prefilterTokens("eggplant"))
        assertTrue("ban" in FoodSearch.prefilterTokens("bannana"))
    }
}
