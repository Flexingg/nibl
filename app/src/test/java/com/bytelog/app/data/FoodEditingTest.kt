package com.bytelog.app.data

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.remote.SparkyNutrients
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.ui.foods.FoodEditorScreen
import com.bytelog.app.ui.theme.ByteLogTheme
import com.bytelog.app.ui.viewmodel.FoodEditorViewModel
import com.bytelog.app.ui.viewmodel.FoodForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CopyOnWriteArrayList

/** Editing foods goes to Sparky first: own foods change in place, anyone else's are saved as the user's copy. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class FoodEditingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val me = "user-me"
    private val json = Json { ignoreUnknownKeys = true }
    private val calls = CopyOnWriteArrayList<Pair<String, String>>()   // "METHOD path" to body
    @Volatile private var failVariantPut = false

    private fun foodJson(id: String, owner: String?, name: String, kcal: Int, barcode: String? = null) = """
        {"id":"$id","name":"$name","brand":"Acme","barcode":${barcode?.let { "\"$it\"" } ?: "null"},"is_custom":${owner != null},
         "user_id":${owner?.let { "\"$it\"" } ?: "null"},"provider_type":null,
         "default_variant":{"id":"var-$id","serving_size":40,"serving_unit":"g","calories":$kcal,"protein":5,"carbs":27,"fat":3,
           "dietary_fiber":4,"sugars":1,"sodium":null,"saturated_fat":0.5}}
    """.trimIndent()

    private lateinit var server: MockWebServer
    private lateinit var db: ByteLogDatabase
    private lateinit var repo: FoodRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                calls += "${request.method} $path" to request.body.readUtf8()
                return when {
                    request.method == "GET" && path.startsWith("/api/foods/foods-paginated") && path.contains("foodFilter=mine") ->
                        MockResponse().setBody("""{"foods":[${foodJson("oats", me, "Rolled Oats", 150)}],"totalCount":1}""")
                    request.method == "GET" && path == "/api/foods/oats" -> MockResponse().setBody(foodJson("oats", me, "Rolled Oats", 150))
                    request.method == "GET" && path == "/api/foods/public-bread" -> MockResponse().setBody(foodJson("public-bread", null, "Whole Wheat Bread", 110, "0123456789012"))
                    request.method == "PUT" && path == "/api/foods/food-variants/var-oats" ->
                        if (failVariantPut) MockResponse().setResponseCode(500).setBody("""{"error":"Database is down"}""") else MockResponse().setBody("{}")
                    request.method == "PUT" && path.startsWith("/api/foods/") -> MockResponse().setBody("{}")
                    request.method == "POST" && path == "/api/foods" -> MockResponse().setResponseCode(201).setBody("""{"id":"my-copy-1"}""")
                    request.method == "POST" && path == "/api/foods/update-snapshot" -> MockResponse().setBody("""{"message":"ok"}""")
                    request.method == "DELETE" && path.startsWith("/api/foods/") -> MockResponse().setBody("""{"status":"hidden"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        val api = SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        repo = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, SettingsRepository(context), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        runBlocking {
            db.foodDao().insertFoods(
                listOf(
                    FoodItem("oats", "Rolled Oats", "Acme", servingSize = 40f, servingUnit = "g", calories = 150f, protein = 5f, carbs = 27f, fat = 3f, isFavorite = true),
                    FoodItem("public-bread", "Whole Wheat Bread", "Acme", servingSize = 40f, servingUnit = "g", calories = 110f, protein = 5f, carbs = 27f, fat = 3f)
                )
            )
            db.foodEntryDao().insertEntry(
                FoodEntry(
                    id = "e1", foodId = "oats", foodName = "Rolled Oats", mealTypeId = "breakfast", mealName = "Breakfast",
                    quantity = 80f, unit = "g", calories = 300f, protein = 10f, carbs = 54f, fat = 6f,
                    entryDate = "2026-10-07", syncStatus = FoodEntry.SYNC_STATUS_SYNCED
                )
            )
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun body(methodPath: String): JsonObject =
        json.parseToJsonElement(calls.last { it.first.startsWith(methodPath) }.second).jsonObject

    @Test fun ownFoodIsUpdatedInPlaceOnSparkyAndOnThePhone() = runBlocking {
        val f = repo.loadFoodForEditing("oats").getOrThrow()
        assertTrue(f.owned)
        assertEquals("var-oats", f.sparky!!.variantId)
        assertEquals(4f, f.sparky!!.nutrients.fiber)

        val n = f.sparky!!.nutrients.copy(calories = 160f, protein = 6f)
        val saved = repo.saveFoodEdit(f, "Rolled Oats (dry)", "Acme", null, n, updatePastEntries = false).getOrThrow()

        assertEquals("Rolled Oats (dry)", body("PUT /api/foods/oats")["name"]!!.jsonPrimitive.content)
        val v = body("PUT /api/foods/food-variants/var-oats")
        assertEquals("oats", v["food_id"]!!.jsonPrimitive.content)
        assertEquals(160f, v["calories"]!!.jsonPrimitive.content.toFloat())
        assertEquals(4f, v["dietary_fiber"]!!.jsonPrimitive.content.toFloat())  // unchanged extras are sent back as they were
        assertFalse("sodium unknown, so not sent", v.containsKey("sodium"))
        assertTrue(calls.none { it.first.contains("update-snapshot") })

        val local = db.foodDao().getFoodById("oats")!!
        assertEquals("Rolled Oats (dry)", local.name)
        assertEquals(160f, local.calories)
        assertTrue("keeps phone-only details", local.isFavorite)
        assertEquals(saved, local)
        assertEquals("past entries untouched", 300f, db.foodEntryDao().getEntriesForFood("oats").single().calories)
    }

    @Test fun pastEntriesAreRewrittenWhenAsked() = runBlocking {
        val f = repo.loadFoodForEditing("oats").getOrThrow()
        repo.saveFoodEdit(f, "Rolled Oats", "Acme", null, f.sparky!!.nutrients.copy(calories = 160f), updatePastEntries = true).getOrThrow()
        val snap = body("POST /api/foods/update-snapshot")
        assertEquals("oats", snap["foodId"]!!.jsonPrimitive.content)
        assertEquals("var-oats", snap["variantId"]!!.jsonPrimitive.content)
        assertEquals("false", snap["syncImages"]!!.jsonPrimitive.content)
        val e = db.foodEntryDao().getEntriesForFood("oats").single()
        assertEquals(80f, e.quantity)
        assertEquals(320f, e.calories, 0.01f)   // 80 g at 160 kcal / 40 g
    }

    @Test fun sharedFoodIsSavedAsTheUsersOwnCopy() = runBlocking {
        val f = repo.loadFoodForEditing("public-bread").getOrThrow()
        assertFalse(f.owned)
        val saved = repo.saveFoodEdit(f, "Whole Wheat Bread", "Acme", "0123456789012", f.sparky!!.nutrients.copy(calories = 100f), false).getOrThrow()

        assertEquals("my-copy-1", saved.id)
        val created = body("POST /api/foods")
        assertFalse("barcode would make Sparky return the shared food", created.containsKey("barcode"))
        assertEquals(100f, created["calories"]!!.jsonPrimitive.content.toFloat())
        assertEquals("0123456789012", body("PUT /api/foods/my-copy-1")["barcode"]!!.jsonPrimitive.content)
        assertTrue("the shared food itself is never changed", calls.none { it.first == "PUT /api/foods/public-bread" })
        assertEquals(100f, db.foodDao().getFoodById("my-copy-1")!!.calories)
        assertNull("cached original replaced by the copy", db.foodDao().getFoodById("public-bread"))
    }

    @Test fun aFailedSaveChangesNothingOnThePhone() = runBlocking {
        failVariantPut = true
        val f = repo.loadFoodForEditing("oats").getOrThrow()
        val r = repo.saveFoodEdit(f, "Changed", "Acme", null, f.sparky!!.nutrients.copy(calories = 999f), false)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("Database is down"))
        assertEquals(150f, db.foodDao().getFoodById("oats")!!.calories)
    }

    @Test fun hidingRemovesTheFoodFromThePhone() = runBlocking {
        repo.deleteFood("oats", hideOnly = true).getOrThrow()
        assertTrue(calls.any { it.first == "DELETE /api/foods/oats?mode=hide" })
        assertNull(db.foodDao().getFoodById("oats"))
    }

    @Test fun formChecksAndMacroWarning() {
        val ok = FoodForm(name = "Oats", servingSize = "40", servingUnit = "g", calories = "150", protein = "5", carbs = "27", fat = "3")
        assertNull(ok.problem)
        assertFalse(ok.macrosDisagree)
        assertEquals("Give the food a name", ok.copy(name = " ").problem)
        assertEquals("Serving size must be more than 0", ok.copy(servingSize = "0").problem)
        assertTrue(ok.copy(calories = "500").macrosDisagree)
        assertEquals(SparkyNutrients(40f, "g", 150f, 5f, 27f, 3f), ok.nutrients())
    }

    @Test fun editorScreenSavesToSparky() {
        val vm = FoodEditorViewModel(repo, "oats")
        var finished: FoodItem? = null
        compose.setContent { ByteLogTheme(darkTheme = true) { FoodEditorScreen(vm, {}, { finished = it }) } }
        waitFor { compose.onAllNodesWithText("Rolled Oats").fetchSemanticsNodes().isNotEmpty() && !vm.state.value.loading }
        compose.onNodeWithContentDescription("Calories").performTextReplacement("165")
        compose.onNodeWithContentDescription("Update past diary entries").performScrollTo().performClick()
        waitFor { vm.state.value.updatePastEntries }
        compose.waitForIdle()
        shot("food_editor")
        compose.onNodeWithText("Save to Sparky").performClick()
        waitFor { compose.waitForIdle(); finished != null }
        assertEquals(165f, runBlocking { db.foodDao().getFoodById("oats") }!!.calories)
        assertTrue(calls.any { it.first == "POST /api/foods/update-snapshot" })
    }

    @Test fun editorExplainsCopyForSharedFood() {
        val vm = FoodEditorViewModel(repo, "public-bread")
        compose.setContent { ByteLogTheme(darkTheme = false) { FoodEditorScreen(vm, {}, {}) } }
        waitFor { !vm.state.value.loading }
        assertEquals(2, compose.onAllNodesWithText("Save as my food").fetchSemanticsNodes().size) // title and button
        shot("food_editor_copy")
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
}
