package com.bytelog.app.data

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.remote.createdEntryId
import com.bytelog.app.data.remote.stableEntryId
import com.bytelog.app.data.repository.EntryMatching
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntryMatchingTest {
    private fun e(id: String, name: String = "Rice", qty: Float = 100f, meal: String = "Lunch", at: Long = 1000L, date: String = "2026-11-24") =
        FoodEntry(id = id, foodName = name, mealTypeId = "x", mealName = meal, quantity = qty, unit = "g",
            calories = 100f, protein = 1f, carbs = 1f, fat = 1f, entryDate = date, createdAt = at)

    @Test fun matchesTheSameFoodAmountMealAndDayOnly() {
        assertEquals(mapOf("p1" to "s1"), EntryMatching.pair(listOf(e("p1")), listOf(e("s1", name = " rice "))))
        assertEquals(emptyMap<String, String>(), EntryMatching.pair(listOf(e("p1")), listOf(e("s1", qty = 150f))))
        assertEquals(emptyMap<String, String>(), EntryMatching.pair(listOf(e("p1")), listOf(e("s1", meal = "Dinner"))))
        assertEquals(emptyMap<String, String>(), EntryMatching.pair(listOf(e("p1")), listOf(e("s1", date = "2026-11-23"))))
    }

    @Test fun pairsOneToOneNearestInTime() {
        val out = EntryMatching.pair(listOf(e("p1", at = 1000), e("p2", at = 5000)), listOf(e("s1", at = 5100), e("s2", at = 1100), e("s3", at = 9000)))
        assertEquals(mapOf("p1" to "s2", "p2" to "s1"), out)
    }

    @Test fun anUnansweredUploadOnlyMatchesSomethingMadeAroundThen() {
        val attempt = 10 * 60_000L
        assertEquals("s1", EntryMatching.findUnanswered(e("p"), attempt, listOf(e("s1", at = attempt + 2_000)))?.id)
        assertNull("made long before the upload: another one", EntryMatching.findUnanswered(e("p"), attempt, listOf(e("s1", at = 1_000))))
    }

    @Test fun createdIdsAreFoundWhereverSparkyPutsThem() {
        fun id(json: String) = createdEntryId(Json.parseToJsonElement(json))
        assertEquals("a", id("""{"id":"a"}"""))
        assertEquals("b", id("""{"data":{"id":"b"}}"""))
        assertEquals("c", id("""[{"id":"c"}]"""))
        assertEquals("d", id("""{"entry":{"id":"d"}}"""))
        assertNull(id("""{"message":"ok"}"""))
    }

    @Test fun anEntryWithoutAnIdGetsTheSameIdEverySync() {
        val o = Json.parseToJsonElement("""{"food_name":"Rice","quantity":1,"meal_type":"lunch"}""") as JsonObject
        assertEquals(stableEntryId("2026-11-24", o), stableEntryId("2026-11-24", o))
    }
}
