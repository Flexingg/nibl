package com.bytelog.app.ui.components

/**
 * What the assistant did, as a few short chips under its answer: tools grouped by what they did ("Logged food ×4"),
 * changes first, then what it only looked at.
 */
object ChatToolChips {
    data class Chip(val label: String, val count: Int, val wrote: Boolean)

    private val reads = listOf("search", "lookup", "list", "get", "find", "read", "check", "summary", "history", "calculate", "preview", "estimate")

    /** [tools] are names as the chat keeps them: "tool", "tool#action", or "sparky_ai:tool#action". */
    fun summarize(tools: List<String>): List<Chip> {
        val labelled = tools.filter { it.isNotBlank() && !it.startsWith("sparky_ask_user") }.map { label(it) }
        return labelled.groupBy { it }.map { (k, v) -> Chip(k.first, v.size, k.second) }
            .sortedBy { if (it.wrote) 0 else 1 }
    }

    /** A tool's chip label and whether it changed something. */
    fun label(raw: String): Pair<String, Boolean> {
        val viaSparkyAi = raw.startsWith("sparky_ai:")
        val tool = raw.removePrefix("sparky_ai:")
        val name = tool.substringBefore('#')
        val action = tool.substringAfter('#', "").lowercase()
        val (text, wrote) = labelFor(name.lowercase(), action)
        return (if (viaSparkyAi) "Sparky AI · ${text.replaceFirstChar { it.lowercase() }}" else text) to wrote
    }

    private fun labelFor(name: String, action: String): Pair<String, Boolean> {
        // The assistant's own food-library tools
        when (name) {
            "create_foods" -> return "Created foods" to true
            "edit_food", "add_serving", "edit_serving", "delete_serving" -> return "Edited foods" to true
            "set_food_image" -> return "Added a picture" to true
            "delete_food" -> return "Deleted a food" to true
            "find_my_foods", "get_food" -> return "Checked your foods" to false
            "read_food_image" -> return "Read your photo" to false
        }
        if (action.isNotEmpty()) {
            return when {
                action in setOf("log_food", "log_external_food", "log_meal") -> "Logged food" to true
                action == "log_water" -> "Logged water" to true
                action == "update_entry" -> "Edited an entry" to true
                action == "delete_entry" -> "Deleted an entry" to true
                action == "create_food" -> "Created a food" to true
                action == "delete_food" -> "Deleted a food" to true
                action.startsWith("set_food") || action == "update_food_variant" -> "Edited a food" to true
                action == "copy_from_yesterday" -> "Copied meals" to true
                action == "save_as_meal_template" -> "Saved a meal" to true
                reads.any { action.startsWith(it) || action.contains("_$it") } -> readLabel(name) to false
                else -> action.replace('_', ' ').replaceFirstChar { it.uppercase() } to true
            }
        }
        return when {
            reads.any { name.startsWith("sparky_$it") } || name.startsWith("sparky_get_") -> readLabel(name) to false
            name.contains("checkin") || name.contains("weight") || name.contains("measurement") -> "Updated check-in" to true
            name.contains("exercise") || name.contains("workout") -> "Logged exercise" to true
            name.contains("goal") -> "Updated goals" to true
            name.contains("water") -> "Logged water" to true
            name.contains("food") || name.contains("meal") || name.contains("diary") -> "Logged food" to true
            name.contains("report") -> "Checked reports" to false
            else -> name.removePrefix("sparky_").replace('_', ' ').replaceFirstChar { it.uppercase() } to true
        }
    }

    private fun readLabel(name: String) = when {
        name.contains("food") || name.contains("meal") || name.contains("diary") -> "Checked foods"
        name.contains("exercise") -> "Checked exercise"
        name.contains("goal") -> "Checked goals"
        name.contains("report") -> "Checked reports"
        name.contains("checkin") || name.contains("weight") -> "Checked check-ins"
        else -> "Checked your data"
    }
}
