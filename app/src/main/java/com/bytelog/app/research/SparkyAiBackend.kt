package com.bytelog.app.research

import com.bytelog.app.data.remote.ChatMessageInput
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.remote.SparkyTools

/** What Sparky's own AI answered, and which of its tools it used. */
data class SparkyAiReply(
    val text: String,
    val executedTools: List<String>,
    /** Set when Sparky's AI is asking something back (it may offer options). */
    val question: String? = null,
    val options: List<String> = emptyList()
)

/**
 * Sparky's own AI (the server's chat agent). It knows the user's food library, has the food lookup steps, and does the
 * logging, so the web assistant hands findings to it instead of writing to the diary itself.
 */
interface SparkyAiBackend {
    /** [thread] is the conversation with Sparky's AI so far in this request; [message] is the new message to it. */
    suspend fun ask(thread: List<ChatTurn>, message: String, categories: Set<String>): Result<SparkyAiReply>
}

/** Talks to the server's `/api/chat` through the app's existing authenticated client. */
class ChatSparkyAiBackend(private val api: SparkyApiClient) : SparkyAiBackend {
    override suspend fun ask(thread: List<ChatTurn>, message: String, categories: Set<String>): Result<SparkyAiReply> {
        val messages = thread.map { ChatMessageInput(if (it.fromUser) "user" else "assistant", it.text) } + ChatMessageInput("user", message)
        return api.sendChatMessage(messages, toolCategories = SparkyTools.normalize(categories).takeIf { it.isNotEmpty() }).map { out ->
            SparkyAiReply(
                text = out.content,
                executedTools = out.executedTools.filter { it != "sparky_ask_user" }.distinct(),
                question = out.askUserPrompt?.question,
                options = out.askUserPrompt?.options.orEmpty()
            )
        }
    }
}
