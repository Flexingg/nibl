package com.bytelog.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.SparkyChatRepository
import com.bytelog.app.data.repository.WeightRepository
import com.bytelog.app.data.repository.dataStore
import com.bytelog.app.research.ProviderId
import com.bytelog.app.research.SecretCipher
import com.bytelog.app.ui.components.ResearchAssistantCard
import com.bytelog.app.ui.components.SparkyToolsCard
import com.bytelog.app.ui.screens.SparkyChatScreen
import com.bytelog.app.ui.theme.ByteLogTheme
import com.bytelog.app.ui.viewmodel.SparkyChatViewModel
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Renders the web research settings and chat. Keep PNGs with -Pbytelog.screenshots=<dir>. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class ResearchScreensRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class PlainCipher : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(token: String): String? = token.removePrefix("enc:")
    }

    private lateinit var server: MockWebServer
    private lateinit var db: ByteLogDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var chat: SparkyChatRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val replies = ConcurrentLinkedQueue<String>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() = runBlocking {
        context.dataStore.edit { it.clear() }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/mcp" -> if (request.body.readUtf8().contains("tools/list"))
                    MockResponse().setBody("""{"result":{"tools":[{"name":"sparky_manage_food","description":"Food","inputSchema":{"type":"object","properties":{"action":{"type":"string"}}}}]},"jsonrpc":"2.0","id":1}""")
                else MockResponse().setBody("""{"result":{"content":[{"type":"text","text":"ok"}]},"jsonrpc":"2.0","id":1}""")
                "/v1/messages" -> MockResponse().setBody(replies.poll() ?: """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(context, PlainCipher())
        val base = server.url("").toString().trimEnd('/')
        val api = SparkyApiClient(baseUrl = base, apiKey = "k")
        chat = SparkyChatRepository(
            api, FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, settings, scope, db.prepBatchDao()),
            WeightRepository(db.weightDao(), api, settings, scope), scope, settings, providerBaseUrl = base
        )
        Unit
    }

    @After fun tearDown() { server.shutdown(); db.close() }

    private fun shot(name: String) {
        val dir = System.getProperty("bytelog.screenshots") ?: return
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            check(System.currentTimeMillis() < end) { "Timed out waiting" }
            Thread.sleep(25)
        }
    }

    @Test fun settingsCards() {
        runBlocking { settings.saveResearchKey(ProviderId.GEMINI, "AIza-key"); settings.saveResearchProvider(ProviderId.DEEPSEEK) }
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ResearchAssistantCard(settings)
                    SparkyToolsCard(com.bytelog.app.data.remote.SparkyTools.allSlugs, {})
                }
            }
        }
        waitFor { compose.onAllNodes(androidx.compose.ui.test.hasText("WEB RESEARCH ASSISTANT"), true).fetchSemanticsNodes().isNotEmpty() }
        // DeepSeek has no search of its own; with a Gemini key saved, searches go through Google
        waitFor { compose.onAllNodes(androidx.compose.ui.test.hasText("via your Gemini key", substring = true), true).fetchSemanticsNodes().isNotEmpty() }
        shot("08-settings-research")
    }

    @Test fun chatWithWebSearchShowsSourcesAndApproval() {
        replies.add("""{"content":[
            {"type":"server_tool_use","id":"s1","name":"web_search","input":{"query":"chipotle chicken bowl nutrition"}},
            {"type":"web_search_tool_result","tool_use_id":"s1","content":[{"type":"web_search_result","url":"https://www.chipotle.com/nutrition","title":"Chipotle Nutrition Calculator"},{"type":"web_search_result","url":"https://fatsecret.com/chipotle-bowl","title":"Chipotle Chicken Bowl - FatSecret"}]},
            {"type":"text","text":"A Chipotle chicken burrito bowl (rice, black beans, fajita veggies, salsa) is about 655 kcal, 44 g protein, 63 g carbs and 22 g fat. Your usual lunch bowl is 540 kcal."}],"stop_reason":"end_turn"}""")
        runBlocking { settings.saveResearchKey(ProviderId.ANTHROPIC, "sk-ant-x") }
        compose.setContent { ByteLogTheme(darkTheme = true) { SparkyChatScreen(SparkyChatViewModel(chat), onNavigateBack = {}) } }
        waitFor { compose.onAllNodes(androidx.compose.ui.test.hasText("Web search"), true).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Web search", useUnmergedTree = true).performClick()
        waitFor { compose.onAllNodes(androidx.compose.ui.test.hasText("Web search on", substring = true), true).fetchSemanticsNodes().isNotEmpty() }

        runBlocking { chat.sendMessage("How many calories are in a Chipotle chicken bowl, and how does it compare to my usual lunch?", webResearch = true) }
        waitFor { chat.messages.value.any { it.viaWeb } }
        waitFor { compose.onAllNodes(androidx.compose.ui.test.hasText("Chipotle Nutrition Calculator", substring = true), true).fetchSemanticsNodes().isNotEmpty() }
        shot("09-chat-web-answer")

        // A change the assistant wants to make asks first
        replies.add("""{"content":[{"type":"tool_use","id":"t1","name":"sparky_manage_food","input":{"action":"log_food","food_name":"Chipotle chicken bowl","quantity":1,"meal_type":"lunch"}}],"stop_reason":"tool_use"}""")
        replies.add("""{"content":[{"type":"text","text":"Logged."}],"stop_reason":"end_turn"}""")
        val sending = scope.async { chat.sendMessage("Log it for lunch", webResearch = true) }
        waitFor { chat.pendingAction.value != null }
        waitFor { compose.onAllNodes(androidx.compose.ui.test.hasText("Allow this change?"), true).fetchSemanticsNodes().isNotEmpty() }
        shot("10-chat-approval")
        compose.onNodeWithText("Allow", useUnmergedTree = true).performClick()
        runBlocking { sending.await() }
        assertTrue(chat.messages.value.last().content == "Logged.")
    }
}
