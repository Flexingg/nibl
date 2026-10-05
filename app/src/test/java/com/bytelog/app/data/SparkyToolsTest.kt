package com.bytelog.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.remote.SparkyTools
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.MealTemplateRepository
import com.bytelog.app.data.repository.RecipeRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.SparkyChatRepository
import com.bytelog.app.data.repository.WeightRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Checks that the tool categories chosen in Settings reach Sparky's /api/chat request, and that the
 * recipe AI assist asks for the food tools (search = read, create = write).
 * Same sandbox config as the other Robolectric tests (see DatabaseMigrationTest).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class SparkyToolsTest {

    private lateinit var server: MockWebServer
    private lateinit var db: ByteLogDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var api: SparkyApiClient
    private val chatBodies = mutableListOf<String>()
    private var chatReply = """{"content":"ok","action":null,"executedTools":[]}"""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/chat/ai-service-settings/active") == true ->
                    MockResponse().setBody("""{"id":"svc-1"}""")
                request.path == "/api/chat" && request.method == "POST" -> {
                    chatBodies += request.body.readUtf8()
                    MockResponse().setBody(chatReply)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(context)
        api = SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        runBlocking { settings.saveSparkyToolCategories(SparkyTools.allSlugs) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun toolCategoriesOf(body: String): List<String>? =
        Json.parseToJsonElement(body).jsonObject["toolCategories"]?.jsonArray?.map { it.jsonPrimitive.content }

    private fun chatRepo(): SparkyChatRepository {
        val food = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, settings, scope, db.prepBatchDao())
        val weight = WeightRepository(db.weightDao(), api, settings, scope)
        return SparkyChatRepository(api, food, weight, scope, settings)
    }

    @Test
    fun settingsDefaultToEveryCategoryAndPersistChanges() = runBlocking {
        val fresh = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        fresh.saveSparkyToolCategories(setOf("food", "reports", "not-a-category"))
        assertEquals(setOf("food", "reports"), fresh.sparkyToolCategories.first())
        assertEquals(9, SparkyTools.allSlugs.size)
        assertEquals(listOf("food", "reports"), SparkyTools.normalize(setOf("reports", "food", "bogus")))
        assertTrue("reports is the only read-only category", SparkyTools.categories.filter { !it.writes }.map { it.slug } == listOf("reports"))
    }

    @Test
    fun chatSendsAllToolCategoriesByDefault() = runBlocking {
        chatRepo().sendMessage("Log 2 eggs for breakfast")
        assertEquals(1, chatBodies.size)
        assertEquals(SparkyTools.categories.map { it.slug }, toolCategoriesOf(chatBodies[0]))
    }

    @Test
    fun chatSendsTheUsersSelection() = runBlocking {
        settings.saveSparkyToolCategories(setOf("goals", "food"))
        chatRepo().sendMessage("What are my goals?")
        assertEquals(listOf("food", "goals"), toolCategoriesOf(chatBodies.last()))
    }

    @Test
    fun recipeAssistUsesFoodToolsAndParsesTheReply() = runBlocking {
        chatReply = """{"content":"Done.\n```json\n[{\"raw\":\"1 lb turkey\",\"name\":\"Ground Turkey\",\"quantity\":1,\"unit\":\"lb\",\"grams\":454}]\n```","executedTools":["sparky_search_foods","sparky_manage_food"]}"""
        val templates = MealTemplateRepository(db.mealTemplateDao(), api, scope)
        val food = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, settings, scope, db.prepBatchDao())
        val recipes = RecipeRepository(db.recipeDetailsDao(), db.ingredientAliasDao(), db.foodDao(), food, templates, api)

        val parsed = recipes.aiParseIngredients(listOf("1 lb turkey")).getOrThrow()
        assertEquals("Ground Turkey", parsed.single().name)
        assertEquals(454.0, parsed.single().grams!!, 1e-9)
        assertEquals(listOf("food"), toolCategoriesOf(chatBodies.last()))
        val prompt = Json.parseToJsonElement(chatBodies.last()).jsonObject["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(prompt.contains("search the user's foods") && prompt.contains("create a food"))
    }
}
