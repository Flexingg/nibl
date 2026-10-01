package com.bytelog.app.data.remote

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
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

data class ConnectionTestResult(
    val isSuccess: Boolean,
    val serverVersion: String? = null,
    val isAuthenticated: Boolean = false,
    val message: String
)

class SparkyApiClient(
    private var baseUrl: String = "http://10.0.2.2:3010",
    private var authToken: String? = null,
    private var apiKey: String? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()

            // Better Auth and Express CORS compatibility
            builder.header("Origin", baseUrl)
            builder.header("Accept", "application/json")
            builder.header("X-Meal-Model-Version", "2")

            // Session token authentication
            authToken?.let { token ->
                val cleanToken = if (token.startsWith("better-auth.session_token=")) {
                    token.substringAfter("better-auth.session_token=")
                } else token

                builder.header("Authorization", "Bearer $cleanToken")
                builder.header("Cookie", "better-auth.session_token=$cleanToken")
            }

            // API key authentication
            apiKey?.takeIf { it.isNotBlank() }?.let { key ->
                builder.header("X-API-Key", key.trim())
                if (authToken == null) {
                    builder.header("Authorization", "Bearer ${key.trim()}")
                }
            }

            chain.proceed(builder.build())
        }
        .build()

    fun updateConfig(url: String, token: String?, key: String?) {
        this.baseUrl = url.trim().trimEnd('/')
        this.authToken = token?.takeIf { it.isNotBlank() }
        this.apiKey = key?.takeIf { it.isNotBlank() }
    }

    /**
     * Diagnostic connection test that validates reachability and authentication.
     */
    suspend fun testConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        var detectedVersion: String? = null

        // Step 1: Check server reachability via version endpoints
        try {
            val versionReq = Request.Builder()
                .url("$cleanUrl/api/version/current")
                .get()
                .build()

            client.newCall(versionReq).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    try {
                        val parsed = json.parseToJsonElement(body).jsonObject
                        detectedVersion = parsed["version"]?.jsonPrimitive?.content
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            // Try fallback endpoint
            try {
                val fallbackReq = Request.Builder()
                    .url("$cleanUrl/api/version")
                    .get()
                    .build()
                client.newCall(fallbackReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val parsed = json.parseToJsonElement(body).jsonObject
                        detectedVersion = parsed["version"]?.jsonPrimitive?.content
                    }
                }
            } catch (e2: Exception) {
                return@withContext ConnectionTestResult(
                    isSuccess = false,
                    serverVersion = null,
                    isAuthenticated = false,
                    message = "Cannot reach server at $cleanUrl (${e2.message ?: e2.javaClass.simpleName}). Please check the IP, port, and network connection."
                )
            }
        }

        // Step 2: Validate Authentication via protected endpoint
        val hasCredentials = !authToken.isNullOrBlank() || !apiKey.isNullOrBlank()
        if (!hasCredentials) {
            return@withContext ConnectionTestResult(
                isSuccess = true,
                serverVersion = detectedVersion,
                isAuthenticated = false,
                message = "Server reached (v${detectedVersion ?: "unknown"}), but no API key or session token is set."
            )
        }

        try {
            val authCheckReq = Request.Builder()
                .url("$cleanUrl/api/meal-types")
                .get()
                .build()

            client.newCall(authCheckReq).execute().use { response ->
                when (response.code) {
                    200 -> {
                        ConnectionTestResult(
                            isSuccess = true,
                            serverVersion = detectedVersion,
                            isAuthenticated = true,
                            message = "Connected to Sparky Fitness v${detectedVersion ?: "1.x"}! Authentication verified."
                        )
                    }
                    401 -> {
                        ConnectionTestResult(
                            isSuccess = false,
                            serverVersion = detectedVersion,
                            isAuthenticated = false,
                            message = "Server reached, but authentication failed (HTTP 401). Please verify your API Key or sign in with your email & password."
                        )
                    }
                    403 -> {
                        ConnectionTestResult(
                            isSuccess = false,
                            serverVersion = detectedVersion,
                            isAuthenticated = false,
                            message = "Server reached, but access was forbidden (HTTP 403)."
                        )
                    }
                    else -> {
                        ConnectionTestResult(
                            isSuccess = false,
                            serverVersion = detectedVersion,
                            isAuthenticated = false,
                            message = "Server returned unexpected HTTP ${response.code}."
                        )
                    }
                }
            }
        } catch (e: Exception) {
            ConnectionTestResult(
                isSuccess = false,
                serverVersion = detectedVersion,
                isAuthenticated = false,
                message = "Auth check failed: ${e.message ?: "Connection error"}"
            )
        }
    }

    /**
     * Authenticate via Better Auth Email/Password.
     */
    suspend fun signInEmail(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = """{"email":"${email.trim()}","password":"$password"}"""
            val body = payload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/auth/sign-in/email")
                .header("Origin", baseUrl)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    var token: String? = null
                    try {
                        val parsed = json.parseToJsonElement(respBody).jsonObject
                        token = parsed["token"]?.jsonPrimitive?.content
                    } catch (_: Exception) {}

                    if (token.isNullOrBlank()) {
                        val cookieHeader = response.header("set-cookie").orEmpty()
                        val match = Regex("""better-auth\.session_token=([^;]+)""").find(cookieHeader)
                        token = match?.groupValues?.get(1)
                    }

                    val finalToken = token ?: "session_active"
                    authToken = finalToken
                    Result.success(finalToken)
                } else {
                    var errorMsg = "Sign-in failed (HTTP ${response.code})"
                    try {
                        val parsed = json.parseToJsonElement(respBody).jsonObject
                        val msg = parsed["message"]?.jsonPrimitive?.content ?: parsed["error"]?.jsonPrimitive?.content
                        if (!msg.isNullOrBlank()) errorMsg = msg
                    } catch (_: Exception) {}
                    Result.failure(IOException(errorMsg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch meal types from Sparky Fitness Server.
     */
    suspend fun fetchMealTypes(): Result<List<MealType>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/meal-types")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string().orEmpty()
                    val root = json.parseToJsonElement(content).jsonArray
                    val mealTypes = root.mapIndexed { index, el ->
                        val obj = el.jsonObject
                        MealType(
                            id = obj["id"]?.jsonPrimitive?.content ?: "meal_$index",
                            name = (obj["name"]?.jsonPrimitive?.content ?: "Meal $index").trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                            sortOrder = obj["sort_order"]?.jsonPrimitive?.intOrNull ?: index
                        )
                    }
                    Result.success(mealTypes)
                } else {
                    Result.failure(IOException("Failed to fetch meal types: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Search foods remotely from Sparky Fitness database.
     * Extracts nutrition from `default_variant` (or fallback to root).
     */
    suspend fun searchFoods(query: String): Result<List<FoodItem>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/foods/foods-paginated?searchTerm=$encodedQuery&currentPage=1&itemsPerPage=30")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string().orEmpty()
                    val root = json.parseToJsonElement(content)
                    val foodsArray = if (root is kotlinx.serialization.json.JsonObject) {
                        root["foods"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
                    } else if (root is kotlinx.serialization.json.JsonArray) {
                        root
                    } else {
                        kotlinx.serialization.json.JsonArray(emptyList())
                    }

                    val items = foodsArray.map { el ->
                        val obj = el.jsonObject
                        val variant = obj["default_variant"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonObject
                            ?: obj["variants"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonArray?.firstOrNull()?.jsonObject

                        val calories = variant?.get("calories")?.jsonPrimitive?.floatOrNull
                            ?: obj["calories"]?.jsonPrimitive?.floatOrNull ?: 0f
                        val protein = variant?.get("protein")?.jsonPrimitive?.floatOrNull
                            ?: obj["protein"]?.jsonPrimitive?.floatOrNull ?: 0f
                        val carbs = variant?.get("carbs")?.jsonPrimitive?.floatOrNull
                            ?: obj["carbs"]?.jsonPrimitive?.floatOrNull ?: 0f
                        val fat = variant?.get("fat")?.jsonPrimitive?.floatOrNull
                            ?: obj["fat"]?.jsonPrimitive?.floatOrNull ?: 0f
                        val fiber = variant?.get("dietary_fiber")?.jsonPrimitive?.floatOrNull
                            ?: obj["dietary_fiber"]?.jsonPrimitive?.floatOrNull ?: 0f
                        val servingSize = variant?.get("serving_size")?.jsonPrimitive?.floatOrNull
                            ?: obj["serving_size"]?.jsonPrimitive?.floatOrNull ?: 100f
                        val servingUnit = variant?.get("serving_unit")?.jsonPrimitive?.content
                            ?: obj["serving_unit"]?.jsonPrimitive?.content ?: "g"

                        FoodItem(
                            id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
                            name = obj["name"]?.jsonPrimitive?.content ?: "Unknown Food",
                            brand = obj["brand"]?.jsonPrimitive?.content,
                            barcode = obj["barcode"]?.jsonPrimitive?.content,
                            servingSize = servingSize,
                            servingUnit = servingUnit,
                            calories = calories,
                            protein = protein,
                            carbs = carbs,
                            fat = fat,
                            fiber = fiber,
                            isFavorite = obj["is_favorite"]?.jsonPrimitive?.booleanOrNull ?: false
                        )
                    }
                    Result.success(items)
                } else {
                    Result.failure(IOException("Remote search error: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Look up barcode via Sparky Fitness OpenFoodFacts / Provider proxy.
     */
    suspend fun getFoodByBarcode(barcode: String): Result<FoodItem?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/foods/barcode/$barcode")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string().orEmpty()
                    val obj = json.parseToJsonElement(content).jsonObject
                    val variant = obj["default_variant"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonObject
                        ?: obj["variants"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonArray?.firstOrNull()?.jsonObject

                    val calories = variant?.get("calories")?.jsonPrimitive?.floatOrNull
                        ?: obj["calories"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val protein = variant?.get("protein")?.jsonPrimitive?.floatOrNull
                        ?: obj["protein"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val carbs = variant?.get("carbs")?.jsonPrimitive?.floatOrNull
                        ?: obj["carbs"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val fat = variant?.get("fat")?.jsonPrimitive?.floatOrNull
                        ?: obj["fat"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val fiber = variant?.get("dietary_fiber")?.jsonPrimitive?.floatOrNull
                        ?: obj["dietary_fiber"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val servingSize = variant?.get("serving_size")?.jsonPrimitive?.floatOrNull
                        ?: obj["serving_size"]?.jsonPrimitive?.floatOrNull ?: 100f
                    val servingUnit = variant?.get("serving_unit")?.jsonPrimitive?.content
                        ?: obj["serving_unit"]?.jsonPrimitive?.content ?: "g"

                    val item = FoodItem(
                        id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
                        name = obj["name"]?.jsonPrimitive?.content ?: "Scanned Product",
                        brand = obj["brand"]?.jsonPrimitive?.content,
                        barcode = barcode,
                        servingSize = servingSize,
                        servingUnit = servingUnit,
                        calories = calories,
                        protein = protein,
                        carbs = carbs,
                        fat = fat,
                        fiber = fiber
                    )
                    Result.success(item)
                } else if (response.code == 404) {
                    Result.success(null)
                } else {
                    Result.failure(IOException("Barcode fetch error: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch diary entries for date (YYYY-MM-DD) from /api/food-entries/by-date/:date.
     */
    suspend fun fetchEntriesForDate(date: String): Result<List<FoodEntry>> = withContext(Dispatchers.IO) {
        try {
            val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
            val request = Request.Builder()
                .url("$baseUrl/api/food-entries/by-date/$cleanDate")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string().orEmpty()
                    val array = json.parseToJsonElement(content).jsonArray
                    val entries = array.map { el ->
                        val obj = el.jsonObject
                        val rawEntryDate = obj["entry_date"]?.jsonPrimitive?.content ?: cleanDate
                        val normalizedDate = if (rawEntryDate.contains("T")) rawEntryDate.substringBefore("T") else rawEntryDate
                        val rawMealName = obj["meal_type"]?.jsonPrimitive?.content ?: "Meal"
                        val cleanMealName = rawMealName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

                        FoodEntry(
                            id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
                            foodId = obj["food_id"]?.jsonPrimitive?.content,
                            foodName = obj["food_name"]?.jsonPrimitive?.content ?: "Food Item",
                            brandName = obj["brand_name"]?.jsonPrimitive?.content,
                            mealTypeId = obj["meal_type_id"]?.jsonPrimitive?.content ?: cleanMealName.lowercase(),
                            mealName = cleanMealName,
                            quantity = obj["quantity"]?.jsonPrimitive?.floatOrNull ?: 1f,
                            unit = obj["unit"]?.jsonPrimitive?.content ?: "g",
                            calories = obj["calories"]?.jsonPrimitive?.floatOrNull ?: 0f,
                            protein = obj["protein"]?.jsonPrimitive?.floatOrNull ?: 0f,
                            carbs = obj["carbs"]?.jsonPrimitive?.floatOrNull ?: 0f,
                            fat = obj["fat"]?.jsonPrimitive?.floatOrNull ?: 0f,
                            entryDate = normalizedDate,
                            entryTime = obj["entry_time"]?.jsonPrimitive?.content,
                            syncStatus = FoodEntry.SYNC_STATUS_SYNCED
                        )
                    }
                    Result.success(entries)
                } else {
                    Result.failure(IOException("Fetch entries error: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Post a newly logged food entry to Sparky Fitness Server.
     */
    suspend fun createFoodEntry(entry: FoodEntry): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val isUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            val cleanMealName = entry.mealName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val timeString = entry.entryTime?.take(5)
            val cleanDate = if (entry.entryDate.contains("T")) entry.entryDate.substringBefore("T") else entry.entryDate

            val payload = buildString {
                append("{")
                append("\"id\":\"${entry.id}\",")
                if (isUuid.matches(entry.mealTypeId)) {
                    append("\"meal_type_id\":\"${entry.mealTypeId}\",")
                }
                append("\"meal_type\":\"${cleanMealName.replace("\"", "\\\"")}\",")
                append("\"quantity\":${entry.quantity},")
                append("\"unit\":\"${entry.unit}\",")
                append("\"serving_size\":${entry.quantity},")
                append("\"serving_unit\":\"${entry.unit}\",")
                append("\"entry_date\":\"$cleanDate\",")
                if (!timeString.isNullOrBlank() && Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(timeString)) {
                    append("\"entry_time\":\"$timeString\",")
                }
                append("\"food_name\":\"${entry.foodName.replace("\"", "\\\"")}\",")
                entry.brandName?.let { append("\"brand_name\":\"${it.replace("\"", "\\\"")}\",") }
                append("\"calories\":${entry.calories},")
                append("\"protein\":${entry.protein},")
                append("\"carbs\":${entry.carbs},")
                append("\"fat\":${entry.fat}")
                append("}")
            }

            val body = payload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/food-entries/")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    val errBody = response.body?.string().orEmpty()
                    android.util.Log.e("ByteLog", "Failed to create food entry on Sparky: HTTP ${response.code} $errBody")
                    Result.failure(IOException("Create food entry failed: HTTP ${response.code} - $errBody"))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ByteLog", "Exception logging to Sparky: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Delete an entry from Sparky Fitness Server.
     */
    suspend fun deleteFoodEntry(entryId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/food-entries/$entryId")
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 404) {
                    Result.success(true)
                } else {
                    Result.failure(IOException("Delete food entry failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
