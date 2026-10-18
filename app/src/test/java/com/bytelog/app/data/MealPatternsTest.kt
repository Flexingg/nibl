package com.bytelog.app.data

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.repository.MealPatterns
import com.bytelog.app.data.repository.MealShortcuts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MealPatternsTest {
    private val today = LocalDate.of(2026, 10, 18) // a Sunday

    private fun e(food: String, meal: String, daysAgo: Long, qty: Float = 1f, kcal: Float = 100f) = FoodEntry(
        id = "$food-$meal-$daysAgo", foodId = food, foodName = food.replaceFirstChar { it.uppercase() },
        mealTypeId = meal.lowercase(), mealName = meal, quantity = qty, unit = "serving",
        calories = kcal, protein = 1f, carbs = 1f, fat = 1f, entryDate = today.minusDays(daysAgo).toString()
    )

    @Test
    fun suggestionsPutThisMealsFoodsFirst() {
        val history = listOf(
            e("oats", "Breakfast", 1), e("oats", "Breakfast", 2), e("coffee", "Breakfast", 3),
            e("chicken", "Lunch", 1), e("chicken", "Lunch", 2), e("chicken", "Lunch", 3), e("rice", "Lunch", 1)
        )
        assertEquals(listOf("oats", "coffee", "chicken", "rice"), MealPatterns.rankFoods(history, "Breakfast", today))
        assertEquals(listOf("chicken", "rice"), MealPatterns.rankFoods(history, "lunch", today).take(2))
    }

    @Test
    fun recentAndSameWeekdayCountMore() {
        // pasta twice five weeks ago vs soup once yesterday: recency wins
        val history = listOf(e("pasta", "Dinner", 35), e("pasta", "Dinner", 36), e("soup", "Dinner", 1))
        assertEquals("soup", MealPatterns.rankFoods(history, "Dinner", today).first())
        // pizza last Sunday (same weekday) beats salad the Monday after, though that was a day more recent
        val weekday = listOf(e("pizza", "Dinner", 7), e("salad", "Dinner", 6))
        assertEquals("pizza", MealPatterns.rankFoods(weekday, "Dinner", today).first())
    }

    @Test
    fun snackAndSnacksAreTheSameMeal() {
        assertTrue(MealPatterns.sameMeal("Snacks", " snack "))
        assertFalse(MealPatterns.sameMeal("Lunch", "Dinner"))
    }

    @Test
    fun usualNeedsTheSameFoodsOnThreeDays() {
        val twice = listOf(e("eggs", "Breakfast", 2), e("toast", "Breakfast", 2), e("eggs", "Breakfast", 4), e("toast", "Breakfast", 4))
        assertNull(MealPatterns.usual(twice, "Breakfast", today))

        val history = twice + listOf(
            e("eggs", "Breakfast", 6, qty = 3f), e("toast", "Breakfast", 6),
            e("oats", "Breakfast", 1) // yesterday was different
        )
        val usual = MealPatterns.usual(history, "Breakfast", today)!!
        assertEquals(setOf("eggs", "toast"), usual.map { it.foodId }.toSet())
        // amounts from the latest of those days (2 days ago: 1 egg serving, not the 3 from 6 days ago)
        assertEquals(1f, usual.first { it.foodId == "eggs" }.quantity, 0f)

        val sc = MealShortcuts.of(history, "Breakfast", today)
        assertEquals(listOf("oats"), sc.yesterday.map { it.foodId })
        assertTrue(sc.showUsual)
    }

    @Test
    fun usualIsHiddenWhenItIsJustYesterdayAgain() {
        val history = (1L..3L).flatMap { listOf(e("eggs", "Breakfast", it), e("toast", "Breakfast", it)) }
        val sc = MealShortcuts.of(history, "Breakfast", today)
        assertEquals(2, sc.yesterday.size)
        assertFalse(sc.showUsual)
        assertFalse(sc.isEmpty)
        assertTrue(MealShortcuts.of(history, "Dinner", today).isEmpty)
    }

    @Test
    fun copiesAreNewEntriesForTheTargetDayAndMeal() {
        val src = listOf(e("eggs", "Breakfast", 1), e("toast", "Breakfast", 1))
        val copies = MealPatterns.copiesFor(src, "2026-10-18", "brunch", "Brunch", now = 1000L)
        assertEquals(2, copies.size)
        assertTrue(copies.all { it.entryDate == "2026-10-18" && it.mealName == "Brunch" && it.syncStatus == FoodEntry.SYNC_STATUS_PENDING_INSERT })
        assertTrue(copies.map { it.id }.intersect(src.map { it.id }.toSet()).isEmpty())
        assertEquals(listOf(1000L, 1001L), copies.map { it.createdAt })
    }
}
