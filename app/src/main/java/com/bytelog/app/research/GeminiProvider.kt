package com.bytelog.app.research

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Gemini through `generateContent` (raw HTTPS, the user's own key).
 *
 * Gemini's built-in Google Search can't be assumed to mix with function calling on every model, so web search is a
 * plain function here (`web_search`, run by [GeminiGroundedSearch] in a separate grounded call). The model's `content`
 * is echoed back unchanged, which keeps Gemini 3's thought signatures on function calls intact.
 */
class GeminiProvider(
    private val apiKey: String,
    private val model: String = ProviderId.GEMINI.defaultModel,
    private val http: ProviderHttp = ProviderHttp(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com"
) : LlmProvider {

    override val id = ProviderId.GEMINI
    override val searchesWebNatively = false

    private fun headers() = mapOf("x-goog-api-key" to apiKey, "content-type" to "application/json")

    override fun newSession(system: String, history: List<ChatTurn>, userText: String, tools: List<ToolSpec>): LlmSession {
        val contents = mutableListOf<JsonObject>()
        history.forEach { contents += content(if (it.fromUser) "user" else "model", it.text) }
        contents += content("user", userText)
        val declarations = buildJsonArray {
            tools.forEach { t ->
                add(buildJsonObject {
                    put("name", JsonPrimitive(t.name))
                    put("description", JsonPrimitive(t.description))
                    val schema = SchemaUtil.forGemini(t.schema)
                    // Gemini rejects an empty `properties` on a parameterless function.
                    if ((schema["properties"] as? JsonObject)?.isNotEmpty() == true) put("parameters", schema)
                })
            }
        }
        return Session(system, contents, declarations)
    }

    private fun content(role: String, text: String) = buildJsonObject {
        put("role", JsonPrimitive(role))
        put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(text)) }) })
    }

    private inner class Session(
        private val system: String,
        private val contents: MutableList<JsonObject>,
        private val declarations: JsonArray
    ) : LlmSession {
        override suspend fun next(toolResults: List<ToolResult>): LlmTurn {
            if (toolResults.isNotEmpty()) {
                contents += buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("parts", buildJsonArray {
                        toolResults.forEach { r ->
                            add(buildJsonObject {
                                put("functionResponse", buildJsonObject {
                                    put("name", JsonPrimitive(r.name))
                                    // Echo Gemini's own call id when it sent one.
                                    if (!r.callId.startsWith(SYNTHETIC_ID)) put("id", JsonPrimitive(r.callId))
                                    put("response", buildJsonObject { put(if (r.isError) "error" else "result", JsonPrimitive(r.text)) })
                                })
                            })
                        }
                    })
                }
            }
            val body = buildJsonObject {
                put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(system)) }) }) })
                put("contents", JsonArray(contents))
                if (declarations.isNotEmpty()) put("tools", buildJsonArray { add(buildJsonObject { put("functionDeclarations", declarations) }) })
            }
            val r = http.postJson("$baseUrl/v1beta/models/$model:generateContent", headers(), body)
            val response = r.body
            if (r.code != 200 || response == null) throw ProviderException(ProviderHttp.errorMessage("Gemini", r), r.code)

            val blocked = response["promptFeedback"].obj()?.str("blockReason")
            val candidate = response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            val modelContent = candidate?.get("content")?.obj()
            if (candidate == null || modelContent == null) {
                return LlmTurn("", notice = if (blocked != null) "Gemini blocked this request ($blocked)." else "Gemini didn't return an answer.")
            }
            // Echo the model turn exactly as received (thought signatures live inside it).
            contents += modelContent

            val text = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            (modelContent["parts"] as? JsonArray)?.forEachIndexed { i, p ->
                val part = p as? JsonObject ?: return@forEachIndexed
                val isThought = part["thought"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true
                part.str("text")?.let { if (!isThought) text.append(it) }
                part["functionCall"].obj()?.let { fc ->
                    calls += ToolCall(fc.str("id") ?: "$SYNTHETIC_ID$i", fc.str("name").orEmpty(), fc["args"].obj() ?: JsonObject(emptyMap()))
                }
            }
            val finish = candidate.str("finishReason")
            val notice = when (finish) {
                "MAX_TOKENS" -> "The answer was cut short."
                "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> "Gemini stopped this answer ($finish)."
                else -> null
            }
            return LlmTurn(text.toString().trim(), calls, notice = notice)
        }
    }

    override val readsImages = true

    override suspend fun lookAt(instructions: String, images: List<Pair<String, String>>, schema: JsonObject): String {
        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(instructions)) }) }) })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("parts", buildJsonArray {
                        images.forEach { (data, mediaType) ->
                            add(buildJsonObject {
                                put("inline_data", buildJsonObject {
                                    put("mime_type", JsonPrimitive(mediaType))
                                    put("data", JsonPrimitive(data))
                                })
                            })
                        }
                        add(buildJsonObject { put("text", JsonPrimitive("Estimate this meal. Reply with only the JSON object.")) })
                    })
                })
            })
            // The shape is spelled out in the instructions; JSON mode keeps the reply to the object alone.
            put("generationConfig", buildJsonObject { put("responseMimeType", JsonPrimitive("application/json")) })
        }
        val r = http.postJson("$baseUrl/v1beta/models/$model:generateContent", headers(), body)
        val response = r.body
        if (r.code != 200 || response == null) throw ProviderException(ProviderHttp.errorMessage("Gemini", r), r.code)
        val parts = response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content").obj()?.get("parts") as? JsonArray
        val out = parts.orEmpty().mapNotNull { p ->
            val part = p as? JsonObject ?: return@mapNotNull null
            val isThought = part["thought"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true
            part.str("text")?.takeUnless { isThought }
        }.joinToString("")
        if (out.isBlank()) {
            val blocked = response["promptFeedback"].obj()?.str("blockReason")
            throw ProviderException(if (blocked != null) "Gemini blocked this photo ($blocked)." else "Gemini didn't return an estimate.")
        }
        return out
    }

    override suspend fun listModels(): Result<List<String>> = runCatching {
        val r = http.get("$baseUrl/v1beta/models?pageSize=200", headers())
        if (r.code != 200) throw ProviderException(ProviderHttp.errorMessage("Gemini", r), r.code)
        r.body?.get("models")?.jsonArray?.mapNotNull { m ->
            val o = m.jsonObject
            val methods = (o["supportedGenerationMethods"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            o.str("name")?.removePrefix("models/")?.takeIf { it.startsWith("gemini") && "generateContent" in methods }
        }.orEmpty()
    }

    companion object {
        /** Marks call ids the app made up because Gemini sent none. */
        const val SYNTHETIC_ID = "bytelog-call-"
    }
}
