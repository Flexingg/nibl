package com.bytelog.app.recipes

/**
 * Turns pasted recipe text (from notes, messages, a cookbook transcription) into an
 * [ImportedRecipe]. Recognises "Ingredients" / "Directions" headings; without them,
 * lines that start with an amount are treated as ingredients and the rest as steps.
 */
object RecipeTextParser {

    private val ingredientHeader = Regex("""^\s*#*\s*ingredients?\s*:?\s*$""", RegexOption.IGNORE_CASE)
    private val stepsHeader = Regex(
        """^\s*#*\s*(directions?|instructions?|method|steps|preparation|how to make( it)?)\s*:?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val notesHeader = Regex("""^\s*#*\s*(notes?|tips?|storage)\s*:?\s*$""", RegexOption.IGNORE_CASE)
    private val servingsPattern = Regex(
        """(?:serves|servings|yield|makes)\s*:?\s*(\d+(?:[.,]\d+)?)|(\d+(?:[.,]\d+)?)\s*servings""",
        RegexOption.IGNORE_CASE
    )
    private val startsWithAmount = Regex("""^\s*[-•*]?\s*(\d|½|¼|¾|⅓|⅔|⅛|a |an |one |two |three |four )""", RegexOption.IGNORE_CASE)

    fun parse(text: String, fallbackName: String = "Imported Recipe"): ImportedRecipe {
        val lines = text.lines().map { it.trim() }
        var name: String? = null
        var servings: Double? = null
        val ingredients = mutableListOf<String>()
        val steps = mutableListOf<RecipeStep>()
        val notes = mutableListOf<String>()
        var section: String? = null // "ingredients" | "steps" | "notes"
        var stepSection: String? = null
        val hasHeaders = lines.any { ingredientHeader.matches(it) || stepsHeader.matches(it) }

        for (line in lines) {
            if (line.isBlank()) continue
            // A short "Serves 4" / "Yield: 6 servings" line sets the yield and is not a step.
            val servingsMatch = if (line.length < 40) servingsPattern.find(line) else null
            if (servingsMatch != null) {
                if (servings == null) {
                    servings = servingsMatch.groupValues[1].ifBlank { servingsMatch.groupValues[2] }.replace(',', '.').toDoubleOrNull()
                }
                continue
            }
            when {
                ingredientHeader.matches(line) -> { section = "ingredients"; continue }
                stepsHeader.matches(line) -> { section = "steps"; stepSection = null; continue }
                notesHeader.matches(line) -> { section = "notes"; continue }
            }
            if (name == null && section == null && (!startsWithAmount.containsMatchIn(line) || hasHeaders)) {
                name = line.trimStart('#', ' ')
                continue
            }
            val effective = section ?: if (startsWithAmount.containsMatchIn(line)) "ingredients" else "steps"
            when (effective) {
                "ingredients" -> ingredients += line.replace(Regex("""^[-•*▢☐□]+\s*"""), "")
                "notes" -> notes += line
                else -> {
                    if (line.endsWith(":") && line.length < 50) {
                        stepSection = line.removeSuffix(":").trim()
                    } else {
                        val clean = line.replace(Regex("""^(step\s*)?\d+[.):]\s*""", RegexOption.IGNORE_CASE), "")
                        steps += RecipeStep(clean, stepSection, TimerDetector.firstTimerSeconds(clean))
                    }
                }
            }
        }
        return ImportedRecipe(
            name = name ?: fallbackName,
            description = notes.joinToString("\n").ifBlank { null },
            yieldServings = servings,
            ingredientLines = ingredients,
            steps = steps
        )
    }
}
