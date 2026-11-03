package com.bytelog.app.data

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.repository.CoLogging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoLoggingTest {
    private var t = 1_000L
    private fun e(day: Int, meal: String, name: String, foodId: String? = "f-${name.lowercase().replace(' ', '-')}", status: String = FoodEntry.SYNC_STATUS_SYNCED) =
        FoodEntry(id = "e${t}", foodId = foodId, foodName = name, mealTypeId = meal.lowercase(), mealName = meal, quantity = 1f, unit = "serving",
            calories = 100f, protein = 1f, carbs = 1f, fat = 1f, entryDate = "2026-10-%02d".format(day), syncStatus = status, createdAt = t++)

    private val oatmeal = e(0, "Breakfast", "Oatmeal").copy(entryDate = "2026-11-01")

    private fun names(r: List<com.bytelog.app.data.repository.CoFood>) = r.map { it.entry.foodName }

    @Test fun foodsEatenInTheSameMealComeUpMostOftenFirst() {
        val history = listOf(
            // Oatmeal with banana 4 times, with peanut butter 2 times, with coffee once
            e(1, "Breakfast", "Oatmeal"), e(1, "Breakfast", "Banana"), e(1, "Breakfast", "Peanut Butter"),
            e(2, "Breakfast", "Oatmeal"), e(2, "Breakfast", "Banana"), e(2, "Breakfast", "Peanut Butter"),
            e(3, "Breakfast", "Oatmeal"), e(3, "Breakfast", "Banana"),
            e(4, "Breakfast", "Oatmeal"), e(4, "Breakfast", "Banana"), e(4, "Breakfast", "Coffee")
        )
        val r = CoLogging.suggest(history, listOf(oatmeal))
        assertEquals(listOf("Banana", "Peanut Butter"), names(r))        // coffee: once isn't a pattern
        assertEquals(4, r[0].together); assertEquals(4, r[0].ofSeed)
        assertEquals(2, r[1].together)
    }

    @Test fun aFoodThatGoesWithEverythingDoesntSuggestEverything() {
        // Coffee is in 10 breakfasts; oatmeal in 2 of them together (20%): below the 30% share
        val history = (1..10).flatMap { d -> listOf(e(d, "Breakfast", "Coffee")) + (if (d <= 2) listOf(e(d, "Breakfast", "Oatmeal")) else emptyList()) }
        assertTrue(CoLogging.suggest(history, listOf(e(0, "Breakfast", "Coffee")), minShare = 0.3f).isEmpty())   // coffee seed: oatmeal 2/10
        assertEquals(listOf("Coffee"), names(CoLogging.suggest(history, listOf(oatmeal))))                        // oatmeal seed: coffee 2/2
    }

    @Test fun onlyTheSameMealOnTheSameDayCounts() {
        val history = listOf(
            e(1, "Breakfast", "Oatmeal"), e(1, "Dinner", "Salmon"),
            e(2, "Breakfast", "Oatmeal"), e(2, "Dinner", "Salmon"),
            e(3, "Breakfast", "Oatmeal"), e(3, "Snacks", "Salmon")
        )
        assertTrue(CoLogging.suggest(history, listOf(oatmeal)).isEmpty())
        // "Snack" and "Snacks" are one meal
        val snacks = listOf(e(1, "Snack", "Chips"), e(1, "Snacks", "Salsa"), e(2, "Snacks", "Chips"), e(2, "Snack", "Salsa"))
        assertEquals(listOf("Salsa"), names(CoLogging.suggest(snacks, listOf(e(0, "Snacks", "Chips")))))
    }

    @Test fun whatsInTheTrayOrAlreadyInTheMealIsLeftOut() {
        val history = (1..3).flatMap { d -> listOf(e(d, "Dinner", "Chicken"), e(d, "Dinner", "Rice"), e(d, "Dinner", "Broccoli")) }
        val chicken = e(0, "Dinner", "Chicken")
        assertEquals(setOf("Rice", "Broccoli"), names(CoLogging.suggest(history, listOf(chicken))).toSet())
        // Rice is already in the tray: only broccoli
        assertEquals(listOf("Broccoli"), names(CoLogging.suggest(history, listOf(chicken, e(0, "Dinner", "Rice")))))
        // Rice is already logged in this dinner today
        assertEquals(listOf("Broccoli"), names(CoLogging.suggest(history, listOf(chicken), alreadyIn = listOf(e(0, "Dinner", "Rice")))))
    }

    @Test fun severalFoodsInTheTrayRankWhatGoesWithMostOfThem() {
        val history = (1..3).flatMap { d ->
            listOf(e(d, "Dinner", "Chicken"), e(d, "Dinner", "Rice"), e(d, "Dinner", "Broccoli"), e(d, "Dinner", "Salsa")) 
        } + (4..6).flatMap { d -> listOf(e(d, "Dinner", "Rice"), e(d, "Dinner", "Beans")) } +
            (7..9).flatMap { d -> listOf(e(d, "Dinner", "Chicken"), e(d, "Dinner", "Broccoli")) }
        val tray = listOf(e(0, "Dinner", "Chicken"), e(0, "Dinner", "Rice"))
        // Broccoli goes with chicken (6 of 6) and rice (3 of 6); beans only with rice (3 of 6); salsa with both (3/6, 3/6)
        val r = names(CoLogging.suggest(history, tray))
        assertEquals("Broccoli", r.first())
        assertTrue(r.indexOf("Salsa") < r.indexOf("Beans"))
    }

    @Test fun theSameFoodUnderDifferentIdsIsOneFoodAndDeletedEntriesDontCount() {
        val history = listOf(
            e(1, "Lunch", "Turkey Sandwich", foodId = "old-id"), e(1, "Lunch", "Chips", foodId = null),
            e(2, "Lunch", "turkey sandwich ", foodId = "new-id"), e(2, "Lunch", "Chips", foodId = "chips-1"),
            e(3, "Lunch", "Turkey Sandwich", foodId = "new-id"), e(3, "Lunch", "Pickle", status = FoodEntry.SYNC_STATUS_PENDING_DELETE),
            e(4, "Lunch", "Turkey Sandwich", foodId = "new-id"), e(4, "Lunch", "Pickle", status = FoodEntry.SYNC_STATUS_PENDING_DELETE)
        )
        val r = CoLogging.suggest(history, listOf(e(0, "Lunch", "Turkey Sandwich", foodId = "new-id")))
        assertEquals(listOf("Chips"), names(r))
        assertEquals(2, r.single().together); assertEquals(4, r.single().ofSeed)
        // Logged twice in one meal counts once
        val twice = listOf(e(1, "Lunch", "Turkey Sandwich"), e(1, "Lunch", "Chips"), e(1, "Lunch", "Chips"), e(2, "Lunch", "Turkey Sandwich"), e(2, "Lunch", "Chips"))
        assertEquals(2, CoLogging.suggest(twice, listOf(e(0, "Lunch", "Turkey Sandwich"))).single().together)
        assertTrue(CoLogging.suggest(twice, emptyList()).isEmpty())
    }
}
