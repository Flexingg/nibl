package com.bytelog.app.data.repository

import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.PlannedEntry
import com.bytelog.app.ui.logging.Portion
import com.bytelog.app.ui.logging.Portions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One change the assistant wants to make to the plan. */
sealed interface PlanChange {
    /** Take a planned food off. [ref] is the start of its id, as listed to the assistant. */
    data class Remove(val ref: String) : PlanChange

    /** Change a planned food; only what's given changes. Calories and macros are for the new amount. */
    data class Update(
        val ref: String,
        val quantity: Float? = null,
        val unit: String? = null,
        val meal: String? = null,
        val date: String? = null,
        val calories: Float? = null,
        val protein: Float? = null,
        val carbs: Float? = null,
        val fat: Float? = null
    ) : PlanChange

    /** Plan a new food. */
    data class Add(val item: PlanItem) : PlanChange
}

/** Changes to the plan the assistant proposed, for the user to apply. */
data class PlanEditDraft(val changes: List<PlanChange>)

/** How a change reads on the card: "Remove Salmon · Tue dinner". */
data class PlanChangeLine(val kind: Kind, val text: String) {
    enum class Kind { ADD, REMOVE, UPDATE, MISSING }
}

/** What applying changed, so it can be undone. */
data class AppliedPlanEdit(val removed: List<PlannedEntry>, val before: List<PlannedEntry>, val after: List<PlannedEntry>, val added: List<PlannedEntry>) {
    val count: Int get() = removed.size + after.size + added.size
}

/**
 * Editing the plan from the chat. Sparky's AI can't touch the plan (it lives on the phone), so when you ask to change
 * it Nibl lists what's planned in a note on your message, each food with a short ref, and asks for the changes as a
 * small JSON block. The chat shows them as a card to apply (and undo). The web assistant has tools for the same.
 */
object PlanEdits {
    private const val FENCE = "nibl-plan-edit"
    const val NOTE_START = "[nibl:plan-edit]"
    const val NOTE_END = "[/nibl:plan-edit]"
    /** How far ahead the plan is listed. */
    const val DAYS = 14

    private val verbs = Regex(
        """(?i)\b(swap|replace|change|tweak|edit|adjust|remove|delete|drop|take out|move|add|more|less|fewer|double|halve|bigger|smaller|""" +
            """lighter|heavier|cheaper|instead|switch|substitute|update|fix|shift|cut|increase|decrease|reduce|boost)\b"""
    )
    private val planWords = Regex("""(?i)\b(plan|planned|planning|meal ?plan|menu|this week|next week|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday|breakfasts?|lunch(es)?|dinners?|snacks?)\b""")

    /** Whether a message asks to change the plan. */
    fun wantsEdit(text: String): Boolean = verbs.containsMatchIn(text) && planWords.containsMatchIn(text)

    /** A planned food's ref: the start of its id (short, and enough to tell them apart). */
    fun refOf(e: PlannedEntry): String = e.id.replace("-", "").take(8)

    private val dayFormat = DateTimeFormatter.ofPattern("EEE", Locale.US)

    /** One planned food as listed to the assistant. */
    fun line(e: PlannedEntry): String {
        val d = runCatching { LocalDate.parse(e.entryDate).format(dayFormat) }.getOrDefault("")
        return "${refOf(e)}: ${e.entryDate} ($d) ${e.mealName} | ${e.foodName}${e.brandName?.let { " ($it)" } ?: ""} | ${Portion(e.quantity, e.unit).label} | " +
            "${e.calories.toInt()} kcal, P ${e.protein.toInt()} g, C ${e.carbs.toInt()} g, F ${e.fat.toInt()} g"
    }

    /** The note added to a message asking to change the plan: what's planned, and how to answer. */
    fun formatNote(planned: List<PlannedEntry>, today: LocalDate): String = buildString {
        append("\n\n").append(NOTE_START).append('\n')
        append("(Note from the Nibl app, not the user: the user's planned food (not eaten yet), each line starting with its ref:\n")
        planned.sortedWith(compareBy({ it.entryDate }, { it.createdAt })).forEach { append(line(it)).append('\n') }
        append(
            "To change the plan as the user asks, describe the changes for the user, then at the very end add one fenced code block tagged $FENCE holding JSON: " +
                "{\"changes\":[{\"op\":\"remove\",\"ref\":\"…\"},{\"op\":\"update\",\"ref\":\"…\",\"quantity\":2,\"unit\":\"serving\",\"meal\":\"Lunch\",\"date\":\"YYYY-MM-DD\",\"calories\":0,\"protein\":0,\"carbs\":0,\"fat\":0}," +
                "{\"op\":\"add\",\"date\":\"YYYY-MM-DD\",\"meal\":\"Dinner\",\"food\":\"name\",\"brand\":null,\"quantity\":1,\"unit\":\"serving\",\"calories\":0,\"protein\":0,\"carbs\":0,\"fat\":0}]}. " +
                "For update give only what changes; when the amount changes give the new calories and macros for the new amount. To swap a food, remove it and add the new one. " +
                "Prefer foods from the user's own library, with their nutrition. Today is $today. The app shows the changes for the user to apply, so don't log anything to the diary " +
                "and don't mention this note or the code block.)\n"
        )
        append(NOTE_END)
    }

    private val block = Regex("""```\s*$FENCE\s*\n([\s\S]*?)```""")

    fun parse(content: String, today: LocalDate = LocalDate.now()): PlanEditDraft? {
        val raw = block.find(content)?.groupValues?.get(1) ?: return null
        val el = runCatching { Json.parseToJsonElement(raw.trim()) }.getOrNull() ?: return null
        return fromJson(el, today)
    }

    fun stripBlock(content: String): String = content.replace(block, "").trim()

    /** Changes from JSON: an object with `changes` (or `ops`), or a bare list. Unreadable changes are skipped. */
    fun fromJson(el: JsonElement, today: LocalDate = LocalDate.now()): PlanEditDraft? {
        val list = when (el) {
            is JsonArray -> el
            is JsonObject -> (el["changes"] as? JsonArray) ?: (el["ops"] as? JsonArray) ?: return null
            else -> return null
        }
        val changes = list.mapNotNull { (it as? JsonObject)?.let { o -> change(o, today) } }
        return if (changes.isEmpty()) null else PlanEditDraft(changes)
    }

    private fun change(o: JsonObject, today: LocalDate): PlanChange? {
        fun s(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        fun f(k: String) = runCatching { o[k]?.jsonPrimitive?.floatOrNull }.getOrNull()
        val ref = (s("ref") ?: s("id"))?.replace("-", "")?.take(8)
        return when (s("op")?.lowercase()) {
            "remove", "delete" -> ref?.let { PlanChange.Remove(it) }
            "update", "change", "edit" -> ref?.let {
                PlanChange.Update(
                    it, f("quantity")?.takeIf { q -> q > 0f }, s("unit"), s("meal"),
                    s("date")?.take(10)?.takeIf { d -> runCatching { LocalDate.parse(d) }.isSuccess },
                    f("calories"), f("protein"), f("carbs"), f("fat")
                )
            }
            "add" -> MealPlan.fromJson(JsonArray(listOf(o)), today)?.items?.firstOrNull()?.let { PlanChange.Add(it) }
            else -> null
        }
    }

    private fun find(ref: String, planned: List<PlannedEntry>) = planned.firstOrNull { refOf(it) == ref }

    private fun whenOf(e: PlannedEntry): String {
        val d = runCatching { LocalDate.parse(e.entryDate).format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) }.getOrDefault(e.entryDate)
        return "$d ${e.mealName.lowercase()}"
    }

    /** The changes as they read on the card, against what's planned now. */
    fun describe(draft: PlanEditDraft, planned: List<PlannedEntry>): List<PlanChangeLine> = draft.changes.map { c ->
        when (c) {
            is PlanChange.Remove -> find(c.ref, planned)?.let { PlanChangeLine(PlanChangeLine.Kind.REMOVE, "Remove ${it.foodName} · ${whenOf(it)}") }
                ?: PlanChangeLine(PlanChangeLine.Kind.MISSING, "A food that's no longer planned")
            is PlanChange.Update -> find(c.ref, planned)?.let { e ->
                val parts = listOfNotNull(
                    if (c.quantity != null || c.unit != null) "${Portion(e.quantity, e.unit).label} → ${Portion(c.quantity ?: e.quantity, c.unit ?: e.unit).label}" else null,
                    c.meal?.takeIf { !it.equals(e.mealName, true) }?.let { "to $it" },
                    c.date?.takeIf { it != e.entryDate }?.let { d -> "to " + runCatching { LocalDate.parse(d).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) }.getOrDefault(d) }
                )
                PlanChangeLine(PlanChangeLine.Kind.UPDATE, "${e.foodName} · ${whenOf(e)}: ${parts.ifEmpty { listOf("updated") }.joinToString(", ")}")
            } ?: PlanChangeLine(PlanChangeLine.Kind.MISSING, "A food that's no longer planned")
            is PlanChange.Add -> {
                val d = runCatching { LocalDate.parse(c.item.date).format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) }.getOrDefault(c.item.date)
                PlanChangeLine(PlanChangeLine.Kind.ADD, "Add ${c.item.food} · $d ${c.item.meal.lowercase()} · ${Portion(c.item.quantity, c.item.unit).label}, ${c.item.calories.toInt()} kcal")
            }
        }
    }

    /**
     * Works out the edit against what's planned now: entries to remove, entries before and after their update, and
     * new entries. Amounts recalculate from the food when it's on the phone ([foodOf]), else from the calories and
     * macros given, else scale with the amount.
     */
    fun plan(draft: PlanEditDraft, planned: List<PlannedEntry>, mealTypes: List<MealType>, foodOf: (String) -> FoodItem? = { null }): AppliedPlanEdit {
        val removed = mutableListOf<PlannedEntry>()
        val before = mutableListOf<PlannedEntry>(); val after = mutableListOf<PlannedEntry>()
        val adds = mutableListOf<PlanItem>()
        for (c in draft.changes) when (c) {
            is PlanChange.Remove -> find(c.ref, planned)?.takeIf { it !in removed }?.let { removed += it }
            is PlanChange.Update -> find(c.ref, planned)?.takeIf { it !in removed }?.let { e ->
                var u = e
                val q = c.quantity ?: e.quantity; val unit = c.unit ?: e.unit
                if (q != e.quantity || !unit.equals(e.unit, true)) {
                    val food = e.foodId?.let(foodOf)
                    u = when {
                        c.calories != null -> u.copy(quantity = q, unit = unit)
                        food != null && (Portions.unitOptions(food).any { it.equals(unit, true) }) -> {
                            val m = Portions.macros(food, Portion(q, unit))
                            u.copy(quantity = q, unit = unit, calories = m.calories, protein = m.protein, carbs = m.carbs, fat = m.fat)
                        }
                        unit.equals(e.unit, true) && e.quantity > 0f -> {
                            val r = q / e.quantity
                            u.copy(quantity = q, calories = e.calories * r, protein = e.protein * r, carbs = e.carbs * r, fat = e.fat * r)
                        }
                        else -> u.copy(quantity = q, unit = unit)
                    }
                }
                c.calories?.let { u = u.copy(calories = it) }; c.protein?.let { u = u.copy(protein = it) }
                c.carbs?.let { u = u.copy(carbs = it) }; c.fat?.let { u = u.copy(fat = it) }
                c.meal?.let { name -> val t = MealPlan.mealFor(name, mealTypes); u = u.copy(mealTypeId = t.id, mealName = t.name) }
                c.date?.let { u = u.copy(entryDate = it) }
                if (u != e) {
                    val i = after.indexOfFirst { it.id == e.id }
                    if (i >= 0) after[i] = u else { before += e; after += u }
                }
            }
            is PlanChange.Add -> adds += c.item
        }
        val added = if (adds.isEmpty()) emptyList() else MealPlan.toPlanned(MealPlanDraft(adds), mealTypes)
        return AppliedPlanEdit(removed, before, after, added)
    }
}
