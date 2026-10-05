package com.bytelog.app.research

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

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

        override suspend fun next(toolResults: List<ToolResult>): LlmTurn {
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
                val response = request()
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
                            .map { ToolCall(it.str("id").orEmpty(), it.str("name").orEmpty(), (it["input"] as? JsonObject) ?: JsonObject(emptyMap())) }
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

        private suspend fun request(): JsonObject {
            while (true) {
                val body = buildJsonObject {
                    put("model", JsonPrimitive(model))
                    put("max_tokens", JsonPrimitive(16000))
                    put("system", JsonPrimitive(system))
                    put("messages", JsonArray(messages))
                    put("tools", tools)
                    if (useFallbacks) put("fallbacks", JsonPrimitive("default"))
                }
                val r = http.postJson("$baseUrl/v1/messages", headers(useFallbacks), body)
                if (r.code == 200 && r.body != null) return r.body
                if (useFallbacks && r.code == 400 && r.raw.contains("fallback", ignoreCase = true)) {
                    useFallbacks = false
                    continue
                }
                throw ProviderException(ProviderHttp.errorMessage("Claude", r), r.code)
            }
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
    }
}
