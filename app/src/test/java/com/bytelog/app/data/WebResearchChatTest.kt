package com.bytelog.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.remote.SparkyTools
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.SparkyChatRepository
import com.bytelog.app.data.repository.WeightRepository
import com.bytelog.app.data.repository.dataStore
import com.bytelog.app.research.ProviderId
import com.bytelog.app.research.SecretCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The web research flow through the real chat repository: the user's message goes to the model (a mock of
 * Anthropic's Messages API), the model asks for Sparky tools (a mock of Sparky's /mcp), approvals are asked, and
 * the answer comes back with its sources. Same sandbox config as the other Robolectric tests.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class WebResearchChatTest {
    private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject


    private class PlainCipher : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(token: String): String? = token.removePrefix("enc:")
    }

    private lateinit var server: MockWebServer
    private lateinit var db: ByteLogDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var chat: SparkyChatRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelReplies = ConcurrentLinkedQueue<String>()
    private val modelRequests = ConcurrentLinkedQueue<String>()
    private val mcpCalls = ConcurrentLinkedQueue<String>()
    // Sparky's own AI (POST /api/chat): scripted replies, and what it was sent
    private val sparkyAiReplies = ConcurrentLinkedQueue<String>()
    private val sparkyAiRequests = ConcurrentLinkedQueue<String>()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val toolsList = """{"result":{"tools":[
        {"name":"sparky_search_foods","description":"Search foods","inputSchema":{"type":"object","properties":{"food_name":{"type":"string"}},"required":["food_name"]}},
        {"name":"sparky_manage_food","description":"Food actions","inputSchema":{"type":"object","properties":{"action":{"type":"string"},"food_name":{"type":"string"}}}},
        {"name":"sparky_manage_goals","description":"Goals","inputSchema":{"type":"object","properties":{"action":{"type":"string"}}}}]},"jsonrpc":"2.0","id":1}"""

    @Before fun setUp() = runBlocking {
        context.dataStore.edit { it.clear() }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/mcp" -> {
                    val body = request.body.readUtf8()
                    if (body.contains("tools/list")) MockResponse().setBody(toolsList)
                    else { mcpCalls += body; MockResponse().setBody("""{"result":{"content":[{"type":"text","text":"{\"found\":[{\"name\":\"Rice\",\"calories\":130}]}"}]},"jsonrpc":"2.0","id":1}""") }
                }
                request.path?.startsWith("/api/chat/ai-service-settings/active") == true -> MockResponse().setBody("""{"id":"svc-1"}""")
                request.path == "/api/chat" && request.method == "POST" -> {
                    sparkyAiRequests += request.body.readUtf8()
                    MockResponse().setBody(sparkyAiReplies.poll() ?: """{"content":"(no scripted reply)","executedTools":[]}""")
                }
                request.path == "/v1/messages" -> {
                    modelRequests += request.body.readUtf8()
                    MockResponse().setBody(modelReplies.poll() ?: """{"content":[{"type":"text","text":"(no scripted reply)"}],"stop_reason":"end_turn"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(context, PlainCipher())
        val base = server.url("").toString().trimEnd('/')
        val api = SparkyApiClient(baseUrl = base, apiKey = "sparky-key")
        val food = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, settings, scope, db.prepBatchDao())
        chat = SparkyChatRepository(api, food, WeightRepository(db.weightDao(), api, settings, scope), scope, settings, providerBaseUrl = base,
            planRepository = com.bytelog.app.data.repository.PlanRepository(db.plannedEntryDao(), food), today = { java.time.LocalDate.of(2026, 11, 14) })
        settings.saveResearchKey(ProviderId.ANTHROPIC, "sk-ant-test")
        Unit
    }

    @After fun tearDown() { server.shutdown(); db.close() }

    private fun reply(json: String) = modelReplies.add(json)

    private suspend fun waitForPending() = withTimeout(10_000) {
        while (chat.pendingAction.value == null) kotlinx.coroutines.delay(20)
        chat.pendingAction.value!!
    }

    @Test fun searchesTheWebAndSparkyAndAnswersWithSources() = runBlocking {
        reply("""{"content":[{"type":"text","text":"Let me look."},{"type":"tool_use","id":"toolu_1","name":"sparky_search_foods","input":{"food_name":"rice"}}],"stop_reason":"tool_use"}""")
        reply("""{"content":[
            {"type":"server_tool_use","id":"s1","name":"web_search","input":{"query":"chipotle rice calories"}},
            {"type":"web_search_tool_result","tool_use_id":"s1","content":[{"type":"web_search_result","url":"https://chipotle.com/nutrition","title":"Chipotle Nutrition"}]},
            {"type":"text","text":"Your rice is 130 kcal; Chipotle's is 210."}],"stop_reason":"end_turn"}""")

        chat.sendMessage("How does my rice compare to Chipotle's?", webResearch = true)

        val answer = chat.messages.value.last()
        assertEquals("assistant", answer.role)
        assertTrue(answer.viaWeb)
        assertEquals("Your rice is 130 kcal; Chipotle's is 210.", answer.content)
        assertEquals(listOf("https://chipotle.com/nutrition"), answer.webSources.map { it.url })
        assertEquals(listOf("sparky_search_foods"), answer.executedTools)
        assertTrue(chat.messages.value.any { it.role == "user" && it.content.startsWith("How does my rice") })
        assertEquals(1, mcpCalls.size)
        assertTrue(mcpCalls.single().contains("\"food_name\":\"rice\""))
        assertFalse(chat.isLoading.value)
        assertNull(chat.researchStatus.value)

        // The second model request carries Sparky's answer as a tool_result for the same call id.
        val second = modelRequests.toList()[1]
        assertTrue(second.contains("\"tool_use_id\":\"toolu_1\""))
        assertTrue(second.contains("Rice"))
    }

    @Test fun categoriesFromSettingsLimitTheTools() = runBlocking {
        settings.saveSparkyToolCategories(setOf("food"))
        reply("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""")
        chat.sendMessage("hi", webResearch = true)
        val sent = modelRequests.single()
        assertTrue(sent.contains("sparky_search_foods"))
        assertFalse("goals are switched off in Settings", sent.contains("sparky_manage_goals"))
        assertTrue(sent.contains("web_search_20260209"))
    }

    @Test fun missingKeyExplainsWhereToAddIt() = runBlocking {
        settings.saveResearchKey(ProviderId.ANTHROPIC, "")
        chat.sendMessage("hi", webResearch = true)
        assertTrue(chat.messages.value.last().content.contains("Settings → Web research assistant"))
        assertTrue(modelRequests.isEmpty())
        assertFalse(chat.isLoading.value)
    }

    @Test fun providerErrorsShowUpAsAFriendlyMessage() = runBlocking {
        modelReplies.add("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        // The queue gives HTTP 200, so replace the dispatcher's behaviour for this case with a 401
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/mcp" -> MockResponse().setBody(toolsList)
                "/v1/messages" -> MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"message":"invalid x-api-key"}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        chat.sendMessage("hi", webResearch = true)
        val last = chat.messages.value.last()
        assertTrue(last.content.contains("rejected the API key"))
        assertFalse(last.content.contains("sk-ant-test"))
        assertFalse(chat.isLoading.value)
    }

    @Test fun withoutTheWebToggleTheNormalSparkyChatIsUsed() = runBlocking {
        chat.sendMessage("hi") // no webResearch: goes to /api/chat (404 here), never to the model
        assertTrue(modelRequests.isEmpty())
        assertFalse(chat.messages.value.any { it.viaWeb })
    }

    // --- Meal plans ---

    private val planJson = """{"items":[{"date":"2026-11-15","meal":"Breakfast","food":"Oats","quantity":1,"unit":"cup","calories":300,"protein":10,"carbs":54,"fat":5},
        {"date":"2026-11-15","meal":"Dinner","food":"Salmon","quantity":150,"unit":"g","calories":310,"protein":34,"carbs":0,"fat":18},
        {"date":"2026-11-16","meal":"Lunch","food":"Turkey wrap","calories":420,"protein":30,"carbs":40,"fat":14}]}"""

    @Test fun sparkysChatWritesAMealPlanThatGoesOnThePlannedList() = runBlocking {
        val content = "Here's a 2-day plan built around your targets.\n\n```nibl-plan\n$planJson\n```"
        sparkyAiReplies.add("""{"content":${kotlinx.serialization.json.JsonPrimitive(content)},"executedTools":[]}""")

        chat.sendMessage("Make me a meal plan for the next 2 days")

        // Sparky's AI was asked for the plan in a form Nibl can save; the user's message in the chat doesn't show that
        val sent = sparkyAiRequests.single()
        assertTrue(sent.contains("nibl-plan")); assertTrue(sent.contains("2026-11-14"))
        assertEquals("Make me a meal plan for the next 2 days", chat.messages.value.first { it.role == "user" }.content)

        val answer = chat.messages.value.last()
        assertEquals("Here's a 2-day plan built around your targets.", answer.content)
        val plan = answer.mealPlan!!
        assertEquals(listOf("Oats", "Salmon", "Turkey wrap"), plan.items.map { it.food })
        assertFalse(answer.planAdded)
        // Nothing is planned or logged until the user taps Add
        assertTrue(db.plannedEntryDao().forDate("2026-11-15").first().isEmpty())

        val summary = chat.addPlan(answer.id)!!
        assertTrue(summary, summary.startsWith("3 foods over 2 days"))
        val day1 = db.plannedEntryDao().forDate("2026-11-15").first()
        assertEquals(listOf("Oats", "Salmon"), day1.map { it.foodName })
        assertEquals(listOf("Breakfast", "Dinner"), day1.map { it.mealName })
        assertEquals(150f, day1[1].quantity); assertEquals("g", day1[1].unit); assertEquals(34f, day1[1].protein)
        assertEquals(1, db.plannedEntryDao().forDate("2026-11-16").first().size)
        assertTrue(chat.messages.value.last().planAdded)

        // Tapping again doesn't add it twice
        chat.addPlan(answer.id)
        assertEquals(2, db.plannedEntryDao().forDate("2026-11-15").first().size)
    }

    @Test fun ordinaryMessagesAreSentAsTyped() = runBlocking {
        sparkyAiReplies.add("""{"content":"Logged.","executedTools":[]}""")
        chat.sendMessage("Log 2 eggs for breakfast")
        assertFalse(sparkyAiRequests.single().contains("nibl-plan"))
        assertNull(chat.messages.value.last().mealPlan)
    }

    @Test fun theWebAssistantsMealPlanCanBeAddedToo() = runBlocking {
        reply("""{"content":[{"type":"tool_use","id":"toolu_1","name":"plan_meals","input":$planJson}],"stop_reason":"tool_use"}""")
        reply("""{"content":[{"type":"text","text":"Here's your plan. Tap Add to Planned to save it."}],"stop_reason":"end_turn"}""")
        chat.sendMessage("Plan my meals for 2 days", webResearch = true)
        assertTrue(modelRequests.first().contains("plan_meals"))
        val answer = chat.messages.value.last()
        assertEquals(3, answer.mealPlan!!.items.size)
        assertTrue(mcpCalls.isEmpty())
        chat.addPlan(answer.id)
        assertEquals(1, db.plannedEntryDao().forDate("2026-11-16").first().size)
    }

    @Test fun theChatChangesThePlanWithAnApplyAndAnUndo() = runBlocking {
        val tomorrow = java.time.LocalDate.of(2026, 11, 15).toString()
        val plans = com.bytelog.app.data.repository.PlanRepository(db.plannedEntryDao(), FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'), apiKey = "k"), settings, scope, db.prepBatchDao()))
        db.plannedEntryDao().insert(listOf(
            com.bytelog.app.data.local.model.PlannedEntry("aaaaaaaa-0000-0000-0000-000000000001", null, "Salmon", null, "dinner", "Dinner", 150f, "g", 310f, 34f, 0f, 18f, tomorrow),
            com.bytelog.app.data.local.model.PlannedEntry("bbbbbbbb-0000-0000-0000-000000000002", null, "Oats", null, "breakfast", "Breakfast", 40f, "g", 150f, 5f, 27f, 3f, tomorrow)
        ))
        val content = "Swapped tomorrow's salmon for chicken.\n\n```nibl-plan-edit\n" +
            """{"changes":[{"op":"remove","ref":"aaaaaaaa"},{"op":"add","date":"$tomorrow","meal":"Dinner","food":"Chicken Breast","quantity":150,"unit":"g","calories":248,"protein":46,"carbs":0,"fat":5}]}""" +
            "\n```"
        sparkyAiReplies.add("""{"content":${kotlinx.serialization.json.JsonPrimitive(content)},"executedTools":[]}""")

        chat.sendMessage("Swap tomorrow's dinner for something cheaper")

        // Sparky's AI was shown the plan with refs; the user's message reads as typed
        val sent = sparkyAiRequests.single()
        assertTrue(sent.contains("aaaaaaaa: $tomorrow"))
        assertTrue(sent.contains("nibl-plan-edit"))
        assertEquals("Swap tomorrow's dinner for something cheaper", chat.messages.value.first { it.role == "user" }.content)

        val answer = chat.messages.value.last()
        assertEquals("Swapped tomorrow's salmon for chicken.", answer.content)
        assertEquals(2, answer.planEditLines.size)
        // Nothing changes until it's applied
        assertEquals(listOf("Salmon", "Oats").sorted(), db.plannedEntryDao().forDate(tomorrow).first().map { it.foodName }.sorted())

        assertEquals(2, chat.applyPlanEdit(answer.id))
        assertEquals(listOf("Chicken Breast", "Oats"), db.plannedEntryDao().forDate(tomorrow).first().map { it.foodName }.sorted())
        assertTrue(chat.messages.value.last().planEditApplied)

        chat.undoPlanEdit(answer.id)
        assertEquals(listOf("Oats", "Salmon"), db.plannedEntryDao().forDate(tomorrow).first().map { it.foodName }.sorted())
        assertFalse(chat.messages.value.last().planEditApplied)
        assertTrue(plans.upcoming(java.time.LocalDate.of(2026, 11, 14)).isNotEmpty())
    }

    @Test fun theWebAssistantReadsAndEditsThePlan() = runBlocking {
        val tomorrow = "2026-11-15"
        db.plannedEntryDao().insert(listOf(
            com.bytelog.app.data.local.model.PlannedEntry("cccccccc-0000-0000-0000-000000000003", null, "Pasta", null, "lunch", "Lunch", 1f, "serving", 600f, 20f, 100f, 10f, tomorrow)
        ))
        reply("""{"content":[{"type":"tool_use","id":"t1","name":"get_planned_meals","input":{}}],"stop_reason":"tool_use"}""")
        reply("""{"content":[{"type":"tool_use","id":"t2","name":"edit_plan","input":{"changes":[{"op":"update","ref":"cccccccc","quantity":0.5,"calories":300,"protein":10,"carbs":50,"fat":5}]}}],"stop_reason":"tool_use"}""")
        reply("""{"content":[{"type":"text","text":"Halved tomorrow's pasta. Tap Apply."}],"stop_reason":"end_turn"}""")
        chat.sendMessage("Halve tomorrow's lunch in my plan", webResearch = true)
        assertTrue(modelRequests.toList()[1].contains("cccccccc: $tomorrow"))
        val answer = chat.messages.value.last()
        assertEquals(1, answer.planEditLines.size)
        chat.applyPlanEdit(answer.id)
        val pasta = db.plannedEntryDao().forDate(tomorrow).first().single()
        assertEquals(0.5f, pasta.quantity); assertEquals(300f, pasta.calories)
    }

    // --- Hand-off to Sparky's own AI ---

    @Test fun theWebModelHandsItsFindingsToSparkyAiWhichLogsThem() = runBlocking {
        reply("""{"content":[
            {"type":"server_tool_use","id":"s1","name":"web_search","input":{"query":"chipotle chicken bowl nutrition"}},
            {"type":"web_search_tool_result","tool_use_id":"s1","content":[{"type":"web_search_result","url":"https://chipotle.com/nutrition","title":"Chipotle Nutrition"}]},
            {"type":"tool_use","id":"toolu_1","name":"ask_sparky","input":{"message":"Log 1 Chipotle chicken bowl for lunch today: 655 kcal, 44 g protein, 63 g carbs, 22 g fat (chipotle.com)"}}],"stop_reason":"tool_use"}""")
        sparkyAiReplies.add("""{"content":"Logged 1 Chipotle chicken bowl to lunch: 655 kcal.","action":"food_added","executedTools":[{"name":"sparky_manage_food","args":{}}]}""")
        reply("""{"content":[{"type":"text","text":"Logged your Chipotle bowl (655 kcal) for lunch."}],"stop_reason":"end_turn"}""")

        val sending = scope.async { chat.sendMessage("log a chipotle chicken bowl for lunch", webResearch = true) }
        val pending = waitForPending()
        assertEquals("ask_sparky", pending.toolName)
        assertTrue(pending.argsPreview.contains("655 kcal"))
        assertTrue("nothing is sent to Sparky's AI before approval", sparkyAiRequests.isEmpty())
        chat.resolvePendingAction(true)
        sending.await()

        // What Sparky's AI received: the findings, framed, with the user's tool categories
        val sent = obj(sparkyAiRequests.single())
        val messages = sent["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        val content = messages[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(content.contains("Log 1 Chipotle chicken bowl for lunch today: 655 kcal"))
        assertTrue(content.contains("web research assistant"))
        assertEquals("svc-1", sent["service_config_id"]!!.jsonPrimitive.content)
        assertEquals(SparkyTools.categories.map { it.slug }, sent["toolCategories"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("the web model did not write through MCP", 0, mcpCalls.size)

        // Its reply went back to the web model, and the chat shows the result
        assertTrue(modelRequests.toList().last().contains("Logged 1 Chipotle chicken bowl to lunch: 655 kcal."))
        val answer = chat.messages.value.last()
        assertEquals("Logged your Chipotle bowl (655 kcal) for lunch.", answer.content)
        assertTrue(answer.viaWeb)
        assertEquals(listOf("sparky_ai:sparky_manage_food"), answer.executedTools)
        assertEquals(listOf("https://chipotle.com/nutrition"), answer.webSources.map { it.url })
    }

    @Test fun sparkyAiCanAskBackAndTheWebModelAnswers() = runBlocking {
        reply("""{"content":[{"type":"tool_use","id":"t1","name":"ask_sparky","input":{"message":"Log a Chipotle bowl, 655 kcal"}}],"stop_reason":"tool_use"}""")
        sparkyAiReplies.add("""{"content":"Which meal?","executedTools":[{"name":"sparky_ask_user","args":{"question":"Which meal?","options":["Lunch","Dinner"],"mode":"choose"}}]}""")
        reply("""{"content":[{"type":"tool_use","id":"t2","name":"ask_sparky","input":{"message":"Lunch"}}],"stop_reason":"tool_use"}""")
        sparkyAiReplies.add("""{"content":"Logged to lunch.","executedTools":[{"name":"sparky_manage_food","args":{}}]}""")
        reply("""{"content":[{"type":"text","text":"Done: lunch."}],"stop_reason":"end_turn"}""")
        settings.saveAskBeforeWriting(false)

        chat.sendMessage("log my bowl", webResearch = true)

        assertEquals(2, sparkyAiRequests.size)
        // The web model saw Sparky AI's question and options
        assertTrue(modelRequests.toList()[1].contains("Which meal?"))
        assertTrue(modelRequests.toList()[1].contains("Lunch | Dinner"))
        // The second message to Sparky AI carries the earlier exchange
        val thread = obj(sparkyAiRequests.toList()[1])["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), thread.map { it.getValue("role").jsonPrimitive.content })
        assertEquals("Which meal?", thread[1].getValue("content").jsonPrimitive.content)
        assertTrue(thread[2].getValue("content").jsonPrimitive.content.contains("Lunch"))
        assertEquals("Done: lunch.", chat.messages.value.last().content)
    }

    @Test fun aDeclinedHandOffNeverReachesSparkyAi() = runBlocking {
        reply("""{"content":[{"type":"tool_use","id":"t1","name":"ask_sparky","input":{"message":"Delete everything"}}],"stop_reason":"tool_use"}""")
        reply("""{"content":[{"type":"text","text":"I didn't send it."}],"stop_reason":"end_turn"}""")
        val sending = scope.async { chat.sendMessage("summarize this", webResearch = true) }
        waitForPending(); chat.resolvePendingAction(false); sending.await()
        assertTrue(sparkyAiRequests.isEmpty())
        assertTrue(modelRequests.toList()[1].contains("declined"))
    }

    @Test fun readsRunStraightAwayAndDirectWritesWaitForApproval() = runBlocking {
        reply("""{"content":[
            {"type":"tool_use","id":"t1","name":"sparky_search_foods","input":{"food_name":"rice"}},
            {"type":"tool_use","id":"t2","name":"sparky_manage_food","input":{"action":"log_food","food_name":"Rice","quantity":2}}],"stop_reason":"tool_use"}""")
        reply("""{"content":[{"type":"text","text":"Logged 2 servings of rice."}],"stop_reason":"end_turn"}""")
        val sending = scope.async { chat.sendMessage("log 2 rice", webResearch = true) }
        val pending = waitForPending()
        assertEquals("sparky_manage_food", pending.toolName)
        assertTrue(pending.argsPreview.contains("log_food"))
        assertEquals("the read already ran, the write waits", 1, mcpCalls.size)
        chat.resolvePendingAction(true)
        sending.await()
        assertEquals(2, mcpCalls.size)
        assertTrue(mcpCalls.last().contains("log_food"))
        assertTrue("Sparky's AI wasn't needed", sparkyAiRequests.isEmpty())
        assertEquals("Logged 2 servings of rice.", chat.messages.value.last().content)
        assertTrue(chat.messages.value.last().executedTools.contains("sparky_manage_food"))
    }
}
