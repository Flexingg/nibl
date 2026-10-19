package com.bytelog.app.data.repository

/** The sections of the home screen list below the calorie, water and weight header, in their default order. */
enum class HomeSection(val key: String, val title: String, val description: String, val hideable: Boolean = true) {
    WEEKLY_REVIEW("weekly_review", "Your week", "Monday's look back at the last seven days"),
    LEFTOVERS("fridge", "Leftovers", "Batches in the fridge, one tap logs a portion"),
    FAMILY("family", "Family", "What everyone has eaten today"),
    MEALS("meals", "Meals", "The day's logged meals", hideable = false),
    WHAT_FITS("what_fits", "What fits?", "Foods you eat that fit what's left today"),
    QUICK_LOG("quick_meals", "Log a meal", "Yesterday's and usual meals, templates"),
    TRENDS("weight_chart", "Weight & calories", "Weight trend against calories, and TDEE");

    companion object {
        fun fromKey(key: String): HomeSection? = values().firstOrNull { it.key == key }
    }
}

/** The order of the home sections and which are hidden. Stored as "meals,-family,what_fits" ("-" = hidden). */
data class HomeLayout(val order: List<HomeSection>, val hidden: Set<HomeSection> = emptySet()) {
    val visible: List<HomeSection> get() = order.filter { it !in hidden }

    fun move(from: Int, to: Int): HomeLayout {
        if (from !in order.indices || to !in order.indices || from == to) return this
        val list = order.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(order = list)
    }

    fun toggle(section: HomeSection, visible: Boolean): HomeLayout = when {
        !section.hideable -> this
        visible -> copy(hidden = hidden - section)
        else -> copy(hidden = hidden + section)
    }

    fun encode(): String = order.joinToString(",") { (if (it in hidden) "-" else "") + it.key }

    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = HomeLayout(HomeSection.values().toList())

        /** Reads a stored layout; unknown keys are dropped and sections added since it was saved go in at their default spot. */
        fun parse(raw: String?): HomeLayout {
            if (raw.isNullOrBlank()) return DEFAULT
            val order = mutableListOf<HomeSection>()
            val hidden = mutableSetOf<HomeSection>()
            raw.split(',').forEach { token ->
                val t = token.trim()
                val section = HomeSection.fromKey(t.removePrefix("-")) ?: return@forEach
                if (section in order) return@forEach
                order += section
                if (t.startsWith("-") && section.hideable) hidden += section
            }
            HomeSection.values().forEachIndexed { i, s -> if (s !in order) order.add(i.coerceAtMost(order.size), s) }
            return HomeLayout(order, hidden)
        }
    }
}
