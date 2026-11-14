package com.bytelog.app.data

import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.repository.MealPlan
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MealPlanTest {
    private val today = LocalDate.of(2026, 11, 14)

    @Test fun recognisesMealPlanRequests() {
        listOf(
            "Make me a meal plan for the next 3 days", "can you build a mealplan?", "Plan my meals for the week",
            "plan out my week of food", "Plan my dinners", "I want a weekly menu", "meal prep plan for 5 days please"
        ).forEach { assertTrue(it, MealPlan.wantsPlan(it)) }
        listOf("Log 2 eggs for breakfast", "What did I eat yesterday?", "Is my plan to lose weight working?", "Planet Fitness smoothie")
            .forEach { assertFalse(it, MealPlan.wantsPlan(it)) }
    }

    @Test fun theFormatNoteComesOffAgain() {
        val sent = "Plan my meals for 2 days" + MealPlan.formatNote(today)
        assertTrue(sent.contains("nibl-plan")); assertTrue(sent.contains("2026-11-14"))
        assertEquals("Plan my meals for 2 days", MealPlan.stripNote(sent))
        assertEquals("no note here", MealPlan.stripNote("no note here"))
    }

    private val reply = """
        Here's your plan! Day 1 is protein heavy.

        ```nibl-plan
        {"items":[
          {"date":"2026-11-15","meal":"Breakfast","food":"Greek Yogurt","brand":"Fage","quantity":1,"unit":"cup","calories":190,"protein":18,"carbs":8,"fat":10},
          {"date":"2026-11-15","meal":"Dinner","food":"Chicken Breast","quantity":200,"unit":"g","calories":330,"protein":62,"carbs":0,"fat":7},
          {"date":"2026-11-16","meal":"snack","food":"Apple","calories":95},
          {"date":"2026-11-16","meal":"Lunch","food":"","calories":100},
          {"date":"not a date","meal":"Lunch","food":"Soup","calories":100}
        ]}
        ```
    """.trimIndent()

    @Test fun readsThePlanAndSkipsBadItems() {
        val plan = MealPlan.parse(reply, today)!!
        assertEquals(3, plan.items.size)
        assertEquals(listOf("2026-11-15", "2026-11-16"), plan.days)
        val yogurt = plan.items.first()
        assertEquals("Fage", yogurt.brand); assertEquals(1f, yogurt.quantity); assertEquals("cup", yogurt.unit); assertEquals(18f, yogurt.protein)
        val apple = plan.items.last()
        assertEquals(1f, apple.quantity); assertEquals("serving", apple.unit); assertEquals(0f, apple.protein)
        assertEquals((190 + 330 + 95) / 2, plan.caloriesPerDay)
        assertEquals("Here's your plan! Day 1 is protein heavy.", MealPlan.stripBlock(reply))
    }

    @Test fun noBlockOrBrokenJsonIsNoPlan() {
        assertNull(MealPlan.parse("Just some advice, no plan.", today))
        assertNull(MealPlan.parse("```nibl-plan\n{not json\n```", today))
        assertNull(MealPlan.parse("```nibl-plan\n{\"items\":[]}\n```", today))
    }

    @Test fun daysCanBeNestedOrCountedFromToday() {
        val nested = Json.parseToJsonElement("""{"days":[{"day":1,"meals":[{"meal":"Lunch","food":"Wrap","calories":450}]},
            {"date":"2026-11-20","items":[{"meal":"Dinner","food":"Pasta","calories":600},{"date":"2026-11-21","meal":"Dinner","food":"Tacos","calories":500}]}]}""")
        val plan = MealPlan.fromJson(nested, today)!!
        assertEquals(listOf("2026-11-15", "2026-11-20", "2026-11-21"), plan.items.map { it.date })
        assertEquals(listOf("Wrap", "Pasta", "Tacos"), plan.items.map { it.food })
    }

    @Test fun mealsMatchTheUsersMealTypes() {
        val types = listOf(MealType("b-uuid", "Breakfast", 1), MealType("l-uuid", "Lunch", 2), MealType("d-uuid", "Dinner", 3), MealType("s-uuid", "Snacks", 4))
        assertEquals("b-uuid", MealPlan.mealFor("breakfast", types).id)
        assertEquals("s-uuid", MealPlan.mealFor("Snack", types).id)
        assertEquals("s-uuid", MealPlan.mealFor("Afternoon bite", types).id)
        assertEquals("d-uuid", MealPlan.mealFor("Supper", types).id)
        assertEquals("snacks", MealPlan.mealFor("Pre-workout", emptyList()).id)

        val planned = MealPlan.toPlanned(MealPlan.parse(reply, today)!!, types) { name, _ -> if (name == "Apple") "food-apple" else null }
        assertEquals(listOf("b-uuid", "d-uuid", "s-uuid"), planned.map { it.mealTypeId })
        assertEquals(listOf("Breakfast", "Dinner", "Snacks"), planned.map { it.mealName })
        assertEquals("food-apple", planned.last().foodId)
        assertEquals("2026-11-15", planned.first().entryDate)
        assertEquals(3, planned.map { it.id }.toSet().size)
    }
}
