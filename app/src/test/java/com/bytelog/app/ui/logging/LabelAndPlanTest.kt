package com.bytelog.app.ui.logging

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.runtime.collectAsState
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.remote.LabelScan
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.MealPrepRepository
import com.bytelog.app.data.repository.MealTemplateRepository
import com.bytelog.app.data.repository.PlanRepository
import com.bytelog.app.data.repository.RecipeRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.WaterRepository
import com.bytelog.app.data.repository.WeightRepository
import com.bytelog.app.recipes.HouseholdServing
import com.bytelog.app.ui.components.CustomFoodForm
import com.bytelog.app.ui.screens.DiaryScreen
import com.bytelog.app.ui.theme.ByteLogTheme
import com.bytelog.app.ui.viewmodel.DiaryViewModel
import com.bytelog.app.ui.viewmodel.FastSearchViewModel
import com.bytelog.app.ui.viewmodel.RecipesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import java.util.concurrent.TimeUnit
import com.bytelog.app.data.remote.createCustomFood

/** Scanning a nutrition label into a new food, and planning food for days ahead. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class LabelAndPlanTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var server: MockWebServer
    private lateinit var db: ByteLogDatabase
    private lateinit var repo: FoodRepository
    private lateinit var plans: PlanRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var labelResponse = MockResponse().setBody(
        """{"name":"Choco Bites","brand":"Nibbles","serving_size":28,"serving_unit":"g","calories":140,"protein":2,"carbs":19,"fat":7,"fiber":1,"sodium":90}"""
    )
    private val today = LocalDate.now()

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/foods/scan-label" -> labelResponse
                request.method == "POST" && request.path == "/api/foods" -> MockResponse().setBody(
                    """{"id":"new-food","name":"Choco Bites","brand":"Nibbles","default_variant":{"id":"v1","serving_size":28,"serving_unit":"g","calories":140,"protein":2,"carbs":19,"fat":7}}"""
                )
                request.path == "/api/foods/food-variants" -> MockResponse().setResponseCode(201).setBody("{}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        val api = SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        repo = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, SettingsRepository(context), scope, db.prepBatchDao())
        db.mealTypeDao().insertMealTypes(MealType.DEFAULT_MEAL_TYPES)
        plans = PlanRepository(db.plannedEntryDao(), repo)
    }

    @After fun tearDown() { server.shutdown(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            check(System.currentTimeMillis() < end) { "Timed out waiting" }
            Thread.sleep(20)
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
    fun labelsAreReadIncludingFencedAndPartialAnswers() {
        val fenced = LabelScan.parse("```json\n{\"name\":\"Bar\",\"serving_size\":\"40\",\"serving_unit\":\"G\",\"calories\":\"190 kcal\",\"protein\":null}\n```")!!
        assertEquals(40f, fenced.servingSize)
        assertEquals("g", fenced.servingUnit)
        assertEquals(190f, fenced.calories)
        assertNull(fenced.protein)
        assertNull("no calories: not a nutrition label", LabelScan.parse("""{"name":"A photo of a cat"}"""))
    }

    @Test
    fun aScannedLabelFillsTheFormAndPiecesBecomeAServing() {
        val vm = FastSearchViewModel(repo).apply { initContext("snacks", "Snacks", today.toString()) }
        var saved: List<Any?>? = null
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                val state = vm.labelScan.collectAsState().value
                CustomFoodForm(
                    onDismiss = {},
                    onCreateCustomFood = { n, b, ss, su, c, p, cb, f, fi, h -> saved = listOf(n, b, ss, su, c, p, cb, f, fi, h) },
                    labelScan = state,
                    onScanLabel = { vm.scanLabel(Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)) }
                )
            }
        }
        compose.onNodeWithText("Scan nutrition label").assertExists()
        // (Started directly: the reading spinner never lets the screen settle, which a test click waits for)
        vm.scanLabel(Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888))
        waitFor { vm.labelScan.value is FastSearchViewModel.LabelScanState.Read }
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasSetTextAction() and hasText("Choco Bites")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasSetTextAction() and hasText("140")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasSetTextAction() and hasText("28")).fetchSemanticsNodes().isNotEmpty())
        // The label said "2 cookies (28 g)": a serving is 2 cookies
        compose.onNode(hasSetTextAction() and hasText("How many")).performScrollTo().performTextInput("2")
        compose.onNode(hasSetTextAction() and hasText("cookies, slices…")).performTextInput("cookies")
        shot("label_scan_filled")
        compose.onNodeWithText("Save Custom Food to Sparky").performScrollTo().performClick()
        val s = saved!!
        assertEquals("Choco Bites", s[0]); assertEquals("Nibbles", s[1]); assertEquals(28f, s[2]); assertEquals("g", s[3]); assertEquals(140f, s[4])
        assertEquals(HouseholdServing("cookie", 0.5f, 2f), s[9])

        // Saved: the food keeps its household serving, and the tray gets "2 cookies"
        vm.createCustomFood("Choco Bites", "Nibbles", 28f, "g", 140f, 2f, 19f, 7f, 1f, household = s[9] as HouseholdServing)
        waitFor { vm.stagedEntries.value.isNotEmpty() }
        val staged = vm.stagedEntries.value.single()
        assertEquals(2f, staged.quantity, 0f); assertEquals("cookie", staged.unit); assertEquals(140f, staged.calories, 0.5f)
        runBlocking { assertEquals("cookie|0.5|2.0", db.foodDao().getFoodById("new-food")!!.householdServings) }
        assertTrue(server.requestCount >= 3)   // scan, create, the cookie serving
    }

    @Test
    fun aLabelThatCantBeReadSaysWhy() {
        labelResponse = MockResponse().setResponseCode(422).setBody("""{"error":"No AI service configured"}""")
        val vm = FastSearchViewModel(repo)
        vm.scanLabel(Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888))
        waitFor { vm.labelScan.value is FastSearchViewModel.LabelScanState.Failed }
        assertTrue((vm.labelScan.value as FastSearchViewModel.LabelScanState.Failed).message.contains("AI service set up in Sparky"))
    }

    @Test
    fun foodAddedToADayAheadIsPlannedThenTickedOffWhenEaten() {
        val tomorrow = today.plusDays(1).toString()
        val vm = FastSearchViewModel(repo, plans = plans).apply { initContext("breakfast", "Breakfast", tomorrow) }
        assertTrue(vm.isPlanning)
        val oats = com.bytelog.app.data.local.model.FoodItem("f-oats", "Rolled Oats", servingSize = 40f, servingUnit = "g", calories = 150f, protein = 5f, carbs = 27f, fat = 3f)
        vm.stageFood(oats)
        var done = false
        vm.commitStagedTray { done = true }
        waitFor { done }
        // Planned, not logged: nothing counts towards tomorrow yet
        runBlocking {
            assertEquals(0, db.foodEntryDao().getEntriesForDateOnce(tomorrow).size)
            assertEquals("Rolled Oats", plans.forDate(tomorrow).first().single().foodName)
        }
        // On the day: ticking it off logs it
        runBlocking {
            val planned = plans.forDate(tomorrow).first()
            plans.eat(planned)
            val logged = db.foodEntryDao().getEntriesForDateOnce(tomorrow)
            assertEquals(listOf("Rolled Oats"), logged.map { it.foodName })
            assertEquals(FoodEntry.SYNC_STATUS_PENDING_INSERT, logged.single().syncStatus)
            assertEquals(150f, logged.single().calories, 0f)
            assertTrue(plans.forDate(tomorrow).first().isEmpty())
        }
        // Today still logs straight away
        val todayVm = FastSearchViewModel(repo, plans = plans).apply { initContext("breakfast", "Breakfast", today.toString()) }
        assertTrue(!todayVm.isPlanning)
    }

    @Test
    fun theDiaryShowsTodaysPlanAndTicksItOff() {
        runBlocking {
            plans.plan(listOf(
                FoodEntry(id = "p1", foodName = "Greek Yogurt", mealTypeId = "breakfast", mealName = "Breakfast", quantity = 1f, unit = "cup", calories = 220f, protein = 20f, carbs = 9f, fat = 11f, entryDate = today.toString()),
                FoodEntry(id = "p2", foodName = "Chicken Salad", mealTypeId = "lunch", mealName = "Lunch", quantity = 1f, unit = "bowl", calories = 480f, protein = 40f, carbs = 18f, fat = 25f, entryDate = today.toString())
            ))
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val api = SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        val settings = SettingsRepository(context)
        val templates = MealTemplateRepository(db.mealTemplateDao(), api, scope)
        val recipesVm = RecipesViewModel(RecipeRepository(db.recipeDetailsDao(), db.ingredientAliasDao(), db.foodDao(), repo, templates, api),
            MealPrepRepository(db.prepBatchDao(), repo, api, scope), templates, repo)
        val diaryVm = DiaryViewModel(repo, WaterRepository(db.waterLogDao(), api, settings, scope), settings,
            WeightRepository(db.weightDao(), api, settings, scope), templates, null, plans)
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                DiaryScreen(diaryVm, recipesVm, onOpenRecipe = {}, onImportRecipe = {}, onNavigateToSearch = { _, _, _ -> }, onNavigateToBarcode = { _, _, _ -> }, onNavigateToChat = {}, onNavigateToSettings = {}, onOpenRecentMeals = {})
            }
        }
        waitFor { diaryVm.plannedEntries.value.size == 2 }
        val lists = compose.onAllNodes(androidx.compose.ui.test.hasScrollToKeyAction())
        (0 until lists.fetchSemanticsNodes().size).firstOrNull { i -> runCatching { lists[i].performScrollToKey("planned") }.isSuccess }
        waitFor { compose.onAllNodesWithText("700 kcal planned").fetchSemanticsNodes().isNotEmpty() }
        shot("planned_today")
        compose.onAllNodesWithContentDescription("Ate Greek Yogurt").fetchSemanticsNodes().single()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Ate Greek Yogurt")).performClick()
        waitFor { runBlocking { db.foodEntryDao().getEntriesForDateOnce(today.toString()) }.any { it.foodName == "Greek Yogurt" } }
        waitFor { diaryVm.plannedEntries.value.map { it.foodName } == listOf("Chicken Salad") }
    }
}
