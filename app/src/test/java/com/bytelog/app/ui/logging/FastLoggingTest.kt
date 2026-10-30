package com.bytelog.app.ui.logging

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.ui.screens.FastSearchScreen
import com.bytelog.app.ui.theme.ByteLogTheme
import com.bytelog.app.ui.viewmodel.FastSearchViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Search → tap → adjust → log: one tap stages a sensible amount, and the amount and unit change in place. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class FastLoggingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: ByteLogDatabase
    private lateinit var vm: FastSearchViewModel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val today = "2026-10-08"

    private val oats = FoodItem("food-oats", "Rolled Oats", servingSize = 40f, servingUnit = "g", calories = 150f, protein = 5f, carbs = 27f, fat = 3f)
    private val chicken = FoodItem("food-chicken", "Chicken Breast", servingSize = 100f, servingUnit = "g", calories = 165f, protein = 31f, carbs = 0f, fat = 3.6f)
    private val milk = FoodItem("food-milk", "Oat Milk", servingSize = 240f, servingUnit = "ml", calories = 120f, protein = 3f, carbs = 16f, fat = 5f)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        val api = SparkyApiClient(baseUrl = "http://127.0.0.1:9")
        val repo = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, SettingsRepository(context), scope, db.prepBatchDao())
        db.mealTypeDao().insertMealTypes(MealType.DEFAULT_MEAL_TYPES)
        db.foodDao().insertFoods(listOf(oats, chicken, milk))
        // Chicken was last logged as 150 g
        db.foodEntryDao().insertEntry(
            FoodEntry(
                id = "old-1", foodId = chicken.id, foodName = chicken.name, mealTypeId = "lunch", mealName = "Lunch",
                quantity = 150f, unit = "g", calories = 247.5f, protein = 46.5f, carbs = 0f, fat = 5.4f,
                entryDate = "2026-10-07", syncStatus = FoodEntry.SYNC_STATUS_SYNCED
            )
        )
        vm = FastSearchViewModel(repo)
        vm.initContext("breakfast", "Breakfast", today)
    }

    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            check(System.currentTimeMillis() < end) { "Timed out waiting" }
            Thread.sleep(25)
        }
    }

    private fun shot(name: String) {
        val dir = System.getProperty("bytelog.screenshots") ?: return
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun tapRepeatsTheLastAmountAndTappingAgainAddsAnotherPortion() {
        vm.prefetchPortions(listOf(chicken, oats))
        waitFor { vm.lastPortions.value.containsKey(chicken.id) }
        assertEquals(Portion(150f, "g"), vm.portionFor(chicken))
        assertEquals(Portion(40f, "g"), vm.portionFor(oats)) // never logged: one serving

        vm.tapFood(chicken)
        val e = vm.stagedEntries.value.single()
        assertEquals(150f, e.quantity, 0f)
        assertEquals(247.5f, e.calories, 0.1f)
        assertEquals(e.id, vm.activeStagedId.value)

        vm.tapFood(chicken) // a second tap adds to the same line
        val bumped = vm.stagedEntries.value.single()
        assertEquals(300f, bumped.quantity, 0f)
        assertEquals(495f, bumped.calories, 0.5f)
    }

    @Test
    fun changingTheUnitRecalculatesFromTheFood() {
        vm.tapFood(chicken)
        val id = vm.stagedEntries.value.single().id
        assertEquals(listOf("breast", "serving", "g", "oz"), vm.unitOptionsFor(id).take(4))

        val oz = Portions.convert(chicken, Portion(100f, "g"), "oz")
        vm.updateStagedPortion(id, oz)
        val e = vm.stagedEntries.value.single()
        assertEquals("oz", e.unit)
        assertEquals(165f, e.calories, 2f) // still ~100 g of chicken

        vm.updateStagedPortion(id, Portion(2f, "serving"))
        assertEquals(330f, vm.stagedEntries.value.single().calories, 0.5f)
    }

    @Test
    fun foodsAddedBeforePickingTheMealLogToThePickedMealAndDay() {
        // Opened from yesterday's diary with the default meal (Breakfast)
        vm.initContext("breakfast", "Breakfast", "2026-10-06")
        vm.tapFood(oats)
        vm.setTargetMeal("lunch", "lunch")            // then Lunch is picked
        assertEquals("Lunch", vm.stagedEntries.value.single().mealName)
        vm.tapFood(chicken)
        var done = false
        vm.commitStagedTray { done = true }
        waitFor { done }
        val logged = runBlocking { db.foodEntryDao().getEntriesForDateOnce("2026-10-06") }
        assertEquals(2, logged.size)
        assertTrue(logged.all { it.mealName == "Lunch" && it.mealTypeId == "lunch" })
    }

    @Test
    fun searchTapAdjustAndLog() {
        compose.setContent { ByteLogTheme(darkTheme = true) { FastSearchScreen(vm, {}, {}) } }
        vm.setSearchQuery("oat")
        waitFor { compose.onAllNodesWithText("Rolled Oats").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Oat Milk").fetchSemanticsNodes().let { assertEquals(1, it.size) }
        shot("fast_log_1_results")

        compose.onNodeWithText("Rolled Oats").performClick()
        waitFor { compose.onAllNodesWithContentDescription("Amount").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("search clears for the next food", "", vm.searchQuery.value)

        // + steps 40 g → 45 g; the serving chip switches to servings of the same food
        compose.onNodeWithContentDescription("More").performClick()
        waitFor { vm.stagedEntries.value.single().quantity == 45f }
        compose.onNodeWithContentDescription("Unit serving").performClick()
        waitFor { vm.stagedEntries.value.single().unit == "serving" }
        assertEquals(1.13f, vm.stagedEntries.value.single().quantity, 0.006f)
        assertEquals(169f, vm.stagedEntries.value.single().calories, 1f)

        // Add a second food: it becomes the one being edited, the first drops to a chip
        vm.setSearchQuery("milk")
        waitFor { compose.onAllNodesWithText("Oat Milk").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Oat Milk").performClick()
        waitFor { vm.stagedEntries.value.size == 2 }
        compose.onNodeWithContentDescription("Unit cup").performClick()
        waitFor { vm.stagedEntries.value.last().unit == "cup" }
        compose.waitForIdle()
        shot("fast_log_2_tray")
        compose.onNodeWithText("Log 2 to Breakfast").assertExists()

        compose.onNodeWithContentDescription("Log 2 to Breakfast").performClick()
        waitFor { runBlocking { db.foodEntryDao().getEntriesForDateOnce(today) }.size == 2 }
    }
}
