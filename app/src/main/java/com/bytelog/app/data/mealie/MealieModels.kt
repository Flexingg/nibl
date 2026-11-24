package com.bytelog.app.data.mealie

import kotlinx.serialization.Serializable

enum class MealieSource {
    DIRECT,
    SPARKY
}

@Serializable
data class MealieConfig(
    val baseUrl: String = "",
    val apiToken: String = "",
    val defaultShoppingListId: String? = null
) {
    val isDirectConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiToken.isNotBlank()

    val normalizedUrl: String
        get() = baseUrl.trim().trimEnd('/')
}

@Serializable
data class MealieNutrition(
    val calories: Float? = null,
    val protein: Float? = null,
    val carbs: Float? = null,
    val fat: Float? = null,
    val fiber: Float? = null,
    val sodium: Float? = null,
    val sugar: Float? = null
) {
    val hasAny: Boolean
        get() = calories != null || protein != null || carbs != null || fat != null
}

@Serializable
data class MealieIngredient(
    val display: String,
    val quantity: Float? = null,
    val unit: String? = null,
    val food: String? = null,
    val note: String? = null,
    val title: String? = null
)

@Serializable
data class MealieInstruction(
    val title: String? = null,
    val text: String
)

@Serializable
data class MealieRecipeSummary(
    val id: String,
    val slug: String,
    val name: String,
    val description: String? = null,
    val servings: Float = 1f,
    val prepTime: String? = null,
    val cookTime: String? = null,
    val totalTime: String? = null,
    val imageUrl: String? = null,
    val tags: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val calories: Float? = null,
    val source: MealieSource = MealieSource.DIRECT,
    val sparkyProviderId: String? = null
)

@Serializable
data class MealieRecipeDetail(
    val id: String,
    val slug: String,
    val name: String,
    val description: String? = null,
    val servings: Float = 1f,
    val prepTime: String? = null,
    val cookTime: String? = null,
    val totalTime: String? = null,
    val imageUrl: String? = null,
    val originalUrl: String? = null,
    val ingredients: List<MealieIngredient> = emptyList(),
    val instructions: List<MealieInstruction> = emptyList(),
    val nutrition: MealieNutrition? = null,
    val notes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val source: MealieSource = MealieSource.DIRECT
)

@Serializable
data class MealieMealPlanEntry(
    val id: String,
    val date: String, // YYYY-MM-DD
    val entryType: String = "dinner", // breakfast, lunch, dinner, side, snack
    val title: String = "",
    val text: String? = null,
    val recipeId: String? = null,
    val recipeSlug: String? = null,
    val recipeName: String? = null
) {
    val displayTitle: String
        get() = recipeName?.takeIf { it.isNotBlank() }
            ?: title.takeIf { it.isNotBlank() }
            ?: text?.takeIf { it.isNotBlank() }
            ?: "Planned meal"
}

@Serializable
data class MealieShoppingList(
    val id: String,
    val name: String,
    val itemCount: Int = 0
)

@Serializable
data class MealieShoppingItem(
    val id: String,
    val listId: String,
    val note: String = "",
    val display: String = "",
    val quantity: Float = 1f,
    val checked: Boolean = false,
    val position: Int = 0,
    val foodName: String? = null,
    val unitName: String? = null,
    val labelName: String? = null
) {
    val label: String
        get() = display.takeIf { it.isNotBlank() }
            ?: listOfNotNull(
                quantity.takeIf { it > 0f && it != 1f }?.let { if (it % 1f == 0f) it.toInt().toString() else "%.1f".format(it) },
                unitName?.takeIf { it.isNotBlank() },
                foodName?.takeIf { it.isNotBlank() },
                note.takeIf { it.isNotBlank() }
            ).joinToString(" ").ifBlank { note.ifBlank { "Item" } }
}

@Serializable
data class MealieImportPreview(
    val name: String,
    val slug: String? = null,
    val servings: Float = 1f,
    val ingredients: List<String> = emptyList(),
    val calories: Float? = null,
    val protein: Float? = null,
    val carbs: Float? = null,
    val fat: Float? = null,
    val rawPayload: String? = null
)

@Serializable
data class MealieSyncResult(
    val success: Boolean,
    val importedCount: Int = 0,
    val pushedCount: Int = 0,
    val skippedCount: Int = 0,
    val message: String
)

object MealieDurationParser {
    private val isoRegex = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""", RegexOption.IGNORE_CASE)
    private val hourRegex = Regex("""(\d+)\s*(?:h|hr|hrs|hour|hours)\b""", RegexOption.IGNORE_CASE)
    private val minRegex = Regex("""(\d+)\s*(?:m|min|mins|minute|minutes)\b""", RegexOption.IGNORE_CASE)
    private val plainNumberRegex = Regex("""^\d+$""")

    /** Parses ISO-8601 ("PT1H20M") or human duration ("45 mins") into total minutes. */
    fun toMinutes(raw: String?): Int? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        isoRegex.find(text)?.let { match ->
            val h = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            val s = match.groupValues[3].toIntOrNull() ?: 0
            val total = h * 60 + m + if (s >= 30) 1 else 0
            if (total > 0) return total
        }
        val hours = hourRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = minRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (hours > 0 || mins > 0) return hours * 60 + mins
        if (plainNumberRegex.matches(text)) return text.toIntOrNull()?.takeIf { it > 0 }
        return null
    }
}
