package com.bytelog.app.quick

import kotlinx.coroutines.flow.MutableStateFlow

/** A question from a launcher command that the chat types in when it opens (or right away, if it's open). */
object ChatDraft {
    val pending = MutableStateFlow<String?>(null)
}
