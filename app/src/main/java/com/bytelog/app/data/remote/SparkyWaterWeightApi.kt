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

// Water and weight: intake, logs, containers and weigh-ins. Part of [SparkyApiClient].

/**
 * Fetch daily water intake totals for a date from Sparky Fitness Server.
 */
suspend fun SparkyApiClient.fetchWaterIntake(date: String): Result<Float> = withContext(Dispatchers.IO) {
    try {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
        val request = Request.Builder()
            .url("$baseUrl/api/v2/measurements/water-intake/$cleanDate")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val content = response.body?.string().orEmpty()
                val obj = json.parseToJsonElement(content).jsonObject
                val waterMl = obj["water_ml"]?.jsonPrimitive?.floatOrNull
                    ?: obj["manual_ml"]?.jsonPrimitive?.floatOrNull
                    ?: obj["ledger_ml"]?.jsonPrimitive?.floatOrNull ?: 0f
                Result.success(waterMl)
            } else if (response.code == 404) {
                Result.success(0f)
            } else {
                Result.failure(IOException("Fetch water intake failed: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Fetch itemized water intake log entries for a date.
 */
suspend fun SparkyApiClient.fetchWaterLog(date: String): Result<List<WaterLogDto>> = withContext(Dispatchers.IO) {
    try {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
        val request = Request.Builder()
            .url("$baseUrl/api/v2/measurements/water-intake/$cleanDate/log")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val content = response.body?.string().orEmpty()
                val array = json.parseToJsonElement(content).jsonArray
                val logs = array.mapNotNull { elem ->
                    try {
                        val o = elem.jsonObject
                        val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val waterMl = o["water_ml"]?.jsonPrimitive?.floatOrNull ?: 0f
                        val entryDate = o["entry_date"]?.jsonPrimitive?.content ?: cleanDate
                        val containerId = o["container_id"]?.jsonPrimitive?.intOrNull
                        val containerName = o["container_name"]?.jsonPrimitive?.content
                        val createdAt = o["created_at"]?.jsonPrimitive?.content
                            ?: o["logged_at"]?.jsonPrimitive?.content
                        WaterLogDto(
                            id = id,
                            waterMl = waterMl,
                            entryDate = entryDate,
                            containerId = containerId,
                            containerName = containerName,
                            createdAt = createdAt,
                            source = (o["source"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                            sourceId = (o["source_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
                Result.success(logs)
            } else if (response.code == 404) {
                Result.success(emptyList())
            } else {
                Result.failure(IOException("Fetch water log failed: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Upsert water intake (positive or negative drinks count, optional container ID).
 */
suspend fun SparkyApiClient.upsertWaterIntake(
    date: String,
    changeDrinks: Int,
    containerId: Int? = null
): Result<Float> = withContext(Dispatchers.IO) {
    try {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
        val payload = buildJsonObject {
            put("entry_date", cleanDate)
            put("change_drinks", changeDrinks)
            put("container_id", containerId)
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/v2/measurements/water-intake")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val content = response.body?.string().orEmpty()
                val waterMl = try {
                    val obj = json.parseToJsonElement(content).jsonObject
                    obj["water_ml"]?.jsonPrimitive?.floatOrNull
                        ?: obj["total_ml"]?.jsonPrimitive?.floatOrNull ?: 0f
                } catch (_: Exception) {
                    0f
                }
                Result.success(waterMl)
            } else {
                Result.failure(IOException("Upsert water failed: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Delete a specific water log entry by its UUID.
 */
suspend fun SparkyApiClient.deleteWaterLog(logId: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url("$baseUrl/api/v2/measurements/water-intake/log/$logId")
            .delete()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code == 404) {
                Result.success(Unit)
            } else {
                Result.failure(IOException("Delete water log failed: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Fetch user's configured water containers from Sparky Fitness.
 */
suspend fun SparkyApiClient.fetchWaterContainers(): Result<List<WaterContainer>> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url("$baseUrl/api/water-containers")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val content = response.body?.string().orEmpty()
                val array = json.parseToJsonElement(content).jsonArray
                val containers = array.mapNotNull { elem ->
                    try {
                        val o = elem.jsonObject
                        val id = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                        val name = o["name"]?.jsonPrimitive?.content ?: "Container $id"
                        val volume = o["volume"]?.jsonPrimitive?.floatOrNull ?: 250f
                        val unit = o["unit"]?.jsonPrimitive?.content ?: "ml"
                        val isPrimary = o["is_primary"]?.jsonPrimitive?.booleanOrNull ?: false
                        val servings = o["servings_per_container"]?.jsonPrimitive?.intOrNull ?: 1
                        WaterContainer(
                            id = id,
                            name = name,
                            volume = volume,
                            unit = unit,
                            isPrimary = isPrimary,
                            servingsPerContainer = servings
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
                Result.success(containers)
            } else {
                Result.failure(IOException("Fetch water containers failed: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Create a new water container on Sparky Fitness Server.
 */
suspend fun SparkyApiClient.createWaterContainer(
    name: String,
    volume: Float,
    unit: String = "ml"
): Result<WaterContainer> = withContext(Dispatchers.IO) {
    try {
        val payload = buildJsonObject {
            put("name", name)
            put("volume", volume)
            put("unit", unit)
            put("servings_per_container", 1)
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/water-containers")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val content = response.body?.string().orEmpty()
                val o = json.parseToJsonElement(content).jsonObject
                val id = o["id"]?.jsonPrimitive?.intOrNull ?: 0
                val container = WaterContainer(
                    id = id,
                    name = o["name"]?.jsonPrimitive?.content ?: name,
                    volume = o["volume"]?.jsonPrimitive?.floatOrNull ?: volume,
                    unit = o["unit"]?.jsonPrimitive?.content ?: unit,
                    isPrimary = o["is_primary"]?.jsonPrimitive?.booleanOrNull ?: false,
                    servingsPerContainer = 1
                )
                Result.success(container)
            } else {
                Result.failure(IOException("Create container failed: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

suspend fun SparkyApiClient.fetchWeightForDate(date: String): Result<Float?> = withContext(Dispatchers.IO) {
    try {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
        val request = Request.Builder()
            .url("$baseUrl/api/measurements/check-in/$cleanDate")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val content = response.body?.string().orEmpty()
                if (content.isBlank() || content == "{}") {
                    fetchLatestWeightOnOrBefore(cleanDate)
                } else {
                    val obj = json.parseToJsonElement(content).jsonObject
                    val weight = obj["weight"]?.jsonPrimitive?.floatOrNull
                    Result.success(weight)
                }
            } else if (response.code == 404) {
                fetchLatestWeightOnOrBefore(cleanDate)
            } else {
                Result.failure(IOException("Fetch weight failed: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

internal suspend fun SparkyApiClient.fetchLatestWeightOnOrBefore(date: String): Result<Float?> {
    try {
        val request = Request.Builder()
            .url("$baseUrl/api/measurements/check-in/latest-on-or-before-date?date=$date")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val content = response.body?.string().orEmpty()
                if (content.isBlank() || content == "{}") {
                    return Result.success(null)
                }
                val obj = json.parseToJsonElement(content).jsonObject
                val weight = obj["weight"]?.jsonPrimitive?.floatOrNull
                return Result.success(weight)
            } else {
                return Result.success(null)
            }
        }
    } catch (e: Exception) {
        return Result.failure(e)
    }
}

suspend fun SparkyApiClient.upsertWeight(date: String, weight: Float, bodyFatPercentage: Float? = null): Result<Boolean> = withContext(Dispatchers.IO) {
    try {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
        val payload = buildJsonObject {
            put("entry_date", cleanDate)
            put("weight", weight)
            bodyFatPercentage?.let { put("body_fat_percentage", it) }
        }.toString()
        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/measurements/check-in")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                val err = response.body?.string().orEmpty()
                Result.failure(IOException("Save weight failed: HTTP ${response.code} $err"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Fetch all check-in weights in range from Sparky Fitness Server (weights returned in kg).
 */
suspend fun SparkyApiClient.fetchWeightRange(startDate: String, endDate: String): Result<List<Pair<String, Float>>> = withContext(Dispatchers.IO) {
    try {
        val sDate = if (startDate.contains("T")) startDate.substringBefore("T") else startDate
        val eDate = if (endDate.contains("T")) endDate.substringBefore("T") else endDate
        val request = Request.Builder()
            .url("$baseUrl/api/measurements/check-in-measurements-range/$sDate/$eDate")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val content = response.body?.string().orEmpty()
                if (content.isBlank() || content == "[]") {
                    return@withContext Result.success(emptyList())
                }
                val array = json.parseToJsonElement(content).jsonArray
                val list = mutableListOf<Pair<String, Float>>()
                for (elem in array) {
                    val obj = elem.jsonObject
                    val d = obj["entry_date"]?.jsonPrimitive?.content ?: continue
                    val w = obj["weight"]?.jsonPrimitive?.floatOrNull ?: continue
                    if (w > 0f) {
                        list.add(Pair(d, w))
                    }
                }
                Result.success(list)
            } else {
                Result.failure(IOException("Fetch weight range failed: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
