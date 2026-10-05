package com.bytelog.app.research

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * DeepSeek through its OpenAI-compatible chat completions API (raw HTTPS, the user's own key).
 *
 * DeepSeek has no built-in web search, so `web_search` is a plain function backed by [SearchBackend]. The assistant
 * message is echoed back unchanged, which keeps any `reasoning_content` it sent.
 */
class DeepSeekProvider(
    private val apiKey: String,
    private val model: String = ProviderId.DEEPSEEK.defaultModel,
    private val http: ProviderHttp = ProviderHttp(),
    private val baseUrl: String = "https://api.deepseek.com"
) : LlmProvider {

    override val id = ProviderId.DEEPSEEK
    override val searchesWebNatively = false

    private fun headers() = mapOf("Authorization" to "Bearer $apiKey", "content-type" to "application/json")

    override fun newSession(system: String, history: List<ChatTurn>, userText: String, tools: List<ToolSpec>): LlmSession {
        val messages = mutableListOf<JsonObject>()
        messages += msg("system", system)
        history.forEach { messages += msg(if (it.fromUser) "user" else "assistant", it.text) }
        messages += msg("user", userText)
        val toolDefs = buildJsonArray {
            tools.forEach { t ->
                add(buildJsonObject {
                    put("type", JsonPrimitive("function"))
                    put("function", buildJsonObject {
                        put("name", JsonPrimitive(t.name))
                        put("description", JsonPrimitive(t.description))
                        put("parameters", SchemaUtil.asObjectSchema(t.schema))
                    })
                })
            }
        }
        return Session(messages, toolDefs)
    }

    private fun msg(role: String, text: String) = buildJsonObject {
        put("role", JsonPrimitive(role))
        put("content", JsonPrimitive(text))
    }

    private inner class Session(private val messages: MutableList<JsonObject>, private val tools: JsonArray) : LlmSession {
        override suspend fun next(toolResults: List<ToolResult>): LlmTurn {
            toolResults.forEach { r ->
                messages += buildJsonObject {
                    put("role", JsonPrimitive("tool"))
                    put("tool_call_id", JsonPrimitive(r.callId))
                    put("content", JsonPrimitive(if (r.isError) "Error: ${r.text}" else r.text))
                }
            }
            val body = buildJsonObject {
                put("model", JsonPrimitive(model))
                put("messages", JsonArray(messages))
                if (tools.isNotEmpty()) put("tools", tools)
                put("stream", JsonPrimitive(false))
            }
            val r = http.postJson("$baseUrl/chat/completions", headers(), body)
            val response = r.body
            if (r.code != 200 || response == null) throw ProviderException(ProviderHttp.errorMessage("DeepSeek", r), r.code)

            val choice = response["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val message = choice?.get("message").obj() ?: return LlmTurn("", notice = "DeepSeek didn't return an answer.")
            messages += message // verbatim, including reasoning_content

            val calls = (message["tool_calls"] as? JsonArray)?.mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                val fn = o["function"].obj() ?: return@mapNotNull null
                val raw = fn.str("arguments").orEmpty().ifBlank { "{}" }
                val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ToolCall(
                    id = o.str("id").orEmpty(),
                    name = fn.str("name").orEmpty(),
                    args = parsed ?: JsonObject(emptyMap()),
                    parseError = if (parsed == null) "The arguments were not valid JSON." else null
                )
            }.orEmpty()
            val notice = when (choice?.str("finish_reason")) {
                "length" -> "The answer was cut short."
                "content_filter" -> "DeepSeek filtered this answer."
                else -> null
            }
            return LlmTurn(message.str("content").orEmpty().trim(), calls, notice = notice)
        }
    }

    override suspend fun listModels(): Result<List<String>> = runCatching {
        val r = http.get("$baseUrl/models", headers())
        if (r.code != 200) throw ProviderException(ProviderHttp.errorMessage("DeepSeek", r), r.code)
        r.body?.get("data")?.jsonArray?.mapNotNull { it.jsonObject.str("id") }.orEmpty()
    }
}
