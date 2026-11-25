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

// Meals: copying meals between days, meal templates, and family members. Part of [SparkyApiClient].

suspend fun SparkyApiClient.copyMealFromYesterday(mealType: String, targetDate: String): Result<Boolean> = withContext(Dispatchers.IO) {
    try {
        val cleanDate = if (targetDate.contains("T")) targetDate.substringBefore("T") else targetDate
        val cleanMeal = mealType.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val payload = """{"mealType":"$cleanMeal","targetDate":"$cleanDate"}"""
        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/food-entries/copy-yesterday")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(IOException("Copy yesterday failed: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

suspend fun SparkyApiClient.copyMeal(
    sourceDate: String,
    sourceMealType: String,
    targetDate: String,
    targetMealType: String
): Result<Boolean> = withContext(Dispatchers.IO) {
    try {
        val sDate = if (sourceDate.contains("T")) sourceDate.substringBefore("T") else sourceDate
        val tDate = if (targetDate.contains("T")) targetDate.substringBefore("T") else targetDate
        val sMeal = sourceMealType.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val tMeal = targetMealType.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val payload = """{"sourceDate":"$sDate","sourceMealType":"$sMeal","targetDate":"$tDate","targetMealType":"$tMeal"}"""
        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/food-entries/copy")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(IOException("Copy meal failed: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

suspend fun SparkyApiClient.copyAllFromYesterday(targetDate: String): Result<Boolean> = withContext(Dispatchers.IO) {
    try {
        val cleanDate = if (targetDate.contains("T")) targetDate.substringBefore("T") else targetDate
        val payload = """{"targetDate":"$cleanDate"}"""
        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/food-entries/copy-all-yesterday")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(IOException("Copy all yesterday failed: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

suspend fun SparkyApiClient.createMealFromDiary(
    date: String,
    mealType: String,
    mealName: String,
    description: String? = null,
    isPublic: Boolean = false
): Result<String> = withContext(Dispatchers.IO) {
    try {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
        val cleanMeal = mealType.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val payload = buildJsonObject {
            put("date", cleanDate)
            put("mealType", cleanMeal)
            put("mealName", mealName)
            if (!description.isNullOrBlank()) put("description", description)
            put("isPublic", isPublic)
        }.toString()
        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/meals/create-meal-from-diary")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val content = response.body?.string().orEmpty()
                val id = try {
                    json.parseToJsonElement(content).jsonObject["id"]?.jsonPrimitive?.content
                } catch (_: Exception) { null }
                Result.success(id ?: UUID.randomUUID().toString())
            } else {
                val err = response.body?.string().orEmpty()
                Result.failure(IOException("Create meal from diary failed: HTTP ${response.code} $err"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

suspend fun SparkyApiClient.fetchMealTemplates(): Result<List<MealTemplate>> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url("$baseUrl/api/meals?filter=all")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val content = response.body?.string().orEmpty()
                val array = json.parseToJsonElement(content).jsonArray
                val templates = array.mapNotNull { elem ->
                    try {
                        val o = elem.jsonObject
                        val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val name = o["name"]?.jsonPrimitive?.content ?: "Meal Template"
                        val desc = o["description"]?.jsonPrimitive?.content
                        val servingSize = o["serving_size"]?.jsonPrimitive?.floatOrNull ?: 1f
                        val servingUnit = o["serving_unit"]?.jsonPrimitive?.content ?: "serving"
                        val totalServings = o["total_servings"]?.jsonPrimitive?.floatOrNull ?: 1f
                        val calories = o["calories"]?.jsonPrimitive?.floatOrNull ?: 0f
                        val protein = o["protein"]?.jsonPrimitive?.floatOrNull ?: 0f
                        val carbs = o["carbs"]?.jsonPrimitive?.floatOrNull ?: 0f
                        val fat = o["fat"]?.jsonPrimitive?.floatOrNull ?: 0f
                        val isPub = o["is_public"]?.jsonPrimitive?.booleanOrNull ?: false
                        val createdAt = o["created_at"]?.jsonPrimitive?.content
                        val foods = o["foods"]?.toString() ?: "[]"

                        MealTemplate(
                            id = id,
                            name = name,
                            description = desc,
                            servingSize = servingSize,
                            servingUnit = servingUnit,
                            totalServings = totalServings,
                            calories = calories,
                            protein = protein,
                            carbs = carbs,
                            fat = fat,
                            foodsJson = foods,
                            isPublic = isPub,
                            createdAt = createdAt,
                            syncStatus = MealTemplate.SYNC_STATUS_SYNCED
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
                Result.success(templates)
            } else {
                Result.failure(IOException("Fetch meal templates failed: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

suspend fun SparkyApiClient.createMealTemplate(
    name: String,
    description: String? = null,
    servingSize: Float = 1f,
    servingUnit: String = "serving",
    totalServings: Float = 1f,
    foods: List<TemplateIngredient>,
    notes: String? = null
): Result<String> = withContext(Dispatchers.IO) {
    try {
        // Each ingredient carries a nutrition snapshot for its own quantity. Sparky scales
        // row nutrients by quantity / serving_size, so serving_size = quantity makes the
        // stored calories the row total, independent of the food's variants.
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("mealData", kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(name))
                if (!description.isNullOrBlank()) put("description", kotlinx.serialization.json.JsonPrimitive(description))
                if (!notes.isNullOrBlank()) put("notes", kotlinx.serialization.json.JsonPrimitive(notes))
                put("serving_size", kotlinx.serialization.json.JsonPrimitive(servingSize))
                put("serving_unit", kotlinx.serialization.json.JsonPrimitive(servingUnit))
                put("total_servings", kotlinx.serialization.json.JsonPrimitive(totalServings))
                put("foods", kotlinx.serialization.json.buildJsonArray {
                    foods.forEach { ing ->
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("item_type", kotlinx.serialization.json.JsonPrimitive("food"))
                            put("food_id", kotlinx.serialization.json.JsonPrimitive(ing.foodId))
                            put("quantity", kotlinx.serialization.json.JsonPrimitive(ing.quantity))
                            put("unit", kotlinx.serialization.json.JsonPrimitive(ing.unit))
                            if (ing.calories > 0f || ing.protein > 0f || ing.carbs > 0f || ing.fat > 0f) {
                                put("serving_size", kotlinx.serialization.json.JsonPrimitive(ing.quantity))
                                put("serving_unit", kotlinx.serialization.json.JsonPrimitive(ing.unit))
                                put("calories", kotlinx.serialization.json.JsonPrimitive(ing.calories))
                                put("protein", kotlinx.serialization.json.JsonPrimitive(ing.protein))
                                put("carbs", kotlinx.serialization.json.JsonPrimitive(ing.carbs))
                                put("fat", kotlinx.serialization.json.JsonPrimitive(ing.fat))
                            }
                        })
                    }
                })
            })
        }.toString()

        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/meals")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val content = response.body?.string().orEmpty()
                val id = try {
                    json.parseToJsonElement(content).jsonObject["id"]?.jsonPrimitive?.content
                } catch (_: Exception) { null }
                Result.success(id ?: UUID.randomUUID().toString())
            } else {
                val err = response.body?.string().orEmpty()
                Result.failure(IOException("Create recipe template failed: HTTP ${response.code} $err"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

suspend fun SparkyApiClient.logMealTemplate(
    templateId: String,
    mealType: String,
    mealTypeId: String?,
    date: String,
    quantity: Float = 1f,
    unit: String = "serving"
): Result<Boolean> = withContext(Dispatchers.IO) {
    try {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
        val cleanMeal = mealType.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val isUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val payload = buildJsonObject {
            put("meal_template_id", templateId)
            put("meal_type", cleanMeal)
            if (!mealTypeId.isNullOrBlank() && isUuid.matches(mealTypeId)) put("meal_type_id", mealTypeId)
            put("entry_date", cleanDate)
            put("quantity", quantity)
            put("unit", unit)
        }.toString()
        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/food-entry-meals")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                val err = response.body?.string().orEmpty()
                Result.failure(IOException("Log meal template failed: HTTP ${response.code} $err"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

suspend fun SparkyApiClient.deleteMealTemplate(templateId: String): Result<Boolean> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url("$baseUrl/api/meals/$templateId?force=true")
            .delete()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code == 404) {
                Result.success(true)
            } else {
                Result.failure(IOException("Delete template failed: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/** GET /api/identity/users/accessible-users — everyone who has shared their data with the signed-in user. */
suspend fun SparkyApiClient.fetchFamilyMembers(): Result<List<FamilyMember>> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder().url("$baseUrl/api/identity/users/accessible-users").get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@use Result.failure(IOException("Couldn't load family: ${errorText(response.code, body)}"))
            val root = json.parseToJsonElement(body)
            val arr = (root as? kotlinx.serialization.json.JsonArray)
                ?: (root as? kotlinx.serialization.json.JsonObject)?.get("users")?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
            Result.success(arr.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.let(FamilyMember::parse) })
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
