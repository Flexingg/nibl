package com.bytelog.app.research

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Reads the foods off a photo of a nutrition table, menu, label or recipe card (for the assistant's
 * `read_food_image` tool): every row with its portion and nutrition exactly as printed, and what wasn't clear.
 * The assistant then checks the user's library and asks about anything unclear before creating foods.
 */
object FoodImageReader {

    /** One food read off the photo. Nutrients are per [amount] [unit], by the food tools' nutrient names. */
    data class Row(
        val name: String,
        val section: String?,
        val amount: Double?,
        val unit: String?,
        val portion: String?,
        val nutrients: Map<String, Double>,
        val unclear: String?
    )

    data class Reading(val brand: String?, val kind: String?, val rows: List<Row>, val questions: List<String>, val notes: String?)

    /** Nutrients the reader is asked for, in the order they're shown. */
    private val NUTRIENTS = listOf(
        "calories", "protein", "carbs", "fat", "saturated_fat", "trans_fat", "cholesterol_mg", "sodium_mg",
        "fiber", "sugar", "potassium_mg", "calcium_mg", "iron_mg", "vitamin_a", "vitamin_c", "caffeine_mg"
    )

    fun instructions(focus: String?): String = buildString {
        append("You read foods and their nutrition off photos (restaurant nutrition tables, menus, packaged-food labels, recipe cards) for a food-tracking app. ")
        append("Copy what is printed; never guess a number that isn't there.\n\n")
        append("- One row per food or drink. Keep the name as printed, but write it the way a person would search it (\"Orange Chicken\", not \"ORANGE CHICKEN*\").\n")
        append("- section: the table's heading the row sits under (\"Entrées\", \"Sides\", \"Chicken\"), or null.\n")
        append("- amount and unit: the portion the numbers are for (5.7 and \"oz\"; 1 and \"roll\"; 100 and \"g\"). portion_text: the portion exactly as printed.\n")
        append("- Nutrients for that portion: calories in kcal; protein, carbs, fat, saturated_fat, trans_fat, fiber and sugar in grams; ")
        append("cholesterol_mg, sodium_mg, potassium_mg, calcium_mg, iron_mg and caffeine_mg in mg. null for anything not printed. ")
        append("Ignore \"calories from fat\" and % daily value columns unless that's all there is (then leave the nutrient null and say so in unclear).\n")
        append("- A value shown as a range or \"<1\": use the number given (\"<1\" is 0.5) and mention it in unclear.\n")
        append("- unclear: anything about that row you couldn't read or had to interpret (blurry digit, merged cells, a footnote). null when it's clean.\n")
        append("- brand: the restaurant or brand if the photo shows it (a logo, a header), else null. kind: what the photo is (\"nutrition table\", \"label\", \"menu\").\n")
        append("- questions: what you'd need the user to answer before these become foods (\"Is the portion for the small or regular size?\"). Empty if nothing.\n")
        if (!focus.isNullOrBlank()) append("\nThe user's request (follow it about what to read): ").append(focus.trim()).append('\n')
        append("\nReply with only the JSON object.")
    }

    private fun type(vararg t: String, description: String? = null) = buildJsonObject {
        put("type", if (t.size == 1) JsonPrimitive(t[0]) else JsonArray(t.map { JsonPrimitive(it) }))
        description?.let { put("description", JsonPrimitive(it)) }
    }
    private fun obj(vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
        put("required", buildJsonArray { props.forEach { add(JsonPrimitive(it.first)) } })
        put("additionalProperties", JsonPrimitive(false))
    }
    private fun array(items: JsonObject) = buildJsonObject { put("type", JsonPrimitive("array")); put("items", items) }

    val SCHEMA: JsonObject = obj(
        "brand" to type("string", "null"),
        "kind" to type("string", "null"),
        "foods" to array(obj(
            *(listOf(
                "name" to type("string"),
                "section" to type("string", "null"),
                "amount" to type("number", "null", description = "Portion size the numbers are for"),
                "unit" to type("string", "null"),
                "portion_text" to type("string", "null")
            ) + NUTRIENTS.map { it to type("number", "null") } + listOf("unclear" to type("string", "null"))).toTypedArray()
        )),
        "questions" to array(type("string")),
        "notes" to type("string", "null")
    )

    /** Reads the model's reply. Null when there's no usable JSON in it. */
    fun parse(raw: String): Reading? {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject }.getOrNull() ?: return null
        val rows = (root["foods"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.s("name") ?: return@mapNotNull null
            Row(
                name = name, section = o.s("section"), amount = o.n("amount")?.takeIf { it > 0 }, unit = o.s("unit"),
                portion = o.s("portion_text"),
                nutrients = NUTRIENTS.mapNotNull { k -> o.n(k)?.let { k to it } }.toMap(),
                unclear = o.s("unclear")
            )
        }
        return Reading(root.s("brand"), root.s("kind"), rows,
            (root["questions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { q -> q.isNotEmpty() } },
            root.s("notes"))
    }

    /** The reading as text for the assistant: one line per food, then what's unclear and what to ask. */
    fun toToolText(raw: String): String {
        val r = parse(raw) ?: return "Couldn't read foods from this photo (the reply wasn't usable). Ask the user for a clearer photo, or to type the foods."
        if (r.rows.isEmpty()) return "No foods with nutrition were found in this photo." + (r.notes?.let { " ($it)" }.orEmpty())
        return buildString {
            append("Read ${r.rows.size} food${if (r.rows.size == 1) "" else "s"} from the photo")
            r.kind?.let { append(" ($it)") }
            r.brand?.let { append(", brand: $it") }
            append(". Nutrients are per the portion shown.\n")
            var section: String? = null
            r.rows.forEachIndexed { i, row ->
                if (row.section != null && row.section != section) { section = row.section; append("[${row.section}]\n") }
                append("${i + 1}. ${row.name} — ")
                append(if (row.amount != null && row.unit != null) "${FoodLibraryTools.num(row.amount)} ${row.unit}" else row.portion ?: "portion not shown")
                append(": ")
                append(row.nutrients.entries.joinToString(", ") { (k, v) -> "$k ${FoodLibraryTools.num(v)}" }.ifEmpty { "no nutrients" })
                row.unclear?.let { append(" (unclear: $it)") }
                append('\n')
            }
            val missingPortion = r.rows.count { it.amount == null || it.unit == null }
            if (missingPortion > 0) append("$missingPortion food(s) have no portion size: ask the user what the numbers are for.\n")
            r.notes?.let { append("Notes: $it\n") }
            if (r.questions.isNotEmpty()) append("Questions to settle with the user first:\n" + r.questions.joinToString("\n") { "- $it" } + "\n")
            append("Before creating foods: check the user's library (find_my_foods) for ones that already exist, and ask the user about anything unclear above (and the brand, if none was read) before calling create_foods.")
        }.trimEnd()
    }

    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.n(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
}
