package com.bytelog.app.widget

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.ByteLogApplication
import com.bytelog.app.data.local.model.FoodEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetBehaviourTest {
    private val app: ByteLogApplication get() = ApplicationProvider.getApplicationContext()
    private val today = LocalDate.now().toString()

    @Test fun taps_encodeAndParse() {
        listOf(WidgetDestination.Diary, WidgetDestination.Family, WidgetDestination.Search("Lunch"), WidgetDestination.Scan("Snack"), WidgetDestination.Share("Dinner"))
            .forEach { assertEquals(it, WidgetLinks.parse(WidgetLinks.encode(it))) }
        assertNull(WidgetLinks.parse(null))
        assertNull(WidgetLinks.parse("search:"))
        assertNull(WidgetLinks.parse("nonsense"))
    }

    @Test fun theMealFollowsTheTimeOfDay() {
        assertEquals("Breakfast", suggestedMealAt(LocalTime.of(7, 0)))
        assertEquals("Lunch", suggestedMealAt(LocalTime.of(10, 30)))
        assertEquals("Lunch", suggestedMealAt(LocalTime.of(14, 59)))
        assertEquals("Dinner", suggestedMealAt(LocalTime.of(18, 0)))
        assertEquals("Snack", suggestedMealAt(LocalTime.of(22, 0)))
    }

    @Test fun widgetDataComesFromTodaysDiaryWaterAndGoals() = runBlocking {
        val dao = app.database.foodEntryDao()
        dao.insertEntries(
            listOf(
                FoodEntry(id = "a", foodName = "Oats", mealTypeId = "b", mealName = "Breakfast", quantity = 1f, calories = 300f, protein = 10f, carbs = 50f, fat = 6f, entryDate = today),
                FoodEntry(id = "b", foodName = "Chicken", mealTypeId = "l", mealName = "Lunch", quantity = 1f, calories = 500f, protein = 45f, carbs = 0f, fat = 12f, entryDate = today),
                FoodEntry(id = "gone", foodName = "Deleted", mealTypeId = "l", mealName = "Lunch", quantity = 1f, calories = 900f, protein = 0f, carbs = 0f, fat = 0f, entryDate = today, syncStatus = FoodEntry.SYNC_STATUS_PENDING_DELETE),
                FoodEntry(id = "old", foodName = "Yesterday", mealTypeId = "l", mealName = "Lunch", quantity = 1f, calories = 700f, protein = 0f, carbs = 0f, fat = 0f, entryDate = LocalDate.now().minusDays(1).toString())
            )
        )
        app.settingsRepository.saveMacroTargets(2610f, 190f, 250f, 80f, waterGoalOz = 80f)
        WidgetFamilyCache.save(app, today, listOf(FamilyPerson("Sam", 1400, 2400)))
        val waterBefore = app.database.waterLogDao().getTotalWaterForDate(today).first() / 29.5735f
        app.waterRepository.quickAddWaterOz(today, 20f)
        kotlinx.coroutines.delay(500)

        val d = WidgetDataLoader.load(app, LocalDateTime.of(LocalDate.now(), LocalTime.of(12, 0)))
        assertEquals(800, d.eaten)                       // today's, without the one pending deletion
        assertEquals(2610, d.goal)
        assertEquals(1810, d.left)
        assertEquals(55, d.protein); assertEquals(190, d.proteinGoal)
        assertEquals(waterBefore + 20f, d.waterOz, 0.1f)
        assertEquals(80f, d.waterGoalOz, 0.1f)
        assertEquals("Lunch", d.suggestedMeal)
        assertEquals(listOf("You", "Sam"), d.family.map { it.name })
        assertEquals(800, d.family[0].kcal)
        assertEquals(1400, d.family[1].kcal)
    }

    @Test fun yesterdaysFamilyNumbersAreNotShownAsToday() {
        WidgetFamilyCache.save(app, LocalDate.now().minusDays(1).toString(), listOf(FamilyPerson("Sam", 1400, 2400)))
        val people = WidgetFamilyCache.load(app, today)
        assertEquals(0, people.single().kcal)
        assertEquals(2400, people.single().goal)
    }

    @Test fun tappingTheWaterWidgetLogsABottle() = runBlocking {
        val before = app.database.waterLogDao().getTotalWaterForDate(today).first()
        WidgetActionReceiver().onReceive(app, Intent(WidgetLinks.ACTION_ADD_WATER))
        var after = before
        val end = System.currentTimeMillis() + 10_000
        while (after <= before && System.currentTimeMillis() < end) { kotlinx.coroutines.delay(100); after = app.database.waterLogDao().getTotalWaterForDate(today).first() }
        val oz = app.settingsRepository.defaultContainerOz.first()
        assertTrue("logged ${after - before} ml", after - before > oz * 29f)   // the default bottle (20 oz) was added
    }
}
