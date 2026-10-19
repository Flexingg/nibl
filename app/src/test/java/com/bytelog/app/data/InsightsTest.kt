package com.bytelog.app.data

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.Fasting
import com.bytelog.app.data.repository.FitOption
import com.bytelog.app.data.repository.Remaining
import com.bytelog.app.data.repository.WeeklyReview
import com.bytelog.app.data.repository.WhatFits
import com.bytelog.app.data.repository.parseServerTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class InsightsTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun millis(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

    private fun e(
        name: String, date: String, kcal: Float, protein: Float = 0f, carbs: Float = 0f, fat: Float = 0f,
        time: String? = null, created: Long = 0L, status: String = FoodEntry.SYNC_STATUS_SYNCED
    ) = FoodEntry(
        id = "$name-$date-$created", foodId = "food-$name", foodName = name, mealTypeId = "snack", mealName = "Snack",
        quantity = 1f, unit = "serving", calories = kcal, protein = protein, carbs = carbs, fat = fat,
        entryDate = date, entryTime = time, syncStatus = status, createdAt = created
    )

    // ----- fasting -----

    @Test
    fun lastMealUsesEntryTimeThenLoggedTimeButNotLaterThanItsDay() {
        val now = millis(LocalDateTime.of(2026, 10, 19, 9, 0))
        val dinner = e("Pasta", "2026-10-18", 700f, time = "19:30:00", created = millis(LocalDateTime.of(2026, 10, 19, 8, 0)))
        // logged this morning for yesterday, no time: counts as the end of yesterday at the latest
        val backlogged = e("Cookie", "2026-10-18", 150f, created = millis(LocalDateTime.of(2026, 10, 19, 8, 30)))
        val water = e("Water", "2026-10-19", 0f, created = millis(LocalDateTime.of(2026, 10, 19, 8, 45)))
        val deleted = e("Toast", "2026-10-19", 100f, created = millis(LocalDateTime.of(2026, 10, 19, 8, 50)), status = FoodEntry.SYNC_STATUS_PENDING_DELETE)
        val planned = e("Lunch", "2026-10-19", 500f, time = "12:30")
        val last = Fasting.lastMealAt(listOf(dinner, backlogged, water, deleted, planned), now, zone)!!
        assertEquals(millis(LocalDateTime.of(2026, 10, 19, 0, 0)) - 1, last)
        assertEquals("9h 0m", Fasting.format(now - last))
        assertEquals("45m", Fasting.format(45 * 60_000L))
        assertNull(Fasting.lastMealAt(listOf(water), now, zone))
    }

    @Test
    fun serverTimesParse() {
        assertEquals(java.time.Instant.parse("2026-10-17T21:28:17.155Z").toEpochMilli(), parseServerTime("2026-10-17T21:28:17.155Z"))
        assertNotNull(parseServerTime("2026-10-17T21:28:17+02:00"))
        assertNull(parseServerTime("yesterday"))
        assertNull(parseServerTime(null))
    }

    // ----- what fits -----

    private val history = listOf(
        e("Greek Yogurt", "2026-10-15", 150f, protein = 20f, carbs = 8f, fat = 4f, created = 1),
        e("Greek Yogurt", "2026-10-16", 150f, protein = 20f, carbs = 8f, fat = 4f, created = 2),
        e("Greek Yogurt", "2026-10-17", 150f, protein = 20f, carbs = 8f, fat = 4f, created = 3),
        e("Chicken Breast", "2026-10-16", 250f, protein = 46f, carbs = 0f, fat = 5f, created = 4),
        e("Chicken Breast", "2026-10-17", 250f, protein = 46f, carbs = 0f, fat = 5f, created = 5),
        e("Pizza", "2026-10-17", 900f, protein = 35f, carbs = 100f, fat = 38f, created = 6),
        e("Apple", "2026-10-17", 95f, protein = 0.5f, carbs = 25f, fat = 0.3f, created = 7),
        e("Chips", "2026-10-14", 300f, protein = 3f, carbs = 30f, fat = 20f, created = 8)
    )

    @Test
    fun suggestionsFitTheCaloriesLeftAndFavourProteinWhenItIsStillNeeded() {
        val options = WhatFits.suggest(Remaining(450f, 60f, 60f, 15f), WhatFits.candidates(history))
        assertTrue(options.isNotEmpty())
        assertTrue(options.all { it.calories <= 450f * 1.05f })
        assertTrue("pizza is over", options.none { o -> o.foods.any { it.foodName == "Pizza" } })
        // Yogurt + chicken: 400 kcal, 66 g protein covers it best
        assertEquals(setOf("Greek Yogurt", "Chicken Breast"), options.first().foods.map { it.foodName }.toSet())
        // each food at most once across suggestions
        val names = options.flatMap { o -> o.foods.map { it.foodName } }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun nothingWhenAlmostAtTheGoal() {
        assertTrue(WhatFits.suggest(Remaining(80f, 5f, 10f, 2f), WhatFits.candidates(history)).isEmpty())
        assertTrue(WhatFits.suggest(Remaining(600f, 0f, 80f, 20f), emptyList()).isEmpty())
        // protein already met: fills calories instead, without going over
        val opts = WhatFits.suggest(Remaining(320f, 0f, 60f, 20f), WhatFits.candidates(history))
        assertTrue(opts.isNotEmpty() && opts.all { it.calories <= 336f })
        assertEquals(400f, FitOption(history.take(1) + history[3]).calories, 0f)
    }

    // ----- weekly review -----

    @Test
    fun weekAveragesTargetsStreakAndWeight() {
        val today = LocalDate.of(2026, 10, 19) // Monday; the week is Oct 12–18
        val entries = (1L..7L).flatMap { ago ->
            val d = today.minusDays(ago).toString()
            when (ago) {
                3L -> emptyList() // a day off
                1L -> listOf(e("Big day", d, 2600f, protein = 120f))
                else -> listOf(e("Day", d, 2000f, protein = 150f))
            }
        } + e("Breakfast", today.toString(), 400f, protein = 30f) + e("Old", "2026-10-01", 2000f)
        val weights = listOf(LocalDate.of(2026, 10, 11) to 181.0f, LocalDate.of(2026, 10, 15) to 180.2f, LocalDate.of(2026, 10, 18) to 179.6f)
        val r = WeeklyReview.of(entries, today, calorieGoal = 2000f, proteinGoal = 150f, weights = weights)
        assertEquals(LocalDate.of(2026, 10, 12), r.start)
        assertEquals(LocalDate.of(2026, 10, 18), r.end)
        assertEquals(6, r.daysLogged)
        assertEquals(5, r.daysOnTarget)            // the 2,600 day is over by more than 10%
        assertEquals(5, r.proteinDays)
        assertEquals(2100, r.avgCalories)          // (5 × 2000 + 2600) / 6
        assertEquals(3, r.streak)                  // today, yesterday, the day before; the 16th was missed
        assertEquals(-1.4f, r.weightChange!!, 0.01f)
        assertTrue(WeeklyReview.isReviewDay(today))
        assertTrue(!WeeklyReview.isReviewDay(today.plusDays(1)))
        assertEquals("Your week: 5/7 days on target, 2,100 kcal and 145 g protein a day. 3-day logging streak.", WeeklyReview.summaryLine(r))
    }

    // ----- photo estimate -----

    @Test
    fun photoEstimateReadsEachFood() {
        val json = """
            {"meal_summary":"Chicken Rice Bowl","overall_confidence":"high","confidence_reason":"",
             "items":[
               {"name":"grilled chicken thigh","canonical_name":"chicken thigh","estimated_grams":150,"portion_description":"1 thigh",
                "calories_kcal":290,"protein_g":38,"carbs_g":0,"fat_g":15,"fiber_g":0,"item_confidence":"high"},
               {"name":"white rice","canonical_name":"rice","estimated_grams":180,"portion_description":"1 cup cooked",
                "calories_kcal":234,"protein_g":4.3,"carbs_g":51,"fat_g":0.5,"item_confidence":"low"}
             ],
             "totals":{"calories_kcal":524,"protein_g":42.3,"carbs_g":51,"fat_g":15.5,"fiber_g":0,"sugar_g":0,"total_grams":330},
             "clarifying_questions":["Was the chicken skin on?"]}
        """.trimIndent()
        val est = SparkyApiClient.parsePhotoEstimate(json)!!
        assertEquals("Chicken Rice Bowl", est.summary)
        assertEquals(2, est.items.size)
        assertEquals(150f, est.items[0].grams, 0f)
        assertEquals("low", est.items[1].confidence)
        assertNull(est.confidenceReason)
        assertEquals(listOf("Was the chicken skin on?"), est.questions)

        // older servers: totals only → one item
        val old = SparkyApiClient.parsePhotoEstimate("""{"meal_summary":"Salad","totals":{"calories_kcal":300,"protein_g":10,"carbs_g":20,"fat_g":18,"total_grams":250}}""")!!
        assertEquals(listOf("Salad"), old.items.map { it.name })
        assertEquals(250f, old.items.single().grams, 0f)

        assertNull(SparkyApiClient.parsePhotoEstimate("not json"))
        assertNull(SparkyApiClient.parsePhotoEstimate("{}"))
    }
}
