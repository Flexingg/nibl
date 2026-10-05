package com.bytelog.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealTemplate
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.RecipeDetails
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.MealPrepRepository
import com.bytelog.app.data.repository.MealTemplateRepository
import com.bytelog.app.data.repository.RecipeRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.WaterRepository
import com.bytelog.app.data.repository.WeightRepository
import com.bytelog.app.recipes.Macros
import com.bytelog.app.recipes.RecipeStep
import com.bytelog.app.ui.recipes.CookModeScreen
import com.bytelog.app.ui.recipes.RecipeDetailScreen
import com.bytelog.app.ui.recipes.RecipeImportScreen
import com.bytelog.app.ui.screens.DiaryScreen
import com.bytelog.app.ui.theme.ByteLogTheme
import com.bytelog.app.ui.viewmodel.DiaryViewModel
import com.bytelog.app.ui.viewmodel.ImportState
import com.bytelog.app.ui.viewmodel.RecipesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.time.LocalDate

/**
 * Renders the recipe and meal-prep screens with real repositories over an in-memory
 * database (Sparky unreachable), checks the key behaviour and saves screenshots.
 * Set -Dbytelog.screenshots=<dir> to keep the PNGs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class RecipeScreensRenderTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: ByteLogDatabase
    private lateinit var recipesVm: RecipesViewModel
    private lateinit var diaryVm: DiaryViewModel
    private lateinit var mealPrep: MealPrepRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val today = LocalDate.now().toString()

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        val api = SparkyApiClient(baseUrl = "http://127.0.0.1:9") // nothing listens: every call fails fast
        val settings = SettingsRepository(context)
        val foodRepo = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, settings, scope, db.prepBatchDao())
        val templates = MealTemplateRepository(db.mealTemplateDao(), api, scope)
        val recipes = RecipeRepository(db.recipeDetailsDao(), db.ingredientAliasDao(), db.foodDao(), foodRepo, templates, api)
        mealPrep = MealPrepRepository(db.prepBatchDao(), foodRepo, api, scope)
        recipesVm = RecipesViewModel(recipes, mealPrep, templates, foodRepo)
        diaryVm = DiaryViewModel(
            foodRepo,
            WaterRepository(db.waterLogDao(), api, settings, scope),
            settings,
            WeightRepository(db.weightDao(), api, settings, scope),
            templates,
            null
        )
        db.mealTypeDao().insertMealTypes(MealType.DEFAULT_MEAL_TYPES)

        val uuid = { n: Int -> "00000000-0000-4000-8000-%012d".format(n) }
        db.foodDao().insertFoods(
            listOf(
                FoodItem(uuid(1), "Ground Turkey 93% Lean", servingSize = 112f, servingUnit = "g", calories = 170f, protein = 21f, carbs = 0f, fat = 9f),
                FoodItem(uuid(2), "Black Beans, canned", servingSize = 130f, servingUnit = "g", calories = 110f, protein = 7f, carbs = 20f, fat = 0.5f),
                FoodItem(uuid(3), "Crushed Tomatoes", servingSize = 121f, servingUnit = "g", calories = 40f, protein = 2f, carbs = 9f, fat = 0f),
                FoodItem(uuid(4), "Yellow Onion", servingSize = 110f, servingUnit = "g", calories = 44f, protein = 1.2f, carbs = 10f, fat = 0.1f),
                FoodItem(uuid(5), "Olive Oil", servingSize = 1f, servingUnit = "tbsp", calories = 119f, protein = 0f, carbs = 0f, fat = 13.5f),
                FoodItem(uuid(6), "Chili Powder", servingSize = 1f, servingUnit = "tsp", calories = 8f, protein = 0.4f, carbs = 1.4f, fat = 0.4f),
                FoodItem(uuid(7), "Garlic", servingSize = 3f, servingUnit = "g", calories = 4f, protein = 0.2f, carbs = 1f, fat = 0f)
            )
        )
        // A saved recipe with directions, as if imported earlier.
        db.mealTemplateDao().insertTemplate(
            MealTemplate(
                id = "tmpl-chili", name = "Turkey Chili", totalServings = 6f,
                foodsJson = """[
                  {"food_id":"${uuid(1)}","food_name":"Ground Turkey 93% Lean","quantity":907,"unit":"g","serving_size":907,"serving_unit":"g","calories":1377,"protein":170,"carbs":0,"fat":73},
                  {"food_id":"${uuid(2)}","food_name":"Black Beans, canned","quantity":822,"unit":"g","serving_size":822,"serving_unit":"g","calories":695,"protein":44,"carbs":126,"fat":3},
                  {"food_id":"${uuid(3)}","food_name":"Crushed Tomatoes","quantity":794,"unit":"g","serving_size":794,"serving_unit":"g","calories":262,"protein":13,"carbs":59,"fat":0},
                  {"food_id":"${uuid(5)}","food_name":"Olive Oil","quantity":1,"unit":"tbsp","serving_size":1,"serving_unit":"tbsp","calories":119,"protein":0,"carbs":0,"fat":13.5}
                ]"""
            )
        )
        db.recipeDetailsDao().upsert(
            RecipeDetails(
                templateId = "tmpl-chili", prepTimeMinutes = 15, cookTimeMinutes = 45, fridgeDays = 4, freezerDays = 90,
                sourceUrl = "https://example.com/turkey-chili",
                stepsJson = RecipeRepository.encodeSteps(
                    listOf(
                        RecipeStep("Heat the oil in a large pot over medium-high heat.", "Brown the meat"),
                        RecipeStep("Add turkey and onion; cook 8-10 minutes until browned.", "Brown the meat", 480),
                        RecipeStep("Stir in tomatoes, beans and spices.", "Simmer"),
                        RecipeStep("Simmer uncovered for 30 minutes, stirring now and then.", "Simmer", 1800)
                    )
                )
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Runs the main looper (viewModelScope) until [condition] holds. */
    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) {
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
        // Draw the window's view tree with Robolectric's native canvas (captureToImage needs a
        // hardware PixelCopy that isn't available here).
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun importReviewMatchesPastedRecipe() {
        recipesVm.startImport(
            """
            Turkey Chili
            Serves 6

            Ingredients
            2 lbs 93% lean ground turkey
            1 tbsp olive oil
            1 large yellow onion, diced
            4 cloves garlic, minced
            2 (15 oz) cans black beans, rinsed and drained
            1 (28-ounce; 794g) can crushed tomatoes
            2 tbsp chili powder
            Salt and pepper, to taste

            Directions
            1. Heat the oil and brown the turkey and onion, 8-10 minutes.
            2. Add the rest and simmer 30 minutes.
            """.trimIndent()
        )
        waitFor { recipesVm.importState.value is ImportState.Review }
        val review = recipesVm.importState.value as ImportState.Review
        assertEquals(6f, review.servings)
        assertEquals(8, review.matches.size)
        val turkey = review.matches.first { it.parsed.raw.contains("turkey") }
        assertEquals("Ground Turkey 93% Lean", turkey.food?.name)
        assertEquals(907.2f, turkey.quantity, 0.5f)
        assertTrue(review.matches.first { it.parsed.raw.startsWith("Salt") }.let { !it.include })

        compose.setContent { ByteLogTheme(darkTheme = true) { RecipeImportScreen(recipesVm, null, {}, {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("Review recipe").assertExists()
        shot("01-import-review")
    }

    // The Start batch dialog is not exercised here: under Robolectric any Compose dialog that
    // contains a text field never reports idle. Its portion maths is covered in BatchMathTest.
    @Test
    fun recipeDetail() {
        compose.setContent { ByteLogTheme(darkTheme = true) { RecipeDetailScreen(recipesVm, "tmpl-chili", {}, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Turkey Chili").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Brown the meat").assertExists()
        compose.onNodeWithText("Start batch").assertExists()
        compose.onNodeWithText("Prep 15 min · Cook 45 min · Fridge 4 days · Freezer 90 days").assertExists()
        shot("02-recipe-detail")
    }

    @Test
    fun cookMode() {
        compose.setContent { ByteLogTheme(darkTheme = true) { CookModeScreen(recipesVm, "tmpl-chili", {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Ingredients", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("907 g  Ground Turkey 93% Lean", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("⏱ 8-10 minutes", useUnmergedTree = true).performClick()
        // A running countdown chip appears under the header
        compose.waitUntil(5_000) { compose.onAllNodesWithText("· 8-10 minutes", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("2×", useUnmergedTree = true).performClick()
        // Scaling doubles every ingredient
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1814 g  Ground Turkey 93% Lean", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1. Heat the oil in a large pot over medium-high heat.", useUnmergedTree = true).performClick()
        compose.onNodeWithText("1 of 4 steps done · screen stays on", useUnmergedTree = true).assertExists()
        shot("04-cook-mode")
    }

    @Test
    fun fridgeStripOnDiaryLogsAPortion() = runBlocking {
        val total = Macros(2453f, 227f, 185f, 89.5f)
        val day = MealPrepRepository.DAY_MS
        val now = System.currentTimeMillis()
        // Newest first in the strip: chili, then oats (eat today), then frozen bowls.
        mealPrep.startBatch("Beef & Sweet Potato Bowls", null, Macros(2800f, 210f, 260f, 90f), portions = 5, cookedWeightG = null, scale = 1f, location = "freezer", shelfLifeDays = 60, cookedAt = now - 9 * day)
        mealPrep.startBatch("Overnight Oats", null, Macros(1600f, 80f, 220f, 40f), portions = 4, cookedWeightG = null, scale = 1f, location = "fridge", shelfLifeDays = 3, cookedAt = now - 3 * day)
        val chili = mealPrep.startBatch("Turkey Chili", "tmpl-chili", total, portions = 6, cookedWeightG = 2400f, scale = 1f, location = "fridge", shelfLifeDays = 4, cookedAt = now - 2 * day)

        val entry = mealPrep.logFromBatch(chili.id, today, "lunch", "Lunch", portions = 1f)!!
        assertEquals(2453f / 6f, entry.calories, 0.5f)
        val weighed = mealPrep.logFromBatch(chili.id, today, "dinner", "Dinner", grams = 320f)!!
        assertEquals(2453f * 320f / 2400f, weighed.calories, 0.5f)

        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                DiaryScreen(diaryVm, recipesVm, onOpenRecipe = {}, onImportRecipe = {}, onNavigateToSearch = { _, _, _ -> }, onNavigateToBarcode = { _, _, _ -> }, onNavigateToChat = {}, onNavigateToSettings = {})
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Meal prep").fetchSemanticsNodes().isNotEmpty() }
        // 1 of 6 portions + 320 g of 2400 g eaten → about 4.2 portions left
        compose.onNodeWithText("4.2 of 6 left").assertExists()
        shot("05-diary-fridge")

        // Deleting the diary entry gives the portion back.
        db.foodEntryDao().getEntriesForDateOnce(today).first { it.id == entry.id || it.calories == entry.calories }.let {
            diaryVm.deleteEntry(it)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("5.2 of 6 left").fetchSemanticsNodes().isNotEmpty() }
    }
}
