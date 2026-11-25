package com.bytelog.app.ui.logging

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.MealTemplateRepository
import com.bytelog.app.data.repository.MealPrepRepository
import com.bytelog.app.data.repository.RecipeRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.WaterRepository
import com.bytelog.app.data.repository.WeightRepository
import com.bytelog.app.ui.screens.DiaryScreen
import com.bytelog.app.ui.screens.FastSearchScreen
import com.bytelog.app.ui.theme.ByteLogTheme
import com.bytelog.app.ui.viewmodel.DiaryViewModel
import com.bytelog.app.ui.viewmodel.FastSearchViewModel
import com.bytelog.app.ui.viewmodel.RecipesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * The fewer-taps logging changes: suggestions that know the meal, swipe to log, undo, one-tap "same as
 * yesterday" / "your usual", amounts changed in place in the diary, the meal picker on the Log button and water
 * sizes on a long press.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class QuickerLoggingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: ByteLogDatabase
    private lateinit var repo: FoodRepository
    private lateinit var vm: FastSearchViewModel
    private lateinit var diaryVm: DiaryViewModel
    private lateinit var recipesVm: RecipesViewModel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val today = LocalDate.now()
    private fun day(ago: Long) = today.minusDays(ago).toString()

    private val oats = FoodItem("food-oats", "Rolled Oats", servingSize = 40f, servingUnit = "g", calories = 150f, protein = 5f, carbs = 27f, fat = 3f)
    private val eggs = FoodItem("food-eggs", "Eggs", servingSize = 1f, servingUnit = "serving", calories = 70f, protein = 6f, carbs = 0f, fat = 5f)
    private val toast = FoodItem("food-toast", "Sourdough Toast", servingSize = 1f, servingUnit = "slice", calories = 120f, protein = 4f, carbs = 22f, fat = 1f)
    private val chicken = FoodItem("food-chicken", "Chicken Breast", servingSize = 100f, servingUnit = "g", calories = 165f, protein = 31f, carbs = 0f, fat = 3.6f)
    private val rice = FoodItem("food-rice", "Jasmine Rice", servingSize = 150f, servingUnit = "g", calories = 200f, protein = 4f, carbs = 44f, fat = 0.4f)

    private fun logged(food: FoodItem, meal: String, ago: Long, qty: Float = food.servingSize) = FoodEntry(
        id = "${food.id}-$meal-$ago", foodId = food.id, foodName = food.name, mealTypeId = meal.lowercase(), mealName = meal,
        quantity = qty, unit = food.servingUnit, calories = food.calories * qty / food.servingSize,
        protein = food.protein, carbs = food.carbs, fat = food.fat, entryDate = day(ago),
        syncStatus = FoodEntry.SYNC_STATUS_SYNCED, createdAt = 1_000_000L + ago
    )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        val api = SparkyApiClient(baseUrl = "http://127.0.0.1:9")
        val settings = SettingsRepository(context)
        repo = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, settings, scope, db.prepBatchDao())
        db.mealTypeDao().insertMealTypes(MealType.DEFAULT_MEAL_TYPES)
        db.foodDao().insertFoods(listOf(oats, eggs, toast, chicken, rice))
        // Breakfast: eggs and toast on three earlier days (the usual), oats yesterday. Lunch: chicken and rice.
        db.foodEntryDao().insertEntries(
            listOf(2L, 3L, 4L).flatMap { listOf(logged(eggs, "Breakfast", it, 2f), logged(toast, "Breakfast", it)) } +
                logged(oats, "Breakfast", 1) +
                (1L..4L).flatMap { listOf(logged(chicken, "Lunch", it, 150f), logged(rice, "Lunch", it)) }
        )
        vm = FastSearchViewModel(repo)
        vm.initContext("breakfast", "Breakfast", today.toString())
        val templates = MealTemplateRepository(db.mealTemplateDao(), api, scope)
        val recipes = RecipeRepository(db.recipeDetailsDao(), db.ingredientAliasDao(), db.foodDao(), repo, templates, api)
        recipesVm = RecipesViewModel(recipes, MealPrepRepository(db.prepBatchDao(), repo, api, scope), templates, repo)
        diaryVm = DiaryViewModel(
            repo, WaterRepository(db.waterLogDao(), api, settings, scope), settings,
            WeightRepository(db.weightDao(), api, settings, scope), templates, null
        )
    }

    @After
    fun tearDown() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
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

    /** Scrolls the diary list to the item with [key]. */
    private fun scrollTo(key: Any) {
        val lists = compose.onAllNodes(androidx.compose.ui.test.hasScrollToKeyAction())
        (0 until lists.fetchSemanticsNodes().size).firstOrNull { i -> runCatching { lists[i].performScrollToKey(key) }.isSuccess }
        compose.waitForIdle()
    }

    private fun todays() = runBlocking { db.foodEntryDao().getEntriesForDate(today.toString()).first() }

    @Test
    fun suggestionsFollowTheMeal() = runBlocking {
        assertEquals(listOf("food-eggs", "food-toast", "food-oats"), repo.getMealSuggestions("Breakfast").first().take(3).map { it.id })
        assertEquals(listOf("food-chicken", "food-rice"), repo.getMealSuggestions("Lunch").first().take(2).map { it.id })
    }

    @Test
    fun swipeLogsAtOnceAndUndoTakesItBack() {
        var logged: List<FoodEntry> = emptyList()
        vm.logNow(chicken) { logged = it }
        waitFor { logged.isNotEmpty() }
        assertEquals(1, todays().size)
        assertEquals("Breakfast", todays().single().mealName)
        assertTrue(vm.stagedEntries.value.isEmpty())
        vm.undoLogged(logged)
        waitFor { todays().isEmpty() }
    }

    @Test
    fun loggingTheTrayLeavesAnUndoForTheDiary() {
        vm.tapFood(oats)
        vm.tapFood(eggs)
        var done = false
        vm.commitStagedTray { done = true }
        waitFor { done }
        val batch = repo.pendingUndo.value
        assertNotNull(batch)
        assertEquals("Logged 2 to Breakfast", batch!!.label)
        runBlocking { repo.undoLogged(batch.entries) }
        assertTrue(todays().isEmpty())
    }

    @Test
    fun searchHasTheFieldAtTheBottomAndTheMealOnTheLogButton() {
        compose.setContent { ByteLogTheme(darkTheme = true) { FastSearchScreen(vm, {}, {}) } }
        waitFor { compose.onAllNodesWithText("Eggs").fetchSemanticsNodes().isNotEmpty() }
        shot("quick_1_search_suggested")

        compose.onAllNodesWithText("Eggs").onFirst().performClick()
        waitFor { vm.stagedEntries.value.size == 1 }
        compose.onNodeWithContentDescription("Change meal").performClick()
        compose.waitForIdle()
        shot("quick_2_meal_menu")
        compose.onAllNodesWithText("Lunch").onLast().performClick()
        waitFor { vm.stagedEntries.value.single().mealName == "Lunch" }
        compose.onNodeWithContentDescription("Log 1 to Lunch").assertExists()

        // Swiping a suggestion logs it straight away
        compose.onAllNodesWithText("Sourdough Toast").onFirst().performTouchInput { swipeRight() }
        waitFor { todays().any { it.foodId == toast.id } }
        shot("quick_3_swiped")
    }

    @Test
    fun whileSearchingTheRestFoldsAwayAndFoodIsStampedWithWhenItWasEaten() {
        compose.setContent { ByteLogTheme(darkTheme = true) { FastSearchScreen(vm, {}, {}) } }
        waitFor { compose.onAllNodesWithText("Eggs").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("Search, scan, speak or add your own").fetchSemanticsNodes().isNotEmpty())

        vm.setSearchQuery("chick")
        waitFor { compose.onAllNodesWithText("Search, scan, speak or add your own").fetchSemanticsNodes().isEmpty() }
        assertTrue("one slim line keeps the context", compose.onAllNodesWithText("Logging to Breakfast", substring = true).fetchSemanticsNodes().isNotEmpty())
        shot("quick_search_focus_mode")
        vm.setSearchQuery("")
        waitFor { compose.onAllNodesWithText("Search, scan, speak or add your own").fetchSemanticsNodes().isNotEmpty() }

        // Logged yesterday at 7:30 PM
        compose.onAllNodesWithText("Eggs").onFirst().performClick()
        waitFor { vm.stagedEntries.value.size == 1 }
        vm.closeStagedEditor()
        compose.waitForIdle()
        compose.onNodeWithText("Today · now").assertExists()
        vm.setEatenAt(com.bytelog.app.ui.components.EatenAt(today.minusDays(1), java.time.LocalTime.of(19, 30)))
        compose.waitForIdle()
        shot("quick_when_chip")
        var done = false
        vm.commitStagedTray { done = true }
        waitFor { done }
        val logged = runBlocking { db.foodEntryDao().getEntriesForDate(today.minusDays(1).toString()).first() }.single { it.foodId == eggs.id && it.syncStatus != FoodEntry.SYNC_STATUS_SYNCED }
        assertEquals("19:30:00", logged.entryTime)
    }

    @Test
    fun foodLoggedForTodayGetsTheTimeItWasLogged() = runBlocking {
        repo.commitMultiAdd(listOf(logged(oats, "Breakfast", 0).copy(id = "now-1", entryTime = null), logged(oats, "Breakfast", 3).copy(id = "old-1", entryTime = null)))
        val now = db.foodEntryDao().getEntryById("now-1")!!
        assertTrue("today: stamped ${now.entryTime}", now.entryTime != null)
        assertEquals("another day: no time made up", null, db.foodEntryDao().getEntryById("old-1")!!.entryTime)
    }

    @Test
    fun diaryShortcutsInlineAmountsAndWaterSizes() {
        // Lunch is logged today; breakfast is not, so it offers yesterday's oats and the usual eggs and toast
        runBlocking { repo.commitMultiAdd(listOf(logged(chicken, "Lunch", 0, 150f).copy(id = "today-chicken", syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT))) }
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                DiaryScreen(diaryVm, recipesVm, onOpenRecipe = {}, onImportRecipe = {}, onNavigateToSearch = { _, _, _ -> }, onNavigateToBarcode = { _, _, _ -> }, onNavigateToChat = {}, onNavigateToSettings = {}, onOpenRecentMeals = {})
            }
        }
        // On Sundays and Mondays the weekly review sits above; scroll down to the meal shortcuts
        waitFor { diaryVm.mealShortcuts.value.isNotEmpty() }
        scrollTo("quick_meals")
        waitFor { compose.onAllNodesWithText("Your usual").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Same as yesterday").assertExists()
        shot("quick_4_diary_shortcuts")

        // Tapping a food opens its amount editor in place; + changes it, saved after a short pause
        scrollTo("Lunch")
        compose.onNodeWithText("Chicken Breast").performClick()
        compose.mainClock.advanceTimeBy(400) // past the double-tap window (a double tap still adds a serving)
        waitFor { compose.onAllNodesWithContentDescription("More").fetchSemanticsNodes().isNotEmpty() }
        shot("quick_5_inline_editor")
        compose.onNodeWithContentDescription("More").performClick()
        compose.mainClock.advanceTimeBy(800) // saved after a short pause
        waitFor { (todays().firstOrNull { it.foodId == chicken.id }?.quantity ?: 0f) > 150f }
        val bigger = todays().first { it.foodId == chicken.id }
        assertEquals(165f * bigger.quantity / 100f, bigger.calories, 0.5f)
        compose.onNodeWithText("Done").performClick()
        compose.mainClock.advanceTimeBy(400)

        // One tap logs the usual breakfast, with an undo
        scrollTo("quick_meals")
        compose.onNodeWithText("Your usual").performClick()
        waitFor { todays().count { it.mealName == "Breakfast" } == 2 }
        assertEquals(2f, todays().first { it.foodId == eggs.id }.quantity, 0f)
        waitFor { compose.onAllNodesWithText("Logged 2 to Breakfast").fetchSemanticsNodes().isNotEmpty() }
        shot("quick_6_logged_usual")
        compose.onNodeWithText("Undo").performClick()
        waitFor { todays().none { it.mealName == "Breakfast" } }

        // Long press on the drink button offers other sizes
        // (the full water card, or the narrow pill when the list has scrolled)
        val pill = compose.onAllNodesWithContentDescription("Log 20 oz").fetchSemanticsNodes().isNotEmpty()
        (if (pill) compose.onAllNodesWithContentDescription("Log 20 oz") else compose.onAllNodesWithText("+20 oz")).onFirst()
            .performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(300)
        waitFor { compose.onAllNodesWithText("Pint").fetchSemanticsNodes().isNotEmpty() }
        shot("quick_7_water_sizes")
    }
}
