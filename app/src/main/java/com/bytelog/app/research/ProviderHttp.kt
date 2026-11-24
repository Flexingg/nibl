package com.bytelog.app.research

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal JSON-over-HTTPS client shared by the providers. It never logs requests (they hold the user's API key)
 * and its errors never include the key.
 */
class ProviderHttp(
    private val client: OkHttpClient = defaultClient()
) {
    data class Response(val code: Int, val body: JsonObject?, val raw: String, val streamed: Boolean = false)

    suspend fun postJson(url: String, headers: Map<String, String>, body: JsonObject): Response = send(url, headers, body)

    suspend fun get(url: String, headers: Map<String, String>): Response = send(url, headers, null)

    /**
     * Posts [body] and, when the server answers with a stream of server-sent events, hands each event's JSON to
     * [onEvent] as it arrives and returns a [Response] with a null body. Any other answer (an error, or a server that
     * ignored the stream request) comes back as a normal [Response], so callers handle both.
     */
    suspend fun postStream(url: String, headers: Map<String, String>, body: JsonObject, onEvent: (JsonObject) -> Unit): Response =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.header("accept", "text/event-stream")
            builder.post(body.toString().toRequestBody("application/json".toMediaType()))
            try {
                client.newCall(builder.build()).execute().use { r ->
                    val type = r.header("content-type").orEmpty()
                    val source = r.body?.source()
                    if (r.code != 200 || !type.contains("event-stream", ignoreCase = true) || source == null) {
                        val raw = r.body?.string().orEmpty()
                        val parsed = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
                        return@use Response(r.code, parsed, raw)
                    }
                    val data = StringBuilder()
                    fun dispatch() {
                        if (data.isEmpty()) return
                        val event = try { Json.parseToJsonElement(data.toString()) as? JsonObject } catch (_: Exception) { null }
                        data.setLength(0)
                        if (event != null) onEvent(event)
                    }
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> dispatch()
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").removePrefix(" "))
                            }
                            // `event:`, `id:` and `:` comment lines carry nothing the JSON doesn't
                        }
                    }
                    dispatch()
                    Response(r.code, null, "", streamed = true)
                }
            } catch (e: IOException) {
                throw ProviderException("Couldn't reach the AI service: ${e.message ?: e.javaClass.simpleName}")
            }
        }

    private suspend fun send(url: String, headers: Map<String, String>, body: JsonObject?): Response = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType())) else builder.get()
        try {
            client.newCall(builder.build()).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                val parsed = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
                Response(r.code, parsed, raw)
            }
        } catch (e: IOException) {
            throw ProviderException("Couldn't reach the AI service: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // Web search and long answers can take a while.
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /** Turns an error response into a message for the user. Handles Anthropic, Google and OpenAI-style error bodies. */
        fun errorMessage(provider: String, r: Response): String {
            val detail = errorDetail(r.body) ?: r.raw.take(200).takeIf { it.isNotBlank() }
            val base = when (r.code) {
                400 -> "$provider rejected the request"
                401, 403 -> "$provider rejected the API key (HTTP ${r.code}). Check it in Settings."
                404 -> "$provider doesn't know that model or endpoint (HTTP 404). Check the model name in Settings."
                429 -> "$provider rate limit or quota reached (HTTP 429). Try again later."
                in 500..599 -> "$provider is having trouble (HTTP ${r.code}). Try again."
                else -> "$provider returned HTTP ${r.code}"
            }
            return if (detail != null && r.code !in setOf(401, 403)) "$base: $detail" else base
        }

        private fun errorDetail(body: JsonObject?): String? {
            val err: JsonElement = body?.get("error") ?: return null
            return when (err) {
                is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
                else -> runCatching { err.jsonPrimitive.contentOrNull }.getOrNull()
            }
        }
    }
}

internal fun JsonObject.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
