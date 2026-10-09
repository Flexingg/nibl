package com.bytelog.app.data.remote

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealTemplate
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.TemplateIngredient
import com.bytelog.app.data.local.model.WeightEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()

            // Better Auth and Express CORS compatibility
            builder.header("Origin", baseUrl)
            // Callers that need another Accept (the MCP endpoint wants "application/json, text/event-stream") set their own.
            if (original.header("Accept") == null) builder.header("Accept", "application/json")
            builder.header("X-Meal-Model-Version", "2")

            // Session token authentication
            authToken?.let { token ->
                val cleanToken = when {
                    token.startsWith("better-auth.session_token=") -> token.substringAfter("better-auth.session_token=")
                    token.startsWith("sparky.session_token=") -> token.substringAfter("sparky.session_token=")
                    token.startsWith("__Secure-sparky.session_token=") -> token.substringAfter("__Secure-sparky.session_token=")
                    token.startsWith("__Secure-better-auth.session_token=") -> token.substringAfter("__Secure-better-auth.session_token=")
                    else -> token
                }.trim()

                if (cleanToken.isNotBlank()) {
                    builder.header("Authorization", "Bearer $cleanToken")
                }
            }

            // API key authentication (only when session token is not present)
            if (authToken.isNullOrBlank()) {
                apiKey?.takeIf { it.isNotBlank() }?.let { key ->
                    val cleanKey = key.trim()
                    builder.header("Authorization", "Bearer $cleanKey")
                    // Better Auth's API key plugin only accepts actual 64+ char API keys without dots.
                    // Do not send shorter tokens as X-API-Key to avoid 401 failures.
                    if (cleanKey.length >= 64 && !cleanKey.contains('.')) {
                        builder.header("X-API-Key", cleanKey)
                    }
                }
            }

            chain.proceed(builder.build())
        }
        .build()
 
    /**
     * Dedicated OkHttpClient for AI interactions (LLM multi-step tool calls, multimodal vision),
     * matching Sparky Fitness Server's 5-minute timeout window to prevent premature socket timeouts.
     */
    private val aiClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    private var cachedServiceConfigId: String? = null

    fun updateConfig(url: String, token: String?, key: String?) {
        this.baseUrl = url.trim().trimEnd('/')
        this.authToken = token?.takeIf { it.isNotBlank() }
        this.apiKey = key?.takeIf { it.isNotBlank() }
        this.cachedServiceConfigId = null
    }

    /**
     * Resolve active AI service configuration ID from Sparky Fitness Server.
     * Queries /api/chat/ai-service-settings/active first, and falls back to
     * the first configured provider in /api/chat/ai-service-settings.
     */
    suspend fun fetchActiveServiceConfigId(): String? = withContext(Dispatchers.IO) {
        cachedServiceConfigId?.let { return@withContext it }

        // 1. Try GET /api/chat/ai-service-settings/active
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/chat/ai-service-settings/active")
                .get()
                .build()

            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    try {
                        val parsed = json.parseToJsonElement(body).jsonObject
                        val id = parsed["id"]?.jsonPrimitive?.content
                        if (!id.isNullOrBlank()) {
                            cachedServiceConfigId = id
                            return@withContext id
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback: Try GET /api/chat/ai-service-settings
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/chat/ai-service-settings")
                .get()
                .build()

            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    try {
                        val arr = json.parseToJsonElement(body).jsonArray
                        for (element in arr) {
                            val id = element.jsonObject["id"]?.jsonPrimitive?.content
                            if (!id.isNullOrBlank()) {
                                cachedServiceConfigId = id
                                return@withContext id
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        null
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
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    var token: String? = null
                    try {
                        val parsed = json.parseToJsonElement(respBody).jsonObject
                        token = parsed["token"]?.jsonPrimitive?.content
                            ?: parsed["session"]?.jsonObject?.get("token")?.jsonPrimitive?.content
                    } catch (_: Exception) {}

                    if (token.isNullOrBlank()) {
                        val cookieHeaders = response.headers("Set-Cookie")
                        for (cookie in cookieHeaders) {
                            val match = Regex("""(?:__Secure-)?(?:sparky|better-auth)\.session_token=([^;]+)""").find(cookie)
                            if (match != null) {
                                val fullVal = match.groupValues[1]
                                token = if (fullVal.contains(".")) fullVal.substringBefore(".") else fullVal
                                break
                            }
                        }
                    }

                    if (token.isNullOrBlank()) {
                        return@use Result.failure(IOException("Sign-in succeeded, but no session token could be found in the server response."))
                    }

                    authToken = token
                    Result.success(token)
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
    /**
     * Search foods remotely from Sparky Fitness database.
     * Queries both user's custom foods and Sparky's OpenFoodFacts proxy to ensure comprehensive search results.
     */
    suspend fun searchFoods(query: String): Result<List<FoodItem>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext Result.success(emptyList())

        val combinedResults = mutableListOf<FoodItem>()
        val seenKeys = mutableSetOf<String>()

        // 1. Query Sparky Custom / Saved Foods
        try {
            val encodedQuery = java.net.URLEncoder.encode(trimmed, "UTF-8")
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

                    for (el in foodsArray) {
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

                        val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                        val name = obj["name"]?.jsonPrimitive?.content ?: "Unknown Food"
                        val brand = obj["brand"]?.jsonPrimitive?.content
                        val barcode = obj["barcode"]?.jsonPrimitive?.content

                        val item = FoodItem(
                            id = id,
                            name = name,
                            brand = brand,
                            barcode = barcode,
                            servingSize = servingSize,
                            servingUnit = servingUnit,
                            calories = calories,
                            protein = protein,
                            carbs = carbs,
                            fat = fat,
                            fiber = fiber,
                            isFavorite = obj["is_favorite"]?.jsonPrimitive?.booleanOrNull ?: false
                        )
                        val key = (barcode ?: "${name.lowercase()}_${brand?.lowercase()}").trim()
                        if (seenKeys.add(key)) {
                            combinedResults.add(item)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Non-fatal, continue to OpenFoodFacts search
        }

        // 2. Query OpenFoodFacts database proxy on Sparky server
        try {
            val offResult = searchOpenFoodFacts(trimmed, page = 1)
            if (offResult.isSuccess) {
                val offFoods = offResult.getOrNull().orEmpty()
                for (food in offFoods) {
                    val key = (food.barcode ?: "${food.name.lowercase()}_${food.brand?.lowercase()}").trim()
                    if (seenKeys.add(key)) {
                        combinedResults.add(food)
                    }
                }
            }
        } catch (_: Exception) {
            // Non-fatal
        }

        Result.success(combinedResults)
    }

    /**
     * Search global OpenFoodFacts database proxy via Sparky Fitness backend.
     */
    suspend fun searchOpenFoodFacts(query: String, page: Int = 1): Result<List<FoodItem>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/foods/openfoodfacts/search?query=$encodedQuery&page=$page")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string().orEmpty()
                    val root = json.parseToJsonElement(content).jsonObject
                    val productsArray = root["products"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())

                    val items = productsArray.mapNotNull { el ->
                        val obj = el.jsonObject
                        val name = obj["product_name"]?.jsonPrimitive?.content
                            ?: obj["product_name_en"]?.jsonPrimitive?.content
                        if (name.isNullOrBlank()) return@mapNotNull null

                        val brand = obj["brands"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        val code = obj["code"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        val servingQty = obj["serving_quantity"]?.jsonPrimitive?.floatOrNull ?: 100f
                        val servingUnit = obj["serving_quantity_unit"]?.jsonPrimitive?.content
                            ?: obj["product_quantity_unit"]?.jsonPrimitive?.content ?: "g"

                        val nutriments = obj["nutriments"]?.jsonObject
                        val calories = nutriments?.get("energy-kcal_100g")?.jsonPrimitive?.floatOrNull
                            ?: nutriments?.get("energy-kcal")?.jsonPrimitive?.floatOrNull
                            ?: nutriments?.get("energy-kcal_serving")?.jsonPrimitive?.floatOrNull ?: 0f
                        val protein = nutriments?.get("proteins_100g")?.jsonPrimitive?.floatOrNull
                            ?: nutriments?.get("proteins")?.jsonPrimitive?.floatOrNull ?: 0f
                        val carbs = nutriments?.get("carbohydrates_100g")?.jsonPrimitive?.floatOrNull
                            ?: nutriments?.get("carbohydrates")?.jsonPrimitive?.floatOrNull ?: 0f
                        val fat = nutriments?.get("fat_100g")?.jsonPrimitive?.floatOrNull
                            ?: nutriments?.get("fat")?.jsonPrimitive?.floatOrNull ?: 0f
                        val fiber = nutriments?.get("fiber_100g")?.jsonPrimitive?.floatOrNull
                            ?: nutriments?.get("fiber")?.jsonPrimitive?.floatOrNull ?: 0f

                        FoodItem(
                            id = code?.let { "off_$it" } ?: UUID.randomUUID().toString(),
                            name = name,
                            brand = brand,
                            barcode = code,
                            servingSize = servingQty,
                            servingUnit = servingUnit,
                            calories = calories,
                            protein = protein,
                            carbs = carbs,
                            fat = fat,
                            fiber = fiber
                        )
                    }
                    Result.success(items)
                } else {
                    Result.failure(IOException("OpenFoodFacts search error: HTTP ${response.code}"))
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
     * Returns the server-assigned UUID on success.
     */
    /**
     * Logs an entry on Sparky. Sparky only accepts entries that point at a food in its library ([foodId] with its
     * serving [variantId]); the entry's own amount and nutrition are sent too and override the food's.
     */
    suspend fun createFoodEntry(entry: FoodEntry, foodId: String? = null, variantId: String? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            val isUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            val cleanMealName = entry.mealName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val timeString = entry.entryTime?.take(5)
            val cleanDate = if (entry.entryDate.contains("T")) entry.entryDate.substringBefore("T") else entry.entryDate

            val payload = buildString {
                append("{")
                append("\"id\":\"${entry.id}\",")
                if (foodId != null && variantId != null) {
                    append("\"food_id\":\"$foodId\",")
                    append("\"variant_id\":\"$variantId\",")
                }
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
                    val content = response.body?.string().orEmpty()
                    val serverId = try {
                        json.parseToJsonElement(content).jsonObject["id"]?.jsonPrimitive?.content
                    } catch (_: Exception) { null }
                    Result.success(serverId ?: entry.id)
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
     * Update an existing food entry on Sparky Fitness Server.
     */
    suspend fun updateFoodEntry(entry: FoodEntry): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val isUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            val cleanMealName = entry.mealName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val timeString = entry.entryTime?.take(5)
            val cleanDate = if (entry.entryDate.contains("T")) entry.entryDate.substringBefore("T") else entry.entryDate

            val payload = buildString {
                append("{")
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
                .url("$baseUrl/api/food-entries/${entry.id}")
                .put(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    val errBody = response.body?.string().orEmpty()
                    android.util.Log.e("ByteLog", "Failed to update food entry on Sparky: HTTP ${response.code} $errBody")
                    Result.failure(IOException("Update food entry failed: HTTP ${response.code} - $errBody"))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ByteLog", "Exception updating entry on Sparky: ${e.message}", e)
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

    /**
     * Fetch user's daily nutrition goals from Sparky Fitness Server for a given date.
     */
    suspend fun fetchGoalsForDate(date: String): Result<UserMacroGoals> = withContext(Dispatchers.IO) {
        try {
            val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
            val request = Request.Builder()
                .url("$baseUrl/api/goals/by-date/$cleanDate")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string().orEmpty()
                    val obj = json.parseToJsonElement(content).jsonObject
                    val calories = obj["calories"]?.jsonPrimitive?.floatOrNull
                        ?: obj["calorie_target"]?.jsonPrimitive?.floatOrNull ?: 2000f
                    val protein = obj["protein"]?.jsonPrimitive?.floatOrNull
                        ?: obj["protein_grams"]?.jsonPrimitive?.floatOrNull ?: 150f
                    val carbs = obj["carbs"]?.jsonPrimitive?.floatOrNull
                        ?: obj["carbs_grams"]?.jsonPrimitive?.floatOrNull ?: 250f
                    val fat = obj["fat"]?.jsonPrimitive?.floatOrNull
                        ?: obj["fat_grams"]?.jsonPrimitive?.floatOrNull ?: 67f
                    val waterGoal = obj["water_goal_ml"]?.jsonPrimitive?.floatOrNull
                        ?: obj["water_goal"]?.jsonPrimitive?.floatOrNull
                        ?: obj["water_target_ml"]?.jsonPrimitive?.floatOrNull ?: 2000f
                    Result.success(UserMacroGoals(calories, protein, carbs, fat, waterGoal))
                } else {
                    Result.failure(IOException("Fetch goals failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Hydration / Water Intake Endpoints ---

    /**
     * Fetch daily water intake totals for a date from Sparky Fitness Server.
     */
    suspend fun fetchWaterIntake(date: String): Result<Float> = withContext(Dispatchers.IO) {
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
    suspend fun fetchWaterLog(date: String): Result<List<WaterLogDto>> = withContext(Dispatchers.IO) {
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
                                createdAt = createdAt
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
    suspend fun upsertWaterIntake(
        date: String,
        changeDrinks: Int,
        containerId: Int? = null
    ): Result<Float> = withContext(Dispatchers.IO) {
        try {
            val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
            val payload = buildString {
                append("{")
                append("\"entry_date\":\"").append(cleanDate).append("\",")
                append("\"change_drinks\":").append(changeDrinks).append(",")
                if (containerId != null) {
                    append("\"container_id\":").append(containerId)
                } else {
                    append("\"container_id\":null")
                }
                append("}")
            }

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
    suspend fun deleteWaterLog(logId: String): Result<Unit> = withContext(Dispatchers.IO) {
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
    suspend fun fetchWaterContainers(): Result<List<WaterContainer>> = withContext(Dispatchers.IO) {
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
    suspend fun createWaterContainer(
        name: String,
        volume: Float,
        unit: String = "ml"
    ): Result<WaterContainer> = withContext(Dispatchers.IO) {
        try {
            val payload = buildString {
                append("{")
                append("\"name\":\"").append(name.replace("\"", "\\\"")).append("\",")
                append("\"volume\":").append(volume).append(",")
                append("\"unit\":\"").append(unit).append("\",")
                append("\"servings_per_container\":1")
                append("}")
            }

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

    // --- Copy Meals & Diary Entries ---

    suspend fun copyMealFromYesterday(mealType: String, targetDate: String): Result<Boolean> = withContext(Dispatchers.IO) {
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

    suspend fun copyMeal(
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

    suspend fun copyAllFromYesterday(targetDate: String): Result<Boolean> = withContext(Dispatchers.IO) {
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

    // --- Meal Templates & Recipes ---

    suspend fun createMealFromDiary(
        date: String,
        mealType: String,
        mealName: String,
        description: String? = null,
        isPublic: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
            val cleanMeal = mealType.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val payload = buildString {
                append("{")
                append("\"date\":\"").append(cleanDate).append("\",")
                append("\"mealType\":\"").append(cleanMeal.replace("\"", "\\\"")).append("\",")
                append("\"mealName\":\"").append(mealName.replace("\"", "\\\"")).append("\",")
                if (!description.isNullOrBlank()) {
                    append("\"description\":\"").append(description.replace("\"", "\\\"")).append("\",")
                }
                append("\"isPublic\":").append(isPublic)
                append("}")
            }
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

    suspend fun fetchMealTemplates(): Result<List<MealTemplate>> = withContext(Dispatchers.IO) {
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

    suspend fun createMealTemplate(
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

    suspend fun logMealTemplate(
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
            val payload = buildString {
                append("{")
                append("\"meal_template_id\":\"").append(templateId).append("\",")
                append("\"meal_type\":\"").append(cleanMeal.replace("\"", "\\\"")).append("\",")
                if (!mealTypeId.isNullOrBlank() && isUuid.matches(mealTypeId)) {
                    append("\"meal_type_id\":\"").append(mealTypeId).append("\",")
                }
                append("\"entry_date\":\"").append(cleanDate).append("\",")
                append("\"quantity\":").append(quantity).append(",")
                append("\"unit\":\"").append(unit).append("\"")
                append("}")
            }
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

    suspend fun deleteMealTemplate(templateId: String): Result<Boolean> = withContext(Dispatchers.IO) {
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

    // --- Custom Food Builder ---

    suspend fun createCustomFood(
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
            val payload = buildString {
                append("{")
                append("\"name\":\"").append(name.replace("\"", "\\\"")).append("\",")
                brand?.let { append("\"brand\":\"").append(it.replace("\"", "\\\"")).append("\",") }
                append("\"serving_size\":").append(servingSize).append(",")
                append("\"serving_unit\":\"").append(servingUnit.replace("\"", "\\\"")).append("\",")
                append("\"calories\":").append(calories).append(",")
                append("\"protein\":").append(protein).append(",")
                append("\"carbs\":").append(carbs).append(",")
                append("\"fat\":").append(fat).append(",")
                fiber?.let { append("\"dietary_fiber\":").append(it).append(",") }
                append("\"is_custom\":true")
                append("}")
            }
            val body = payload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/foods")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
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

    // --- Editing foods in the Sparky library ---
    // Sparky keeps a food's name/brand/barcode on the food and its nutrition on the food's default variant;
    // both PUTs only change the fields sent. Diary entries keep their own snapshot unless update-snapshot is called.

    private fun jsonBody(obj: kotlinx.serialization.json.JsonObject) =
        obj.toString().toRequestBody("application/json".toMediaType())

    private fun errorText(code: Int, body: String): String {
        val msg = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
        return msg ?: "HTTP $code"
    }

    /** GET /api/foods/:id — the food with its default serving and nutrients. */
    suspend fun getFoodDetails(foodId: String): Result<SparkyFood> = withContext(Dispatchers.IO) {
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
    suspend fun listMyFoods(search: String = "", page: Int = 1, perPage: Int = 50): Result<List<SparkyFood>> = withContext(Dispatchers.IO) {
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
    suspend fun updateFood(foodId: String, name: String, brand: String?, barcode: String?): Result<Unit> = withContext(Dispatchers.IO) {
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
    suspend fun updateFoodVariant(variantId: String, foodId: String, n: SparkyNutrients): Result<Unit> = withContext(Dispatchers.IO) {
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
    suspend fun createFoodFull(
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
            client.newCall(request).execute().use { response ->
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
    suspend fun updatePastEntries(foodId: String, variantId: String?): Result<Unit> = withContext(Dispatchers.IO) {
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
    suspend fun deleteFood(foodId: String, hideOnly: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
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

    private fun nutrientsJson(n: SparkyNutrients, foodId: String?): Map<String, kotlinx.serialization.json.JsonElement> {
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

    // --- Bodyweight Tracking (Check-In) ---

    suspend fun fetchWeightForDate(date: String): Result<Float?> = withContext(Dispatchers.IO) {
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

    private suspend fun fetchLatestWeightOnOrBefore(date: String): Result<Float?> {
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

    suspend fun upsertWeight(date: String, weight: Float, bodyFatPercentage: Float? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
            val payload = buildString {
                append("{")
                append("\"entry_date\":\"").append(cleanDate).append("\",")
                append("\"weight\":").append(weight)
                bodyFatPercentage?.let { append(",\"body_fat_percentage\":").append(it) }
                append("}")
            }
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
    suspend fun fetchWeightRange(startDate: String, endDate: String): Result<List<Pair<String, Float>>> = withContext(Dispatchers.IO) {
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

    /**
     * Fetch Adaptive TDEE calculation from Sparky Fitness Server.
     */
    suspend fun fetchAdaptiveTdee(date: String? = null): Result<AdaptiveTdeeResult> = withContext(Dispatchers.IO) {
        try {
            val url = if (date != null) "$baseUrl/api/adaptive-tdee?date=$date" else "$baseUrl/api/adaptive-tdee"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val elem = json.parseToJsonElement(body).jsonObject
                    val tdee = elem["tdee"]?.jsonPrimitive?.floatOrNull ?: 2200f
                    val confidence = elem["confidence"]?.jsonPrimitive?.content ?: "LOW"
                    val weightTrend = elem["weightTrend"]?.jsonPrimitive?.floatOrNull
                    val isFallback = elem["isFallback"]?.jsonPrimitive?.booleanOrNull ?: false
                    val fallbackReason = elem["fallbackReason"]?.jsonPrimitive?.content
                    val avgIntake = elem["avgIntake"]?.jsonPrimitive?.floatOrNull
                    val daysOfData = elem["daysOfData"]?.jsonPrimitive?.intOrNull ?: 0
                    val weightChangeKg = elem["weightChangeKg"]?.jsonPrimitive?.floatOrNull
                    val dailyWeightChangeKg = elem["dailyWeightChangeKg"]?.jsonPrimitive?.floatOrNull
                    val weightChangeCalories = elem["weightChangeCalories"]?.jsonPrimitive?.floatOrNull
                    val rawTdee = elem["rawTdee"]?.jsonPrimitive?.floatOrNull
                    val wasClamped = elem["wasClamped"]?.jsonPrimitive?.booleanOrNull ?: false

                    Result.success(
                        AdaptiveTdeeResult(
                            tdee = tdee,
                            confidence = confidence,
                            weightTrend = weightTrend,
                            isFallback = isFallback,
                            fallbackReason = fallbackReason,
                            avgIntake = avgIntake,
                            daysOfData = daysOfData,
                            weightChangeKg = weightChangeKg,
                            dailyWeightChangeKg = dailyWeightChangeKg,
                            weightChangeCalories = weightChangeCalories,
                            rawTdee = rawTdee,
                            wasClamped = wasClamped
                        )
                    )
                } else {
                    Result.failure(IOException("Fetch adaptive TDEE failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * One JSON-RPC call to Sparky's MCP endpoint (`POST /mcp`, stateless Streamable HTTP). Returns the `result`
     * object; a JSON-RPC `error` becomes a failure. The server answers with plain JSON; an event-stream reply
     * is handled too. `tools/list` and `tools/call` need no `initialize` first.
     */
    suspend fun mcpRequest(method: String, params: kotlinx.serialization.json.JsonObject? = null): Result<kotlinx.serialization.json.JsonObject> = withContext(Dispatchers.IO) {
        try {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
                put("id", kotlinx.serialization.json.JsonPrimitive(1))
                put("method", kotlinx.serialization.json.JsonPrimitive(method))
                if (params != null) put("params", params)
            }
            val request = Request.Builder()
                .url("$baseUrl/mcp")
                .header("Accept", "application/json, text/event-stream")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            aiClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val hint = when (response.code) {
                        401, 403 -> " Sign in again or check your API key in Settings."
                        404 -> " This Sparky server has no MCP endpoint."
                        else -> ""
                    }
                    return@use Result.failure(IOException("Sparky MCP request failed: HTTP ${response.code}.$hint"))
                }
                val root = parseMcpBody(body, response.header("Content-Type"))
                    ?: return@use Result.failure(IOException("Sparky MCP sent a reply that isn't JSON-RPC."))
                val error = root["error"] as? kotlinx.serialization.json.JsonObject
                if (error != null) {
                    return@use Result.failure(IOException("Sparky MCP error: ${error["message"]?.jsonPrimitive?.content ?: "unknown"}"))
                }
                val result = root["result"] as? kotlinx.serialization.json.JsonObject
                    ?: return@use Result.failure(IOException("Sparky MCP reply had no result."))
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Send chat messages to Sparky Fitness AI Chatbot (/api/chat).
     * The Sparky server runs the LLM, executes fitness tools (food logging, weight recording, checkins),
     * and returns the assistant reply along with executed tools.
     */
    suspend fun sendChatMessage(
        messages: List<ChatMessageInput>,
        toolCategories: List<String>? = null
    ): Result<ChatMessageOutput> = withContext(Dispatchers.IO) {
        try {
            val serviceConfigId = fetchActiveServiceConfigId()
            if (serviceConfigId.isNullOrBlank()) {
                return@withContext Result.failure(
                    IOException("No active AI service configuration found on Sparky server. Please configure an AI service in Sparky Fitness web settings.")
                )
            }

            val jsonArray = kotlinx.serialization.json.buildJsonArray {
                for (m in messages) {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("role", kotlinx.serialization.json.JsonPrimitive(m.role))
                        put("content", kotlinx.serialization.json.JsonPrimitive(m.content))
                        if (!m.imageBase64.isNullOrBlank()) {
                            val formattedImage = if (m.imageBase64.startsWith("data:")) {
                                m.imageBase64
                            } else {
                                "data:image/jpeg;base64,${m.imageBase64}"
                            }
                            val partsArray = kotlinx.serialization.json.buildJsonArray {
                                add(kotlinx.serialization.json.buildJsonObject {
                                    put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                                    put("text", kotlinx.serialization.json.JsonPrimitive(m.content))
                                })
                                add(kotlinx.serialization.json.buildJsonObject {
                                    put("type", kotlinx.serialization.json.JsonPrimitive("image"))
                                    put("image", kotlinx.serialization.json.JsonPrimitive(formattedImage))
                                })
                            }
                            put("parts", partsArray)
                        }
                    })
                }
            }
            val requestBodyObj = kotlinx.serialization.json.buildJsonObject {
                put("messages", jsonArray)
                put("service_config_id", kotlinx.serialization.json.JsonPrimitive(serviceConfigId))
                if (!toolCategories.isNullOrEmpty()) {
                    put("toolCategories", kotlinx.serialization.json.buildJsonArray {
                        toolCategories.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    })
                }
            }
            val body = requestBodyObj.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/api/chat")
                .post(body)
                .build()

            aiClient.newCall(request).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = json.parseToJsonElement(respBody).jsonObject
                    val replyContent = root["content"]?.jsonPrimitive?.content ?: ""
                    val action = root["action"]?.jsonPrimitive?.content
                    val executedToolsArray = root["executedTools"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
                    val executedToolsList = mutableListOf<String>()
                    var askUserPrompt: ChatAskUserPrompt? = null

                    for (el in executedToolsArray) {
                        if (el is kotlinx.serialization.json.JsonObject) {
                            val toolName = el["name"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content.orEmpty()
                            if (toolName.isNotBlank()) {
                                executedToolsList.add(toolName)
                            }
                            if (toolName == "sparky_ask_user") {
                                val argsObj = el["args"]?.takeIf { it is kotlinx.serialization.json.JsonObject }?.jsonObject
                                if (argsObj != null) {
                                    val question = argsObj["question"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
                                    val mode = argsObj["mode"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
                                    val optionsArray = argsObj["options"]?.takeIf { it is kotlinx.serialization.json.JsonArray }?.jsonArray
                                    val options = optionsArray?.mapNotNull { opt ->
                                        opt.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
                                    } ?: emptyList()
                                    if (options.isNotEmpty()) {
                                        askUserPrompt = ChatAskUserPrompt(
                                            question = question,
                                            options = options,
                                            mode = mode
                                        )
                                    }
                                }
                            }
                        } else if (el is kotlinx.serialization.json.JsonPrimitive) {
                            executedToolsList.add(el.content)
                        }
                    }

                    if (askUserPrompt == null) {
                        askUserPrompt = extractAskUserPromptFromText(replyContent)
                    }

                    Result.success(
                        ChatMessageOutput(
                            id = UUID.randomUUID().toString(),
                            role = "assistant",
                            content = replyContent,
                            action = action,
                            executedTools = executedToolsList,
                            askUserPrompt = askUserPrompt
                        )
                    )
                } else {
                    if (response.code == 404) {
                        cachedServiceConfigId = null
                    }
                    var errorMsg = when (response.code) {
                        401 -> "Authentication error (HTTP 401). Please check your Sparky credentials in Settings."
                        403 -> "Access forbidden (HTTP 403)."
                        404 -> "AI service not configured or endpoint not found on server (HTTP 404)."
                        else -> "Chat request failed: HTTP ${response.code}"
                    }
                    try {
                        val parsed = json.parseToJsonElement(respBody).jsonObject
                        val serverError = parsed["error"]?.jsonPrimitive?.content
                            ?: parsed["message"]?.jsonPrimitive?.content
                        if (!serverError.isNullOrBlank()) {
                            errorMsg += " ($serverError)"
                        }
                    } catch (_: Exception) {}
                    Result.failure(IOException(errorMsg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Load recent chat history from Sparky Fitness server (/api/chat/sparky-chat-history).
     */
    suspend fun fetchChatHistory(): Result<List<ChatHistoryItem>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/chat/sparky-chat-history")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string().orEmpty()
                    val root = json.parseToJsonElement(content).jsonArray
                    val items = root.mapNotNull { el ->
                        val obj = el.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                        val msgType = obj["message_type"]?.jsonPrimitive?.content ?: "assistant"
                        val text = obj["content"]?.jsonPrimitive?.content ?: ""
                        val createdAt = obj["created_at"]?.jsonPrimitive?.content
                        ChatHistoryItem(
                            id = id,
                            message_type = msgType,
                            content = text,
                            created_at = createdAt
                        )
                    }
                    Result.success(items)
                } else {
                    Result.failure(IOException("Failed to fetch chat history: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Clear all user chat history on Sparky Fitness server (/api/chat/clear-all-history).
     */
    suspend fun clearChatHistory(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = "{}".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/chat/clear-all-history")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(IOException("Failed to clear chat history: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Estimate nutrition from food photo using Sparky Fitness Server (/api/foods/estimate-food-photo).
     */
    suspend fun estimateFoodPhoto(
        base64: String,
        mimeType: String = "image/jpeg",
        description: String? = null
    ): Result<FoodPhotoEstimateResult> = withContext(Dispatchers.IO) {
        try {
            val cleanBase64 = if (base64.contains(",")) base64.substringAfter(",") else base64
            val payloadObj = kotlinx.serialization.json.buildJsonObject {
                put("images", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("image", kotlinx.serialization.json.JsonPrimitive(cleanBase64))
                        put("mime_type", kotlinx.serialization.json.JsonPrimitive(mimeType))
                    })
                })
                if (!description.isNullOrBlank()) {
                    put("description", kotlinx.serialization.json.JsonPrimitive(description))
                }
            }
            val body = payloadObj.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/foods/estimate-food-photo")
                .post(body)
                .build()

            aiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string().orEmpty()
                    val root = json.parseToJsonElement(content).jsonObject
                    val mealSummary = root["meal_summary"]?.jsonPrimitive?.content ?: "Estimated Meal"
                    val totals = root["totals"]?.jsonObject
                    val calories = totals?.get("calories_kcal")?.jsonPrimitive?.floatOrNull ?: 0f
                    val protein = totals?.get("protein_g")?.jsonPrimitive?.floatOrNull ?: 0f
                    val carbs = totals?.get("carbs_g")?.jsonPrimitive?.floatOrNull ?: 0f
                    val fat = totals?.get("fat_g")?.jsonPrimitive?.floatOrNull ?: 0f
                    val fiber = totals?.get("fiber_g")?.jsonPrimitive?.floatOrNull ?: 0f
                    val totalGrams = totals?.get("total_grams")?.jsonPrimitive?.floatOrNull ?: 100f

                    Result.success(
                        FoodPhotoEstimateResult(
                            mealSummary = mealSummary,
                            calories = calories,
                            protein = protein,
                            carbs = carbs,
                            fat = fat,
                            fiber = fiber,
                            servingSize = totalGrams,
                            servingUnit = "g"
                        )
                    )
                } else {
                    val err = response.body?.string().orEmpty()
                    Result.failure(IOException("Food photo estimation failed: HTTP ${response.code} $err"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        /** Reads a JSON-RPC reply that is either plain JSON or a Server-Sent Events stream (last `data:` event wins). */
        fun parseMcpBody(body: String, contentType: String?): kotlinx.serialization.json.JsonObject? {
            val text = if (contentType?.contains("text/event-stream", ignoreCase = true) == true || body.trimStart().startsWith("event:") || body.trimStart().startsWith("data:")) {
                body.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.lastOrNull { it.isNotBlank() } ?: return null
            } else body
            return try {
                Json { ignoreUnknownKeys = true }.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Fallback extractor for ask-user prompts / options:
         * 1) Server chat history replay: `(options offered: opt1 | opt2)`
         * 2) Text formatted choices at the end of assistant message (2 to 4 options)
         */
        fun extractAskUserPromptFromText(content: String): ChatAskUserPrompt? {
            if (content.isBlank()) return null

            // 1. Check for (options offered: opt1 | opt2)
            val offeredMatch = Regex("""(?i)\(options offered:\s*(.+?)\)""").find(content)
            if (offeredMatch != null) {
                val raw = offeredMatch.groupValues[1]
                val opts = raw.split('|').map { it.trim() }.filter { it.isNotBlank() }
                if (opts.size >= 2) {
                    val q = content.substringBefore(offeredMatch.value).trim().ifBlank { null }
                    return ChatAskUserPrompt(
                        question = q,
                        options = opts,
                        mode = "choose"
                    )
                }
            }

            // 2. Check for numbered or bulleted options (2 to 4 items) at the end of the text
            val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size >= 3) {
                for (count in 4 downTo 2) {
                    if (lines.size > count) {
                        val candidateLines = lines.takeLast(count)
                        val isNumbered = candidateLines.mapIndexed { idx, line ->
                            line.matches(Regex("""^${idx + 1}[.)]\s+.+"""))
                        }.all { it }
                        val isBulleted = candidateLines.all { line ->
                            line.matches(Regex("""^[-*•]\s+.+"""))
                        }
                        if (isNumbered || isBulleted) {
                            val opts = candidateLines.map { line ->
                                line.replace(Regex("""^(\d+[.)]|[-*•])\s+"""), "").trim()
                            }
                            if (opts.all { it.length in 1..60 }) {
                                val question = lines.dropLast(count).joinToString(" ").trim()
                                if (question.endsWith("?") ||
                                    question.contains("select", ignoreCase = true) ||
                                    question.contains("choose", ignoreCase = true) ||
                                    question.contains("which", ignoreCase = true) ||
                                    question.contains("prefer", ignoreCase = true)
                                ) {
                                    return ChatAskUserPrompt(
                                        question = question.ifBlank { null },
                                        options = opts,
                                        mode = if (isNumbered) "choose" else "ask"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            return null
        }
    }
}

@kotlinx.serialization.Serializable
data class FoodPhotoEstimateResult(
    val mealSummary: String,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val fiber: Float = 0f,
    val servingSize: Float = 100f,
    val servingUnit: String = "g"
)

@kotlinx.serialization.Serializable
data class ChatAskUserPrompt(
    val question: String? = null,
    val options: List<String> = emptyList(),
    val mode: String? = null
)

@kotlinx.serialization.Serializable
data class ChatMessageInput(
    val role: String,
    val content: String,
    val imageBase64: String? = null
)

data class ChatMessageOutput(
    val id: String = UUID.randomUUID().toString(),
    val role: String = "assistant",
    val content: String,
    val imageBase64: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val action: String? = null,
    val executedTools: List<String> = emptyList(),
    val askUserPrompt: ChatAskUserPrompt? = null,
    /** Pages the web research assistant used. */
    val webSources: List<com.bytelog.app.research.WebSource> = emptyList(),
    /** True for answers from the web research assistant (not Sparky's own chat). */
    val viaWeb: Boolean = false
)

@kotlinx.serialization.Serializable
data class ChatHistoryItem(
    val id: String,
    val message_type: String,
    val content: String,
    val created_at: String? = null
)

@kotlinx.serialization.Serializable
data class AdaptiveTdeeResult(
    val tdee: Float,
    val confidence: String = "LOW",
    val weightTrend: Float? = null,
    val isFallback: Boolean = false,
    val fallbackReason: String? = null,
    val avgIntake: Float? = null,
    val daysOfData: Int = 0,
    val weightChangeKg: Float? = null,
    val dailyWeightChangeKg: Float? = null,
    val weightChangeCalories: Float? = null,
    val rawTdee: Float? = null,
    val wasClamped: Boolean = false
)

@kotlinx.serialization.Serializable
data class UserMacroGoals(
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val waterGoalMl: Float = 2000f
)

@kotlinx.serialization.Serializable
data class WaterContainer(
    val id: Int,
    val name: String,
    /** Always millilitres: Sparky converts to ml when saving, whatever [unit] says. */
    val volume: Float,
    /** Display label only (ml, oz, liter). It does NOT change what [volume] is measured in. */
    val unit: String = "ml",
    val isPrimary: Boolean = false,
    val servingsPerContainer: Int = 1
) {
    val volumeMl: Float get() = volume
    val volumeOz: Float get() = volume / 29.5735f
}

@kotlinx.serialization.Serializable
data class WaterLogDto(
    val id: String,
    val waterMl: Float,
    val entryDate: String,
    val containerId: Int? = null,
    val containerName: String? = null,
    val createdAt: String? = null
)
