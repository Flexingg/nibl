package com.bytelog.app.research

import com.bytelog.app.data.remote.PhotoMealEstimate
import com.bytelog.app.data.remote.PhotoMealItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Estimates a meal photo with the user's own model. Each food comes back with two or three best guesses, most
 * likely first, so the user can pick the right one (or edit it) instead of retyping a wrong estimate.
 */
class MealPhotoGuesser(private val provider: LlmProvider) {

    suspend fun estimate(images: List<Pair<String, String>>, note: String?): Result<PhotoMealEstimate> = runCatching {
        val raw = provider.lookAt(instructions(note), images, SCHEMA)
        parse(raw, provider.id.shortName) ?: throw ProviderException("${provider.id.shortName} sent an estimate the app couldn't read. Try again.")
    }

    companion object {
        const val MAX_GUESSES = 3

        fun instructions(note: String?): String = buildString {
            append("You estimate nutrition from meal photos for a food-logging app. The user picks from your guesses, so make them count.\n\n")
            append("- List every separate food and drink you can see, one item each (a sandwich is one item; rice and curry side by side are two).\n")
            append("- For each item give 2 or 3 guesses, most likely first. Make the guesses differ where you're actually unsure: ")
            append("what the food is (chicken thigh or breast, white or brown rice, latte or flat white) or, when that's clear, how much there is ")
            append("(a smaller and a larger portion). Give only 1 guess when you're sure of both.\n")
            append("- grams is the edible weight you estimate for that guess; nutrition is for that weight, from standard reference values (USDA or similar). ")
            append("Count cooking fat and sauces you can see or would expect.\n")
            append("- portion is how a person would say the amount (\"1 cup\", \"2 slices\", \"1 medium\").\n")
            append("- why is a few words on what you saw (\"grill marks, no skin\").\n")
            append("- questions: up to 3 short things that would most change the estimate (\"Was it cooked in oil?\"). Empty if none.\n")
            append("- confidence: high when you'd bet on it, low when the photo is unclear.\n")
            if (!note.isNullOrBlank()) {
                append("\nThe user added (trust this over what the photo suggests): ").append(note.trim()).append('\n')
            }
            append("\nReply with only this JSON:\n")
            append("""{"summary":"Chicken rice bowl","confidence":"medium","confidence_reason":"…","items":[{"guesses":[{"name":"Grilled chicken breast","grams":120,"portion":"1 fillet","calories":198,"protein":37,"carbs":0,"fat":4.3,"fiber":0,"confidence":"medium","why":"…"}]}],"questions":["…"]}""")
        }

        /** Reads the model's reply. Null when there's nothing usable in it. */
        fun parse(raw: String, estimatedBy: String? = null): PhotoMealEstimate? {
            val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val root = runCatching { Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject }.getOrNull() ?: return null
            val items = (root["items"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                // A model may skip the guesses list and send the food itself
                val guesses = ((o["guesses"] as? JsonArray)?.mapNotNull { guess(it) } ?: listOfNotNull(guess(o)))
                    .distinctBy { it.name.lowercase() to it.grams.toInt() }
                    .take(MAX_GUESSES)
                guesses.firstOrNull()?.copy(alternatives = guesses.drop(1))
            }
            if (items.isEmpty()) return null
            return PhotoMealEstimate(
                summary = root.text("summary") ?: items.joinToString(", ") { it.name },
                confidence = root.text("confidence")?.lowercase()?.takeIf { it in setOf("high", "medium", "low") } ?: "medium",
                confidenceReason = root.text("confidence_reason"),
                items = items,
                questions = (root["questions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { q -> q.isNotEmpty() } }.take(3),
                estimatedBy = estimatedBy
            )
        }

        private fun guess(el: JsonElement): PhotoMealItem? {
            val o = el as? JsonObject ?: return null
            val name = o.text("name") ?: return null
            val calories = o.num("calories") ?: return null
            return PhotoMealItem(
                name = name,
                canonicalName = null,
                grams = (o.num("grams") ?: 0f).coerceAtLeast(0f),
                portion = o.text("portion"),
                calories = calories.coerceAtLeast(0f),
                protein = (o.num("protein") ?: 0f).coerceAtLeast(0f),
                carbs = (o.num("carbs") ?: 0f).coerceAtLeast(0f),
                fat = (o.num("fat") ?: 0f).coerceAtLeast(0f),
                fiber = (o.num("fiber") ?: 0f).coerceAtLeast(0f),
                confidence = o.text("confidence")?.lowercase() ?: "medium",
                reason = o.text("why")
            )
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

        private fun JsonObject.num(key: String): Float? =
            (this[key] as? JsonPrimitive)?.let { it.floatOrNull ?: it.contentOrNull?.filter { c -> c.isDigit() || c == '.' }?.toFloatOrNull() }

        private fun str(description: String) = buildJsonObject { put("type", JsonPrimitive("string")); put("description", JsonPrimitive(description)) }
        private fun num(description: String) = buildJsonObject { put("type", JsonPrimitive("number")); put("description", JsonPrimitive(description)) }
        private fun level() = buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("enum", buildJsonArray { listOf("high", "medium", "low").forEach { add(JsonPrimitive(it)) } })
        }
        private fun obj(vararg props: Pair<String, JsonObject>) = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
            put("required", buildJsonArray { props.forEach { add(JsonPrimitive(it.first)) } })
            put("additionalProperties", JsonPrimitive(false))
        }
        private fun array(items: JsonObject) = buildJsonObject { put("type", JsonPrimitive("array")); put("items", items) }

        /** The reply's shape, for models that can be held to a schema. */
        val SCHEMA: JsonObject = obj(
            "summary" to str("A short name for the meal"),
            "confidence" to level(),
            "confidence_reason" to str("Why, in a sentence"),
            "items" to array(obj(
                "guesses" to array(obj(
                    "name" to str("Food name"),
                    "grams" to num("Estimated edible weight in grams"),
                    "portion" to str("The amount as a person would say it"),
                    "calories" to num("kcal for that weight"),
                    "protein" to num("g"),
                    "carbs" to num("g"),
                    "fat" to num("g"),
                    "fiber" to num("g"),
                    "confidence" to level(),
                    "why" to str("A few words on what you saw")
                ))
            )),
            "questions" to array(str("Something that would sharpen the estimate"))
        )
    }
}
