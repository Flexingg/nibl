package com.bytelog.app.recipes

import kotlinx.serialization.Serializable

/** One cooking direction. [section] groups steps such as "For the marinade". */
@Serializable
data class RecipeStep(
    val text: String,
    val section: String? = null,
    val timerSeconds: Int? = null
)

/** Nutrition the source site publishes per serving, kept for sanity-checking our own totals. */
@Serializable
data class SiteNutrition(
    val calories: Float? = null,
    val protein: Float? = null,
    val carbs: Float? = null,
    val fat: Float? = null
)

/** A recipe read from a web page (or pasted text) before ingredients are matched to foods. */
@Serializable
data class ImportedRecipe(
    val name: String,
    val description: String? = null,
    val sourceUrl: String? = null,
    val imageUrl: String? = null,
    val yieldServings: Double? = null,
    val yieldText: String? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val totalMinutes: Int? = null,
    val ingredientLines: List<String> = emptyList(),
    val steps: List<RecipeStep> = emptyList(),
    val siteNutrition: SiteNutrition? = null
)
