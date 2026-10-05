package com.bytelog.app.recipes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * Reads a recipe out of a web page.
 *
 * 1. Schema.org `Recipe` in JSON-LD (handles top-level arrays, `@graph`, array `@type`).
 * 2. Schema.org microdata (`itemprop="recipeIngredient"` …) as a fallback.
 *
 * Pure JVM code so it can be unit-tested against saved HTML.
 */
object RecipeExtractor {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun extract(html: String, sourceUrl: String? = null): ImportedRecipe? {
        val doc = Jsoup.parse(html, sourceUrl ?: "")
        return extractJsonLd(doc, sourceUrl) ?: extractMicrodata(doc, sourceUrl)
    }

    // --- JSON-LD ---

    private fun extractJsonLd(doc: Document, sourceUrl: String?): ImportedRecipe? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val raw = script.data().trim().ifBlank { script.html().trim() }
            if (raw.isBlank()) continue
            val root = try {
                json.parseToJsonElement(raw)
            } catch (_: Exception) {
                // Some sites put stray control characters in JSON-LD.
                try { json.parseToJsonElement(raw.replace(Regex("""[\u0000-\u001F]"""), " ")) } catch (_: Exception) { null }
            } ?: continue
            val recipe = findRecipeNode(root) ?: continue
            return fromJsonLd(recipe, sourceUrl, doc)
        }
        return null
    }

    private fun findRecipeNode(el: JsonElement): JsonObject? {
        when (el) {
            is JsonArray -> el.forEach { child -> findRecipeNode(child)?.let { return it } }
            is JsonObject -> {
                if (isType(el["@type"], "Recipe")) return el
                el["@graph"]?.let { g -> findRecipeNode(g)?.let { return it } }
                el["mainEntity"]?.let { m -> findRecipeNode(m)?.let { return it } }
            }
            else -> {}
        }
        return null
    }

    private fun isType(typeEl: JsonElement?, type: String): Boolean = when (typeEl) {
        is JsonPrimitive -> typeEl.contentOrNull.equals(type, ignoreCase = true) ||
            typeEl.contentOrNull?.endsWith("/$type", ignoreCase = true) == true
        is JsonArray -> typeEl.any { isType(it, type) }
        else -> false
    }

    private fun fromJsonLd(o: JsonObject, sourceUrl: String?, doc: Document): ImportedRecipe? {
        val name = text(o["name"])?.let(::clean) ?: doc.title().takeIf { it.isNotBlank() } ?: return null
        val ingredients = stringList(o["recipeIngredient"] ?: o["ingredients"]).map(::clean).filter { it.isNotBlank() }
        val steps = parseInstructions(o["recipeInstructions"])
        val (yieldServings, yieldText) = parseYield(o["recipeYield"] ?: o["yield"])
        return ImportedRecipe(
            name = name,
            description = text(o["description"])?.let(::clean)?.take(500),
            sourceUrl = sourceUrl ?: text(o["url"]),
            imageUrl = parseImage(o["image"]),
            yieldServings = yieldServings,
            yieldText = yieldText,
            prepMinutes = text(o["prepTime"])?.let(::parseIsoDurationMinutes),
            cookMinutes = text(o["cookTime"])?.let(::parseIsoDurationMinutes),
            totalMinutes = text(o["totalTime"])?.let(::parseIsoDurationMinutes),
            ingredientLines = ingredients,
            steps = steps,
            siteNutrition = parseNutrition(o["nutrition"])
        )
    }

    private fun parseInstructions(el: JsonElement?, section: String? = null): List<RecipeStep> {
        val out = mutableListOf<RecipeStep>()
        when (el) {
            null, is JsonNull -> {}
            is JsonPrimitive -> {
                // A single block of text, sometimes HTML with <li> or line breaks.
                val raw = el.contentOrNull ?: return out
                splitInstructionText(raw).forEach { out += RecipeStep(it, section, TimerDetector.firstTimerSeconds(it)) }
            }
            is JsonArray -> el.forEach { out += parseInstructions(it, section) }
            is JsonObject -> {
                when {
                    isType(el["@type"], "HowToSection") -> {
                        val sectionName = text(el["name"])?.let(::clean)
                        out += parseInstructions(el["itemListElement"], sectionName ?: section)
                    }
                    else -> {
                        val t = text(el["text"]) ?: text(el["name"]) ?: text(el["description"])
                        if (t != null) {
                            splitInstructionText(t).forEach { out += RecipeStep(it, section, TimerDetector.firstTimerSeconds(it)) }
                        }
                        el["itemListElement"]?.let { out += parseInstructions(it, section) }
                    }
                }
            }
        }
        return out
    }

    private fun splitInstructionText(raw: String): List<String> {
        if (raw.contains("<li", ignoreCase = true)) {
            return Jsoup.parse(raw).select("li").map { clean(it.text()) }.filter { it.isNotBlank() }
        }
        val text = clean(raw.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n"), keepNewlines = true)
        return text.split('\n').map { it.trim().replace(Regex("""^\d+[.)]\s+"""), "") }.filter { it.isNotBlank() }
    }

    private fun parseYield(el: JsonElement?): Pair<Double?, String?> {
        val values = stringList(el)
        if (values.isEmpty()) return null to null
        // Prefer the value that is a plain number, otherwise the first number in any value.
        values.firstOrNull { it.trim().toDoubleOrNull() != null }?.let { v ->
            val n = v.trim().toDouble()
            return n to (values.firstOrNull { it.trim().toDoubleOrNull() == null } ?: "$v servings")
        }
        for (v in values) {
            Regex("""(\d+(?:[.,]\d+)?)(?:\s*(?:-|–|to)\s*(\d+(?:[.,]\d+)?))?""").find(v)?.let { m ->
                val lo = m.groupValues[1].replace(',', '.').toDouble()
                return lo to clean(v)
            }
        }
        return null to clean(values.first())
    }

    private fun parseImage(el: JsonElement?): String? = when (el) {
        is JsonPrimitive -> el.contentOrNull
        is JsonArray -> {
            // Pick the widest ImageObject, else the last URL (sites usually list small → large).
            val objects = el.filterIsInstance<JsonObject>()
            if (objects.isNotEmpty()) {
                objects.maxByOrNull { text(it["width"])?.toIntOrNull() ?: 0 }?.let { text(it["url"]) }
            } else {
                el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.lastOrNull()
            }
        }
        is JsonObject -> text(el["url"]) ?: text(el["contentUrl"])
        else -> null
    }

    private fun parseNutrition(el: JsonElement?): SiteNutrition? {
        val o = el as? JsonObject ?: return null
        fun num(key: String): Float? = text(o[key])?.let { Regex("""(\d+(?:[.,]\d+)?)""").find(it)?.groupValues?.get(1) }
            ?.replace(',', '.')?.toFloatOrNull()
        val n = SiteNutrition(
            calories = num("calories"),
            protein = num("proteinContent"),
            carbs = num("carbohydrateContent"),
            fat = num("fatContent")
        )
        return if (n.calories == null && n.protein == null) null else n
    }

    // --- Microdata fallback ---

    private fun extractMicrodata(doc: Document, sourceUrl: String?): ImportedRecipe? {
        val scope = doc.selectFirst("[itemtype~=(?i)schema.org/Recipe]") ?: return null
        val ingredients = scope.select("[itemprop=recipeIngredient], [itemprop=ingredients]").map { clean(it.text()) }
            .filter { it.isNotBlank() }
        if (ingredients.isEmpty()) return null
        val name = scope.selectFirst("[itemprop=name]")?.text()?.let(::clean) ?: doc.title()
        val steps = scope.select("[itemprop=recipeInstructions]").flatMap { el ->
            val items = el.select("li")
            if (items.isNotEmpty()) items.map { clean(it.text()) } else listOf(clean(el.text()))
        }.filter { it.isNotBlank() }.map { RecipeStep(it, null, TimerDetector.firstTimerSeconds(it)) }
        fun attr(prop: String): String? = scope.selectFirst("[itemprop=$prop]")?.let { it.attr("content").ifBlank { it.attr("datetime").ifBlank { it.text() } } }
        val (yieldServings, yieldText) = parseYield(attr("recipeYield")?.let { JsonPrimitive(it) })
        return ImportedRecipe(
            name = name,
            description = attr("description")?.let(::clean)?.take(500),
            sourceUrl = sourceUrl,
            imageUrl = scope.selectFirst("[itemprop=image]")?.let { it.absUrl("src").ifBlank { it.attr("content") } }?.ifBlank { null },
            yieldServings = yieldServings,
            yieldText = yieldText,
            prepMinutes = attr("prepTime")?.let(::parseIsoDurationMinutes),
            cookMinutes = attr("cookTime")?.let(::parseIsoDurationMinutes),
            totalMinutes = attr("totalTime")?.let(::parseIsoDurationMinutes),
            ingredientLines = ingredients,
            steps = steps
        )
    }

    // --- Helpers ---

    /** Parses ISO-8601 durations like "PT1H30M" or "P0DT0H45M". Returns whole minutes. */
    fun parseIsoDurationMinutes(value: String): Int? {
        val m = Regex("""^P(?:(\d+)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$""", RegexOption.IGNORE_CASE)
            .find(value.trim()) ?: return value.trim().toIntOrNull()
        val days = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val hours = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val minutes = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val seconds = m.groupValues[4].toDoubleOrNull() ?: 0.0
        val total = days * 1440 + hours * 60 + minutes + seconds / 60
        return if (total > 0) Math.round(total).toInt() else null
    }

    private fun text(el: JsonElement?): String? = when (el) {
        is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonArray -> el.firstNotNullOfOrNull { text(it) }
        is JsonObject -> text(el["@value"]) ?: text(el["text"]) ?: text(el["name"])
        else -> null
    }

    private fun stringList(el: JsonElement?): List<String> = when (el) {
        is JsonPrimitive -> listOfNotNull(el.contentOrNull)
        is JsonArray -> el.flatMap { stringList(it) }
        is JsonObject -> listOfNotNull(text(el))
        else -> emptyList()
    }

    /** Decodes HTML entities, strips tags and collapses whitespace. */
    fun clean(s: String, keepNewlines: Boolean = false): String {
        var t = Parser.unescapeEntities(s, false)
        if (t.contains('<') && t.contains('>')) {
            t = t.replace(Regex("""</(p|div|li)>""", RegexOption.IGNORE_CASE), "\n").replace(Regex("""<[^>]+>"""), " ")
            t = Parser.unescapeEntities(t, false)
        }
        t = t.replace(' ', ' ')
        return if (keepNewlines) {
            t.lines().joinToString("\n") { it.replace(Regex("""[ \t]+"""), " ").trim() }.trim()
        } else {
            t.replace(Regex("""\s+"""), " ").trim()
        }
    }

    /** Pulls the first http(s) URL out of shared text such as "Best Chili\nhttps://…". */
    fun findUrl(text: String): String? =
        Regex("""https?://[^\s<>"']+""").find(text)?.value?.trimEnd('.', ',', ')', ']')
}
