package com.bytelog.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.remote.PhotoMealEstimate
import com.bytelog.app.data.remote.PhotoMealItem
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.MealPrepRepository
import com.bytelog.app.data.repository.MealTemplateRepository
import com.bytelog.app.data.repository.RecipeRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.WaterRepository
import com.bytelog.app.data.repository.WeeklyReview
import com.bytelog.app.data.repository.WeightRepository
import com.bytelog.app.ui.diary.WeeklyReviewCard
import com.bytelog.app.ui.logging.Portion
import com.bytelog.app.ui.logging.PhotoEstimateContent
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Photo logging, "What fits?", the weekly review and the fasting timer, rendered and used. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class InsightsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: ByteLogDatabase
    private lateinit var repo: FoodRepository
    private lateinit var diaryVm: DiaryViewModel
    private lateinit var recipesVm: RecipesViewModel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val estimate = PhotoMealEstimate(
        summary = "Chicken Rice Bowl", confidence = "medium", confidenceReason = "Sauce is hard to see",
        items = listOf(
            PhotoMealItem("grilled chicken thigh", "chicken thigh", 150f, "1 thigh", 290f, 38f, 0f, 15f, confidence = "high"),
            PhotoMealItem("white rice", "rice", 180f, "1 cup cooked", 234f, 4.3f, 51f, 0.5f, confidence = "low"),
            PhotoMealItem("teriyaki sauce", "teriyaki sauce", 30f, "2 tbsp", 50f, 1f, 10f, 0f)
        ),
        questions = listOf("Was the chicken cooked with the skin on?")
    )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        val api = SparkyApiClient(baseUrl = "http://127.0.0.1:9")
        val settings = SettingsRepository(context)
        repo = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, settings, scope, db.prepBatchDao())
        db.mealTypeDao().insertMealTypes(MealType.DEFAULT_MEAL_TYPES)
        val templates = MealTemplateRepository(db.mealTemplateDao(), api, scope)
        val recipes = RecipeRepository(db.recipeDetailsDao(), db.ingredientAliasDao(), db.foodDao(), repo, templates, api)
        recipesVm = RecipesViewModel(recipes, MealPrepRepository(db.prepBatchDao(), repo, api, scope), templates, repo)
        diaryVm = DiaryViewModel(repo, WaterRepository(db.waterLogDao(), api, settings, scope), settings,
            WeightRepository(db.weightDao(), api, settings, scope), templates, null)
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

    @Test
    fun photoEstimateLetsYouPickFoodsForTheTray() {
        val photo = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFC8A060.toInt()) }
        var added: List<PhotoMealItem> = emptyList()
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    PhotoEstimateContent(FastSearchViewModel.PhotoState.Ready(photo, estimate), "Lunch", { added = it }, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Add 3 for Lunch · 574 kcal").assertExists()
        compose.onNodeWithContentDescription("teriyaki sauce, included").performClick()
        compose.onNodeWithText("Add 2 for Lunch · 524 kcal").assertExists()
        shot("insights_1_photo_estimate")
        compose.onNodeWithContentDescription("Add 2 to tray").performClick()
        assertEquals(listOf("grilled chicken thigh", "white rice"), added.map { it.name })

        // In the tray: at the estimated grams, and changing the amount scales the estimate
        val vm = FastSearchViewModel(repo).apply { initContext("lunch", "Lunch", LocalDate.now().toString()) }
        vm.stagePhotoItems(added)
        val staged = vm.stagedEntries.value
        assertEquals(listOf("Grilled chicken thigh", "White rice"), staged.map { it.foodName })
        assertEquals(150f, staged[0].quantity, 0f)
        assertEquals("g", staged[0].unit)
        vm.updateStagedPortion(staged[0].id, Portion(300f, "g"))
        assertEquals(580f, vm.stagedEntries.value[0].calories, 0.5f)
        assertTrue(vm.photoState.value is FastSearchViewModel.PhotoState.Idle)
    }

    @Test
    fun weeklyReviewCard() {
        val today = LocalDate.of(2026, 10, 19)
        val entries = (1L..7L).filter { it != 3L }.map { ago ->
            FoodEntry(id = "d$ago", foodName = "Day", mealTypeId = "dinner", mealName = "Dinner", quantity = 1f, unit = "serving",
                calories = if (ago == 1L) 2600f else 1950f + ago * 20f, protein = 140f + ago, carbs = 200f, fat = 70f,
                entryDate = today.minusDays(ago).toString())
        }
        val review = WeeklyReview.of(entries, today, 2000f, 150f, listOf(today.minusDays(7) to 181f, today.minusDays(1) to 179.6f))
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) { WeeklyReviewCard(review, "lbs", {}, Modifier.padding(16.dp)) }
            }
        }
        compose.onNodeWithText("5/7").assertExists()
        compose.onNodeWithText("-1.4").assertExists()
        shot("insights_2_weekly_review")
    }

    @Test
    fun diaryShowsWhatFitsAndTheFast() {
        val zone = ZoneId.systemDefault()
        val threeHoursAgo = System.currentTimeMillis() - 3 * 3_600_000L - 5 * 60_000L
        val ateOn = Instant.ofEpochMilli(threeHoursAgo).atZone(zone).toLocalDate().toString()
        fun past(name: String, ago: Long, kcal: Float, p: Float, c: Float, f: Float) = FoodEntry(
            id = "$name-$ago", foodId = "food-${name.lowercase()}", foodName = name, mealTypeId = "snacks", mealName = "Snacks",
            quantity = 1f, unit = "serving", calories = kcal, protein = p, carbs = c, fat = f,
            entryDate = LocalDate.now().minusDays(ago).toString(), syncStatus = FoodEntry.SYNC_STATUS_SYNCED, createdAt = 1_000L + ago
        )
        runBlocking {
            db.foodEntryDao().insertEntries(
                listOf(1L, 2L, 3L).map { past("Greek Yogurt", it, 150f, 20f, 8f, 4f) } +
                    listOf(1L, 2L).map { past("Chicken Breast", it, 250f, 46f, 0f, 5f) } +
                    past("Apple", 2L, 95f, 0.5f, 25f, 0.3f) +
                    FoodEntry(id = "breakfast", foodName = "Oatmeal", mealTypeId = "breakfast", mealName = "Breakfast", quantity = 1f, unit = "serving",
                        calories = 400f, protein = 15f, carbs = 60f, fat = 8f, entryDate = ateOn, syncStatus = FoodEntry.SYNC_STATUS_SYNCED, createdAt = threeHoursAgo)
            )
        }
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                DiaryScreen(diaryVm, recipesVm, onOpenRecipe = {}, onImportRecipe = {}, onNavigateToSearch = { _, _, _ -> }, onNavigateToBarcode = { _, _, _ -> }, onNavigateToChat = {}, onNavigateToSettings = {}, onOpenRecentMeals = {})
            }
        }
        waitFor { compose.onAllNodesWithText("Fasting 3h", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("insights_3_diary_fasting")
        waitFor { diaryVm.whatFits.value.isNotEmpty() }
        // Further down the list (below the weekly review on Sundays and Mondays)
        val lists = compose.onAllNodes(androidx.compose.ui.test.hasScrollToKeyAction())
        (0 until lists.fetchSemanticsNodes().size).firstOrNull { i ->
            runCatching { lists[i].performScrollToKey("what_fits") }.isSuccess
        }
        waitFor { compose.onAllNodesWithText("WHAT FITS?").fetchSemanticsNodes().isNotEmpty() }
        shot("insights_4_what_fits")

        val before = runBlocking { db.foodEntryDao().getEntriesForDate(LocalDate.now().toString()).first() }.size
        compose.onAllNodesWithText("Greek Yogurt", substring = true).onFirst().performClick()
        waitFor { runBlocking { db.foodEntryDao().getEntriesForDate(LocalDate.now().toString()).first() }.size > before }
        waitFor { compose.onAllNodesWithText("Logged", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("insights_5_what_fits_logged")
    }
}
