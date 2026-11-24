package com.bytelog.app.data.reports

import com.bytelog.app.data.remote.SparkyApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder

data class DailyNutritionTrend(
    val date: String,
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val fiber: Double = 0.0,
    val sodium: Double = 0.0,
    val sugars: Double = 0.0,
    val saturatedFat: Double = 0.0,
    val potassium: Double = 0.0,
    val cholesterol: Double = 0.0,
    val vitaminA: Double = 0.0,
    val vitaminC: Double = 0.0,
    val calcium: Double = 0.0,
    val iron: Double = 0.0,
    val caffeineMg: Double = 0.0,
    val waterMl: Double = 0.0,
    val alcoholG: Double = 0.0
)

data class HydrationNutritionDay(
    val date: String,
    val waterMl: Double = 0.0,
    val waterGoalMl: Double? = null,
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0
)

data class FastingSessionItem(
    val id: String,
    val date: String,
    val startTime: String?,
    val endTime: String?,
    val durationHours: Double,
    val completed: Boolean
)

data class ComparePoint(
    val date: String,
    val valueA: Double?,
    val valueB: Double?
)

data class CompareReportData(
    val metricA: String,
    val metricB: String,
    val points: List<ComparePoint>,
    val correlation: Double? = null
)

/**
 * SparkyFitness reporting endpoints for Nibl's Detailed Reports module.
 */
class ReportsApi(private val api: SparkyApiClient) {

    suspend fun getMiniNutritionTrends(
        startDate: String,
        endDate: String
    ): Result<List<DailyNutritionTrend>> = withContext(Dispatchers.IO) {
        val url = "${baseUrl()}/api/reports/mini-nutrition-trends?startDate=${enc(startDate)}&endDate=${enc(endDate)}"
        getJson(url).map { root ->
            val arr = extractArray(root, "data", "trends", "days", "items")
            arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val date = obj.str("date", "entry_date", "day")?.take(10) ?: return@mapNotNull null
                DailyNutritionTrend(
                    date = date,
                    calories = obj.dbl("calories", "total_calories", "kcal"),
                    protein = obj.dbl("protein", "total_protein"),
                    carbs = obj.dbl("carbs", "carbohydrates", "total_carbs"),
                    fat = obj.dbl("fat", "total_fat"),
                    fiber = obj.dbl("fiber", "dietary_fiber", "total_fiber"),
                    sodium = obj.dbl("sodium", "total_sodium"),
                    sugars = obj.dbl("sugars", "sugar", "total_sugars"),
                    saturatedFat = obj.dbl("saturated_fat", "saturatedFat"),
                    potassium = obj.dbl("potassium", "total_potassium"),
                    cholesterol = obj.dbl("cholesterol", "total_cholesterol"),
                    vitaminA = obj.dbl("vitamin_a", "vitaminA"),
                    vitaminC = obj.dbl("vitamin_c", "vitaminC"),
                    calcium = obj.dbl("calcium", "total_calcium"),
                    iron = obj.dbl("iron", "total_iron"),
                    caffeineMg = obj.dbl("caffeine_mg", "caffeine", "total_caffeine"),
                    waterMl = obj.dbl("water_ml", "water", "total_water_ml"),
                    alcoholG = obj.dbl("alcohol_g", "alcohol")
                )
            }.sortedBy { it.date }
        }
    }

    suspend fun getHydrationNutritionRange(
        startDate: String,
        endDate: String
    ): Result<List<HydrationNutritionDay>> = withContext(Dispatchers.IO) {
        val url = "${baseUrl()}/api/v2/reports/hydration-nutrition-range?start=${enc(startDate)}&end=${enc(endDate)}"
        getJson(url).map { root ->
            val arr = extractArray(root, "data", "days", "series", "items")
            arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val date = obj.str("date", "entry_date", "day")?.take(10) ?: return@mapNotNull null
                HydrationNutritionDay(
                    date = date,
                    waterMl = obj.dbl("water_ml", "waterMl", "water", "total_water_ml"),
                    waterGoalMl = obj.dblOrNull("water_goal_ml", "waterGoalMl", "goal_ml"),
                    calories = obj.dbl("calories", "total_calories"),
                    protein = obj.dbl("protein", "total_protein"),
                    carbs = obj.dbl("carbs", "total_carbs"),
                    fat = obj.dbl("fat", "total_fat")
                )
            }.sortedBy { it.date }
        }
    }

    suspend fun getFastingRange(
        startDate: String,
        endDate: String
    ): Result<List<FastingSessionItem>> = withContext(Dispatchers.IO) {
        val url = "${baseUrl()}/api/fasting/history/range/${enc(startDate)}/${enc(endDate)}"
        getJson(url).map { root ->
            val arr = extractArray(root, "data", "sessions", "history", "fasts")
            arr.mapIndexedNotNull { idx, el ->
                val obj = el as? JsonObject ?: return@mapIndexedNotNull null
                val start = obj.str("start_time", "startTime", "started_at")
                val end = obj.str("end_time", "endTime", "ended_at")
                val date = (obj.str("date", "entry_date") ?: end ?: start)?.take(10)
                    ?: return@mapIndexedNotNull null
                val durationHours = obj.dblOrNull("duration_hours", "durationHours")
                    ?: obj.dblOrNull("duration_minutes", "durationMinutes")?.div(60.0)
                    ?: obj.dblOrNull("duration_seconds", "durationSeconds")?.div(3600.0)
                    ?: 0.0
                val completed = obj["completed"]?.jsonPrimitive?.booleanOrNull
                    ?: (obj.str("status")?.equals("completed", ignoreCase = true) == true)
                    || (end != null && durationHours > 0.0)
                FastingSessionItem(
                    id = obj.str("id") ?: "fast-$idx-$date",
                    date = date,
                    startTime = start,
                    endTime = end,
                    durationHours = durationHours,
                    completed = completed
                )
            }.sortedBy { it.date }
        }
    }

    suspend fun getCompareReport(
        metricA: String,
        metricB: String,
        startDate: String,
        endDate: String
    ): Result<CompareReportData> = withContext(Dispatchers.IO) {
        val url = "${baseUrl()}/api/v2/reports/compare" +
            "?a=${enc(metricA)}&b=${enc(metricB)}&mode=day&start=${enc(startDate)}&end=${enc(endDate)}"
        getJson(url).map { root ->
            val rootObj = root as? JsonObject
            val arr = extractArray(root, "points", "data", "series", "days")
            val points = arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val date = obj.str("date", "day", "x")?.take(10) ?: return@mapNotNull null
                val a = obj.dblOrNull("a", "valueA", "value_a", metricA)
                val b = obj.dblOrNull("b", "valueB", "value_b", metricB)
                ComparePoint(date = date, valueA = a, valueB = b)
            }.sortedBy { it.date }
            val corr = rootObj?.dblOrNull("correlation", "r", "pearson")
            CompareReportData(
                metricA = metricA,
                metricB = metricB,
                points = points,
                correlation = corr
            )
        }
    }

    private fun baseUrl(): String = api.currentBaseUrl().trimEnd('/')

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun getJson(url: String): Result<JsonElement> {
        if (baseUrl().isBlank()) return Result.failure(IllegalStateException("Sparky server URL is not configured"))
        return try {
            val req = Request.Builder().url(url).get().build()
            api.httpClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(IllegalStateException("HTTP ${resp.code} for $url"))
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    Result.success(JsonArray(emptyList()))
                } else {
                    Result.success(api.jsonCodec().parseToJsonElement(body))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractArray(root: JsonElement, vararg keys: String): JsonArray {
        if (root is JsonArray) return root
        val obj = root as? JsonObject ?: return JsonArray(emptyList())
        for (k in keys) {
            val child = obj[k]
            if (child is JsonArray) return child
        }
        return JsonArray(emptyList())
    }

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.contentOrNull
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun JsonObject.dblOrNull(vararg keys: String): Double? {
        for (k in keys) {
            val prim = this[k]?.jsonPrimitive ?: continue
            prim.doubleOrNull?.let { return it }
            prim.contentOrNull?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.dbl(vararg keys: String): Double = dblOrNull(*keys) ?: 0.0
}
