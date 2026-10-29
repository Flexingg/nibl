package com.bytelog.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.HomeLayout
import com.bytelog.app.data.repository.HomeSection
import com.bytelog.app.data.repository.MealPrepRepository
import com.bytelog.app.data.repository.MealTemplateRepository
import com.bytelog.app.data.repository.RecipeRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.WaterRepository
import com.bytelog.app.data.repository.WeightRepository
import com.bytelog.app.ui.diary.HomeLayoutEditor
import com.bytelog.app.ui.screens.DiaryScreen
import com.bytelog.app.ui.theme.ByteLogTheme
import com.bytelog.app.ui.viewmodel.DiaryViewModel
import com.bytelog.app.ui.viewmodel.RecipesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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

/** Reordering the home screen: the editor, and the diary following the saved order. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class HomeLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: ByteLogDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var diaryVm: DiaryViewModel
    private lateinit var recipesVm: RecipesViewModel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        val api = SparkyApiClient(baseUrl = "http://127.0.0.1:9")
        settings = SettingsRepository(context)
        val repo = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, settings, scope, db.prepBatchDao())
        db.mealTypeDao().insertMealTypes(MealType.DEFAULT_MEAL_TYPES)
        val templates = MealTemplateRepository(db.mealTemplateDao(), api, scope)
        val recipes = RecipeRepository(db.recipeDetailsDao(), db.ingredientAliasDao(), db.foodDao(), repo, templates, api)
        recipesVm = RecipesViewModel(recipes, MealPrepRepository(db.prepBatchDao(), repo, api, scope), templates, repo)
        diaryVm = DiaryViewModel(repo, WaterRepository(db.waterLogDao(), api, settings, scope), settings,
            WeightRepository(db.weightDao(), api, settings, scope), templates, null)
    }

    @After
    fun tearDown() {
        runBlocking { settings.saveHomeLayout(HomeLayout.DEFAULT) }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

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

    private fun top(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().first().boundsInRoot.top

    @Test
    fun draggingASectionMovesItAndSwitchesHideThem() {
        var layout by mutableStateOf(HomeLayout.DEFAULT)
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) { HomeLayoutEditor(layout, onChange = { layout = it }) }
            }
        }
        shot("home_layout_1_editor")
        // Drag "Your week" down past a few sections
        compose.onNodeWithContentDescription("Drag Your week").performTouchInput { swipeDown(startY = centerY, endY = centerY + 600f, durationMillis = 1200) }
        compose.waitForIdle()
        assertTrue("moved down: ${layout.order}", layout.order.indexOf(HomeSection.WEEKLY_REVIEW) >= 2)
        assertEquals(HomeSection.values().toSet(), layout.order.toSet())

        // The accessibility action moves one step
        val before = layout.order.indexOf(HomeSection.TRENDS)
        compose.onNode(SemanticsMatcher("has move up") { n ->
            SemanticsActions.CustomActions in n.config && n.config[SemanticsActions.CustomActions].any { it.label == "Move Weight & calories up" }
        }).let { node ->
            val action = node.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Move Weight & calories up" }
            compose.runOnIdle { action.action() }
        }
        compose.waitForIdle()
        assertEquals(before - 1, layout.order.indexOf(HomeSection.TRENDS))

        // Hiding a section, then resetting
        val switches = compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState))
        switches[0].performClick()
        compose.waitForIdle()
        assertEquals(1, layout.hidden.size)
        shot("home_layout_2_edited")
        compose.onNodeWithText("Reset").performClick()
        compose.waitForIdle()
        assertEquals(HomeLayout.DEFAULT, layout)
    }

    @Test
    fun theDiaryFollowsTheSavedOrder() {
        // Trends first, the quick meal row hidden
        val custom = HomeLayout.DEFAULT
            .move(HomeSection.values().indexOf(HomeSection.TRENDS), 0)
            .toggle(HomeSection.QUICK_LOG, visible = false)
        runBlocking { settings.saveHomeLayout(custom); settings.homeLayout.first { it == custom } }
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                DiaryScreen(diaryVm, recipesVm, onOpenRecipe = {}, onImportRecipe = {}, onNavigateToSearch = { _, _, _ -> }, onNavigateToBarcode = { _, _, _ -> }, onNavigateToChat = {}, onNavigateToSettings = {}, onOpenRecentMeals = {})
            }
        }
        waitFor { diaryVm.homeLayout.value == custom }
        waitFor { compose.onAllNodesWithText("Weight vs Calories").fetchSemanticsNodes().isNotEmpty() }
        // The meals come after it: further down, or not yet composed
        val meals = compose.onAllNodesWithText("No foods logged", substring = true).fetchSemanticsNodes()
        assertTrue("trends above the meals", meals.isEmpty() || top("Weight vs Calories") < meals.first().boundsInRoot.top)
        assertTrue("quick meals hidden", compose.onAllNodesWithText("START LOGGING").fetchSemanticsNodes().isEmpty())
        shot("home_layout_3_diary_custom")

        // Opening the editor from the bottom of the list
        val lists = compose.onAllNodes(androidx.compose.ui.test.hasScrollToKeyAction())
        (0 until lists.fetchSemanticsNodes().size).firstOrNull { i ->
            runCatching { lists[i].performScrollToKey("customize_home") }.isSuccess
        }
        compose.onNodeWithText("Customize home").performClick()
        waitFor { compose.onAllNodesWithText("Drag to reorder", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("home_layout_4_sheet")
    }

    @Test
    fun theHomeScreenScrollsWhereverYouTouchIt() {
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                DiaryScreen(diaryVm, recipesVm, onOpenRecipe = {}, onImportRecipe = {}, onNavigateToSearch = { _, _, _ -> }, onNavigateToBarcode = { _, _, _ -> }, onNavigateToChat = {}, onNavigateToSettings = {}, onOpenRecentMeals = {})
            }
        }
        waitFor { compose.onAllNodesWithText("HYDRATION").fetchSemanticsNodes().isNotEmpty() }

        // A drag up that starts on the water card folds the header and scrolls the list
        compose.onNodeWithText("HYDRATION").performTouchInput { swipeUp(startY = centerY, endY = centerY - 900f, durationMillis = 500) }
        compose.waitForIdle()
        shot("home_scroll_from_header")
        waitFor { compose.onAllNodesWithText("Weight vs Calories").fetchSemanticsNodes().isNotEmpty() }
        val screen = compose.onRoot().fetchSemanticsNode().size.height
        assertTrue("the list came up under the folded header", top("Weight vs Calories") < screen * 0.6f)

        // A drag up or down that starts on the chart moves the page instead of being taken by the chart
        val before = top("Weight vs Calories")
        compose.onNodeWithText("Weight vs Calories").performTouchInput { swipeDown(startY = centerY + 300f, endY = centerY + 700f, durationMillis = 500) }
        compose.waitForIdle()
        assertTrue("the chart let the page scroll", top("Weight vs Calories") > before + 50f)
    }
}
