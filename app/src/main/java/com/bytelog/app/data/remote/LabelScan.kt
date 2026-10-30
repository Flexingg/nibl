package com.bytelog.app.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject

/** The nutrition facts read from a label photo: per [servingSize] [servingUnit]; null where the label didn't show it. */
data class LabelScan(
    val name: String?,
    val brand: String?,
    val servingSize: Float?,
    val servingUnit: String?,
    val calories: Float?,
    val protein: Float?,
    val carbs: Float?,
    val fat: Float?,
    val fiber: Float?
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Sparky answers with the AI's JSON object; it may come wrapped in a code fence. Null when there are no calories. */
        fun parse(content: String): LabelScan? {
            val text = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val root = (obj["nutrition"] as? JsonObject) ?: obj
            fun str(k: String) = (root[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            fun num(k: String) = (root[k] as? JsonPrimitive)?.let { it.floatOrNull ?: it.contentOrNull?.replace(Regex("[^0-9.]"), "")?.toFloatOrNull() }
            val scan = LabelScan(
                name = str("name"), brand = str("brand"),
                servingSize = num("serving_size")?.takeIf { it > 0f }, servingUnit = str("serving_unit")?.lowercase(),
                calories = num("calories"), protein = num("protein"), carbs = num("carbs"), fat = num("fat"),
                fiber = num("fiber") ?: num("dietary_fiber")
            )
            return scan.takeIf { it.calories != null }
        }
    }
}
