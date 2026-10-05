package com.bytelog.app.research

import com.bytelog.app.data.remote.SparkyApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The MCP client against responses captured from the real MCP SDK server, configured like SparkyFitnessServer's
 * `/mcp` route (stateless, JSON responses).
 */
class McpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SparkyApiClient
    private lateinit var backend: McpToolBackend

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'), apiKey = "sparky-key-123")
        backend = McpToolBackend(api)
    }

    @After fun tearDown() = server.shutdown()

    private fun reply(body: String, contentType: String = "application/json") =
        server.enqueue(MockResponse().setHeader("Content-Type", contentType).setBody(body))

    @Test fun listsToolsWithSchemas() = runBlocking {
        reply(resource("mcp-tools-list.json"))
        val tools = backend.listTools().getOrThrow()
        assertEquals(listOf("sparky_search_foods", "sparky_manage_food"), tools.map { it.name })
        assertEquals("Search foods", tools[0].description)
        assertTrue(tools[0].schema["properties"]!!.toString().contains("food_name"))
        assertNull("the draft marker is removed", tools[0].schema["\$schema"])

        val req = server.takeRequest()
        assertEquals("/mcp", req.path)
        // The real server answers 406 unless both are accepted.
        assertEquals("application/json, text/event-stream", req.getHeader("Accept"))
        assertEquals("Bearer sparky-key-123", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("\"method\":\"tools/list\""))
    }

    @Test fun toolListIsCachedBriefly() = runBlocking {
        reply(resource("mcp-tools-list.json"))
        backend.listTools().getOrThrow()
        backend.listTools().getOrThrow()
        assertEquals(1, server.requestCount)
    }

    @Test fun callsATool() = runBlocking {
        reply(resource("mcp-call-ok.json"))
        val out = backend.call("sparky_search_foods", obj("""{"food_name":"chicken"}""")).getOrThrow()
        assertFalse(out.isError)
        assertTrue(out.text.contains("\"calories\":165"))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"method\":\"tools/call\""))
        assertTrue(body.contains("\"name\":\"sparky_search_foods\""))
        assertTrue(body.contains("\"food_name\":\"chicken\""))
    }

    @Test fun toolErrorsAreReturnedAsResultsNotExceptions() = runBlocking {
        reply(resource("mcp-call-error.json"))
        val out = backend.call("sparky_manage_food", obj("""{"action":"create_food"}""")).getOrThrow()
        assertTrue(out.isError)
        assertTrue(out.text.contains("calories required"))
    }

    @Test fun invalidArgumentsComeBackAsAnErrorResult() = runBlocking {
        reply(resource("mcp-call-invalid.json"))
        val out = backend.call("sparky_search_foods", obj("{}")).getOrThrow()
        assertTrue(out.isError)
        assertTrue(out.text.contains("food_name"))
    }

    @Test fun jsonRpcErrorsBecomeFailures() = runBlocking {
        reply(resource("mcp-unknown-method.json"))
        val e = api.mcpRequest("nope/nothing").exceptionOrNull()
        assertNotNull(e)
        assertTrue(e!!.message!!.contains("Method not found"))
    }

    @Test fun httpFailuresSayWhatToDo() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(api.mcpRequest("tools/list").exceptionOrNull()!!.message!!.contains("Sign in again"))
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(api.mcpRequest("tools/list").exceptionOrNull()!!.message!!.contains("no MCP endpoint"))
    }

    @Test fun readsEventStreamReplies() {
        val sse = "event: message\ndata: {\"result\":{\"tools\":[]},\"jsonrpc\":\"2.0\",\"id\":1}\n\n"
        val parsed = SparkyApiClient.parseMcpBody(sse, "text/event-stream")
        assertNotNull(parsed)
        assertTrue(parsed!!.containsKey("result"))
        assertNull(SparkyApiClient.parseMcpBody("not json", "application/json"))
    }

    /** Optional: run against a real MCP server, e.g. `-Pbytelog.mcp.url=http://127.0.0.1:PORT -Pbytelog.mcp.token=test-token`. */
    @Test fun againstALiveMcpServer() = runBlocking {
        val url = System.getProperty("bytelog.mcp.url")
        assumeTrue("set bytelog.mcp.url to run", !url.isNullOrBlank())
        val live = McpToolBackend(SparkyApiClient(baseUrl = url!!, apiKey = System.getProperty("bytelog.mcp.token") ?: "test-token"))
        val tools = live.listTools().getOrThrow()
        assertTrue(tools.any { it.name == "sparky_search_foods" })
        val ok = live.call("sparky_search_foods", obj("""{"food_name":"rice"}""")).getOrThrow()
        assertFalse(ok.isError)
        assertTrue(live.call("sparky_manage_food", obj("""{"action":"create_food"}""")).getOrThrow().isError)
        assertTrue(live.call("sparky_search_foods", obj("{}")).getOrThrow().isError)
    }
}
