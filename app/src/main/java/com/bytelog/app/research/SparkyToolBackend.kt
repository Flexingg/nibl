package com.bytelog.app.research

import com.bytelog.app.data.remote.SparkyApiClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.bytelog.app.data.remote.mcpRequest

/** Sparky's tools: the user's food library, diary, goals and the rest. */
interface SparkyToolBackend {
    suspend fun listTools(): Result<List<ToolSpec>>
    suspend fun call(name: String, args: JsonObject): Result<ToolOutput>
}

data class ToolOutput(val text: String, val isError: Boolean)

/** Talks to Sparky's MCP endpoint through the app's existing authenticated client. */
class McpToolBackend(private val api: SparkyApiClient) : SparkyToolBackend {

    private var cached: Pair<Long, List<ToolSpec>>? = null

    override suspend fun listTools(): Result<List<ToolSpec>> {
        cached?.let { (at, tools) -> if (System.currentTimeMillis() - at < CACHE_MS) return Result.success(tools) }
        return api.mcpRequest("tools/list").mapCatching { result ->
            val tools = result["tools"]?.jsonArray.orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = o.str("name") ?: return@mapNotNull null
                ToolSpec(name, o.str("description").orEmpty(), SchemaUtil.schemaOf(o))
            }
            cached = System.currentTimeMillis() to tools
            tools
        }
    }

    override suspend fun call(name: String, args: JsonObject): Result<ToolOutput> {
        val params = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("arguments", args)
        }
        return api.mcpRequest("tools/call", params).map { result ->
            val text = (result["content"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.takeIf { c -> c.str("type") == "text" }?.str("text") }
                .joinToString("\n")
            val isError = result["isError"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true
            ToolOutput(text.ifBlank { "(no output)" }, isError)
        }
    }

    companion object {
        private const val CACHE_MS = 10 * 60 * 1000L
    }
}
