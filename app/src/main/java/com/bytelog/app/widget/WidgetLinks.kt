package com.bytelog.app.widget

/** Where a tap on a widget takes you in the app. Carried as a short string on the launch intent. */
sealed class WidgetDestination {
    object Diary : WidgetDestination()
    object Family : WidgetDestination()
    data class Search(val meal: String) : WidgetDestination()
    data class Scan(val meal: String) : WidgetDestination()
    data class Share(val meal: String) : WidgetDestination()
}

object WidgetLinks {
    const val EXTRA_DEST = "bytelog_widget_destination"
    const val ACTION_OPEN = "com.bytelog.app.widget.OPEN"
    const val ACTION_ADD_WATER = "com.bytelog.app.widget.ADD_WATER"

    fun encode(d: WidgetDestination): String = when (d) {
        WidgetDestination.Diary -> "diary"
        WidgetDestination.Family -> "family"
        is WidgetDestination.Search -> "search:${d.meal}"
        is WidgetDestination.Scan -> "scan:${d.meal}"
        is WidgetDestination.Share -> "share:${d.meal}"
    }

    fun parse(raw: String?): WidgetDestination? {
        if (raw.isNullOrBlank()) return null
        val kind = raw.substringBefore(':')
        val meal = raw.substringAfter(':', "").trim()
        return when (kind) {
            "diary" -> WidgetDestination.Diary
            "family" -> WidgetDestination.Family
            "search" -> meal.takeIf { it.isNotEmpty() }?.let { WidgetDestination.Search(it) }
            "scan" -> meal.takeIf { it.isNotEmpty() }?.let { WidgetDestination.Scan(it) }
            "share" -> meal.takeIf { it.isNotEmpty() }?.let { WidgetDestination.Share(it) }
            else -> null
        }
    }
}
