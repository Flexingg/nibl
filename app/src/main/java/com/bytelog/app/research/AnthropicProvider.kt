package com.bytelog.app.research

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Claude through the Messages API (raw HTTPS, the user's own key).
 *
 * Web search is Anthropic's server-side tool (`web_search_20260209`), so Claude searches by itself. Sparky's tools
 * and `fetch_url` are ordinary client tools. Assistant content (including thinking and server-tool blocks) is stored
 * and echoed back unchanged, which the API requires. A `pause_turn` (the server's own tool loop hit its limit) is
 * resumed by re-sending the turn with no extra message.
 */
class AnthropicProvider(
    private val apiKey: String,
    private val model: String = ProviderId.ANTHROPIC.defaultModel,
    private val http: ProviderHttp = ProviderHttp(),
    private val baseUrl: String = "https://api.anthropic.com",
    private val maxSearches: Int = 5
) : LlmProvider {

    override val id = ProviderId.ANTHROPIC
    override val searchesWebNatively = true

    override fun newSession(system: String, history: List<ChatTurn>, userText: String, tools: List<ToolSpec>): LlmSession {
        val messages = mutableListOf<JsonObject>()
        history.forEach { messages += message(if (it.fromUser) "user" else "assistant", it.text) }
        messages += message("user", userText)
        val toolDefs = buildJsonArray {
            add(buildJsonObject {
                put("type", JsonPrimitive("web_search_20260209"))
                put("name", JsonPrimitive("web_search"))
                put("max_uses", JsonPrimitive(maxSearches))
            })
            tools.forEachIndexed { i, t ->
                add(buildJsonObject {
                    put("name", JsonPrimitive(t.name))
                    put("description", JsonPrimitive(t.description))
                    put("input_schema", SchemaUtil.asObjectSchema(t.schema))
                    // Sparky's tool list is large and identical on every step of a question: cache it.
                    if (i == tools.lastIndex) put("cache_control", buildJsonObject { put("type", JsonPrimitive("ephemeral")) })
                })
            }
        }
        return Session(system, messages, toolDefs)
    }

    private fun message(role: String, text: String) = buildJsonObject {
        put("role", JsonPrimitive(role))
        put("content", JsonPrimitive(text))
    }

    private fun headers(withFallbackBeta: Boolean): Map<String, String> = buildMap {
        put("x-api-key", apiKey)
        put("anthropic-version", "2023-06-01")
        put("content-type", "application/json")
        if (withFallbackBeta) put("anthropic-beta", "server-side-fallback-2026-07-01")
    }

    private inner class Session(
        private val system: String,
        private val messages: MutableList<JsonObject>,
        private val tools: JsonArray
    ) : LlmSession {
        // If the account or model rejects the fallback option, drop it for the rest of the conversation.
        private var useFallbacks = true
        // Same for the effort setting, which only some models accept.
        private var useEffort = supportsEffort(model)

        // If the account rejects automatic caching of the conversation, drop it for the rest of the conversation.
        private var useAutoCache = true
        // Tool calls whose streamed input wasn't valid JSON (by tool_use id), from the latest response.
        private var badInputs: Set<String> = emptySet()

        override suspend fun next(toolResults: List<ToolResult>): LlmTurn = next(toolResults) {}

        override suspend fun next(toolResults: List<ToolResult>, onText: (String) -> Unit): LlmTurn {
            if (toolResults.isNotEmpty()) {
                // All results of one assistant turn go back in a single user message.
                messages += buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        toolResults.forEach { r ->
                            add(buildJsonObject {
                                put("type", JsonPrimitive("tool_result"))
                                put("tool_use_id", JsonPrimitive(r.callId))
                                put("content", JsonPrimitive(r.text))
                                if (r.isError) put("is_error", JsonPrimitive(true))
                            })
                        }
                    })
                }
            }

            val text = StringBuilder()
            val sources = linkedMapOf<String, WebSource>()
            val queries = mutableListOf<String>()
            var continuations = 0
            while (true) {
                val response = request(onText)
                val content = response["content"]?.jsonArray ?: JsonArray(emptyList())
                collect(content, text, sources, queries)
                val stop = response.str("stop_reason")
                // Echo the assistant turn back exactly as received.
                messages += buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", content)
                }
                when (stop) {
                    "pause_turn" -> {
                        if (++continuations > MAX_CONTINUATIONS) {
                            return LlmTurn(text.toString().trim(), sources = sources.values.toList(), searchQueries = queries,
                                notice = "The search took too many steps and was stopped.")
                        }
                        continue
                    }
                    "tool_use" -> {
                        val calls = content.mapNotNull { it as? JsonObject }
                            .filter { it.str("type") == "tool_use" }
                            .map {
                                val id = it.str("id").orEmpty()
                                ToolCall(id, it.str("name").orEmpty(), (it["input"] as? JsonObject) ?: JsonObject(emptyMap()),
                                    parseError = if (id in badInputs) "The arguments were not valid JSON." else null)
                            }
                        return LlmTurn(text.toString().trim(), calls, sources.values.toList(), queries)
                    }
                    "refusal" -> {
                        val why = response["stop_details"].obj()?.str("explanation")
                        return LlmTurn(text.toString().trim(), sources = sources.values.toList(), searchQueries = queries,
                            notice = "Claude declined to answer this" + (why?.let { ": $it" } ?: "."))
                    }
                    "max_tokens" -> return LlmTurn(text.toString().trim(), sources = sources.values.toList(), searchQueries = queries,
                        notice = "The answer was cut short.")
                    else -> return LlmTurn(text.toString().trim(), sources = sources.values.toList(), searchQueries = queries)
                }
            }
        }

        /** One request, streamed: [onText] gets the answer's text as it arrives. Returns the whole response. */
        private suspend fun request(onText: (String) -> Unit): JsonObject {
            while (true) {
                val body = buildJsonObject {
                    put("model", JsonPrimitive(model))
                    put("max_tokens", JsonPrimitive(32000))
                    put("system", JsonPrimitive(system))
                    put("messages", JsonArray(messages))
                    put("tools", tools)
                    put("stream", JsonPrimitive(true))
                    // Each step of a question re-sends the whole conversation so far. Automatic caching moves a cache
                    // point to the end of it on every request, so the next step reads all of this one from the cache.
                    if (useAutoCache) put("cache_control", buildJsonObject { put("type", JsonPrimitive("ephemeral")) })
                    if (useFallbacks) put("fallbacks", JsonPrimitive("default"))
                    // Multi-step tool use doesn't need the default depth of thinking: "medium" keeps answers quick and cheap.
                    if (useEffort) put("output_config", buildJsonObject { put("effort", JsonPrimitive("medium")) })
                }
                val stream = StreamedMessage(onText)
                val r = http.postStream("$baseUrl/v1/messages", headers(useFallbacks), body, stream::accept)
                if (r.code == 200 && r.streamed) {
                    val rebuilt = stream.result()
                    badInputs = stream.badInputs
                    return rebuilt
                }
                if (r.code == 200 && r.body != null) { badInputs = emptySet(); return r.body }
                if (useAutoCache && r.code == 400 && r.raw.contains("cache_control", ignoreCase = true)) {
                    useAutoCache = false
                    continue
                }
                if (useFallbacks && r.code == 400 && r.raw.contains("fallback", ignoreCase = true)) {
                    useFallbacks = false
                    continue
                }
                if (useEffort && r.code == 400 && (r.raw.contains("effort", ignoreCase = true) || r.raw.contains("output_config", ignoreCase = true))) {
                    useEffort = false
                    continue
                }
                throw ProviderException(ProviderHttp.errorMessage("Claude", r), r.code)
            }
        }
    }

    /**
     * Rebuilds a message from its stream of events, block by block, exactly as the API would have returned it whole:
     * thinking blocks keep their signatures, tool calls get their full input, text keeps its citations. The rebuilt
     * turn is echoed back on the next request, so it must match what the model wrote.
     */
    private class StreamedMessage(private val onText: (String) -> Unit) {
        private val blocks = sortedMapOf<Int, MutableMap<String, JsonElement>>()
        private val text = HashMap<Int, StringBuilder>()
        private val thinking = HashMap<Int, StringBuilder>()
        private val inputJson = HashMap<Int, StringBuilder>()
        private val citations = HashMap<Int, MutableList<JsonElement>>()
        private var stopReason: String? = null
        private var stopDetails: JsonElement? = null
        var badInputs: Set<String> = emptySet(); private set

        fun accept(event: JsonObject) {
            val index = event["index"]?.let { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() }
            when (event.str("type")) {
                "content_block_start" -> {
                    val i = index ?: return
                    val block = (event["content_block"] as? JsonObject)?.toMutableMap() ?: return
                    blocks[i] = block
                    when (block.str("type")) {
                        "text" -> text[i] = StringBuilder(block.str("text").orEmpty())
                        "thinking" -> thinking[i] = StringBuilder(block.str("thinking").orEmpty())
                    }
                    (block["citations"] as? JsonArray)?.let { citations[i] = it.toMutableList() }
                }
                "content_block_delta" -> {
                    val i = index ?: return
                    val delta = event["delta"] as? JsonObject ?: return
                    when (delta.str("type")) {
                        "text_delta" -> delta.str("text")?.let { text.getOrPut(i) { StringBuilder() }.append(it); onText(it) }
                        "thinking_delta" -> delta.str("thinking")?.let { thinking.getOrPut(i) { StringBuilder() }.append(it) }
                        "signature_delta" -> delta["signature"]?.let { sig -> blocks[i]?.put("signature", sig) }
                        "input_json_delta" -> delta.str("partial_json")?.let { inputJson.getOrPut(i) { StringBuilder() }.append(it) }
                        "citations_delta" -> delta["citation"]?.let { citations.getOrPut(i) { mutableListOf() }.add(it) }
                    }
                }
                "message_delta" -> (event["delta"] as? JsonObject)?.let { d ->
                    d.str("stop_reason")?.let { stopReason = it }
                    d["stop_details"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { stopDetails = it }
                }
                "error" -> {
                    val msg = (event["error"] as? JsonObject)?.str("message") ?: "the answer stopped part way"
                    throw ProviderException("Claude had a problem: $msg")
                }
            }
        }

        fun result(): JsonObject {
            val bad = mutableSetOf<String>()
            val content = blocks.map { (i, block) ->
                text[i]?.let { block["text"] = JsonPrimitive(it.toString()) }
                thinking[i]?.let { block["thinking"] = JsonPrimitive(it.toString()) }
                citations[i]?.let { block["citations"] = JsonArray(it) }
                inputJson[i]?.let { raw ->
                    val parsed = if (raw.isBlank()) JsonObject(emptyMap())
                    else runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw.toString()) as? JsonObject }.getOrNull()
                    if (parsed != null) block["input"] = parsed else block.str("id")?.let { bad += it }
                }
                JsonObject(block)
            }
            badInputs = bad
            return buildJsonObject {
                put("content", JsonArray(content))
                stopReason?.let { put("stop_reason", JsonPrimitive(it)) }
                stopDetails?.let { put("stop_details", it) }
            }
        }

        private fun Map<String, JsonElement>.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
    }

    override val readsImages = true

    override suspend fun lookAt(instructions: String, images: List<Pair<String, String>>, schema: JsonObject): String {
        var structured = true
        var effort = supportsEffort(model)
        while (true) {
            val body = buildJsonObject {
                put("model", JsonPrimitive(model))
                put("max_tokens", JsonPrimitive(8000))
                put("system", JsonPrimitive(instructions))
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", buildJsonArray {
                            images.forEach { (data, mediaType) ->
                                add(buildJsonObject {
                                    put("type", JsonPrimitive("image"))
                                    put("source", buildJsonObject {
                                        put("type", JsonPrimitive("base64"))
                                        put("media_type", JsonPrimitive(mediaType))
                                        put("data", JsonPrimitive(data))
                                    })
                                })
                            }
                            add(buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(if (structured) "Estimate this meal." else "Estimate this meal. Reply with only the JSON object, no other text."))
                            })
                        })
                    })
                })
                if (structured || effort) put("output_config", buildJsonObject {
                    if (effort) put("effort", JsonPrimitive("medium"))
                    if (structured) put("format", buildJsonObject {
                        put("type", JsonPrimitive("json_schema"))
                        put("schema", schema)
                    })
                })
            }
            val r = http.postJson("$baseUrl/v1/messages", headers(false), body)
            val response = r.body
            if (r.code == 200 && response != null) {
                if (response.str("stop_reason") == "refusal") throw ProviderException("Claude declined to estimate this photo.")
                val out = response["content"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }.joinToString("")
                if (out.isBlank()) throw ProviderException("Claude didn't return an estimate.")
                return out
            }
            if (r.code == 400 && structured && (r.raw.contains("format", ignoreCase = true) || r.raw.contains("schema", ignoreCase = true))) { structured = false; continue }
            if (r.code == 400 && effort && r.raw.contains("effort", ignoreCase = true)) { effort = false; continue }
            throw ProviderException(ProviderHttp.errorMessage("Claude", r), r.code)
        }
    }

    private fun collect(content: JsonArray, text: StringBuilder, sources: MutableMap<String, WebSource>, queries: MutableList<String>) {
        content.mapNotNull { it as? JsonObject }.forEach { block ->
            when (block.str("type")) {
                "text" -> {
                    block.str("text")?.let { text.append(it) }
                    (block["citations"] as? JsonArray)?.forEach { c ->
                        val o = c as? JsonObject ?: return@forEach
                        val url = o.str("url") ?: return@forEach
                        sources.putIfAbsent(url, WebSource(o.str("title") ?: url, url))
                    }
                }
                "server_tool_use" -> if (block.str("name") == "web_search") block["input"].obj()?.str("query")?.let { queries += it }
                "web_search_tool_result" -> (block["content"] as? JsonArray)?.forEach { r ->
                    val o = r as? JsonObject ?: return@forEach
                    val url = o.str("url") ?: return@forEach
                    sources.putIfAbsent(url, WebSource(o.str("title") ?: url, url))
                }
            }
        }
    }

    override suspend fun listModels(): Result<List<String>> = runCatching {
        val r = http.get("$baseUrl/v1/models?limit=100", headers(false))
        if (r.code != 200) throw ProviderException(ProviderHttp.errorMessage("Claude", r), r.code)
        r.body?.get("data")?.jsonArray?.mapNotNull { it.jsonObject.str("id") }?.filter { it.startsWith("claude") }.orEmpty()
    }

    companion object {
        const val MAX_CONTINUATIONS = 5

        /** Models that take `output_config.effort` (Opus 4.6+, Sonnet 4.6+, the Fable/Mythos line); Haiku 4.5 and older don't. */
        fun supportsEffort(model: String): Boolean =
            Regex("""^claude-(opus-(5|4-[678])|sonnet-(5|4-6)|fable|mythos)""").containsMatchIn(model.trim())
    }
}
