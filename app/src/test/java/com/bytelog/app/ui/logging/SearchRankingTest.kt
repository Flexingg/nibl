package com.bytelog.app.ui.logging

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.concurrent.TimeUnit

/** Search: foods you've logged first, then saved ones, then new ones from Sparky and Open Food Facts as they arrive. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class SearchRankingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var server: MockWebServer
    private lateinit var db: ByteLogDatabase
    private lateinit var repo: FoodRepository
    private lateinit var vm: FastSearchViewModel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var sparkyDelayMs = 0L

    /** A recipe for two: a serving is half of each ingredient. */
    private val parfait = com.bytelog.app.data.local.model.MealTemplate(
        id = "m-parfait", name = "Yogurt parfait", totalServings = 2f,
        foodsJson = """[
            {"food_id":"f-yogurt","food_name":"Greek Yogurt","quantity":340,"unit":"g","serving_size":170,"serving_unit":"g","calories":150,"protein":20,"carbs":6,"fat":4},
            {"food_id":"f-granola","food_name":"Granola","quantity":100,"unit":"g","serving_size":50,"serving_unit":"g","calories":220,"protein":5,"carbs":30,"fat":9},
            {"child_meal_name":"Berry topping","quantity":1}
        ]"""
    )

    private val yogurt = FoodItem("f-yogurt", "Greek Yogurt", brand = "Fage", servingSize = 170f, calories = 150f, protein = 20f, useCount = 5)

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/foods/foods-paginated") -> MockResponse().setBodyDelay(sparkyDelayMs, TimeUnit.MILLISECONDS).setBody(
                        // Sparky also has the yogurt you log (same id), and one you never have
                        """{"foods":[
                            {"id":"f-yogurt","name":"Greek Yogurt","brand":"Fage","default_variant":{"calories":150,"protein":20,"carbs":6,"fat":4,"serving_size":170,"serving_unit":"g"}},
                            {"id":"s-skyr","name":"Yogurt Skyr","brand":"Siggi's","default_variant":{"calories":110,"protein":15,"carbs":8,"fat":0,"serving_size":150,"serving_unit":"g"}}
                        ]}"""
                    )
                    path.startsWith("/api/foods/openfoodfacts/search") -> MockResponse().setBody(
                        """{"products":[
                            {"code":"111","product_name":"Yogurt","brands":"Generic","nutriments":{"energy-kcal_100g":61,"proteins_100g":3.5,"carbohydrates_100g":4.7,"fat_100g":3.3}},
                            {"code":"222","product_name":"Strawberry Yogurt Drink","brands":"Brand","nutriments":{"energy-kcal_100g":80}},
                            {"product_name":"Yogurt cup","brands":"Store","nutriments":{"energy-kcal_100g":90}}
                        ]}"""
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        val api = SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        repo = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, SettingsRepository(context), scope, db.prepBatchDao())
        db.mealTypeDao().insertMealTypes(MealType.DEFAULT_MEAL_TYPES)
        db.foodDao().insertFoods(listOf(yogurt, FoodItem("f-tub", "Yogurt tub (saved)", calories = 90f)))
        var t = 1_000L
        fun e(name: String, foodId: String?, brand: String? = null) = FoodEntry(
            id = "e$t", foodId = foodId, foodName = name, brandName = brand, mealTypeId = "breakfast", mealName = "Breakfast",
            quantity = 1f, unit = "serving", calories = 150f, protein = 20f, carbs = 6f, fat = 4f, entryDate = "2026-10-20",
            syncStatus = FoodEntry.SYNC_STATUS_SYNCED, createdAt = t++
        )
        // Yogurt logged twice; a frozen yogurt once, logged on the web (no food saved on the phone)
        db.foodEntryDao().insertEntries(listOf(e("Greek Yogurt", "f-yogurt", "Fage"), e("Greek Yogurt", "f-yogurt", "Fage"), e("Frozen yogurt", "web-froyo")))
        vm = FastSearchViewModel(repo, kotlinx.coroutines.flow.MutableStateFlow(listOf(parfait)))
        vm.initContext("breakfast", "Breakfast", "2026-10-29")
    }

    @After fun tearDown() { server.shutdown(); db.close(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            // Advances the main thread's clock too, so the typing pause before a server search passes
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

    private fun ids() = vm.searchResults.value.all.map { it.food.id }

    @Test
    fun loggedFoodsAlwaysComeFirstAndServerResultsArriveAsTheyAnswer() {
        sparkyDelayMs = 1_500    // Sparky is slow; Open Food Facts answers straight away
        val job = collectResults()
        vm.setSearchQuery("yog")

        // From the phone, before any server answers: what you've logged, then what you've saved
        waitFor { vm.searchResults.value.logged.isNotEmpty() }
        assertEquals(listOf("f-yogurt", "web-froyo"), vm.searchResults.value.logged.map { it.food.id })
        assertEquals(2, vm.searchResults.value.logged[0].timesLogged)
        assertTrue(ids().contains("f-tub"))

        // Open Food Facts doesn't wait for Sparky
        waitFor { ids().contains("off_111") }
        assertTrue("Sparky still loading", !ids().contains("s-skyr"))
        waitFor { ids().contains("s-skyr") }

        // Logged foods stay on top, and Sparky's copy of the yogurt isn't listed twice
        assertEquals(listOf("f-yogurt", "web-froyo"), ids().take(2))
        assertEquals(1, ids().count { it == "f-yogurt" })

        // Server results aren't saved over the phone's copy (which would reset how often it's used)
        runBlocking { assertEquals(5, db.foodDao().getFoodById("f-yogurt")!!.useCount) }
        runBlocking { assertEquals(null, db.foodDao().getFoodById("off_222")) }
        job.cancel()
    }

    @Test
    fun wordsMatchInAnyOrderAndRepeatSearchesAreInstant() {
        val job = collectResults()
        vm.setSearchQuery("yogurt greek")
        waitFor { vm.searchResults.value.logged.isNotEmpty() }
        assertEquals("f-yogurt", vm.searchResults.value.logged.first().food.id)

        vm.setSearchQuery("drink")
        waitFor { ids().contains("off_222") }
        vm.setSearchQuery("")
        waitFor { vm.searchResults.value.isEmpty }
        sparkyDelayMs = 3_000
        val start = System.currentTimeMillis()
        vm.setSearchQuery("drink")
        waitFor { ids().contains("off_222") }
        assertTrue("came from the cache", System.currentTimeMillis() - start < 1_000)
        job.cancel()
    }

    @Test
    fun theScreenGroupsWhatYouveLogged() {
        compose.setContent { ByteLogTheme(darkTheme = true) { FastSearchScreen(vm, onNavigateBack = {}, onNavigateToBarcode = {}) } }
        vm.setSearchQuery("yog")
        waitFor { compose.onAllNodesWithText("YOU'VE LOGGED").fetchSemanticsNodes().isNotEmpty() }
        waitFor { compose.onAllNodesWithText("MORE FOODS").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("Logged 2×", substring = true).fetchSemanticsNodes().isNotEmpty())
        val logged = compose.onAllNodesWithText("YOU'VE LOGGED").fetchSemanticsNodes().first().boundsInRoot.top
        val more = compose.onAllNodesWithText("MORE FOODS").fetchSemanticsNodes().first().boundsInRoot.top
        val greek = compose.onAllNodesWithText("Greek Yogurt").fetchSemanticsNodes().first().boundsInRoot.top
        assertTrue(logged < greek && greek < more)
        // Your meals and recipes sit between what you've logged and everything else
        waitFor { compose.onAllNodesWithText("YOUR MEALS & RECIPES").fetchSemanticsNodes().isNotEmpty() }
        val meals = compose.onAllNodesWithText("YOUR MEALS & RECIPES").fetchSemanticsNodes().first().boundsInRoot.top
        assertTrue(greek < meals && meals < more)
        assertTrue(compose.onAllNodesWithText("Recipe · 1 of 2 servings", substring = true).fetchSemanticsNodes().isNotEmpty())
        shot("search_logged_first")
    }

    @Test
    fun typosFindWhatYouveLoggedAndProductsKeepTheirIdBetweenSearches() {
        val job = collectResults()
        vm.setSearchQuery("yogrut")
        waitFor { vm.searchResults.value.logged.isNotEmpty() }
        assertEquals("f-yogurt", vm.searchResults.value.logged.first().food.id)

        // An Open Food Facts product without a barcode has the same id every time it's found
        vm.setSearchQuery("yog")
        waitFor { vm.searchResults.value.all.any { it.food.name == "Yogurt cup" } }
        val first = vm.searchResults.value.all.first { it.food.name == "Yogurt cup" }.food.id
        vm.setSearchQuery("yogu")
        waitFor { vm.searchResults.value.all.any { it.food.name == "Yogurt cup" } && vm.searchQuery.value == "yogu" }
        waitFor { !vm.isSearchingRemote.value }
        assertEquals(first, vm.searchResults.value.all.first { it.food.name == "Yogurt cup" }.food.id)
        job.cancel()
    }

    @Test
    fun yourMealsAndRecipesAreFoundAndAddAServingToTheTray() {
        val job = scope.launch { vm.mealResults.collect {} }
        vm.setSearchQuery("parfiat")     // a typo
        waitFor { vm.mealResults.value.isNotEmpty() }
        assertEquals("m-parfait", vm.mealResults.value.single().id)

        assertEquals(2, vm.stageTemplate(parfait))   // the topping is a meal of its own, so it's left out
        val staged = vm.stagedEntries.value
        assertEquals(listOf("Greek Yogurt", "Granola"), staged.map { it.foodName })
        assertEquals(170f, staged[0].quantity, 0.01f)
        assertEquals(150f, staged[0].calories, 0.5f)
        assertEquals(50f, staged[1].quantity, 0.01f)
        assertEquals(220f, staged[1].calories, 0.5f)
        job.cancel()
    }

    /** searchResults only updates while something collects it (as the screen does). */
    private fun collectResults(): kotlinx.coroutines.Job = scope.launch { vm.searchResults.collect {} }
}
