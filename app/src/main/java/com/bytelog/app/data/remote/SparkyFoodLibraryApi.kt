package com.bytelog.app.data.remote

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealTemplate
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.TemplateIngredient
import com.bytelog.app.data.local.model.WeightEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

// The food library: the user's foods, their servings, prices and edits. Part of [SparkyApiClient].

suspend fun SparkyApiClient.createCustomFood(
    name: String,
    brand: String? = null,
    servingSize: Float = 100f,
    servingUnit: String = "g",
    calories: Float,
    protein: Float,
    carbs: Float,
    fat: Float,
    fiber: Float? = 0f
): Result<FoodItem> = withContext(Dispatchers.IO) {
    try {
        val payload = buildJsonObject {
            put("name", name)
            brand?.let { put("brand", it) }
            putNum("serving_size", servingSize)
            put("serving_unit", servingUnit)
            putNum("calories", calories)
            putNum("protein", protein)
            putNum("carbs", carbs)
            putNum("fat", fat)
            fiber?.let { putNum("dietary_fiber", it) }
            put("is_custom", true)
        }.toString()
        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/foods")
            .post(body)
            .build()

        createClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val content = response.body?.string().orEmpty()
                val obj = json.parseToJsonElement(content).jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                val foodItem = FoodItem(
                    id = id,
                    name = name,
                    brand = brand,
                    servingSize = servingSize,
                    servingUnit = servingUnit,
                    calories = calories,
                    protein = protein,
                    carbs = carbs,
                    fat = fat,
                    fiber = fiber
                )
                Result.success(foodItem)
            } else {
                val err = response.body?.string().orEmpty()
                Result.failure(IOException("Create custom food failed: HTTP ${response.code} $err"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/** GET /api/foods/:id — the food with its default serving and nutrients. */
suspend fun SparkyApiClient.getFoodDetails(foodId: String): Result<SparkyFood> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder().url("$baseUrl/api/foods/$foodId").get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@use Result.failure(IOException("Couldn't load food: ${errorText(response.code, body)}"))
            Result.success(SparkyFood.parse(json.parseToJsonElement(body).jsonObject))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/** The signed-in user's own foods (foodFilter=mine), newest Sparky ordering, with their default servings. */
suspend fun SparkyApiClient.listMyFoods(search: String = "", page: Int = 1, perPage: Int = 50): Result<List<SparkyFood>> = withContext(Dispatchers.IO) {
    try {
        val q = java.net.URLEncoder.encode(search.trim(), "UTF-8")
        val request = Request.Builder()
            .url("$baseUrl/api/foods/foods-paginated?searchTerm=$q&foodFilter=mine&currentPage=$page&itemsPerPage=$perPage&sortBy=name:asc")
            .get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@use Result.failure(IOException("Couldn't load your foods: ${errorText(response.code, body)}"))
            val root = json.parseToJsonElement(body)
            val arr = (root as? kotlinx.serialization.json.JsonObject)?.get("foods")?.jsonArray
                ?: (root as? kotlinx.serialization.json.JsonArray) ?: kotlinx.serialization.json.JsonArray(emptyList())
            Result.success(arr.map { SparkyFood.parse(it.jsonObject) })
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/** PUT /api/foods/:id — name, brand and barcode (a blank barcode clears it). */
suspend fun SparkyApiClient.updateFood(foodId: String, name: String, brand: String?, barcode: String?): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("name", JsonPrimitive(name))
            put("brand", JsonPrimitive(brand.orEmpty()))
            put("barcode", barcode?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        val request = Request.Builder().url("$baseUrl/api/foods/$foodId").put(jsonBody(payload)).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(IOException("Couldn't save food: ${errorText(response.code, body)}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/** PUT /api/foods/food-variants/:id — serving and nutrients of the food's default variant. */
suspend fun SparkyApiClient.updateFoodVariant(variantId: String, foodId: String, n: SparkyNutrients): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val payload = kotlinx.serialization.json.JsonObject(nutrientsJson(n, foodId))
        val request = Request.Builder().url("$baseUrl/api/foods/food-variants/$variantId").put(jsonBody(payload)).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(IOException("Couldn't save nutrition: ${errorText(response.code, body)}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/** POST /api/foods — a new custom food of the user's own (used to save an edited copy of someone else's food). */
suspend fun SparkyApiClient.createFoodFull(
    name: String,
    brand: String?,
    n: SparkyNutrients,
    barcode: String? = null,
    providerType: String? = null,
    quickFood: Boolean = false
): Result<SparkyFood> = withContext(Dispatchers.IO) {
    try {
        val payload = kotlinx.serialization.json.JsonObject(
            nutrientsJson(n, null) + mapOf(
                "name" to JsonPrimitive(name),
                "brand" to (brand?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull),
                // No barcode here: Sparky answers a create whose barcode it already knows with that existing food,
                // so the copy is created bare and the barcode is set afterwards with updateFood.
                "is_custom" to JsonPrimitive(providerType == null),
                "is_default" to JsonPrimitive(true),
                "is_quick_food" to JsonPrimitive(quickFood)
            ) + listOfNotNull(
                // Only for foods from a provider (OpenFoodFacts), where finding the existing food is what we want
                barcode?.takeIf { it.isNotBlank() }?.let { "barcode" to JsonPrimitive(it) },
                providerType?.let { "provider_type" to JsonPrimitive(it) },
                providerType?.let { barcode?.takeIf { b -> b.isNotBlank() }?.let { "provider_external_id" to JsonPrimitive(it) } }
            )
        )
        val request = Request.Builder().url("$baseUrl/api/foods").post(jsonBody(payload)).build()
        createClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@use Result.failure(IOException("Couldn't save your copy: ${errorText(response.code, body)}"))
            val parsed = runCatching { SparkyFood.parse(json.parseToJsonElement(body).jsonObject) }.getOrNull()
                ?.takeIf { it.id.isNotBlank() }
                ?: return@use Result.failure(IOException("Sparky didn't return the new food's id"))
            // Sparky's own reply for a new food; a barcode match returns the existing food instead
            Result.success(parsed.copy(nutrients = if (parsed.variantId == null) n else parsed.nutrients))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/** POST /api/foods/update-snapshot — rewrite the user's past diary entries of this food with its current nutrition. */
suspend fun SparkyApiClient.updatePastEntries(foodId: String, variantId: String?): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("foodId", JsonPrimitive(foodId))
            variantId?.let { put("variantId", JsonPrimitive(it)) }
            put("syncImages", JsonPrimitive(false)) // nutrition only; keep any photos set on entries
        }
        val request = Request.Builder().url("$baseUrl/api/foods/update-snapshot").post(jsonBody(payload)).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(IOException("Couldn't update past entries: ${errorText(response.code, body)}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/** DELETE /api/foods/:id?mode=hide|delete — "hide" only drops it from search; "delete" keeps diary entries. */
suspend fun SparkyApiClient.deleteFood(foodId: String, hideOnly: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val mode = if (hideOnly) "hide" else "delete"
        val request = Request.Builder().url("$baseUrl/api/foods/$foodId?mode=$mode").delete().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful || response.code == 404) Result.success(Unit)
            else Result.failure(IOException("Couldn't delete food: ${errorText(response.code, body)}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

internal fun SparkyApiClient.nutrientsJson(n: SparkyNutrients, foodId: String?): Map<String, kotlinx.serialization.json.JsonElement> {
    val m = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
    foodId?.let { m["food_id"] = JsonPrimitive(it) }
    m["serving_size"] = JsonPrimitive(n.servingSize)
    m["serving_unit"] = JsonPrimitive(n.servingUnit)
    m["calories"] = JsonPrimitive(n.calories)
    m["protein"] = JsonPrimitive(n.protein)
    m["carbs"] = JsonPrimitive(n.carbs)
    m["fat"] = JsonPrimitive(n.fat)
    // Optional nutrients: only sent when known, so Sparky keeps what it has for the rest
    n.fiber?.let { m["dietary_fiber"] = JsonPrimitive(it) }
    n.sugars?.let { m["sugars"] = JsonPrimitive(it) }
    n.saturatedFat?.let { m["saturated_fat"] = JsonPrimitive(it) }
    n.sodium?.let { m["sodium"] = JsonPrimitive(it) }
    n.cholesterol?.let { m["cholesterol"] = JsonPrimitive(it) }
    n.potassium?.let { m["potassium"] = JsonPrimitive(it) }
    return m
}

/** Adds another serving to a food on Sparky ("2 cookies" with its nutrition). */
suspend fun SparkyApiClient.createFoodVariant(foodId: String, servingSize: Float, servingUnit: String, calories: Float, protein: Float, carbs: Float, fat: Float): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("food_id", kotlinx.serialization.json.JsonPrimitive(foodId))
            put("serving_size", kotlinx.serialization.json.JsonPrimitive(servingSize))
            put("serving_unit", kotlinx.serialization.json.JsonPrimitive(servingUnit))
            put("calories", kotlinx.serialization.json.JsonPrimitive(calories))
            put("protein", kotlinx.serialization.json.JsonPrimitive(protein))
            put("carbs", kotlinx.serialization.json.JsonPrimitive(carbs))
            put("fat", kotlinx.serialization.json.JsonPrimitive(fat))
            put("is_default", kotlinx.serialization.json.JsonPrimitive(false))
        }
        val request = Request.Builder().url("$baseUrl/api/foods/food-variants").post(jsonBody(body)).build()
        client.newCall(request).await().use { response ->
            if (response.isSuccessful) Result.success(Unit) else Result.failure(IOException("Add serving error: HTTP ${response.code}"))
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Result.failure(e)
    }
}

/** All servings Sparky has for a food (GET /api/foods/food-variants?food_id=), default included. */
suspend fun SparkyApiClient.fetchFoodVariants(foodId: String): Result<List<kotlinx.serialization.json.JsonObject>> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url("$baseUrl/api/foods/food-variants?food_id=${java.net.URLEncoder.encode(foodId, "UTF-8")}")
            .get().build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) return@use Result.failure(IOException("Food servings error: HTTP ${response.code}"))
            val root = json.parseToJsonElement(response.body?.string().orEmpty())
            Result.success((root as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }.orEmpty())
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Result.failure(e)
    }
}

/** A food's default serving on Sparky: its id and its custom nutrients (where Nibl keeps store and price). */
internal suspend fun SparkyApiClient.defaultVariant(foodId: String): Result<kotlinx.serialization.json.JsonObject> =
    fetchFoodVariants(foodId).mapCatching { variants ->
        variants.firstOrNull { (it["is_default"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == "true" }
            ?: variants.firstOrNull() ?: throw IOException("Sparky has no serving for this food")
    }

/** The store and price saved on a food in Sparky (all null when none is). */
suspend fun SparkyApiClient.fetchFoodPrice(foodId: String): Result<com.bytelog.app.data.repository.SparkyPrice> =
    defaultVariant(foodId).map { v -> com.bytelog.app.data.repository.PriceCodec.read(v["custom_nutrients"] as? kotlinx.serialization.json.JsonObject) }

/**
 * Saves store and price on a food in Sparky, in its default serving's custom nutrients, keeping any other custom
 * nutrients it has. Only works for the user's own foods (Sparky refuses others with 403).
 */
suspend fun SparkyApiClient.saveFoodPrice(foodId: String, price: com.bytelog.app.data.repository.SparkyPrice, today: String): Result<Unit> = withContext(Dispatchers.IO) {
    val variant = defaultVariant(foodId).getOrElse { return@withContext Result.failure(it) }
    val variantId = (variant["id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        ?: return@withContext Result.failure(IOException("Sparky's serving has no id"))
    val custom = com.bytelog.app.data.repository.PriceCodec.write(variant["custom_nutrients"] as? kotlinx.serialization.json.JsonObject, price, today)
    try {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("food_id", kotlinx.serialization.json.JsonPrimitive(foodId))
            put("custom_nutrients", custom)
        }
        val request = Request.Builder().url("$baseUrl/api/foods/food-variants/$variantId").put(jsonBody(payload)).build()
        client.newCall(request).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(IOException("Couldn't save the price: ${errorText(response.code, body)}"))
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Result.failure(e)
    }
}
