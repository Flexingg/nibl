package com.bytelog.app.research

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class WebToolsTest {
    private lateinit var server: MockWebServer
    private fun base() = server.url("").toString().trimEnd('/')

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    // --- Gemini grounded search ---

    @Test fun geminiGroundedSearchReturnsTextAndSources() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"The Chipotle chicken bowl is about 650 kcal (chipotle.com)."}]},
            "groundingMetadata":{"webSearchQueries":["chipotle chicken bowl"],"groundingChunks":[
              {"web":{"uri":"https://chipotle.com/nutrition","title":"chipotle.com"}},{"web":{"uri":"https://chipotle.com/nutrition","title":"dup"}},
              {"web":{"uri":"https://example.com/x","title":"example.com"}}]}}]}"""))
        val r = GeminiGroundedSearch("AIza-x", "gemini-test", baseUrl = base()).search("chipotle chicken bowl").getOrThrow()
        assertTrue(r.text.contains("650 kcal"))
        assertEquals(listOf("https://chipotle.com/nutrition", "https://example.com/x"), r.sources.map { it.url })
        val req = server.takeRequest()
        assertEquals("/v1beta/models/gemini-test:generateContent", req.path)
        val body = obj(req.body.readUtf8())
        // Only the search tool: no function declarations in this request.
        assertTrue(body["tools"]!!.jsonArray[0].jsonObject.containsKey("google_search"))
        assertEquals(1, body["tools"]!!.jsonArray.size)
    }

    @Test fun geminiSearchErrorsAreFailures() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"quota"}}"""))
        val e = GeminiGroundedSearch("k", baseUrl = base()).search("x").exceptionOrNull()!!
        assertTrue(e.message!!.contains("quota"))
    }

    // --- Tavily ---

    @Test fun tavilyResultsAreFormattedForTheModel() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"answer":"About 650 kcal.","results":[
            {"title":"Chipotle Nutrition","url":"https://chipotle.com/nutrition","content":"Chicken bowl 650 kcal"},
            {"title":"Other","url":"https://example.com","content":"more"}]}"""))
        val r = TavilySearch("tvly-x", baseUrl = base()).search("chipotle").getOrThrow()
        assertTrue(r.text.startsWith("Summary: About 650 kcal."))
        assertTrue(r.text.contains("1. Chipotle Nutrition (https://chipotle.com/nutrition)"))
        assertEquals(2, r.sources.size)
        val req = server.takeRequest()
        assertEquals("/search", req.path)
        assertEquals("Bearer tvly-x", req.getHeader("Authorization"))
    }

    // --- DuckDuckGo ---

    private val ddgHtml = """
        <div class="result results_links results_links_deep web-result"><div class="links_main links_deep result__body">
          <h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.chipotle.com%2Fnutrition&amp;rut=abc">Chipotle Nutrition Calculator</a></h2>
          <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">Build your meal and see calories and macros.</a></div></div>
        <div class="result results_links results_links_deep web-result"><div class="links_main links_deep result__body">
          <h2 class="result__title"><a rel="nofollow" class="result__a" href="https://example.com/direct">Direct link</a></h2>
          <a class="result__snippet">Snippet two</a></div></div>"""

    @Test fun duckDuckGoResultsAreParsedAndRedirectsResolved() {
        val r = DuckDuckGoSearch.parse(ddgHtml)
        assertEquals(listOf("https://www.chipotle.com/nutrition", "https://example.com/direct"), r.sources.map { it.url })
        assertTrue(r.text.contains("Build your meal and see calories and macros."))
        assertEquals("Chipotle Nutrition Calculator", r.sources[0].title)
    }

    @Test fun duckDuckGoChallengePageIsRecognisedAsBlocked() = runBlocking {
        // A real response captured from html.duckduckgo.com: HTTP 202 with a bot-check page and no results.
        server.enqueue(MockResponse().setResponseCode(202).setBody(resource("ddg-challenge.html")))
        val e = DuckDuckGoSearch(baseUrl = base()).search("chipotle").exceptionOrNull()!!
        assertTrue(e.message!!.contains("blocked"))
        assertTrue(e.message!!.contains("Tavily or Gemini"))
    }

    // --- Page fetching and its network guard ---

    private fun addr(vararg octets: Int) = InetAddress.getByAddress(ByteArray(octets.size) { octets[it].toByte() })

    @Test fun privateAddressesAreRecognised() {
        listOf(addr(127, 0, 0, 1), addr(10, 0, 2, 2), addr(192, 168, 1, 5), addr(172, 16, 0, 9), addr(169, 254, 169, 254), addr(100, 64, 0, 1), addr(0, 0, 0, 0))
            .forEach { assertTrue("$it should be private", WebFetcher.isPrivate(it)) }
        assertTrue(WebFetcher.isPrivate(InetAddress.getByName("::1")))
        assertTrue(WebFetcher.isPrivate(InetAddress.getByName("fd00::1")))
        assertFalse(WebFetcher.isPrivate(addr(93, 184, 216, 34)))
        assertFalse(WebFetcher.isPrivate(addr(8, 8, 8, 8)))
    }

    private fun fetcher(resolveTo: InetAddress = addr(93, 184, 216, 34)) = WebFetcher(resolve = { listOf(resolveTo) })

    @Test fun theModelCannotReadTheUsersNetwork() = runBlocking {
        val f = WebFetcher()
        listOf("http://localhost:3010/api/x", "http://127.0.0.1/", "http://192.168.1.10/admin", "http://10.0.2.2:3010/mcp",
            "http://169.254.169.254/latest/meta-data/", "http://sparky.local/", "http://myserver/", "file:///etc/passwd", "ftp://example.com/")
            .forEach { url ->
                val e = f.fetch(url).exceptionOrNull()
                assertTrue("$url should be refused", e != null)
                assertTrue("$url: ${e!!.message}", e.message!!.contains("Private") || e.message!!.contains("Only http"))
            }
    }

    @Test fun aPublicNameThatResolvesToAPrivateAddressIsRefused() = runBlocking {
        val e = fetcher(addr(192, 168, 0, 1)).fetch("https://innocent.example.com/").exceptionOrNull()!!
        assertTrue(e.message!!.contains("Private"))
    }

    @Test fun fetchesAndCleansAPage() = runBlocking {
        server.enqueue(MockResponse().setBody("""<html><head><title>Best Chili</title><script>alert(1)</script><style>p{}</style></head>
            <body><nav>Home | About</nav><main><h1>Best Chili</h1><p>Brown 1 lb turkey.</p><p>Simmer 30 minutes.</p></main><footer>© site</footer></body></html>"""))
        val text = fetcher().fetch("http://127.0.0.1:${server.port}/chili").getOrThrow()
        assertTrue(text.startsWith("Title: Best Chili"))
        assertTrue(text.contains("Brown 1 lb turkey."))
        assertFalse(text.contains("alert(1)")); assertFalse(text.contains("Home | About")); assertFalse(text.contains("© site"))
        // Nothing from the app is sent to the site
        val req = server.takeRequest()
        assertEquals(null, req.getHeader("Authorization")); assertEquals(null, req.getHeader("Cookie"))
    }

    @Test fun longPagesAreTruncated() {
        val long = "<html><body><main>" + "<p>word ".repeat(10_000) + "</main></body></html>"
        val out = WebFetcher.readable(long, "https://x.test", 500)
        assertTrue(out.length < 600); assertTrue(out.endsWith("[…page truncated]"))
    }

    @Test fun httpErrorsAreExplained() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(fetcher().fetch("http://127.0.0.1:${server.port}/x").exceptionOrNull()!!.message!!.contains("HTTP 403"))
    }
}
