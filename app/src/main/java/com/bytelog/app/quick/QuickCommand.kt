package com.bytelog.app.quick

import java.net.URLDecoder

/**
 * Commands launchers and gesture apps (OmniPrompt, XGesture, Tasker, `am start` over Shizuku) send to Nibl, so you can
 * use Nibl from a search bar without opening it:
 *
 *  - `nibl://ask?q=…&key=…`  Ask Sparky; the answer comes back as a notification you can reply to. `&web=1` uses the
 *    web research assistant instead of Sparky's own AI.
 *  - `nibl://food?q=…`       Find a food; a notification lists the best matches with a Log button on each.
 *  - `nibl://search?q=…`     Open Fast Search with the query typed in.
 *  - `nibl://chat?q=…`       Open the chat with the question typed in, not sent.
 *
 * The same commands come as intents (action `com.bytelog.app.action.ASK`, `.FIND_FOOD`, `.SEARCH`, `.CHAT` with the
 * text in `q`, `query` or `android.intent.extra.TEXT`).
 *
 * Asking runs Sparky's tools, which can change your diary, so it needs the key shown in Settings. Any app can open a
 * nibl:// link; without the key, a question opens the chat with it typed in for you to send.
 */
sealed class QuickCommand {
    data class Ask(val text: String, val web: Boolean = false) : QuickCommand()
    data class FindFood(val query: String) : QuickCommand()
    data class OpenSearch(val query: String) : QuickCommand()
    data class OpenChat(val draft: String) : QuickCommand()
}

/** A command and the key it came with (null when none was given). */
data class QuickRequest(val command: QuickCommand, val key: String?)

object QuickCommands {
    const val SCHEME = "nibl"
    const val ACTION_ASK = "com.bytelog.app.action.ASK"
    const val ACTION_FIND_FOOD = "com.bytelog.app.action.FIND_FOOD"
    const val ACTION_SEARCH = "com.bytelog.app.action.SEARCH"
    const val ACTION_CHAT = "com.bytelog.app.action.CHAT"
    const val EXTRA_QUERY = "q"
    const val EXTRA_KEY = "key"
    const val EXTRA_WEB = "web"

    /** Longest question or search passed on; a launcher may hand over a whole clipboard. */
    const val MAX_TEXT = 2_000

    /** Reads a `nibl://` link. Null when it isn't one of Nibl's commands or has nothing to do. */
    fun parseUrl(url: String): QuickRequest? {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0 || !trimmed.substring(0, schemeEnd).equals(SCHEME, ignoreCase = true)) return null
        val rest = trimmed.substring(schemeEnd + 3)
        val queryStart = rest.indexOf('?').let { if (it < 0) rest.length else it }
        val hostAndPath = rest.substring(0, queryStart).trimEnd('/')
        val host = hostAndPath.substringBefore('/').lowercase()
        // "nibl://ask/what's for dinner" works as well as "nibl://ask?q=…"
        val pathText = hostAndPath.substringAfter('/', "").let { decode(it) }
        val params = parseQuery(rest.substring(queryStart).removePrefix("?"))
        val text = params["q"] ?: params["query"] ?: params["text"] ?: pathText
        return build(host, text, params["key"], truthy(params["web"]))
    }

    /** Reads an intent's action and extras (the text, key and web flag already pulled out by the caller). */
    fun fromAction(action: String?, text: String?, key: String?, web: Boolean = false): QuickRequest? {
        val kind = when (action) {
            ACTION_ASK -> "ask"
            ACTION_FIND_FOOD -> "food"
            ACTION_SEARCH -> "search"
            ACTION_CHAT -> "chat"
            else -> return null
        }
        return build(kind, text.orEmpty(), key, web)
    }

    private fun build(kind: String, rawText: String, key: String?, web: Boolean): QuickRequest? {
        val text = clean(rawText)
        val command = when (kind) {
            "ask", "sparky" -> if (text.isEmpty()) QuickCommand.OpenChat("") else QuickCommand.Ask(text, web)
            "food", "find", "log" -> if (text.isEmpty()) QuickCommand.OpenSearch("") else QuickCommand.FindFood(text)
            "search" -> QuickCommand.OpenSearch(text)
            "chat" -> QuickCommand.OpenChat(text)
            else -> return null
        }
        return QuickRequest(command, key?.trim()?.ifEmpty { null })
    }

    /**
     * What actually runs: a question goes to Sparky only with the right key; otherwise it opens the chat with the
     * question typed in. The other commands need no key (they only show results; logging is a button you press).
     */
    fun authorize(request: QuickRequest, expectedKey: String?): QuickCommand {
        val cmd = request.command
        if (cmd !is QuickCommand.Ask) return cmd
        val ok = !expectedKey.isNullOrBlank() && request.key != null && constantTimeEquals(request.key, expectedKey)
        return if (ok) cmd else QuickCommand.OpenChat(cmd.text)
    }

    /** Collapses whitespace and trims; launchers sometimes leave the unfilled "%s" in. */
    fun clean(text: String): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t == "%s" || t == "{query}" || t == "{q}") return ""
        return t.take(MAX_TEXT)
    }

    private fun truthy(v: String?) = v != null && v.trim().lowercase() in setOf("1", "true", "yes", "on")

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        q.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val name = decode(pair.substringBefore('=')).lowercase()
            val value = decode(pair.substringAfter('=', ""))
            if (name.isNotEmpty() && name !in out) out[name] = value
        }
        return out
    }

    /** Percent-decoding that keeps a stray "%" or "+" a launcher didn't encode ("50% off", "c++"). */
    private fun decode(s: String): String =
        try {
            URLDecoder.decode(s.replace(Regex("%(?![0-9a-fA-F]{2})"), "%25"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            s
        }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }
}
