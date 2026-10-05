package com.bytelog.app.research

import kotlinx.serialization.json.JsonObject

/** The LLM providers the web research assistant can use. The API key is the user's own and stays on the phone. */
enum class ProviderId(val label: String, val defaultModel: String, val keyHint: String) {
    ANTHROPIC("Claude (Anthropic)", "claude-opus-5-5", "sk-ant-…"),
    GEMINI("Gemini (Google)", "gemini-3.8-flash", "AIza…"),
    DEEPSEEK("DeepSeek", "deepseek-flash", "sk-…");

    companion object {
        fun fromName(name: String?): ProviderId = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ANTHROPIC
    }
}

/** A tool the model may call: a Sparky MCP tool, or one of the app's own web tools. */
data class ToolSpec(val name: String, val description: String, val schema: JsonObject)

/** [parseError] is set when the model's arguments weren't valid JSON; the agent reports it back instead of running the tool. */
data class ToolCall(val id: String, val name: String, val args: JsonObject, val parseError: String? = null)

data class ToolResult(val callId: String, val name: String, val text: String, val isError: Boolean = false)

data class WebSource(val title: String, val url: String)

/** One round trip with a model: its text, the tool calls it wants run, and anything it found on the web. */
data class LlmTurn(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val sources: List<WebSource> = emptyList(),
    /** Searches the provider ran itself (Claude's built-in web search). */
    val searchQueries: List<String> = emptyList(),
    /** Set when the model declined or the answer was cut short. */
    val notice: String? = null
)

/** A conversation with one provider. It keeps the provider's native message history, so assistant turns are echoed back verbatim. */
interface LlmSession {
    /** First call: pass an empty list. After running tools: pass their results. */
    suspend fun next(toolResults: List<ToolResult> = emptyList()): LlmTurn
}

/** A previous plain-text message, used to seed a new session with the visible chat. */
data class ChatTurn(val fromUser: Boolean, val text: String)

interface LlmProvider {
    val id: ProviderId

    /** True when the provider searches the web by itself (no `web_search` function needed). */
    val searchesWebNatively: Boolean

    fun newSession(system: String, history: List<ChatTurn>, userText: String, tools: List<ToolSpec>): LlmSession

    /** Model ids this key can use (also validates the key). */
    suspend fun listModels(): Result<List<String>>
}

/** Thrown for provider errors with a message that is safe to show (never contains the API key). */
class ProviderException(message: String, val code: Int? = null) : Exception(message)
