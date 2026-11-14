package com.bytelog.app.data

import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.PlannedEntry
import com.bytelog.app.data.repository.MealPlan
import com.bytelog.app.data.repository.PlanChange
import com.bytelog.app.data.repository.PlanChangeLine
import com.bytelog.app.data.repository.PlanEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlanEditsTest {
    private val today = LocalDate.of(2026, 11, 14)
    private val yog = PlannedEntry("11111111-aaaa-bbbb-cccc-000000000001", "f-yog", "Greek Yogurt", "Fage", "breakfast", "Breakfast", 1f, "serving", 150f, 15f, 6f, 5f, "2026-11-15")
    private val salmon = PlannedEntry("22222222-aaaa-bbbb-cccc-000000000002", null, "Salmon", null, "dinner", "Dinner", 150f, "g", 310f, 34f, 0f, 18f, "2026-11-15")
    private val planned = listOf(yog, salmon)
    private val yogurtFood = FoodItem("f-yog", "Greek Yogurt", servingSize = 170f, servingUnit = "g", calories = 150f, protein = 15f, carbs = 6f, fat = 5f)

    @Test fun recognisesRequestsToChangeThePlan() {
        listOf(
            "Swap tomorrow's dinner for something cheaper", "Remove the salmon from my plan", "Double my breakfast on Monday",
            "Can you make Tuesday's lunch lighter?", "move the yogurt to snacks in my meal plan", "add a protein shake to this week's plan"
        ).forEach { assertTrue(it, PlanEdits.wantsEdit(it)) }
        listOf("Log 2 eggs", "What's my protein today?", "Make me a meal plan for 3 days").forEach { assertFalse(it, PlanEdits.wantsEdit(it)) }
    }

    @Test fun theNoteListsThePlanWithRefsAndComesOffAgain() {
        val note = PlanEdits.formatNote(planned, today)
        assertTrue(note, note.contains("11111111: 2026-11-15 (Sun) Breakfast | Greek Yogurt (Fage) | 1 serving | 150 kcal"))
        assertTrue(note.contains("22222222: 2026-11-15 (Sun) Dinner | Salmon | 150 g"))
        assertTrue(note.contains("nibl-plan-edit"))
        val sent = "Swap the salmon" + MealPlan.formatNote(today) + note
        assertEquals("Swap the salmon", MealPlan.stripNote(sent))
    }

    private val reply = """
        Swapped the salmon for a tofu bowl and doubled your yogurt.

        ```nibl-plan-edit
        {"changes":[
          {"op":"remove","ref":"22222222"},
          {"op":"add","date":"2026-11-15","meal":"Dinner","food":"Tofu Bowl","quantity":1,"unit":"bowl","calories":450,"protein":28,"carbs":50,"fat":14},
          {"op":"update","ref":"11111111","quantity":2},
          {"op":"update","ref":"deadbeef","quantity":3},
          {"op":"explode"}
        ]}
        ```
    """.trimIndent()

    @Test fun readsTheChangesAndDescribesThem() {
        val edit = PlanEdits.parse(reply, today)!!
        assertEquals(4, edit.changes.size)
        assertTrue(edit.changes[0] is PlanChange.Remove)
        assertEquals("Tofu Bowl", (edit.changes[1] as PlanChange.Add).item.food)
        assertEquals("Swapped the salmon for a tofu bowl and doubled your yogurt.", PlanEdits.stripBlock(reply))
        val lines = PlanEdits.describe(edit, planned)
        assertEquals(listOf(PlanChangeLine.Kind.REMOVE, PlanChangeLine.Kind.ADD, PlanChangeLine.Kind.UPDATE, PlanChangeLine.Kind.MISSING), lines.map { it.kind })
        assertTrue(lines[0].text, lines[0].text.startsWith("Remove Salmon"))
        assertTrue(lines[2].text, lines[2].text.contains("1 serving → 2 servings"))
        assertNull(PlanEdits.parse("no block here", today))
    }

    @Test fun workingOutTheEdit() {
        val edit = PlanEdits.parse(reply, today)!!
        val types = MealType.DEFAULT_MEAL_TYPES
        val applied = PlanEdits.plan(edit, planned, types) { if (it == "f-yog") yogurtFood else null }
        assertEquals(listOf(salmon), applied.removed)
        // The yogurt's amount recalculates from its food
        val y = applied.after.single()
        assertEquals(2f, y.quantity); assertEquals(300f, y.calories, 0.1f); assertEquals(30f, y.protein, 0.1f)
        assertEquals(listOf(yog), applied.before)
        val tofu = applied.added.single()
        assertEquals("Tofu Bowl", tofu.foodName); assertEquals("dinner", tofu.mealTypeId); assertEquals("2026-11-15", tofu.entryDate)
        assertEquals(3, applied.count)

        // A food the phone doesn't have scales with its amount; moving changes meal and day
        val move = PlanEdits.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"changes":[{"op":"update","ref":"22222222","quantity":225,"meal":"lunch","date":"2026-11-16"}]}"""
        ), today)!!
        val moved = PlanEdits.plan(move, planned, types).after.single()
        assertEquals(465f, moved.calories, 0.5f); assertEquals("Lunch", moved.mealName); assertEquals("2026-11-16", moved.entryDate)
    }
}
