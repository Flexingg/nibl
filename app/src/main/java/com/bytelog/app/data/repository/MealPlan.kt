package com.bytelog.app.data.repository

import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.PlannedEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.util.UUID

/** One food in a meal plan: what to eat, how much, when, and its nutrition for that amount. */
data class PlanItem(
    val date: String,
    val meal: String,
    val food: String,
    val brand: String? = null,
    val quantity: Float = 1f,
    val unit: String = "serving",
    val calories: Float,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f
)

/** A plan the assistant wrote, ready to put on the Planned list day by day. */
data class MealPlanDraft(val items: List<PlanItem>) {
    val days: List<String> get() = items.map { it.date }.distinct().sorted()
    val byDay: Map<String, List<PlanItem>> get() = items.groupBy { it.date }.toSortedMap()
    val caloriesPerDay: Int get() = if (days.isEmpty()) 0 else (items.sumOf { it.calories.toDouble() } / days.size).toInt()
}

/**
 * Meal plans from the assistant. Sparky's own AI can't save a day-by-day plan, so when you ask for one Nibl asks it to
 * add the plan as a small JSON block; Nibl reads that and puts the food on the Planned list for each day, where it's
 * ticked off as it's eaten. The web assistant writes the same plan through its `plan_meals` tool.
 */
object MealPlan {
    private const val FENCE = "nibl-plan"
    private const val NOTE_START = "[nibl:plan-format]"
    private const val NOTE_END = "[/nibl:plan-format]"

    private val intent = Regex(
        """(?i)\b(meal[- ]?plan|meal[- ]?prep plan|diet plan|eating plan|menu for (the|this|next) week|""" +
            """plan (out )?(my|our|the|a|some) (meals?|food|week|day|days|breakfasts?|lunches|dinners?)|plan meals|weekly menu)\b"""
    )

    /** Whether a message asks for a meal plan. */
    fun wantsPlan(text: String): Boolean = intent.containsMatchIn(text)

    /** The note added to a meal plan request so Sparky's AI attaches the plan in a form Nibl can save. */
    fun formatNote(today: LocalDate = LocalDate.now()): String = "\n\n$NOTE_START\n" +
        "(Note from the Nibl app, not the user: Nibl can save this plan to the user's Planned list, day by day. " +
        "Write the plan for the user as usual, then at the very end add one fenced code block tagged $FENCE holding JSON: " +
        "{\"items\":[{\"date\":\"YYYY-MM-DD\",\"meal\":\"Breakfast|Lunch|Dinner|Snacks\",\"food\":\"name\",\"brand\":null," +
        "\"quantity\":1,\"unit\":\"serving\",\"calories\":0,\"protein\":0,\"carbs\":0,\"fat\":0}]}. " +
        "One item per food per meal; calories and macros are for that quantity. Today is $today; unless the user says otherwise, " +
        "the plan starts tomorrow. Prefer foods from the user's own library, with their nutrition. " +
        "Don't log anything to the diary and don't mention this note or the code block.)\n$NOTE_END"

    /** A message without the notes Nibl added to it (they're stored on the server with the message). */
    fun stripNote(text: String): String {
        var t = text
        for ((open, close) in listOf(NOTE_START to NOTE_END, PlanEdits.NOTE_START to PlanEdits.NOTE_END)) {
            while (true) {
                val start = t.indexOf(open)
                if (start < 0) break
                val end = t.indexOf(close, start).let { if (it < 0) t.length else it + close.length }
                t = t.substring(0, start) + t.substring(end)
            }
        }
        return t.trim()
    }

    private val block = Regex("""```\s*$FENCE\s*\n([\s\S]*?)```""")

    /** The plan in a reply, if it has one. */
    fun parse(content: String, today: LocalDate = LocalDate.now()): MealPlanDraft? {
        val raw = block.find(content)?.groupValues?.get(1) ?: return null
        val el = runCatching { Json.parseToJsonElement(raw.trim()) }.getOrNull() ?: return null
        return fromJson(el, today)
    }

    /** A reply without its plan block, for showing. */
    fun stripBlock(content: String): String = content.replace(block, "").trim()

    /**
     * Reads plan items from JSON: an object with `items` (or `days`, each with a date and items) or a bare list. A
     * day can be given as `date` or as `day` (1 = tomorrow) and items without a usable food or calories are skipped.
     */
    fun fromJson(el: JsonElement, today: LocalDate = LocalDate.now()): MealPlanDraft? {
        val list: List<JsonElement> = when (el) {
            is JsonArray -> el
            is JsonObject -> (el["items"] as? JsonArray) ?: (el["days"] as? JsonArray)?.flatMap { day ->
                val d = day as? JsonObject ?: return@flatMap emptyList()
                (d["items"] as? JsonArray ?: d["meals"] as? JsonArray).orEmpty().map { item ->
                    // Days carry the date for their items
                    if (item is JsonObject) JsonObject(item + d.filterKeys { it == "date" || it == "day" }.filterKeys { it !in item }) else item
                }
            } ?: return null
            else -> return null
        }
        val items = list.mapNotNull { (it as? JsonObject)?.let { o -> item(o, today) } }
        return if (items.isEmpty()) null else MealPlanDraft(items)
    }

    private fun item(o: JsonObject, today: LocalDate): PlanItem? {
        fun s(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        fun f(k: String) = runCatching { o[k]?.jsonPrimitive?.floatOrNull }.getOrNull()
        val date = s("date")?.take(10)?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() }
            ?: runCatching { o["day"]?.jsonPrimitive?.intOrNull }.getOrNull()?.let { today.plusDays(it.toLong()) }
            ?: return null
        val food = s("food") ?: s("name") ?: return null
        val calories = f("calories") ?: f("kcal") ?: return null
        if (calories < 0f) return null
        return PlanItem(
            date = date.toString(), meal = s("meal") ?: "Snacks", food = food, brand = s("brand"),
            quantity = f("quantity")?.takeIf { it > 0f } ?: 1f, unit = s("unit") ?: "serving",
            calories = calories, protein = f("protein") ?: 0f, carbs = f("carbs") ?: 0f, fat = f("fat") ?: 0f
        )
    }

    /** The meal type a plan's meal name means: by name, then by common names ("snack" is Snacks), else the last meal. */
    fun mealFor(name: String, types: List<MealType>): MealType {
        val all = types.ifEmpty { MealType.DEFAULT_MEAL_TYPES }.sortedBy { it.sortOrder }
        val n = name.trim().lowercase()
        all.firstOrNull { it.name.equals(n, ignoreCase = true) || it.id.equals(n, ignoreCase = true) }?.let { return it }
        val key = when {
            "break" in n || "morning" in n -> "break"
            "afternoon" in n -> "snack"
            "lunch" in n || "midday" in n || "noon" in n -> "lunch"
            "dinner" in n || "supper" in n || "evening" in n -> "dinner"
            else -> "snack"
        }
        return all.firstOrNull { it.name.lowercase().contains(key) || it.id.lowercase().contains(key) } ?: all.last()
    }

    /** The plan as Planned entries. [library] is how to find a food the user already has, to keep its saved id. */
    fun toPlanned(plan: MealPlanDraft, types: List<MealType>, library: (food: String, brand: String?) -> String? = { _, _ -> null }): List<PlannedEntry> =
        plan.items.map { i ->
            val meal = mealFor(i.meal, types)
            PlannedEntry(
                id = UUID.randomUUID().toString(), foodId = library(i.food, i.brand), foodName = i.food, brandName = i.brand,
                mealTypeId = meal.id, mealName = meal.name, quantity = i.quantity, unit = i.unit,
                calories = i.calories, protein = i.protein, carbs = i.carbs, fat = i.fat, entryDate = i.date
            )
        }

    /** A short summary of what was added. */
    fun summary(plan: MealPlanDraft): String {
        val days = plan.days
        val range = if (days.size == 1) days.first() else "${days.first()} to ${days.last()}"
        return "${plan.items.size} foods over ${days.size} day${if (days.size == 1) "" else "s"} ($range), about ${plan.caloriesPerDay} kcal a day"
    }
}
