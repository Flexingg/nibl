package com.bytelog.app.research

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** What a search returned: text for the model to read, and the pages behind it. */
data class SearchResult(val text: String, val sources: List<WebSource>)

interface SearchBackend {
    val label: String
    suspend fun search(query: String): Result<SearchResult>
}

/**
 * Search through Gemini's Google Search grounding: a separate request that only has the search tool, so it works
 * for any provider that has no search of its own. Needs a Gemini API key.
 */
class GeminiGroundedSearch(
    private val apiKey: String,
    private val model: String = ProviderId.GEMINI.defaultModel,
    private val http: ProviderHttp = ProviderHttp(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com"
) : SearchBackend {
    override val label = "Google Search (via Gemini)"

    override suspend fun search(query: String): Result<SearchResult> = runCatching {
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", JsonPrimitive("Search the web and answer with the specific facts and numbers found, " +
                                "including the source name for each. Question: $query"))
                        })
                    })
                })
            })
            put("tools", buildJsonArray { add(buildJsonObject { put("google_search", JsonObject(emptyMap())) }) })
        }
        val r = http.postJson("$baseUrl/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to apiKey, "content-type" to "application/json"), body)
        val response = r.body
        if (r.code != 200 || response == null) throw ProviderException(ProviderHttp.errorMessage("Gemini search", r), r.code)
        val candidate = response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw ProviderException("Gemini search returned no answer.")
        val text = (candidate["content"].obj()?.get("parts") as? JsonArray)
            ?.mapNotNull { it.jsonObject.str("text") }?.joinToString("")?.trim().orEmpty()
        val sources = (candidate["groundingMetadata"].obj()?.get("groundingChunks") as? JsonArray)?.mapNotNull { c ->
            val web = c.jsonObject["web"].obj() ?: return@mapNotNull null
            val uri = web.str("uri") ?: return@mapNotNull null
            WebSource(web.str("title") ?: uri, uri)
        }.orEmpty().distinctBy { it.url }
        SearchResult(text.ifBlank { "No results." }, sources)
    }
}

/** Tavily: a search API made for LLM agents (the user's own key). */
class TavilySearch(
    private val apiKey: String,
    private val http: ProviderHttp = ProviderHttp(),
    private val baseUrl: String = "https://api.tavily.com"
) : SearchBackend {
    override val label = "Tavily"

    override suspend fun search(query: String): Result<SearchResult> = runCatching {
        val body = buildJsonObject {
            put("query", JsonPrimitive(query))
            put("max_results", JsonPrimitive(5))
            put("include_answer", JsonPrimitive(true))
        }
        val r = http.postJson("$baseUrl/search", mapOf("Authorization" to "Bearer $apiKey", "content-type" to "application/json"), body)
        val response = r.body
        if (r.code != 200 || response == null) throw ProviderException(ProviderHttp.errorMessage("Tavily", r), r.code)
        val results = response["results"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val text = buildString {
            response.str("answer")?.takeIf { it.isNotBlank() }?.let { append("Summary: ").append(it).append("\n\n") }
            results.forEachIndexed { i, o ->
                append(i + 1).append(". ").append(o.str("title") ?: o.str("url")).append(" (").append(o.str("url")).append(")\n")
                o.str("content")?.let { append(it.take(600)).append('\n') }
            }
        }.trim()
        SearchResult(text.ifBlank { "No results." }, results.mapNotNull { o -> o.str("url")?.let { WebSource(o.str("title") ?: it, it) } })
    }
}

/**
 * DuckDuckGo's HTML results page. No key needed, but DuckDuckGo often answers automated requests with a
 * challenge page, so this is a last resort.
 */
class DuckDuckGoSearch(
    private val client: OkHttpClient = WebFetcher.defaultClient(),
    private val baseUrl: String = "https://html.duckduckgo.com"
) : SearchBackend {
    override val label = "DuckDuckGo (best effort)"

    override suspend fun search(query: String): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/html/?q=" + URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder().url(url).header("User-Agent", WebFetcher.USER_AGENT).header("Accept-Language", "en-US,en;q=0.9").build()
            client.newCall(request).execute().use { r ->
                val html = r.body?.string().orEmpty()
                val parsed = parse(html)
                if (parsed.sources.isEmpty()) {
                    throw ProviderException(
                        if (r.code == 202 || html.contains("anomaly", ignoreCase = true) || html.contains("challenge", ignoreCase = true))
                            "DuckDuckGo blocked the automated search. Add a Tavily or Gemini key in Settings for reliable web search."
                        else "No results found."
                    )
                }
                parsed
            }
        }
    }

    companion object {
        fun parse(html: String): SearchResult {
            val doc = Jsoup.parse(html)
            val sources = mutableListOf<WebSource>()
            val text = StringBuilder()
            doc.select("div.result, div.web-result").forEach { block ->
                val a = block.selectFirst("a.result__a") ?: return@forEach
                val url = resolveRedirect(a.attr("href")) ?: return@forEach
                val title = a.text().trim().ifBlank { url }
                val snippet = block.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
                sources += WebSource(title, url)
                text.append(sources.size).append(". ").append(title).append(" (").append(url).append(")\n")
                if (snippet.isNotBlank()) text.append(snippet).append('\n')
            }
            return SearchResult(text.toString().trim(), sources.take(8))
        }

        /** DuckDuckGo links go through `//duckduckgo.com/l/?uddg=<encoded real url>`. */
        fun resolveRedirect(href: String): String? {
            val full = if (href.startsWith("//")) "https:$href" else href
            val uddg = Regex("""[?&]uddg=([^&]+)""").find(full)?.groupValues?.get(1)
            val target = if (uddg != null) URLDecoder.decode(uddg, "UTF-8") else full
            return target.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
    }
}

/**
 * Reads a web page for the model (`fetch_url`). Only public web addresses are allowed: the model must not be
 * talked into reading the user's home network or their Sparky server. Nothing from the app (no Sparky login)
 * is sent.
 */
class WebFetcher(
    private val client: OkHttpClient = defaultClient(),
    private val resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() }
) {
    suspend fun fetch(url: String, maxChars: Int = MAX_CHARS): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = try { URI(url.trim()) } catch (_: Exception) { throw IOException("That isn't a valid web address.") }
            checkPublic(uri)
            val request = Request.Builder().url(uri.toString()).header("User-Agent", USER_AGENT).header("Accept", "text/html,application/xhtml+xml").build()
            client.newCall(request).execute().use { r ->
                // Redirects are followed by OkHttp; check where we ended up too.
                checkPublic(URI(r.request.url.toString()))
                if (!r.isSuccessful) throw IOException("The site answered HTTP ${r.code}.")
                val body = r.body ?: throw IOException("The page was empty.")
                val bytes = ByteArray(MAX_BYTES)
                var n = 0
                body.byteStream().use { s -> while (n < MAX_BYTES) { val c = s.read(bytes, n, MAX_BYTES - n); if (c < 0) break; n += c } }
                val html = String(bytes, 0, n, body.contentType()?.charset() ?: Charsets.UTF_8)
                readable(html, r.request.url.toString(), maxChars)
            }
        }
    }

    private fun checkPublic(uri: URI) {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw IOException("Only http and https addresses can be read.")
        val host = uri.host ?: throw IOException("That isn't a valid web address.")
        if (host.endsWith(".local", true) || host.endsWith(".internal", true) || host.equals("localhost", true) || !host.contains('.') && !host.contains(':')) {
            throw IOException("Private or local addresses can't be read.")
        }
        val addresses = try { resolve(host) } catch (_: Exception) { throw IOException("Couldn't find that site.") }
        if (addresses.isEmpty() || addresses.any { isPrivate(it) }) throw IOException("Private or local addresses can't be read.")
    }

    companion object {
        const val MAX_CHARS = 12_000
        private const val MAX_BYTES = 2 * 1024 * 1024
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).followRedirects(true).build()

        fun isPrivate(a: InetAddress): Boolean {
            if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
            val b = a.address
            // 100.64.0.0/10 (carrier-grade NAT) and IPv6 unique-local fc00::/7
            if (b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64) return true
            if (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC) return true
            return false
        }

        /** Page text without scripts, navigation and ads, trimmed to [maxChars]. */
        fun readable(html: String, url: String, maxChars: Int = MAX_CHARS): String {
            val doc = Jsoup.parse(html, url)
            doc.select("script, style, noscript, nav, footer, header, aside, form, iframe, svg, [role=navigation], [aria-hidden=true]").remove()
            val title = doc.title().trim()
            val main = doc.selectFirst("article, main, [itemprop=recipeInstructions], #content, .content") ?: doc.body()
            val text = (main?.wholeText() ?: "").lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
            val out = (if (title.isNotBlank()) "Title: $title\n" else "") + text
            return if (out.length > maxChars) out.take(maxChars) + "\n[…page truncated]" else out
        }
    }
}
