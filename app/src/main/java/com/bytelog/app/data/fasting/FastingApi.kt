package com.bytelog.app.data.fasting

import com.bytelog.app.data.remote.SparkyApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant

/**
 * REST client for Sparky Fitness Server's `/api/fasting` endpoints.
 * Reuses [SparkyApiClient]'s configured OkHttpClient, auth headers, and base URL.
 */
class FastingApi(private val api: SparkyApiClient) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun baseUrl(): String = api.currentBaseUrl().trimEnd('/')
    private fun client() = api.httpClient()
    private fun json() = api.jsonCodec()

    suspend fun getCurrentFast(): Result<FastingLog?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/api/fasting/current")
                .get()
                .build()

            client().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty().trim()
                when {
                    response.code == 204 || response.code == 404 -> Result.success(null)
                    !response.isSuccessful -> Result.failure(IOException("Get current fast failed: HTTP ${response.code}"))
                    body.isBlank() || body == "null" || body == "{}" -> Result.success(null)
                    else -> {
                        val el = json().parseToJsonElement(body)
                        val obj = unwrapObject(el, "fast", "current", "data", "session")
                        Result.success(obj?.let(::parseFastingLog))
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun startFast(
        startTimeIso: String,
        targetEndTimeIso: String?,
        fastingType: String
    ): Result<FastingLog> = withContext(Dispatchers.IO) {
        try {
            val payload = buildJsonObject {
                put("start_time", JsonPrimitive(startTimeIso))
                targetEndTimeIso?.let { put("target_end_time", JsonPrimitive(it)) }
                put("fasting_type", JsonPrimitive(fastingType))
            }
            val request = Request.Builder()
                .url("${baseUrl()}/api/fasting/start")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use Result.failure(IOException("Start fast failed: HTTP ${response.code} ${extractError(body)}".trim()))
                }
                val el = json().parseToJsonElement(body)
                val obj = unwrapObject(el, "fast", "data", "session")
                val parsed = obj?.let(::parseFastingLog)
                    ?: return@use Result.failure(IOException("Invalid start fast response"))
                Result.success(parsed)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun endFast(
        id: String,
        endTimeIso: String,
        startTimeIso: String? = null,
        targetEndTimeIso: String? = null,
        fastingType: String? = null
    ): Result<FastingLog> = withContext(Dispatchers.IO) {
        try {
            val payload = buildJsonObject {
                put("id", JsonPrimitive(id))
                put("end_time", JsonPrimitive(endTimeIso))
                startTimeIso?.let { put("start_time", JsonPrimitive(it)) }
                targetEndTimeIso?.let { put("target_end_time", JsonPrimitive(it)) }
                fastingType?.let { put("fasting_type", JsonPrimitive(it)) }
            }
            val request = Request.Builder()
                .url("${baseUrl()}/api/fasting/end")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use Result.failure(IOException("End fast failed: HTTP ${response.code} ${extractError(body)}".trim()))
                }
                val el = json().parseToJsonElement(body)
                val obj = unwrapObject(el, "fast", "data", "session")
                val parsed = obj?.let(::parseFastingLog)
                    ?: return@use Result.failure(IOException("Invalid end fast response"))
                Result.success(parsed)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun updateFast(
        id: String,
        startTimeIso: String? = null,
        endTimeIso: String? = null,
        targetEndTimeIso: String? = null,
        fastingType: String? = null
    ): Result<FastingLog> = withContext(Dispatchers.IO) {
        try {
            val payload = buildJsonObject {
                startTimeIso?.let { put("start_time", JsonPrimitive(it)) }
                endTimeIso?.let { put("end_time", JsonPrimitive(it)) }
                targetEndTimeIso?.let { put("target_end_time", JsonPrimitive(it)) }
                fastingType?.let { put("fasting_type", JsonPrimitive(it)) }
            }
            val encodedId = URLEncoder.encode(id, "UTF-8")
            val request = Request.Builder()
                .url("${baseUrl()}/api/fasting/$encodedId")
                .put(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use Result.failure(IOException("Update fast failed: HTTP ${response.code} ${extractError(body)}".trim()))
                }
                val el = json().parseToJsonElement(body)
                val obj = unwrapObject(el, "fast", "data", "session")
                val parsed = obj?.let(::parseFastingLog)
                    ?: return@use Result.failure(IOException("Invalid update fast response"))
                Result.success(parsed)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun deleteFast(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(id, "UTF-8")
            val request = Request.Builder()
                .url("${baseUrl()}/api/fasting/$encodedId")
                .delete()
                .build()

            client().newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 404) {
                    Result.success(Unit)
                } else {
                    val body = response.body?.string().orEmpty()
                    Result.failure(IOException("Delete fast failed: HTTP ${response.code} ${extractError(body)}".trim()))
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun getHistory(limit: Int = 50): Result<List<FastingLog>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/api/fasting/history?limit=$limit")
                .get()
                .build()

            client().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty().trim()
                if (!response.isSuccessful) {
                    return@use Result.failure(IOException("Get fasting history failed: HTTP ${response.code}"))
                }
                if (body.isBlank() || body == "null") return@use Result.success(emptyList())
                val arr = unwrapArray(json().parseToJsonElement(body), "history", "logs", "fasts", "data")
                Result.success(arr.mapNotNull { (it as? JsonObject)?.let(::parseFastingLog) })
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun getHistoryRange(startDate: String, endDate: String): Result<List<FastingLog>> = withContext(Dispatchers.IO) {
        try {
            val startEnc = URLEncoder.encode(startDate.take(10), "UTF-8")
            val endEnc = URLEncoder.encode(endDate.take(10), "UTF-8")
            val request = Request.Builder()
                .url("${baseUrl()}/api/fasting/history/range/$startEnc/$endEnc")
                .get()
                .build()

            client().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty().trim()
                if (!response.isSuccessful) {
                    return@use Result.failure(IOException("Get fasting history range failed: HTTP ${response.code}"))
                }
                if (body.isBlank() || body == "null") return@use Result.success(emptyList())
                val arr = unwrapArray(json().parseToJsonElement(body), "history", "logs", "fasts", "data")
                Result.success(arr.mapNotNull { (it as? JsonObject)?.let(::parseFastingLog) })
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun getStats(): Result<FastingStats> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/api/fasting/stats")
                .get()
                .build()

            client().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty().trim()
                if (!response.isSuccessful) {
                    return@use Result.failure(IOException("Get fasting stats failed: HTTP ${response.code}"))
                }
                if (body.isBlank() || body == "null") return@use Result.success(FastingStats())
                val obj = unwrapObject(json().parseToJsonElement(body), "stats", "data")
                    ?: return@use Result.success(FastingStats())
                Result.success(parseFastingStats(obj))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun getPreferences(): Result<FastingPreferences> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/api/fasting/preferences")
                .get()
                .build()

            client().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty().trim()
                if (!response.isSuccessful) {
                    return@use Result.failure(IOException("Get fasting preferences failed: HTTP ${response.code}"))
                }
                if (body.isBlank() || body == "null") return@use Result.success(FastingPreferences())
                val obj = unwrapObject(json().parseToJsonElement(body), "preferences", "data")
                    ?: return@use Result.success(FastingPreferences())
                Result.success(parseFastingPreferences(obj))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun updatePreferences(prefs: FastingPreferences): Result<FastingPreferences> = withContext(Dispatchers.IO) {
        try {
            val payload = buildJsonObject {
                put("auto_calculate", JsonPrimitive(prefs.autoCalculate))
                put("default_protocol", JsonPrimitive(prefs.defaultProtocol))
                put("target_fasting_hours", JsonPrimitive(prefs.targetFastingHours))
                put("target_eating_hours", JsonPrimitive(prefs.targetEatingHours))
                put("calorie_threshold", JsonPrimitive(prefs.calorieThreshold))
                put("pre_end_alert_minutes", JsonPrimitive(prefs.preEndAlertMinutes))
                put("eating_window_alert", JsonPrimitive(prefs.eatingWindowAlert))
            }
            val request = Request.Builder()
                .url("${baseUrl()}/api/fasting/preferences")
                .put(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty().trim()
                if (!response.isSuccessful) {
                    return@use Result.failure(IOException("Update fasting preferences failed: HTTP ${response.code} ${extractError(body)}".trim()))
                }
                if (body.isBlank() || body == "null") return@use Result.success(prefs)
                val obj = unwrapObject(json().parseToJsonElement(body), "preferences", "data")
                Result.success(obj?.let(::parseFastingPreferences) ?: prefs)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    // ---------- JSON parsing helpers (tolerant of snake_case and camelCase) ----------

    private fun unwrapObject(el: JsonElement, vararg wrapperKeys: String): JsonObject? {
        if (el is JsonNull) return null
        val obj = el as? JsonObject ?: return null
        for (key in wrapperKeys) {
            val nested = obj[key]
            if (nested is JsonObject) return nested
            if (nested is JsonNull && obj.size == 1) return null
        }
        return obj
    }

    private fun unwrapArray(el: JsonElement, vararg wrapperKeys: String): JsonArray {
        if (el is JsonArray) return el
        if (el is JsonObject) {
            for (key in wrapperKeys) {
                val nested = el[key]
                if (nested is JsonArray) return nested
            }
        }
        return JsonArray(emptyList())
    }

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val p = this[k] as? JsonPrimitive ?: continue
            if (p is JsonNull) continue
            val value = p.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            if (value != null) return value
        }
        return null
    }

    private fun JsonObject.intVal(vararg keys: String): Int? {
        for (k in keys) {
            val p = this[k] as? JsonPrimitive ?: continue
            if (p is JsonNull) continue
            p.intOrNull?.let { return it }
            p.floatOrNull?.let { return it.toInt() }
        }
        return null
    }

    private fun JsonObject.floatVal(vararg keys: String): Float? {
        for (k in keys) {
            val p = this[k] as? JsonPrimitive ?: continue
            if (p is JsonNull) continue
            p.floatOrNull?.let { return it }
        }
        return null
    }

    private fun JsonObject.boolVal(vararg keys: String): Boolean? {
        for (k in keys) {
            val p = this[k] as? JsonPrimitive ?: continue
            if (p is JsonNull) continue
            p.booleanOrNull?.let { return it }
        }
        return null
    }

    private fun parseFastingLog(obj: JsonObject): FastingLog? {
        val startTime = obj.str("start_time", "startTime") ?: return null
        val id = obj.str("id") ?: "auto_${Instant.now().toEpochMilli()}"
        val endTime = obj.str("end_time", "endTime")
        val targetEndTime = obj.str("target_end_time", "targetEndTime")
        val durationMinutes = obj.intVal("duration_minutes", "durationMinutes")
        val fastingType = obj.str("fasting_type", "fastingType", "protocol") ?: "16-8"
        val status = obj.str("status") ?: if (endTime == null) "active" else "completed"
        val isAutoCalculated = obj.boolVal("is_auto_calculated", "isAutoCalculated") ?: false
        val isEatingWindow = obj.boolVal("is_eating_window", "isEatingWindow") ?: false
        val eatingWindowRemainingMinutes = obj.intVal("eating_window_remaining_minutes", "eatingWindowRemainingMinutes")

        return FastingLog(
            id = id,
            startTime = startTime,
            endTime = endTime,
            targetEndTime = targetEndTime,
            durationMinutes = durationMinutes,
            fastingType = fastingType,
            status = status,
            isAutoCalculated = isAutoCalculated,
            isEatingWindow = isEatingWindow,
            eatingWindowRemainingMinutes = eatingWindowRemainingMinutes
        )
    }

    private fun parseFastingStats(obj: JsonObject): FastingStats {
        return FastingStats(
            totalCompletedFasts = obj.intVal("total_completed_fasts", "totalCompletedFasts", "total_fasts", "totalFasts") ?: 0,
            totalMinutesFasted = obj.intVal("total_minutes_fasted", "totalMinutesFasted", "total_minutes", "totalMinutes") ?: 0,
            averageDurationMinutes = obj.intVal("average_duration_minutes", "averageDurationMinutes", "avg_duration_minutes", "avgDurationMinutes") ?: 0
        )
    }

    private fun parseFastingPreferences(obj: JsonObject): FastingPreferences {
        return FastingPreferences(
            autoCalculate = obj.boolVal("auto_calculate", "autoCalculate") ?: false,
            defaultProtocol = obj.str("default_protocol", "defaultProtocol") ?: "16-8",
            targetFastingHours = obj.floatVal("target_fasting_hours", "targetFastingHours") ?: 16f,
            targetEatingHours = obj.floatVal("target_eating_hours", "targetEatingHours") ?: 8f,
            calorieThreshold = obj.intVal("calorie_threshold", "calorieThreshold") ?: 10,
            preEndAlertMinutes = obj.intVal("pre_end_alert_minutes", "preEndAlertMinutes") ?: 30,
            eatingWindowAlert = obj.boolVal("eating_window_alert", "eatingWindowAlert") ?: false
        )
    }

    private fun extractError(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
            val obj = json().parseToJsonElement(body) as? JsonObject
            obj?.str("error", "message").orEmpty()
        }.getOrDefault("")
    }
}
