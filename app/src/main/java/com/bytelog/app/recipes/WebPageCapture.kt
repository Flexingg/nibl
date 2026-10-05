package com.bytelog.app.recipes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Helpers for reading a recipe out of a page shown in a WebView. */
object WebPageCapture {

    /** JavaScript run in the page: returns the rendered DOM, which includes JSON-LD added by scripts. */
    const val READ_DOM_JS = "(function(){return document.documentElement.outerHTML;})()"

    /**
     * `WebView.evaluateJavascript` hands back its result JSON-encoded: a quoted string with escapes
     * such as < for '<'. Returns the plain string, or null for "null"/empty results.
     */
    fun decodeJsResult(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val el = try {
            Json.parseToJsonElement(raw)
        } catch (_: Exception) {
            return null
        }
        return when (el) {
            is JsonNull -> null
            is JsonPrimitive -> if (el.isString) el.content.takeIf { it.isNotBlank() } else null
            else -> null
        }
    }

    /** True for a URL the in-app browser may load (web links only). */
    fun isLoadable(url: String?): Boolean =
        url != null && (url.startsWith("https://") || url.startsWith("http://"))
}
