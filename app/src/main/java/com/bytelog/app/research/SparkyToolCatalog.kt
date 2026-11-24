package com.bytelog.app.research

import com.bytelog.app.data.remote.SparkyTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Knows which category each Sparky MCP tool belongs to and whether a call only reads data.
 *
 * Sparky's MCP tools carry no read-only annotation, and most are `sparky_manage_*` tools that take an
 * `action` (search_food, log_food, delete_entry, …). Anything not recognised as a read is treated as a
 * write, so a new or unknown tool asks before it runs.
 */
object SparkyToolCatalog {

    /** Tool name → category slug (mirrors SparkyFitnessServer `CATEGORY_BUILDERS`). */
    private val categoryOf: Map<String, String> = buildMap {
        fun add(slug: String, vararg names: String) = names.forEach { put(it, slug) }
        add("food", "sparky_manage_food", "sparky_list_foods", "sparky_get_food_details", "sparky_search_foods", "sparky_get_food_diary",
            "sparky_get_nutrition_summary", "sparky_get_recent_food_entries", "sparky_get_food_usage", "sparky_manage_favorites",
            "sparky_manage_meal_plans", "sparky_manage_custom_nutrients", "sparky_manage_water_containers", "sparky_get_caffeine_kinetics",
            "sparky_manage_allergens", "sparky_get_barcode")
        add("exercise", "sparky_manage_exercise", "sparky_list_exercises", "sparky_get_exercise_details", "sparky_search_exercises",
            "sparky_get_exercise_diary", "sparky_get_daily_exercise_totals", "sparky_get_recent_exercise_entries", "sparky_get_exercise_usage",
            "sparky_get_exercise_progress", "sparky_get_exercise_stats", "sparky_manage_workout_plans")
        add("checkin", "sparky_manage_checkin", "sparky_manage_progress_photos", "sparky_get_sleep_science", "sparky_manage_symptoms")
        add("goals", "sparky_manage_goals", "sparky_get_goal_snapshot")
        add("coaching", "sparky_get_health_summary", "sparky_analyze_trends", "sparky_detect_patterns", "sparky_generate_coaching_plan",
            "sparky_check_engagement", "sparky_get_logging_streak", "sparky_get_contextual_nudge", "sparky_daily_checkin_wizard")
        add("vision", "sparky_analyze_food_image", "sparky_log_food_photo", "sparky_scan_label")
        add("profile", "sparky_manage_profile", "sparky_manage_habits", "sparky_get_integrations", "sparky_get_synced_data")
        add("reports", "sparky_get_report", "sparky_get_daily_report", "sparky_get_dashboard")
        add("medications", "sparky_manage_medications")
    }

    /** Category of [toolName], or null for a tool this app doesn't know (e.g. added by a newer server). */
    fun categoryFor(toolName: String): String? = categoryOf[toolName]

    /** Tools allowed by the user's category selection. Unknown tools are only allowed when every category is on. */
    fun isAllowed(toolName: String, enabled: Set<String>): Boolean {
        val category = categoryFor(toolName)
        return if (category != null) category in enabled else enabled.containsAll(SparkyTools.allSlugs)
    }

    private val readActionPrefixes = listOf(
        "search", "lookup", "list", "get", "find", "read", "view", "show", "check", "calculate", "preview", "estimate", "compare", "query", "inspect"
    )
    private val readToolPrefixes = listOf(
        "sparky_get_", "sparky_list_", "sparky_search_", "sparky_analyze_trends", "sparky_detect_patterns", "sparky_check_engagement",
        "sparky_inspect_", "sparky_query_", "sparky_execute_read_only_"
    )

    /** True when the call can only read data. Anything unrecognised counts as a write. */
    fun isRead(toolName: String, args: JsonObject): Boolean {
        val action = args["action"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.lowercase()
        if (toolName.startsWith("sparky_manage_") || action != null) {
            // `manage` tools do whatever `action` says; without a recognisable read action, assume a write.
            return action != null && readActionPrefixes.any { action.startsWith(it) }
        }
        return readToolPrefixes.any { toolName.startsWith(it) }
    }

    private val nameKeys = listOf("food_name", "name", "food", "exercise_name", "medication_name", "title", "description")
    private val amountKeys = listOf("quantity", "servings", "serving_quantity", "amount", "weight", "value", "duration_minutes", "duration")
    private val unitKeys = listOf("unit", "serving_unit", "quantity_unit", "units", "weight_unit")
    private val mealKeys = listOf("meal_type", "meal", "meal_name")
    private val dateKeys = listOf("entry_date", "date", "log_date", "start_date")
    private val calorieKeys = listOf("calories", "kcal", "energy")

    /**
     * A plain-language headline for a change, like "Log food: 2 serving Greek yogurt · Breakfast · today · 150 kcal",
     * and the rest of the arguments as "Label: value" lines, for the approval dialog. Never raw JSON.
     */
    fun humanize(toolName: String, args: JsonObject, today: java.time.LocalDate = java.time.LocalDate.now()): Pair<String, List<String>> {
        fun scalar(key: String): String? = (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        fun first(keys: List<String>): Pair<String, String>? = keys.firstNotNullOfOrNull { k -> scalar(k)?.let { k to it } }
        val used = mutableSetOf("action")

        val action = scalar("action")
        val verb = (action ?: toolName.removePrefix("sparky_").removePrefix("manage_")).replace('_', ' ').trim()
            .replaceFirstChar { it.titlecase() }
        val name = first(nameKeys)?.also { used += it.first }?.second
        val amount = first(amountKeys)?.also { used += it.first }?.second?.let(::number)
        val unit = first(unitKeys)?.also { used += it.first }?.second
        val meal = first(mealKeys)?.also { used += it.first }?.second?.replaceFirstChar { it.titlecase() }
        val date = first(dateKeys)?.also { used += it.first }?.second?.let { dayLabel(it, today) }
        val kcal = first(calorieKeys)?.also { used += it.first }?.second?.let(::number)

        val what = listOfNotNull(amount, unit, name).joinToString(" ").ifBlank { null }
        val parts = listOfNotNull(what, meal, date, kcal?.let { "$it kcal" })
        val headline = if (parts.isEmpty()) verb else "$verb: ${parts.joinToString(" · ")}"

        val details = args.entries.filter { (k, v) -> k !in used && v !is kotlinx.serialization.json.JsonNull }.mapNotNull { (k, v) ->
            val value = when (v) {
                is kotlinx.serialization.json.JsonPrimitive -> v.contentOrNull?.takeIf { it.isNotBlank() }?.let { if (v.isString) it else number(it) }
                is kotlinx.serialization.json.JsonArray -> if (v.isEmpty()) null else "${v.size} item${if (v.size == 1) "" else "s"}"
                is JsonObject -> v.entries.mapNotNull { (ik, iv) -> (iv as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let { "${label(ik)} $it" } }
                    .take(4).joinToString(", ").ifBlank { null }
                else -> null
            } ?: return@mapNotNull null
            "${label(k)}: ${if (value.length > 80) value.take(80) + "…" else value}"
        }.take(8)
        return headline to details
    }

    private fun label(key: String) = key.replace('_', ' ').trim().replaceFirstChar { it.titlecase() }

    private fun number(raw: String): String = raw.toDoubleOrNull()?.let { d ->
        if (d == Math.floor(d) && kotlin.math.abs(d) < 1e9) d.toLong().toString() else "%.1f".format(java.util.Locale.US, d).trimEnd('0').trimEnd('.')
    } ?: raw

    private fun dayLabel(raw: String, today: java.time.LocalDate): String {
        val day = runCatching { java.time.LocalDate.parse(raw.take(10)) }.getOrNull() ?: return raw
        return when (day) {
            today -> "today"
            today.minusDays(1) -> "yesterday"
            today.plusDays(1) -> "tomorrow"
            else -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.US))
        }
    }

    /** A short phrase for the progress line. */
    fun describe(toolName: String, args: JsonObject): String {
        val action = args["action"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        val subject = when (categoryFor(toolName)) {
            "food" -> "food"
            "exercise" -> "exercise"
            "checkin" -> "check-ins"
            "goals" -> "goals"
            "reports" -> "reports"
            "coaching" -> "coaching"
            "vision" -> "photos"
            "profile" -> "profile"
            "medications" -> "medications"
            else -> toolName.removePrefix("sparky_").replace('_', ' ')
        }
        return if (action != null) "$subject: ${action.replace('_', ' ')}" else "$subject: ${toolName.removePrefix("sparky_").replace('_', ' ')}"
    }
}
