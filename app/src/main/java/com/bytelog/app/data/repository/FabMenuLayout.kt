package com.bytelog.app.data.repository

/** What the home screen's + menu can do, in the default order from the top of the menu down to the + button. */
enum class FabAction(val key: String, val label: String, val hideable: Boolean = true) {
    SETTINGS("settings", "Settings", hideable = false),
    FAMILY("family", "Family"),
    ASK("ask", "Ask Sparky"),
    IMPORT("import", "Import recipe"),
    PHOTO("photo", "Log from photo"),
    SCAN("scan", "Scan barcode"),
    SEARCH("search", "Search food");

    companion object {
        fun fromKey(key: String): FabAction? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The order of the + menu (top first, the last one sits next to the + button) and which actions are hidden.
 * Stored as "settings,-family,ask" ("-" = hidden). Settings can't be hidden: the menu is its only way in.
 */
data class FabMenuLayout(val order: List<FabAction>, val hidden: Set<FabAction> = emptySet()) {
    val visible: List<FabAction> get() = order.filter { it !in hidden }

    fun move(from: Int, to: Int): FabMenuLayout {
        if (from !in order.indices || to !in order.indices || from == to) return this
        val list = order.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(order = list)
    }

    fun toggle(action: FabAction, visible: Boolean): FabMenuLayout = when {
        !action.hideable -> this
        visible -> copy(hidden = hidden - action)
        else -> copy(hidden = hidden + action)
    }

    fun encode(): String = order.joinToString(",") { (if (it in hidden) "-" else "") + it.key }

    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = FabMenuLayout(FabAction.entries.toList())

        /** Reads a stored order; unknown keys are dropped and actions added since it was saved go in at their default spot. */
        fun parse(raw: String?): FabMenuLayout {
            if (raw.isNullOrBlank()) return DEFAULT
            val order = mutableListOf<FabAction>()
            val hidden = mutableSetOf<FabAction>()
            raw.split(',').forEach { token ->
                val t = token.trim()
                val action = FabAction.fromKey(t.removePrefix("-")) ?: return@forEach
                if (action in order) return@forEach
                order += action
                if (t.startsWith("-") && action.hideable) hidden += action
            }
            FabAction.entries.forEachIndexed { i, a -> if (a !in order) order.add(i.coerceAtMost(order.size), a) }
            return FabMenuLayout(order, hidden)
        }
    }
}
