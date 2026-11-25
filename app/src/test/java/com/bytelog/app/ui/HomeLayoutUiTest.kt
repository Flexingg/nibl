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
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.longClick
import androidx.compose.material.icons.filled.Add
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.HomeTile
import com.bytelog.app.data.repository.HomeTiles
import com.bytelog.app.data.repository.TileSize
import com.bytelog.app.data.repository.MealPrepRepository
import com.bytelog.app.data.repository.MealTemplateRepository
import com.bytelog.app.data.repository.RecipeRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.WaterRepository
import com.bytelog.app.data.repository.WeightRepository
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

/** The home dashboard: tiles in their sizes, the folding top, and editing it. */
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
        runBlocking { settings.saveHomeTiles(HomeTiles.DEFAULT) }
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

    private fun face(tile: HomeTile) = com.bytelog.app.ui.dashboard.TileFace(
        tile.title, androidx.compose.material.icons.Icons.Default.Add, androidx.compose.ui.graphics.Color(0xFF8CCDFF),
        androidx.compose.ui.graphics.Color(0xFF004B70), value = if (tile.ordinal % 2 == 0) "${tile.ordinal * 10}" else null, detail = tile.description
    )

    private fun diary() {
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                DiaryScreen(diaryVm, recipesVm, onOpenRecipe = {}, onImportRecipe = {}, onNavigateToSearch = { _, _, _ -> }, onNavigateToBarcode = { _, _, _ -> }, onNavigateToChat = {}, onNavigateToSettings = {}, onOpenRecentMeals = {})
            }
        }
    }

    private fun saveTiles(tiles: HomeTiles) = runBlocking { settings.saveHomeTiles(tiles); settings.homeTiles.first { it == tiles } }

    @Test
    fun theEditorMovesResizesRemovesAndAddsTiles() {
        var tiles by mutableStateOf(HomeTiles.DEFAULT)
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    com.bytelog.app.ui.dashboard.HomeTilesEditor(tiles, ::face, onChange = { tiles = it }, onDone = {})
                }
            }
        }
        compose.waitForIdle()
        shot("home_tiles_1_editor")

        // A tap changes the size: full, half, small
        compose.onNodeWithContentDescription("Water, Full width").performClick()
        compose.waitForIdle()
        assertEquals(TileSize.HALF, tiles.sizeOf(HomeTile.WATER))
        compose.onNodeWithContentDescription("Weight, Full width").performClick()
        compose.waitForIdle()
        assertEquals(TileSize.HALF, tiles.sizeOf(HomeTile.WEIGHT))
        compose.onNodeWithContentDescription("Weight, Half width").performClick()
        compose.waitForIdle()
        assertEquals(TileSize.SMALL, tiles.sizeOf(HomeTile.WEIGHT))

        // Hold and drag: weight goes in front of water
        val before = tiles.visible.indexOfFirst { it.tile == HomeTile.WEIGHT }
        compose.onNodeWithContentDescription("Weight, Small").performTouchInput {
            down(center)
            advanceEventTime(900)
            repeat(12) { moveBy(androidx.compose.ui.geometry.Offset(-30f, -4f)) }
            up()
        }
        compose.waitForIdle()
        val after = tiles.visible.indexOfFirst { it.tile == HomeTile.WEIGHT }
        assertTrue("dragged earlier ($before -> $after)", after < before)
        assertEquals(HomeTile.values().size, tiles.order.size)

        // × takes a tile off; the tray puts it back at the end
        compose.onNodeWithContentDescription("Remove Calories").performClick()
        compose.waitForIdle()
        assertTrue(HomeTile.CALORIES in tiles.hidden)
        compose.onNode(androidx.compose.ui.test.hasScrollToKeyAction()).performScrollToKey("add_tray")
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("ADD TILES").fetchSemanticsNodes().isNotEmpty())
        shot("home_tiles_3_removed")
        compose.onNodeWithText("Calories").performClick()
        compose.waitForIdle()
        assertEquals(HomeTile.CALORIES, tiles.visible.last().tile)

        compose.onNodeWithText("Reset").performClick()
        compose.waitForIdle()
        assertEquals(HomeTiles.DEFAULT, tiles)
    }

    @Test
    fun theDiaryShowsTilesInTheirSizesAndTheTopOnesFold() {
        // Water and weight side by side at the top, the calorie card under them, the shortcuts as small tiles
        val custom = HomeTiles.DEFAULT
            .resize(HomeTile.WATER, TileSize.HALF).resize(HomeTile.WEIGHT, TileSize.HALF)
            .moveVisible(0, 2)
        assertEquals(listOf(HomeTile.WATER, HomeTile.WEIGHT, HomeTile.CALORIES), custom.visible.take(3).map { it.tile })
        saveTiles(custom)
        diary()
        waitFor { diaryVm.homeTiles.value == custom }
        waitFor { compose.onAllNodesWithText("PROTEIN").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("half tiles above the calorie card", top("WATER") < top("PROTEIN"))
        assertTrue("water and weight side by side", kotlin.math.abs(top("WATER") - top("WEIGHT")) < 4f)
        shot("home_tiles_4_diary_custom")
        // The four shortcuts are small tiles in one row
        assertTrue(kotlin.math.abs(top("Reports") - top("Mealie")) < 4f)

        compose.onNodeWithText("PROTEIN").performTouchInput { swipeUp(startY = centerY, endY = centerY - 700f, durationMillis = 500) }
        compose.waitForIdle()
        shot("home_tiles_5_diary_folded")
        assertTrue("the calorie card folded to a strip", compose.onAllNodesWithText("KCAL").fetchSemanticsNodes().isNotEmpty())

    }

    @Test
    fun smallTilesOnTopFoldIntoSmallStripsAndCardsCanBeHalfWidth() {
        var tiles = HomeTiles.DEFAULT
        listOf(HomeTile.MEALIE, HomeTile.PROGRESS_PHOTOS, HomeTile.REPORTS).forEach { tiles = tiles.moveTo(it, tiles.visible.first().tile) }
        tiles = tiles.resize(HomeTile.MEAL_PLAN, TileSize.HALF).resize(HomeTile.SHOPPING, TileSize.HALF)
            .resize(HomeTile.WATER, TileSize.SMALL).resize(HomeTile.WEIGHT, TileSize.SMALL)
            .moveTo(HomeTile.MEAL_PLAN, HomeTile.WATER).moveTo(HomeTile.SHOPPING, HomeTile.WATER)
        assertEquals(listOf(HomeTile.REPORTS, HomeTile.PROGRESS_PHOTOS, HomeTile.MEALIE), tiles.visible.take(3).map { it.tile })
        saveTiles(tiles)
        diary()
        waitFor { diaryVm.homeTiles.value == tiles }
        waitFor { compose.onAllNodesWithText("PROTEIN").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("small tiles in one row", kotlin.math.abs(top("Reports") - top("Mealie")) < 4f)
        assertTrue("meal plan and shopping side by side", kotlin.math.abs(top("MEAL PLAN") - top("SHOPPING")) < 4f)
        shot("home_tiles_8_small_top")
        compose.onNodeWithText("PROTEIN").performTouchInput { swipeUp(startY = centerY, endY = centerY - 700f, durationMillis = 500) }
        compose.waitForIdle()
        shot("home_tiles_9_small_top_folded")
    }

    @Test
    fun aLongPressStartsEditingAndDoneKeepsTheChanges() {
        saveTiles(HomeTiles.DEFAULT)
        diary()
        waitFor { compose.onAllNodesWithText("BODYWEIGHT").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("BODYWEIGHT").performTouchInput { longClick() }
        waitFor { compose.onAllNodesWithText("Edit home").fetchSemanticsNodes().isNotEmpty() }
        shot("home_tiles_6_edit_mode")
        compose.onNodeWithContentDescription("Weight, Full width").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Done").performClick()
        waitFor { diaryVm.homeTiles.value.sizeOf(HomeTile.WEIGHT) == TileSize.HALF }
        waitFor { compose.onAllNodesWithText("Edit home").fetchSemanticsNodes().isEmpty() }
        shot("home_tiles_7_after_edit")
    }

    @Test
    fun theHomeScreenHasASmallDateAndTheMenuHoldsSettingsInTheSavedOrder() {
        // Search moved to the top of the menu, Ask Sparky hidden
        val menu = com.bytelog.app.data.repository.FabMenuLayout.DEFAULT
            .move(com.bytelog.app.data.repository.FabAction.entries.indexOf(com.bytelog.app.data.repository.FabAction.SEARCH), 0)
            .toggle(com.bytelog.app.data.repository.FabAction.ASK, visible = false)
        runBlocking { settings.saveFabMenuLayout(menu); settings.fabMenuLayout.first { it == menu } }
        var opened = ""
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                DiaryScreen(diaryVm, recipesVm, onOpenRecipe = {}, onImportRecipe = {}, onNavigateToSearch = { _, _, _ -> opened = "search" }, onNavigateToBarcode = { _, _, _ -> },
                    onNavigateToChat = { opened = "chat" }, onNavigateToSettings = { opened = "settings" }, onOpenRecentMeals = {})
            }
        }
        waitFor { diaryVm.fabMenuLayout.value == menu }
        waitFor { compose.onAllNodesWithText("HYDRATION").fetchSemanticsNodes().isNotEmpty() }
        // No top bar buttons: sync, Sparky and settings moved out
        listOf("Sync with Sparky", "Sparky AI Coach", "Settings").forEach { d ->
            assertTrue("$d is gone from the top", compose.onAllNodesWithContentDescription(d).fetchSemanticsNodes().isEmpty())
        }
        assertTrue(compose.onAllNodesWithText("Today").fetchSemanticsNodes().isNotEmpty())
        // The calorie card starts near the top now
        assertTrue("cards start high: ${top("HYDRATION")}", top("HYDRATION") < compose.onRoot().fetchSemanticsNode().size.height * 0.5f)
        shot("home_compact_top")

        compose.onNodeWithContentDescription("Add food").performClick()
        waitFor { compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("hidden action left out", compose.onAllNodesWithText("Ask Sparky").fetchSemanticsNodes().isEmpty())
        assertTrue("search first, at the top", top("Search food") < top("Settings"))
        shot("home_fab_menu")
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        assertEquals("settings", opened)
    }

    @Test
    fun aSmallerSizeFitsMoreOnScreen() {
        var size by mutableStateOf(com.bytelog.app.ui.theme.UiSize.COMFORTABLE)
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                com.bytelog.app.ui.theme.UiScale(size) {
                    DiaryScreen(diaryVm, recipesVm, onOpenRecipe = {}, onImportRecipe = {}, onNavigateToSearch = { _, _, _ -> }, onNavigateToBarcode = { _, _, _ -> }, onNavigateToChat = {}, onNavigateToSettings = {}, onOpenRecentMeals = {})
                }
            }
        }
        waitFor { compose.onAllNodesWithText("BODYWEIGHT").fetchSemanticsNodes().isNotEmpty() }
        val comfortable = top("BODYWEIGHT")
        shot("size_comfortable")
        size = com.bytelog.app.ui.theme.UiSize.COMPACT
        compose.waitForIdle()
        val compact = top("BODYWEIGHT")
        shot("size_compact")
        assertTrue("compact moves the cards up ($comfortable -> $compact)", compact < comfortable * 0.95f)
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
        // The meal plan and shopping cards sit between the header and the chart, so keep going until it's well in view
        val screen = compose.onRoot().fetchSemanticsNode().size.height
        repeat(3) {
            if (compose.onAllNodesWithText("Weight vs Calories").fetchSemanticsNodes().isEmpty() || top("Weight vs Calories") > screen * 0.6f) {
                compose.onRoot().performTouchInput { swipeUp(startY = centerY + 300f, endY = centerY - 500f, durationMillis = 500) }
                compose.waitForIdle()
            }
        }
        waitFor { compose.onAllNodesWithText("Weight vs Calories").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the list came up under the folded header", top("Weight vs Calories") < screen * 0.6f)

        // A drag up or down that starts on the chart moves the page instead of being taken by the chart
        val before = top("Weight vs Calories")
        compose.onNodeWithText("Weight vs Calories").performTouchInput { swipeDown(startY = centerY + 300f, endY = centerY + 700f, durationMillis = 500) }
        compose.waitForIdle()
        assertTrue("the chart let the page scroll", top("Weight vs Calories") > before + 50f)
    }
}
