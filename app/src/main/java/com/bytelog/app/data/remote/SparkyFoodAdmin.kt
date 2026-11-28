package com.bytelog.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// The whole food library as raw JSON, for the assistant's food tools: foods with every field, all their servings,
// and their pictures. Part of [SparkyApiClient].

private suspend fun SparkyApiClient.jsonCall(request: Request, what: String): Result<kotlinx.serialization.json.JsonElement> = withContext(Dispatchers.IO) {
    try {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@use Result.failure(SparkyHttpException(response.code, "$what: ${errorText(response.code, body)}"))
            Result.success(if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body))
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Result.failure(e)
    }
}

/** GET /api/foods/:id: the food with all its fields (name, brand, barcode, notes, images, default serving). */
suspend fun SparkyApiClient.foodJson(foodId: String): Result<JsonObject> =
    jsonCall(Request.Builder().url("$baseUrl/api/foods/$foodId").get().build(), "Couldn't load the food").mapCatching {
        it as? JsonObject ?: throw IOException("Sparky sent an unreadable food")
    }

/** POST /api/foods: a new food of the user's own, with its first (default) serving's fields at the top level. */
suspend fun SparkyApiClient.createFoodJson(payload: JsonObject): Result<JsonObject> =
    jsonCall(Request.Builder().url("$baseUrl/api/foods").post(jsonBody(payload)).build(), "Couldn't create the food").mapCatching {
        (it as? JsonObject)?.takeIf { o -> (o["id"] as? JsonPrimitive)?.content?.isNotBlank() == true }
            ?: throw IOException("Sparky didn't return the new food's id")
    }

/** PUT /api/foods/:id with only the fields to change (name, brand, barcode, notes…). */
suspend fun SparkyApiClient.updateFoodJson(foodId: String, fields: JsonObject): Result<Unit> =
    jsonCall(Request.Builder().url("$baseUrl/api/foods/$foodId").put(jsonBody(fields)).build(), "Couldn't change the food").map { }

/** POST /api/foods/food-variants: another serving of a food, with its own nutrition. Returns the new serving. */
suspend fun SparkyApiClient.createVariantJson(payload: JsonObject): Result<JsonObject> =
    jsonCall(Request.Builder().url("$baseUrl/api/foods/food-variants").post(jsonBody(payload)).build(), "Couldn't add the serving").mapCatching {
        it as? JsonObject ?: JsonObject(emptyMap())
    }

/** PUT /api/foods/food-variants/:id: changes to one serving ([fields] must include food_id). */
suspend fun SparkyApiClient.updateVariantJson(variantId: String, fields: JsonObject): Result<Unit> =
    jsonCall(Request.Builder().url("$baseUrl/api/foods/food-variants/$variantId").put(jsonBody(fields)).build(), "Couldn't change the serving").map { }

/** DELETE /api/foods/food-variants/:id. */
suspend fun SparkyApiClient.deleteVariant(variantId: String): Result<Unit> =
    jsonCall(Request.Builder().url("$baseUrl/api/foods/food-variants/$variantId").delete().build(), "Couldn't delete the serving").map { }

/**
 * Adds pictures to a food (PUT /api/foods/:id as multipart): [images] are JPEG/PNG bytes with their media type, put
 * after the food's [existing] pictures, or in place of them when [existing] is empty.
 */
suspend fun SparkyApiClient.addFoodImages(foodId: String, existing: List<String>, images: List<Pair<ByteArray, String>>): Result<Unit> {
    val order = JsonArray(existing.map { JsonPrimitive(it) } + images.indices.map { JsonPrimitive("__new__$it") })
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("foodData", buildJsonObject { put("images", order) }.toString())
        .apply {
            images.forEachIndexed { i, (bytes, type) ->
                val ext = if (type.contains("png")) "png" else if (type.contains("webp")) "webp" else "jpg"
                addFormDataPart("images", "food-$i.$ext", bytes.toRequestBody(type.toMediaType()))
            }
        }
        .build()
    return jsonCall(Request.Builder().url("$baseUrl/api/foods/$foodId").put(body).build(), "Couldn't add the picture").map { }
}
