package com.bytelog.app.ui.logging

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.ui.navigation.SEARCH_ROUTE
import com.bytelog.app.ui.navigation.rememberTrayOwner
import com.bytelog.app.ui.screens.FastSearchScreen
import com.bytelog.app.ui.theme.ByteLogTheme
import com.bytelog.app.ui.viewmodel.FastSearchViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Adding food in one go: the tray carries over between searching and scanning, suggests what goes with each pick, and isn't dropped by Back. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class AddModeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var server: MockWebServer
    private lateinit var db: ByteLogDatabase
    private lateinit var repo: FoodRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val today = LocalDate.now().toString()

    private val oatmeal = FoodItem("f-oatmeal", "Oatmeal", servingSize = 40f, servingUnit = "g", calories = 150f, protein = 5f, carbs = 27f, fat = 3f)

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        // Searches answer at once (and empty), so the "searching" spinner doesn't run for ever
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/api/foods/foods-paginated") -> MockResponse().setBody("""{"foods":[]}""")
                request.path.orEmpty().startsWith("/api/v2/foods/search") -> MockResponse().setBody("""{"foods":[]}""")
                request.path.orEmpty().startsWith("/api/foods/openfoodfacts") -> MockResponse().setBody("""{"products":[]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        val api = SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        repo = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, SettingsRepository(context), scope, db.prepBatchDao())
        db.mealTypeDao().insertMealTypes(MealType.DEFAULT_MEAL_TYPES)
        var t = 1_000L
        fun e(day: Int, name: String, meal: String = "Breakfast", date: String = LocalDate.now().minusDays(day.toLong()).toString()) = FoodEntry(
            id = "h${t}", foodId = "f-${name.lowercase().replace(' ', '-')}", foodName = name, mealTypeId = meal.lowercase(), mealName = meal,
            quantity = 1f, unit = "serving", calories = 100f, protein = 5f, carbs = 10f, fat = 2f, entryDate = date,
            syncStatus = FoodEntry.SYNC_STATUS_SYNCED, createdAt = t++
        )
        // Oatmeal went with banana 4 times and peanut butter twice; banana and peanut butter went together twice
        db.foodEntryDao().insertEntries(
            listOf(1 to listOf("Oatmeal", "Banana", "Peanut Butter"), 2 to listOf("Oatmeal", "Banana", "Peanut Butter"),
                3 to listOf("Oatmeal", "Banana"), 4 to listOf("Oatmeal", "Banana"), 5 to listOf("Coffee"), 6 to listOf("Coffee"))
                .flatMap { (d, names) -> names.map { e(d, it) } }
        )
    }

    @After fun tearDown() { server.shutdown(); db.close(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

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
    fun pickingAFoodSuggestsWhatYouUsuallyHaveWithItAndTapsAddThem() {
        val vm = FastSearchViewModel(repo).apply { initContext("breakfast", "Breakfast", today) }
        val job = scope.launch { vm.together.collect {} }
        assertEquals(null, vm.together.value)                                  // nothing picked: nothing to go with

        vm.stageFood(oatmeal)
        waitFor { vm.together.value != null }
        assertEquals("Often with Oatmeal", vm.together.value!!.label)
        assertEquals(listOf("Banana", "Peanut Butter"), vm.together.value!!.foods.map { it.name })   // coffee never shared a meal with it

        // A tap adds the banana; it and the oatmeal now point at the peanut butter
        vm.tapFood(vm.together.value!!.foods.first { it.name == "Banana" })
        waitFor { vm.together.value?.label == "Often with what you've picked" }
        assertEquals(listOf("Oatmeal", "Banana"), vm.stagedEntries.value.map { it.foodName })
        assertEquals(listOf("Peanut Butter"), vm.together.value!!.foods.map { it.name })

        // Food already logged in this breakfast today isn't suggested again
        runBlocking {
            db.foodEntryDao().insertEntry(FoodEntry(id = "today-pb", foodId = "f-peanut-butter", foodName = "Peanut Butter", mealTypeId = "breakfast", mealName = "Breakfast",
                quantity = 1f, unit = "serving", calories = 95f, protein = 4f, carbs = 3f, fat = 8f, entryDate = today, syncStatus = FoodEntry.SYNC_STATUS_SYNCED))
        }
        // Banana comes out of the tray: back to what goes with the oatmeal, now without the peanut butter already eaten today
        vm.removeStagedEntry(vm.stagedEntries.value.last())
        waitFor { vm.together.value?.label == "Often with Oatmeal" && vm.together.value?.foods?.map { it.name } == listOf("Banana") }
        job.cancel()
    }

    @Test
    fun theScreenShowsTheSuggestionsWhenNothingIsTypedAndAsksBeforeLeaving() {
        val vm = FastSearchViewModel(repo).apply { initContext("breakfast", "Breakfast", today) }
        var left = false
        compose.setContent { ByteLogTheme(darkTheme = true) { FastSearchScreen(vm, onNavigateBack = { left = true }, onNavigateToBarcode = {}) } }

        vm.stageFood(oatmeal)
        waitFor { compose.onAllNodesWithText("Often with Oatmeal").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("Banana").fetchSemanticsNodes().isNotEmpty())
        shot("add_mode_suggestions")

        // A tap on a suggestion puts it in the tray, and typing hides the strip so results get the room
        compose.onNode(hasContentDescription("Add Banana", substring = true)).performClick()
        waitFor { vm.stagedEntries.value.size == 2 }
        vm.setSearchQuery("zz")
        waitFor { compose.onAllNodesWithText("Often with", substring = true).fetchSemanticsNodes().isEmpty() }
        vm.setSearchQuery("")
        waitFor { vm.isSearchingRemote.value.not() }

        // Back with food in the tray asks first; "Discard" leaves, and Back again with the dialog open keeps adding
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor { compose.onAllNodesWithText("2 foods in your tray").fetchSemanticsNodes().isNotEmpty() }
        shot("add_mode_leave")
        assertTrue(!left)
        compose.onNodeWithText("Log to Breakfast").performClick()
        waitFor { left }
        runBlocking {
            waitFor { runBlocking { db.foodEntryDao().getEntriesForDateOnce(today) }.size == 2 }
            assertEquals(setOf("Oatmeal", "Banana"), db.foodEntryDao().getEntriesForDateOnce(today).map { it.foodName }.toSet())
        }
    }

    @Test
    fun discardingFromTheLeaveDialogLogsNothing() {
        val vm = FastSearchViewModel(repo).apply { initContext("breakfast", "Breakfast", today) }
        var left = false
        compose.setContent { ByteLogTheme(darkTheme = true) { FastSearchScreen(vm, onNavigateBack = { left = true }, onNavigateToBarcode = {}) } }
        vm.stageFood(oatmeal)
        waitFor { vm.stagedEntries.value.size == 1 }
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor { compose.onAllNodesWithText("1 food in your tray").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Discard").performClick()
        waitFor { left }
        runBlocking { assertEquals(0, db.foodEntryDao().getEntriesForDateOnce(today).size) }
    }

    @Test
    fun theScannerAddsToTheSameTrayAsTheSearchItWasOpenedFrom() {
        var nav: androidx.navigation.NavHostController? = null
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                val navController = rememberNavController().also { nav = it }
                NavHost(navController, startDestination = "diary") {
                    composable("diary") { Text("Diary") }
                    composable(SEARCH_ROUTE, arguments = listOf(
                        navArgument("mealTypeId") { type = NavType.StringType }, navArgument("mealName") { type = NavType.StringType },
                        navArgument("date") { type = NavType.StringType }, navArgument("photo") { type = NavType.BoolType; defaultValue = false }
                    )) { entry ->
                        val vm: FastSearchViewModel = viewModel { FastSearchViewModel(repo).apply { initContext("dinner", "Dinner", today) } }
                        val staged by vm.stagedEntries.collectAsState()
                        Column { Text("search tray: ${staged.size}"); Text("for ${vm.targetMealName}") }
                    }
                    composable("barcode/{mealTypeId}/{mealName}/{date}", arguments = listOf(
                        navArgument("mealTypeId") { type = NavType.StringType }, navArgument("mealName") { type = NavType.StringType },
                        navArgument("date") { type = NavType.StringType }
                    )) { entry ->
                        val vm: FastSearchViewModel = viewModel(viewModelStoreOwner = rememberTrayOwner(navController, entry)) {
                            FastSearchViewModel(repo).apply { initContext("lunch", "Lunch", today) }   // its own tray, if it had to make one
                        }
                        val staged by vm.stagedEntries.collectAsState()
                        Column { Text("scanner tray: ${staged.size}"); Text("scanner for ${vm.targetMealName}") }
                    }
                }
            }
        }
        val controller = nav!!
        compose.runOnIdle { controller.navigate("search/dinner/Dinner/$today") }
        compose.waitForIdle()
        // Pick two things on the search screen, then open the scanner
        val searchVm = controller.getBackStackEntry(SEARCH_ROUTE).let { entry ->
            androidx.lifecycle.ViewModelProvider(entry)[FastSearchViewModel::class.java]
        }
        compose.runOnIdle { searchVm.stageFood(oatmeal); searchVm.stageFood(oatmeal.copy(id = "f-banana", name = "Banana")) }
        compose.runOnIdle { controller.navigate("barcode/dinner/Dinner/$today") }
        compose.waitForIdle()
        assertTrue("the scanner sees what was picked", compose.onAllNodesWithText("scanner tray: 2").fetchSemanticsNodes().isNotEmpty())
        assertTrue("and logs to the same meal, not the one it would have made", compose.onAllNodesWithText("scanner for Dinner").fetchSemanticsNodes().isNotEmpty())
        // Scan something, go back to the search: it's there
        compose.runOnIdle { searchVm.stageFood(oatmeal.copy(id = "f-scanned", name = "Scanned snack")) }
        compose.runOnIdle { controller.popBackStack() }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("search tray: 3").fetchSemanticsNodes().isNotEmpty())

        // Opened on its own (no search before it), the scanner makes a tray of its own
        compose.runOnIdle { controller.popBackStack("diary", false) }
        compose.runOnIdle { controller.navigate("barcode/lunch/Lunch/$today") }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("scanner for Lunch").fetchSemanticsNodes().isNotEmpty())
    }
}
