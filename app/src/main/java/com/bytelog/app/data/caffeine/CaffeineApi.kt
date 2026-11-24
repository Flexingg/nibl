package com.bytelog.app.data.caffeine

import com.bytelog.app.data.remote.SparkyApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Sparky Fitness Server API client for caffeine tracking & user caffeine preferences.
 */
class CaffeineApi(private val api: SparkyApiClient) {

    private val jsonMediaType = "application/json".toMediaType()
    private val timePattern = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    /**
     * `GET /api/v2/nutrition/caffeine/active?date=$date&dose_mg=$doseMg`
     */
    suspend fun getActiveCaffeine(
        date: String,
        doseMg: Float = 200f
    ): Result<CaffeineStatus> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = api.currentBaseUrl().trimEnd('/')
            val cleanDate = date.substringBefore('T').trim()
            val doseParam = if (doseMg % 1f == 0f) doseMg.toInt().toString() else doseMg.toString()
            val request = Request.Builder()
                .url("$baseUrl/api/v2/nutrition/caffeine/active?date=$cleanDate&dose_mg=$doseParam")
                .get()
                .build()

            api.httpClient().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("GET active caffeine failed (HTTP ${response.code}): ${body.take(200)}")
                    )
                }
                val root = api.jsonCodec().parseToJsonElement(body).jsonObject
                val payload = (root["data"] as? JsonObject) ?: root
                Result.success(parseCaffeineStatus(payload))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * `GET /api/user-preferences` -> `(caffeine_half_life_hours, target_bedtime)`
     */
    suspend fun getCaffeineSettings(): Result<Pair<Float, String>> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = api.currentBaseUrl().trimEnd('/')
            val request = Request.Builder()
                .url("$baseUrl/api/user-preferences")
                .get()
                .build()

            api.httpClient().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("GET user-preferences failed (HTTP ${response.code}): ${body.take(200)}")
                    )
                }
                val root = api.jsonCodec().parseToJsonElement(body).jsonObject
                val prefs = (root["preferences"] as? JsonObject)
                    ?: (root["data"] as? JsonObject)
                    ?: root
                val halfLife = prefs.floatOf("caffeine_half_life_hours", "caffeineHalfLifeHours") ?: 5f
                val bedtime = prefs.stringOf("target_bedtime", "targetBedtime") ?: "22:30"
                Result.success(halfLife.coerceIn(2f, 8f) to bedtime.take(5))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * `PUT /api/user-preferences` updating `caffeine_half_life_hours` and `target_bedtime`.
     */
    suspend fun updateCaffeineSettings(
        halfLifeHours: Float,
        targetBedtime: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = api.currentBaseUrl().trimEnd('/')
            val clampedHalfLife = halfLifeHours.coerceIn(2f, 8f)
            val cleanBedtime = targetBedtime.trim().take(5).ifBlank { "22:30" }
            val payload = buildJsonObject {
                put("caffeine_half_life_hours", clampedHalfLife)
                put("target_bedtime", cleanBedtime)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/user-preferences")
                .put(payload.toRequestBody(jsonMediaType))
                .build()

            api.httpClient().newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val body = response.body?.string().orEmpty()
                    Result.failure(IOException("PUT user-preferences failed (HTTP ${response.code}): ${body.take(200)}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Logs a 0-kcal caffeine quick entry via `POST /api/food-entries`.
     */
    suspend fun logCaffeineQuickEntry(
        date: String,
        timeHHmm: String,
        name: String,
        mg: Float,
        mealTypeId: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = api.currentBaseUrl().trimEnd('/')
            val cleanDate = date.substringBefore('T').trim()
            val cleanTime = timeHHmm.trim().take(5)
            val payload = buildJsonObject {
                if (mealTypeId.isNotBlank()) {
                    put("meal_type_id", mealTypeId)
                }
                put("meal_type", "Snacks")
                put("food_name", name.ifBlank { "Caffeine" })
                put("entry_date", cleanDate)
                if (timePattern.matches(cleanTime)) {
                    put("entry_time", cleanTime)
                }
                put("quantity", 1)
                put("unit", "serving")
                put("serving_size", 1)
                put("serving_unit", "serving")
                put("calories", 0)
                put("protein", 0)
                put("carbs", 0)
                put("fat", 0)
                put("caffeine_mg", mg)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/food-entries")
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            api.httpClient().newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val body = response.body?.string().orEmpty()
                    Result.failure(IOException("POST caffeine entry failed (HTTP ${response.code}): ${body.take(200)}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseCaffeineStatus(obj: JsonObject): CaffeineStatus {
        val dosesArr = (obj["doses"] as? JsonArray) ?: JsonArray(emptyList())
        val doses = dosesArr.mapNotNull { el ->
            val d = el as? JsonObject ?: return@mapNotNull null
            val at = d.stringOf("at", "logged_at", "entry_time") ?: return@mapNotNull null
            val mg = d.floatOf("mg", "caffeine_mg") ?: 0f
            val name = d.stringOf("name", "food_name") ?: "Caffeine"
            val isEst = d.boolOf("is_estimated", "isEstimated") ?: false
            CaffeineDose(at = at, mg = mg, name = name, isEstimated = isEst)
        }

        val halfLife = obj.floatOf("half_life_hours", "halfLifeHours") ?: 5f
        val targetBedtime = obj.stringOf("target_bedtime", "targetBedtime") ?: "22:30"
        val bedtimeAt = obj.stringOf("bedtime_at", "bedtimeAt")
        val activeNow = obj.floatOf("active_mg_now", "activeMgNow") ?: 0f
        val atBedtime = obj.floatOf("at_bedtime_mg", "atBedtimeMg") ?: 0f
        val latestSafe = obj.stringOf("latest_safe_dose_time", "latestSafeDoseTime")
        val cutoffState = obj.stringOf("cutoff_state", "cutoffState") ?: "safe"
        val threshold = obj.floatOf("threshold_mg", "thresholdMg") ?: 100f
        val headroom = obj.floatOf("bedtime_headroom_mg", "bedtimeHeadroomMg")
            ?: (threshold - atBedtime).coerceAtLeast(0f)
        val totalToday = obj.floatOf("total_today_mg", "totalTodayMg")
            ?: doses.sumOf { it.mg.toDouble() }.toFloat()

        return CaffeineStatus(
            halfLifeHours = halfLife,
            targetBedtime = targetBedtime.take(5),
            bedtimeAt = bedtimeAt,
            doses = doses,
            activeMgNow = activeNow,
            atBedtimeMg = atBedtime,
            latestSafeDoseTime = latestSafe,
            cutoffState = cutoffState,
            bedtimeHeadroomMg = headroom,
            thresholdMg = threshold,
            totalTodayMg = totalToday
        )
    }

    private fun JsonObject.stringOf(vararg keys: String): String? {
        for (k in keys) {
            val p = this[k] as? JsonPrimitive ?: continue
            if (p is JsonNull) continue
            val s = p.contentOrNull?.trim()
            if (!s.isNullOrEmpty()) return s
        }
        return null
    }

    private fun JsonObject.floatOf(vararg keys: String): Float? {
        for (k in keys) {
            val p = this[k] as? JsonPrimitive ?: continue
            if (p is JsonNull) continue
            p.floatOrNull?.let { return it }
            p.contentOrNull?.toFloatOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.boolOf(vararg keys: String): Boolean? {
        for (k in keys) {
            val p = this[k] as? JsonPrimitive ?: continue
            if (p is JsonNull) continue
            p.booleanOrNull?.let { return it }
        }
        return null
    }
}
