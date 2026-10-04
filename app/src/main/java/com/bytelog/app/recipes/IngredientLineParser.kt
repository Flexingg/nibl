package com.bytelog.app.recipes

import kotlinx.serialization.Serializable

/**
 * One recipe ingredient line split into amount, unit and food name.
 * [grams] is filled when the amount can be converted deterministically.
 */
@Serializable
data class ParsedIngredient(
    val raw: String,
    val quantity: Double?,
    val quantityMax: Double? = null,
    val unit: String?,
    val name: String,
    val note: String? = null,
    val grams: Double? = null,
    val optional: Boolean = false,
    val confidence: Confidence = Confidence.LOW
) {
    enum class Confidence { HIGH, MEDIUM, LOW }
}

/**
 * Deterministic parser for recipe ingredient strings such as
 * "1 1/2 lbs boneless skinless chicken breast, diced" or "1 (14.5 oz) can diced tomatoes".
 */
object IngredientLineParser {

    private val unicodeFractions = mapOf(
        '½' to "1/2", '⅓' to "1/3", '⅔' to "2/3", '¼' to "1/4", '¾' to "3/4", '⅕' to "1/5",
        '⅖' to "2/5", '⅗' to "3/5", '⅘' to "4/5", '⅙' to "1/6", '⅚' to "5/6", '⅛' to "1/8",
        '⅜' to "3/8", '⅝' to "5/8", '⅞' to "7/8"
    )

    // A single amount: "1 1/2", "1-1/2", "1/2", "1.5", "1,5", "2". Mixed numbers and fractions
    // come first so "1/2" is not read as "1".
    private const val NUM = """(?:\d+[\s-]+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?|\.\d+)"""
    private val leadingAmount = Regex("""^\s*($NUM)(?:\s*(?:-|–|to|or)\s*($NUM))?\s*""", RegexOption.IGNORE_CASE)
    private val trailingCountUnits = setOf("clove", "cloves", "slice", "slices", "stalk", "stalks", "sprig", "sprigs", "fillet", "fillets")
    private val sizeWords = setOf("large", "medium", "small", "extra-large", "jumbo")
    private val containerWords = setOf("can", "cans", "jar", "jars", "package", "packages", "pkg", "bag", "bags", "box", "tin", "tins", "carton", "bottle")

    /** "(28-ounce; 794g) can tomatoes" → ("28-ounce; 794g", "can tomatoes") */
    private fun parenAtStart(text: String): Pair<String, String>? {
        val t = text.trimStart()
        if (!t.startsWith("(")) return null
        val close = t.indexOf(')')
        if (close < 0) return null
        return t.substring(1, close).trim() to t.substring(close + 1).trim()
    }

    private fun metricGrams(s: String): Double? =
        Regex("""(\d+(?:[.,]\d+)?)\s*(kg|g|grams?)\b""", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            val n = m.groupValues[1].replace(',', '.').toDouble()
            if (m.groupValues[2].equals("kg", true)) n * 1000 else n
        }

    private fun metricMl(s: String): Double? =
        Regex("""(\d+(?:[.,]\d+)?)\s*(ml|l|liters?|litres?)\b""", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            val n = m.groupValues[1].replace(',', '.').toDouble()
            if (m.groupValues[2].startsWith("l", true)) n * 1000 else n
        }

    private fun imperialGrams(s: String): Double? =
        Regex("""($NUM)\s*-?\s*(ounces?|oz|pounds?|lbs?)\b""", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            parseNumber(m.groupValues[1])?.let { UnitConverter.convert(it, m.groupValues[2], "g") }
        }
    private val wordNumbers = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0,
        "five" to 5.0, "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0,
        "twelve" to 12.0, "half" to 0.5, "dozen" to 12.0
    )
    private val toTastePhrases = listOf("to taste", "as needed", "for garnish", "for serving", "optional", "as desired")

    /** Descriptors that do not change nutrition and are moved into the note. */
    private val prepWords = listOf(
        "finely chopped", "roughly chopped", "coarsely chopped", "thinly sliced", "chopped", "diced",
        "minced", "sliced", "grated", "shredded", "crushed", "peeled", "cubed", "julienned", "halved",
        "quartered", "trimmed", "rinsed", "drained", "rinsed and drained", "softened", "melted",
        "room temperature", "at room temperature", "divided", "packed", "lightly packed", "firmly packed",
        "beaten", "lightly beaten", "sifted", "zested", "juiced", "seeded", "deveined", "torn",
        "cut into", "plus more", "fresh", "freshly"
    )

    fun parse(rawLine: String): ParsedIngredient {
        val raw = rawLine.trim().replace(Regex("""\s+"""), " ")
        var text = normaliseFractions(raw)
        // Strip list bullets like "- " or "• "
        text = text.replace(Regex("""^[-•*▢☐□]+\s*"""), "")

        val optional = toTastePhrases.any { text.contains(it, ignoreCase = true) }

        var quantity: Double? = null
        var quantityMax: Double? = null
        var unit: String? = null
        var packageGrams: Double? = null

        val amountMatch = leadingAmount.find(text)
        if (amountMatch != null && amountMatch.value.isNotBlank()) {
            quantity = parseNumber(amountMatch.groupValues[1])
            quantityMax = amountMatch.groupValues[2].takeIf { it.isNotBlank() }?.let { parseNumber(it) }
            text = text.substring(amountMatch.range.last + 1)
        } else {
            val firstWord = text.substringBefore(' ').lowercase()
            wordNumbers[firstWord]?.let {
                quantity = it
                text = text.substringAfter(' ', "")
            }
        }

        // Package size: "1 (14.5 oz) can tomatoes" or "1 (28-ounce; 794g) can" → weight per can
        parenAtStart(text)?.let { (content, rest) ->
            val grams = metricGrams(content) ?: imperialGrams(content)
            val nextWord = rest.substringBefore(' ').trimEnd(',').lowercase()
            if (grams != null && nextWord in UnitConverter.countUnits) {
                packageGrams = grams
                text = rest
            }
        }

        // Unit: try two-word units first ("fl oz", "fluid ounces")
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.isNotEmpty()) {
            val two = if (words.size >= 2) "${words[0]} ${words[1]}".trimEnd(',', '.') else null
            val one = words[0].trimEnd(',')
            when {
                two != null && UnitConverter.canonical(two) != null -> {
                    unit = UnitConverter.canonical(two)
                    text = words.drop(2).joinToString(" ")
                }
                quantity != null && UnitConverter.canonical(one) != null && one != "c" -> {
                    unit = UnitConverter.canonical(one)
                    text = words.drop(1).joinToString(" ")
                }
                quantity != null && one == "c." -> {
                    unit = "cup"
                    text = words.drop(1).joinToString(" ")
                }
                one.lowercase() in UnitConverter.countUnits && one.lowercase() !in sizeWords + setOf("whole", "egg", "eggs") -> {
                    unit = singular(one.lowercase())
                    text = words.drop(1).joinToString(" ")
                }
            }
        }

        // Metric weight given alongside: "1 pound (450g) beans", "3 tablespoons (26g) salt", "1 1/2 cups (355 ml) water"
        var statedGrams: Double? = null
        var statedMl: Double? = null
        parenAtStart(text)?.let { (content, rest) ->
            val g = metricGrams(content)
            val ml = metricMl(content)
            if (g != null || ml != null || UnitConverter.dimension(content.replace(Regex("""[\d\s./-]+"""), " ").trim()) != null) {
                statedGrams = g
                statedMl = ml
                text = rest
            }
        }
        text = text.removePrefix("of ").trim()

        // "400g can chopped tomatoes": the container word is redundant once there is a weight.
        if (unit != null && UnitConverter.dimension(unit!!) != UnitConverter.Dimension.COUNT) {
            val first = text.substringBefore(' ').lowercase()
            if (first in containerWords) text = text.substringAfter(' ', "")
        }

        var (name, note) = splitNameAndNote(text)
        val extraNotes = mutableListOf<String>()

        // Alternatives: "kosher salt or 6 tablespoons table salt" → "kosher salt"
        Regex("""\s+or\s+""", RegexOption.IGNORE_CASE).find(name)?.let { m ->
            extraNotes += "or " + name.substring(m.range.last + 1).trim()
            name = name.substring(0, m.range.first).trim()
        }
        if (unit == null) {
            // "3 whole ancho chiles", "1 large yellow onion" → keep the size as a note
            val first = name.substringBefore(' ').lowercase()
            if ((first == "whole" || first in sizeWords) && name.contains(' ')) {
                extraNotes += first
                name = name.substringAfter(' ')
            }
            // "2 garlic cloves" → unit clove, name garlic
            val last = name.substringAfterLast(' ').lowercase()
            if (name.contains(' ') && last in trailingCountUnits) {
                unit = singular(last)
                name = name.substringBeforeLast(' ')
            }
        }
        if (extraNotes.isNotEmpty()) note = (listOfNotNull(note) + extraNotes).joinToString(", ")

        // Grams: a stated metric weight wins, then package size, then unit conversion.
        var grams: Double? = null
        var estimatedDensity = false
        if (quantity != null || statedGrams != null) {
            val avgQty = quantity?.let { q -> quantityMax?.let { (q + it) / 2.0 } ?: q } ?: 1.0
            grams = when {
                statedGrams != null -> statedGrams
                packageGrams != null -> avgQty * packageGrams!!
                statedMl != null -> statedMl!! * (IngredientDensities.lookup(name) ?: 1.0.also { estimatedDensity = true })
                unit != null -> UnitConverter.toGrams(avgQty, unit!!, name)
                    ?: if (UnitConverter.dimension(unit!!) == UnitConverter.Dimension.VOLUME) {
                        estimatedDensity = true
                        UnitConverter.convert(avgQty, unit!!, "g", 1.0)
                    } else null
                else -> null
            }
        }

        val confidence = when {
            name.isBlank() -> ParsedIngredient.Confidence.LOW
            statedGrams != null -> ParsedIngredient.Confidence.HIGH
            grams != null && !estimatedDensity && UnitConverter.dimension(unit ?: "") == UnitConverter.Dimension.MASS -> ParsedIngredient.Confidence.HIGH
            grams != null && !estimatedDensity -> ParsedIngredient.Confidence.MEDIUM
            quantity != null -> ParsedIngredient.Confidence.MEDIUM
            else -> ParsedIngredient.Confidence.LOW
        }

        return ParsedIngredient(
            raw = raw,
            quantity = quantity,
            quantityMax = quantityMax,
            unit = unit,
            name = name,
            note = note,
            grams = grams?.let { Math.round(it * 10.0) / 10.0 },
            optional = optional,
            confidence = confidence
        )
    }

    fun normaliseFractions(s: String): String {
        val sb = StringBuilder()
        for ((i, ch) in s.withIndex()) {
            val frac = unicodeFractions[ch]
            if (frac != null) {
                if (i > 0 && s[i - 1].isDigit()) sb.append(' ')
                sb.append(frac)
            } else if (ch == '⁄') {
                sb.append('/')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun parseNumber(s: String): Double? {
        val t = s.trim().replace(',', '.')
        // Mixed number "1 1/2" or "1-1/2"
        Regex("""^(\d+)[\s-]+(\d+)/(\d+)$""").find(t)?.let { m ->
            val whole = m.groupValues[1].toDouble()
            val den = m.groupValues[3].toDouble()
            if (den == 0.0) return null
            return whole + m.groupValues[2].toDouble() / den
        }
        Regex("""^(\d+)/(\d+)$""").find(t)?.let { m ->
            val den = m.groupValues[2].toDouble()
            if (den == 0.0) return null
            return m.groupValues[1].toDouble() / den
        }
        return t.toDoubleOrNull()
    }

    private val irregularSingulars = mapOf("leaves" to "leaf", "loaves" to "loaf", "halves" to "half")

    private fun singular(word: String): String = irregularSingulars[word] ?: when {
        word.endsWith("ches") || word.endsWith("shes") -> word.dropLast(2)
        word.endsWith("s") && word.length > 3 -> word.dropLast(1)
        else -> word
    }

    /**
     * Splits "boneless skinless chicken breast, diced" into name and note.
     * Text after the first comma, and parenthetical remarks, become the note.
     */
    private fun splitNameAndNote(text: String): Pair<String, String?> {
        var name = text.trim()
        val notes = mutableListOf<String>()

        Regex("""\(([^)]*)\)""").findAll(name).forEach { notes += it.groupValues[1].trim() }
        name = name.replace(Regex("""\([^)]*\)"""), " ")

        val comma = name.indexOf(',')
        if (comma >= 0) {
            notes += name.substring(comma + 1).trim()
            name = name.substring(0, comma)
        }
        toTastePhrases.forEach { phrase ->
            if (name.contains(phrase, ignoreCase = true)) {
                name = name.replace(phrase, "", ignoreCase = true)
                notes += phrase
            }
        }
        // Trailing prep words: "onion chopped" → "onion"; leading: "chopped onion" → "onion"
        for (word in prepWords.sortedByDescending { it.length }) {
            val leading = Regex("""^${Regex.escape(word)}\s+""", RegexOption.IGNORE_CASE)
            val trailing = Regex("""\s+${Regex.escape(word)}$""", RegexOption.IGNORE_CASE)
            if (leading.containsMatchIn(name)) {
                name = name.replace(leading, "")
                notes += word
            }
            if (trailing.containsMatchIn(name)) {
                name = name.replace(trailing, "")
                notes += word
            }
        }
        name = name.replace(Regex("""\s+"""), " ").trim().trim(',', '.', ';', ':', '-').trim()
        val note = notes.filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank { null }
        return name to note
    }
}
