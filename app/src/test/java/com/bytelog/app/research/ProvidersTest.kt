package com.bytelog.app.research

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

private val searchTool = ToolSpec(
    "sparky_search_foods", "Search foods",
    obj("""{"type":"object","properties":{"food_name":{"type":"string"}},"required":["food_name"],"additionalProperties":false,"${'$'}schema":"x"}""")
)

private fun RecordedRequest.json() = obj(body.readUtf8())

class AnthropicProviderTest {
    private lateinit var server: MockWebServer
    private fun provider(key: String = "sk-ant-secret") = AnthropicProvider(key, "claude-opus-5-5", baseUrl = server.url("").toString().trimEnd('/'))

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun reply(json: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(json))

    @Test fun requestShapeAndToolCall() = runBlocking {
        reply("""{"content":[{"type":"thinking","thinking":"","signature":"sig-abc"},{"type":"text","text":"Checking."},
            {"type":"tool_use","id":"toolu_1","name":"sparky_search_foods","input":{"food_name":"rice"}}],"stop_reason":"tool_use"}""")
        val turn = provider().newSession("SYSTEM", listOf(ChatTurn(true, "hi"), ChatTurn(false, "hello")), "How many calories in rice?", listOf(searchTool)).next()

        assertEquals("Checking.", turn.text)
        assertEquals(listOf(ToolCall("toolu_1", "sparky_search_foods", obj("""{"food_name":"rice"}"""))), turn.toolCalls)

        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant-secret", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        assertEquals("server-side-fallback-2026-07-01", req.getHeader("anthropic-beta"))
        val body = req.json()
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("SYSTEM", body["system"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        val tools = body["tools"]!!.jsonArray
        assertEquals("web_search_20260209", tools[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("web_search", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
        val custom = tools[1].jsonObject
        assertEquals("sparky_search_foods", custom["name"]!!.jsonPrimitive.content)
        assertFalse("draft marker removed", custom["input_schema"]!!.jsonObject.containsKey("\$schema"))
        assertTrue("tool list is cached", custom.containsKey("cache_control"))
        val messages = body["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
    }

    @Test fun toolResultsGoBackInOneUserMessageAndTheAssistantTurnIsEchoedVerbatim() = runBlocking {
        reply("""{"content":[{"type":"thinking","thinking":"","signature":"sig-abc"},
            {"type":"tool_use","id":"toolu_1","name":"sparky_search_foods","input":{"food_name":"rice"}},
            {"type":"tool_use","id":"toolu_2","name":"sparky_search_foods","input":{"food_name":"beans"}}],"stop_reason":"tool_use"}""")
        reply("""{"content":[{"type":"text","text":"Rice is 130 kcal."}],"stop_reason":"end_turn"}""")
        val session = provider().newSession("S", emptyList(), "q", listOf(searchTool))
        val first = session.next()
        val second = session.next(listOf(ToolResult("toolu_1", "sparky_search_foods", "130 kcal"), ToolResult("toolu_2", "sparky_search_foods", "boom", isError = true)))
        assertEquals("Rice is 130 kcal.", second.text)
        assertTrue(second.toolCalls.isEmpty())

        server.takeRequest()
        val messages = server.takeRequest().json()["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val echoed = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("thinking", echoed[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("sig-abc", echoed[0].jsonObject["signature"]!!.jsonPrimitive.content)
        val results = messages[2].jsonObject["content"]!!.jsonArray
        assertEquals(2, results.size)
        assertEquals("tool_result", results[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", results[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
        assertNull(results[0].jsonObject["is_error"])
        assertEquals("true", results[1].jsonObject["is_error"]!!.jsonPrimitive.content)
        assertEquals(2, first.toolCalls.size)
    }

    @Test fun webSearchSourcesAndQueriesAreCollected() = runBlocking {
        reply("""{"content":[
            {"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"chipotle bowl calories"}},
            {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[
              {"type":"web_search_result","url":"https://chipotle.com/nutrition","title":"Nutrition | Chipotle","encrypted_content":"x"},
              {"type":"web_search_result","url":"https://example.com/a","title":"A"}]},
            {"type":"text","text":"About 650 kcal.","citations":[{"type":"web_search_result_location","url":"https://chipotle.com/nutrition","title":"Nutrition | Chipotle","cited_text":"x"}]}],
            "stop_reason":"end_turn"}""")
        val turn = provider().newSession("S", emptyList(), "q", emptyList()).next()
        assertEquals("About 650 kcal.", turn.text)
        assertEquals(listOf("chipotle bowl calories"), turn.searchQueries)
        assertEquals(listOf("https://chipotle.com/nutrition", "https://example.com/a"), turn.sources.map { it.url })
        assertEquals("Nutrition | Chipotle", turn.sources[0].title)
    }

    @Test fun pauseTurnIsResumedWithoutAnExtraUserMessage() = runBlocking {
        reply("""{"content":[{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"q"}}],"stop_reason":"pause_turn"}""")
        reply("""{"content":[{"type":"text","text":"Done."}],"stop_reason":"end_turn"}""")
        val turn = provider().newSession("S", emptyList(), "q", emptyList()).next()
        assertEquals("Done.", turn.text)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        val messages = server.takeRequest().json()["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("server_tool_use", messages[1].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun pauseTurnLoopIsBounded() = runBlocking {
        repeat(AnthropicProvider.MAX_CONTINUATIONS + 1) {
            reply("""{"content":[{"type":"server_tool_use","id":"s$it","name":"web_search","input":{"query":"q"}}],"stop_reason":"pause_turn"}""")
        }
        val turn = provider().newSession("S", emptyList(), "q", emptyList()).next()
        assertTrue(turn.notice!!.contains("too many steps"))
    }

    @Test fun refusalIsReportedNotThrown() = runBlocking {
        reply("""{"content":[],"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber","explanation":"Not something I can help with."}}""")
        val turn = provider().newSession("S", emptyList(), "q", emptyList()).next()
        assertTrue(turn.notice!!.contains("declined"))
        assertTrue(turn.notice!!.contains("Not something I can help with."))
    }

    @Test fun fallbackOptionIsDroppedWhenTheApiRejectsIt() = runBlocking {
        reply("""{"type":"error","error":{"type":"invalid_request_error","message":"fallbacks: not supported for this account"}}""", 400)
        reply("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""")
        val session = provider().newSession("S", emptyList(), "q", emptyList())
        assertEquals("ok", session.next().text)
        val first = server.takeRequest(); val retry = server.takeRequest()
        assertTrue(first.json().containsKey("fallbacks"))
        assertFalse(retry.json().containsKey("fallbacks"))
        assertNull(retry.getHeader("anthropic-beta"))
    }

    @Test fun badKeyGivesAFriendlyMessageWithoutTheKey() = runBlocking {
        reply("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""", 401)
        val e = runCatching { provider("sk-ant-super-secret").newSession("S", emptyList(), "q", emptyList()).next() }.exceptionOrNull() as ProviderException
        assertTrue(e.message!!.contains("rejected the API key"))
        assertFalse(e.message!!.contains("super-secret"))
        assertEquals(401, e.code)
    }

    @Test fun sonnet55IsTheDefaultAndRunsAtMediumEffort() = runBlocking {
        assertEquals("claude-sonnet-5-5", ProviderId.ANTHROPIC.defaultModel)
        reply("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""")
        AnthropicProvider("k", ProviderId.ANTHROPIC.defaultModel, baseUrl = server.url("").toString().trimEnd('/'))
            .newSession("S", emptyList(), "q", emptyList()).next()
        val body = server.takeRequest().json()
        assertEquals("claude-sonnet-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        // Sonnet 5.5 rejects disabled thinking and forced tool use, so neither is sent
        assertFalse(body.containsKey("thinking")); assertFalse(body.containsKey("tool_choice"))
        assertFalse(body.containsKey("temperature"))
    }

    @Test fun effortIsOnlySentToModelsThatTakeIt() = runBlocking {
        listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-sonnet-4-6", "claude-opus-4-8", "claude-fable-5-1").forEach { assertTrue(it, AnthropicProvider.supportsEffort(it)) }
        listOf("claude-haiku-4-5", "claude-sonnet-4-5", "claude-3-5-sonnet").forEach { assertFalse(it, AnthropicProvider.supportsEffort(it)) }
        reply("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""")
        AnthropicProvider("k", "claude-haiku-4-5", baseUrl = server.url("").toString().trimEnd('/')).newSession("S", emptyList(), "q", emptyList()).next()
        assertFalse(server.takeRequest().json().containsKey("output_config"))
    }

    @Test fun effortIsDroppedWhenTheApiRejectsIt() = runBlocking {
        reply("""{"type":"error","error":{"type":"invalid_request_error","message":"output_config.effort: not supported for this model"}}""", 400)
        reply("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""")
        assertEquals("ok", provider().newSession("S", emptyList(), "q", emptyList()).next().text)
        val first = server.takeRequest(); val retry = server.takeRequest()
        assertTrue(first.json().containsKey("output_config")); assertFalse(retry.json().containsKey("output_config"))
    }

    private fun sse(vararg events: String) = server.enqueue(MockResponse().setResponseCode(200).setHeader("content-type", "text/event-stream")
        .setBody(events.joinToString("") { e -> "event: x\ndata: $e\n\n" }))

    @Test fun answersStreamAndTheTurnIsRebuiltExactly() = runBlocking {
        sse(
            """{"type":"message_start","message":{"id":"m1","content":[]}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-xyz"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Checking "}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"your foods."}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://usda.gov/rice","title":"USDA"}}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_9","name":"sparky_search_foods","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"food_na"}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"me\":\"rice\"}"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
            """{"type":"message_stop"}"""
        )
        reply("""{"content":[{"type":"text","text":"Rice is 130 kcal."}],"stop_reason":"end_turn"}""")
        val session = provider().newSession("S", emptyList(), "q", listOf(searchTool))
        val pieces = mutableListOf<String>()
        val turn = session.next(emptyList()) { pieces += it }
        assertEquals(listOf("Checking ", "your foods."), pieces)
        assertEquals("Checking your foods.", turn.text)
        assertEquals(listOf(ToolCall("toolu_9", "sparky_search_foods", obj("""{"food_name":"rice"}"""))), turn.toolCalls)
        assertEquals(listOf("https://usda.gov/rice"), turn.sources.map { it.url })

        session.next(listOf(ToolResult("toolu_9", "sparky_search_foods", "130 kcal")))
        val first = server.takeRequest().json()
        assertEquals("streamed", "true", first["stream"]!!.jsonPrimitive.content)
        assertEquals("the conversation is cached as it grows", "ephemeral", first["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // The rebuilt turn goes back exactly: signature, text with its citation, the full tool input
        val echoed = server.takeRequest().json()["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("thinking", "text", "tool_use"), echoed.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("sig-xyz", echoed[0]["signature"]!!.jsonPrimitive.content)
        assertEquals("", echoed[0]["thinking"]!!.jsonPrimitive.content)
        assertEquals("Checking your foods.", echoed[1]["text"]!!.jsonPrimitive.content)
        assertEquals(1, echoed[1]["citations"]!!.jsonArray.size)
        assertEquals(obj("""{"food_name":"rice"}"""), echoed[2]["input"])
    }

    @Test fun aBrokenStreamedToolInputIsReportedNotRun() = runBlocking {
        sse(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"sparky_search_foods","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"food_name\":\"ri"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""
        )
        val call = provider().newSession("S", emptyList(), "q", listOf(searchTool)).next().toolCalls.single()
        assertEquals("The arguments were not valid JSON.", call.parseError)
    }

    @Test fun aStreamErrorBecomesAProviderError() = runBlocking {
        sse("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        val e = runCatching { provider().newSession("S", emptyList(), "q", emptyList()).next() }.exceptionOrNull()
        assertTrue(e is ProviderException); assertTrue(e!!.message!!.contains("Overloaded"))
    }

    @Test fun automaticCachingIsDroppedWhenTheApiRejectsIt() = runBlocking {
        reply("""{"type":"error","error":{"type":"invalid_request_error","message":"cache_control: Extra inputs are not permitted"}}""", 400)
        reply("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""")
        assertEquals("ok", provider().newSession("S", emptyList(), "q", emptyList()).next().text)
        assertTrue(server.takeRequest().json().containsKey("cache_control")); assertFalse(server.takeRequest().json().containsKey("cache_control"))
    }

    @Test fun photosAreSentAsImagesWithTheReplyShape() = runBlocking {
        reply("""{"content":[{"type":"text","text":"{\"summary\":\"x\"}"}],"stop_reason":"end_turn"}""")
        val out = provider().lookAt("INSTRUCTIONS", listOf("QUJD" to "image/jpeg"), MealPhotoGuesser.SCHEMA)
        assertEquals("""{"summary":"x"}""", out)
        val body = server.takeRequest().json()
        assertEquals("INSTRUCTIONS", body["system"]!!.jsonPrimitive.content)
        val image = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", image["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("json_schema", body["output_config"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun photosFallBackToPlainJsonWhenTheSchemaIsRejected() = runBlocking {
        reply("""{"type":"error","error":{"type":"invalid_request_error","message":"output_config.format: structured outputs are not supported"}}""", 400)
        reply("""{"content":[{"type":"text","text":"{}"}],"stop_reason":"end_turn"}""")
        provider().lookAt("I", listOf("QUJD" to "image/png"), MealPhotoGuesser.SCHEMA)
        server.takeRequest()
        val retry = server.takeRequest().json()
        assertFalse(retry["output_config"]?.jsonObject?.containsKey("format") ?: false)
    }

    @Test fun listsClaudeModels() = runBlocking {
        reply("""{"data":[{"id":"claude-opus-5-5","type":"model"},{"id":"claude-sonnet-5-5","type":"model"},{"id":"other","type":"model"}]}""")
        assertEquals(listOf("claude-opus-5-5", "claude-sonnet-5-5"), provider().listModels().getOrThrow())
        assertEquals("/v1/models?limit=100", server.takeRequest().path)
    }
}

class GeminiProviderTest {
    private lateinit var server: MockWebServer
    private fun provider() = GeminiProvider("AIza-secret", "gemini-test", baseUrl = server.url("").toString().trimEnd('/'))

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun reply(json: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(json))

    @Test fun functionCallingRoundTripKeepsThoughtSignatures() = runBlocking {
        reply("""{"candidates":[{"content":{"role":"model","parts":[
            {"text":"internal reasoning","thought":true},
            {"functionCall":{"name":"sparky_search_foods","args":{"food_name":"rice"}},"thoughtSignature":"SIGNATURE-1"}]},"finishReason":"STOP"}]}""")
        reply("""{"candidates":[{"content":{"role":"model","parts":[{"text":"Rice is 130 kcal."}]},"finishReason":"STOP"}]}""")

        val session = provider().newSession("SYSTEM", listOf(ChatTurn(true, "hi"), ChatTurn(false, "hello")), "rice?", listOf(searchTool))
        val first = session.next()
        assertEquals("", first.text)
        assertEquals("sparky_search_foods", first.toolCalls.single().name)
        assertEquals(obj("""{"food_name":"rice"}"""), first.toolCalls.single().args)
        assertTrue("thought text isn't shown", !first.text.contains("internal"))

        val second = session.next(listOf(ToolResult(first.toolCalls.single().id, "sparky_search_foods", "130 kcal")))
        assertEquals("Rice is 130 kcal.", second.text)

        val r1 = server.takeRequest()
        assertEquals("/v1beta/models/gemini-test:generateContent", r1.path)
        assertEquals("AIza-secret", r1.getHeader("x-goog-api-key"))
        val b1 = r1.json()
        assertEquals("SYSTEM", b1["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("user", "model", "user"), b1["contents"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val decl = b1["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject
        assertEquals("sparky_search_foods", decl["name"]!!.jsonPrimitive.content)
        val params = decl["parameters"]!!.jsonObject
        assertFalse(params.containsKey("\$schema")); assertFalse(params.containsKey("additionalProperties"))

        val contents = server.takeRequest().json()["contents"]!!.jsonArray
        // history (user, model), the question (user), the model's function call (model), our function response (user)
        assertEquals(listOf("user", "model", "user", "model", "user"), contents.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val echoedModel = contents[3].jsonObject["parts"]!!.jsonArray
        assertEquals("SIGNATURE-1", echoedModel[1].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
        val fr = contents[4].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("sparky_search_foods", fr["name"]!!.jsonPrimitive.content)
        assertEquals("130 kcal", fr["response"]!!.jsonObject["result"]!!.jsonPrimitive.content)
        assertFalse("made-up call ids aren't sent back", fr.containsKey("id"))
    }

    @Test fun geminiCallIdsAreEchoedWhenPresent() = runBlocking {
        reply("""{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"abc123","name":"sparky_search_foods","args":{}}}]}}]}""")
        reply("""{"candidates":[{"content":{"role":"model","parts":[{"text":"x"}]}}]}""")
        val s = provider().newSession("S", emptyList(), "q", listOf(searchTool))
        val call = s.next().toolCalls.single()
        assertEquals("abc123", call.id)
        s.next(listOf(ToolResult(call.id, call.name, "boom", isError = true)))
        server.takeRequest()
        val fr = server.takeRequest().json()["contents"]!!.jsonArray.last().jsonObject["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("abc123", fr["id"]!!.jsonPrimitive.content)
        assertEquals("boom", fr["response"]!!.jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test fun blockedPromptsAreExplained() = runBlocking {
        reply("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
        assertTrue(provider().newSession("S", emptyList(), "q", emptyList()).next().notice!!.contains("SAFETY"))
    }

    @Test fun errorsAreFriendlyAndKeyFree() = runBlocking {
        reply("""{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""", 400)
        val e = runCatching { provider().newSession("S", emptyList(), "q", emptyList()).next() }.exceptionOrNull() as ProviderException
        assertTrue(e.message!!.contains("Gemini rejected the request"))
        assertFalse(e.message!!.contains("AIza-secret"))
        reply("""{"error":{"code":404,"message":"models/gemini-test is not found"}}""", 404)
        val e2 = runCatching { provider().newSession("S", emptyList(), "q", emptyList()).next() }.exceptionOrNull() as ProviderException
        assertTrue(e2.message!!.contains("Check the model name"))
    }

    @Test fun listsGenerateContentModels() = runBlocking {
        reply("""{"models":[{"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-embedding","supportedGenerationMethods":["embedContent"]},{"name":"models/other","supportedGenerationMethods":["generateContent"]}]}""")
        assertEquals(listOf("gemini-3.8-flash"), provider().listModels().getOrThrow())
    }
}

class DeepSeekProviderTest {
    private lateinit var server: MockWebServer
    private fun provider() = DeepSeekProvider("ds-secret", "deepseek-flash", baseUrl = server.url("").toString().trimEnd('/'))

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun reply(json: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(json))

    @Test fun toolCallRoundTripKeepsReasoningContent() = runBlocking {
        reply("""{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"reasoning_content":"need to look it up",
            "tool_calls":[{"id":"call_1","type":"function","function":{"name":"sparky_search_foods","arguments":"{\"food_name\":\"rice\"}"}}]}}]}""")
        reply("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Rice is 130 kcal."}}]}""")
        val s = provider().newSession("SYSTEM", listOf(ChatTurn(true, "hi")), "rice?", listOf(searchTool))
        val first = s.next()
        assertEquals(listOf(ToolCall("call_1", "sparky_search_foods", obj("""{"food_name":"rice"}"""))), first.toolCalls)
        assertEquals("Rice is 130 kcal.", s.next(listOf(ToolResult("call_1", "sparky_search_foods", "130 kcal"))).text)

        val r1 = server.takeRequest()
        assertEquals("/chat/completions", r1.path)
        assertEquals("Bearer ds-secret", r1.getHeader("Authorization"))
        val b1 = r1.json()
        assertEquals("deepseek-flash", b1["model"]!!.jsonPrimitive.content)
        assertEquals(listOf("system", "user", "user"), b1["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val fn = b1["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
        assertEquals("sparky_search_foods", fn["name"]!!.jsonPrimitive.content)
        assertFalse(fn["parameters"]!!.jsonObject.containsKey("\$schema"))

        val messages = server.takeRequest().json()["messages"]!!.jsonArray
        assertEquals(listOf("system", "user", "user", "assistant", "tool"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("need to look it up", messages[3].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("call_1", messages[4].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("130 kcal", messages[4].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun invalidToolArgumentsAreFlagged() = runBlocking {
        reply("""{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"",
            "tool_calls":[{"id":"c","type":"function","function":{"name":"fetch_url","arguments":"{not json"}}]}}]}""")
        val call = provider().newSession("S", emptyList(), "q", emptyList()).next().toolCalls.single()
        assertTrue(call.parseError!!.contains("not valid JSON"))
    }

    @Test fun truncatedAnswersAreFlagged() = runBlocking {
        reply("""{"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"Partial"}}]}""")
        val t = provider().newSession("S", emptyList(), "q", emptyList()).next()
        assertEquals("Partial", t.text); assertTrue(t.notice!!.contains("cut short"))
    }

    @Test fun authErrorsAreFriendly() = runBlocking {
        reply("""{"error":{"message":"Authentication Fails","type":"authentication_error"}}""", 401)
        val e = runCatching { provider().newSession("S", emptyList(), "q", emptyList()).next() }.exceptionOrNull() as ProviderException
        assertTrue(e.message!!.contains("rejected the API key")); assertFalse(e.message!!.contains("ds-secret"))
    }

    @Test fun listsModels() = runBlocking {
        reply("""{"object":"list","data":[{"id":"deepseek-flash"},{"id":"deepseek-v4-pro"}]}""")
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), provider().listModels().getOrThrow())
    }
}
