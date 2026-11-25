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

data class ConnectionTestResult(
    val isSuccess: Boolean,
    val serverVersion: String? = null,
    val isAuthenticated: Boolean = false,
    val message: String
)

class SparkyApiClient(
    internal var baseUrl: String = "http://10.0.2.2:3010",
    internal var authToken: String? = null,
    internal var apiKey: String? = null
) {
    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    internal val client: OkHttpClient = OkHttpClient.Builder()
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
            // Requests made for a family member carry Sparky's active-user cookie
            original.tag(ActingAs::class.java)?.let { builder.header("Cookie", "sparky_active_user_id=${it.userId}") }

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
     * For requests that create something. OkHttp normally resends a request by itself when the connection drops
     * after it was sent; for a new diary entry or food that makes a second copy on Sparky, so these don't retry
     * on their own (the sync tries again later and checks Sparky first).
     */
    internal val createClient: OkHttpClient by lazy { client.newBuilder().retryOnConnectionFailure(false).build() }
 
    /**
     * Dedicated OkHttpClient for AI interactions (LLM multi-step tool calls, multimodal vision),
     * matching Sparky Fitness Server's 5-minute timeout window to prevent premature socket timeouts.
     */
    internal val aiClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    internal var cachedServiceConfigId: String? = null

    fun updateConfig(url: String, token: String?, key: String?) {
        this.baseUrl = url.trim().trimEnd('/')
        this.authToken = token?.takeIf { it.isNotBlank() }
        this.apiKey = key?.takeIf { it.isNotBlank() }
        this.cachedServiceConfigId = null
    }

    fun currentBaseUrl(): String = baseUrl
    fun httpClient(): OkHttpClient = client
    fun jsonCodec(): Json = json

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
    suspend fun fetchMealTypes(actingAs: String? = null): Result<List<MealType>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/meal-types")
                .get()
                .tag(ActingAs::class.java, actingAs?.let { ActingAs(it) })
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
    /**
     * Food search across Sparky's foods and Open Food Facts, asked at the same time; Sparky's come first and a
     * food found in both is listed once.
     */
    suspend fun searchFoods(query: String): Result<List<FoodItem>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext Result.success(emptyList())
        kotlinx.coroutines.coroutineScope {
            val sparky = async { searchSparkyFoods(trimmed).getOrNull().orEmpty() }
            val off = async { searchOpenFoodFacts(trimmed, page = 1).getOrNull().orEmpty() }
            Result.success(mergeFoodResults(sparky.await(), off.await()))
        }
    }

    /** Foods on Sparky (yours, shared and saved) matching [query]. */
    suspend fun searchSparkyFoods(query: String): Result<List<FoodItem>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext Result.success(emptyList())
        val combinedResults = mutableListOf<FoodItem>()
        val seenKeys = mutableSetOf<String>()
        try {
            val encodedQuery = java.net.URLEncoder.encode(trimmed, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/foods/foods-paginated?searchTerm=$encodedQuery&currentPage=1&itemsPerPage=30")
                .get()
                .build()

            client.newCall(request).await().use { response ->
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
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return@withContext Result.failure(e)
        }

        Result.success(combinedResults)
    }

    /** Set once Sparky turns out not to have the v2 food search (older servers); then the original search is used. */
    @Volatile private var v2SearchMissing = false

    /**
     * Open Food Facts, through Sparky. Sparky's v2 search describes each product by its label serving ("30 g")
     * with its household serving ("2 cookies") as a second variant; older Sparky servers only have the raw
     * search, read by [parseOffProducts].
     */
    suspend fun searchOpenFoodFacts(query: String, page: Int = 1): Result<List<FoodItem>> = withContext(Dispatchers.IO) {
        if (!v2SearchMissing) {
            val v2 = searchOpenFoodFactsV2(query, page)
            if (v2 != null) return@withContext v2
        }
        try {
            val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/foods/openfoodfacts/search?query=$encodedQuery&page=$page")
                .get()
                .build()
            client.newCall(request).await().use { response ->
                if (response.isSuccessful) Result.success(parseOffProducts(response.body?.string().orEmpty()))
                else Result.failure(IOException("OpenFoodFacts search error: HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    /** Sparky's v2 search; null when this Sparky doesn't have it. */
    internal suspend fun searchOpenFoodFactsV2(query: String, page: Int): Result<List<FoodItem>>? {
        val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val request = Request.Builder()
            .url("$baseUrl/api/v2/foods/search/openfoodfacts?query=$encodedQuery&page=$page&pageSize=20&autoScale=true")
            .get()
            .build()
        return try {
            client.newCall(request).await().use { response ->
                when {
                    response.isSuccessful -> Result.success(parseNormalizedFoods(response.body?.string().orEmpty()))
                    response.code == 404 -> { v2SearchMissing = true; null }
                    else -> Result.failure(IOException("OpenFoodFacts search error: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
                    // Sparky answers {"source": "local|openfoodfacts|...|not_found", "food": {...} | null}
                    val root = json.parseToJsonElement(content).jsonObject
                    val foodObj = (root["food"] as? kotlinx.serialization.json.JsonObject) ?: return@use Result.success(null)
                    val parsed = SparkyFood.parse(foodObj)
                    // A product with no name isn't a usable result (it would log as a blank item)
                    if (parsed.name == SparkyFood.UNNAMED || parsed.name.isBlank()) return@use Result.success(null)
                    val local = root["source"]?.jsonPrimitive?.content == "local" && parsed.id.isNotBlank()
                    // Products found online aren't in the user's Sparky library yet: "off_<barcode>" until they are
                    val id = if (local) parsed.id else "off_$barcode"
                    Result.success(parsed.copy(id = id, barcode = barcode).toFoodItem())
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
    suspend fun fetchEntriesForDate(date: String, userId: String? = null): Result<List<FoodEntry>> = withContext(Dispatchers.IO) {
        try {
            val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
            val who = userId?.let { "?userId=$it" }.orEmpty()
            val request = Request.Builder()
                .url("$baseUrl/api/food-entries/by-date/$cleanDate$who")
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
                            // Without an id, one made from the entry itself, so the same entry gets the same id every sync
                            id = obj["id"]?.jsonPrimitive?.content ?: stableEntryId(normalizedDate, obj),
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
                            syncStatus = FoodEntry.SYNC_STATUS_SYNCED,
                            // When it was logged on Sparky, not when this phone downloaded it (the fasting timer reads it)
                            createdAt = com.bytelog.app.data.repository.parseServerTime((obj["created_at"] as? JsonPrimitive)?.content)
                                ?: System.currentTimeMillis(),
                            source = (obj["source"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                            sourceId = (obj["source_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
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
    suspend fun createFoodEntry(
        entry: FoodEntry,
        foodId: String? = null,
        variantId: String? = null,
        forUserId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val isUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            val cleanMealName = entry.mealName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val timeString = entry.entryTime?.take(5)
            val cleanDate = if (entry.entryDate.contains("T")) entry.entryDate.substringBefore("T") else entry.entryDate

            val payload = buildJsonObject {
                put("id", entry.id)
                if (foodId != null && variantId != null) {
                    put("food_id", foodId)
                    put("variant_id", variantId)
                }
                // Logging into a family member's diary: Sparky checks the family-access grant for this user
                if (forUserId != null) put("user_id", forUserId)
                if (isUuid.matches(entry.mealTypeId)) put("meal_type_id", entry.mealTypeId)
                put("meal_type", cleanMealName)
                putNum("quantity", entry.quantity)
                put("unit", entry.unit)
                putNum("serving_size", entry.quantity)
                put("serving_unit", entry.unit)
                put("entry_date", cleanDate)
                if (!timeString.isNullOrBlank() && Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(timeString)) put("entry_time", timeString)
                put("food_name", entry.foodName)
                entry.brandName?.let { put("brand_name", it) }
                putNum("calories", entry.calories)
                putNum("protein", entry.protein)
                putNum("carbs", entry.carbs)
                putNum("fat", entry.fat)
            }.toString()

            val body = payload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/food-entries/")
                .post(body)
                .build()

            createClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string().orEmpty()
                    val serverId = try { createdEntryId(json.parseToJsonElement(content)) } catch (_: Exception) { null }
                    Result.success(serverId ?: entry.id)
                } else {
                    val errBody = response.body?.string().orEmpty()
                    android.util.Log.e("ByteLog", "Failed to create food entry on Sparky: HTTP ${response.code} $errBody")
                    Result.failure(SparkyHttpException(response.code, "Create food entry failed: HTTP ${response.code} - $errBody"))
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

            val payload = buildJsonObject {
                if (isUuid.matches(entry.mealTypeId)) put("meal_type_id", entry.mealTypeId)
                put("meal_type", cleanMealName)
                putNum("quantity", entry.quantity)
                put("unit", entry.unit)
                putNum("serving_size", entry.quantity)
                put("serving_unit", entry.unit)
                put("entry_date", cleanDate)
                if (!timeString.isNullOrBlank() && Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(timeString)) put("entry_time", timeString)
                put("food_name", entry.foodName)
                entry.brandName?.let { put("brand_name", it) }
                putNum("calories", entry.calories)
                putNum("protein", entry.protein)
                putNum("carbs", entry.carbs)
                putNum("fat", entry.fat)
            }.toString()

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
    suspend fun fetchGoalsForDate(date: String, actingAs: String? = null): Result<UserMacroGoals> = withContext(Dispatchers.IO) {
        try {
            val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
            val request = Request.Builder()
                .url("$baseUrl/api/goals/by-date/$cleanDate")
                .get()
                .tag(ActingAs::class.java, actingAs?.let { ActingAs(it) })
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

    // --- Copy Meals & Diary Entries ---

    // --- Meal Templates & Recipes ---

    // --- Custom Food Builder ---

    // --- Family access ---

    // --- Editing foods in the Sparky library ---
    // Sparky keeps a food's name/brand/barcode on the food and its nutrition on the food's default variant;
    // both PUTs only change the fields sent. Diary entries keep their own snapshot unless update-snapshot is called.

    internal fun jsonBody(obj: kotlinx.serialization.json.JsonObject) =
        obj.toString().toRequestBody("application/json".toMediaType())

    internal fun errorText(code: Int, body: String): String {
        val msg = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
        return msg ?: "HTTP $code"
    }

    // --- Bodyweight Tracking (Check-In) ---

    companion object {
        /** Reads Sparky's food photo estimate: the meal, and each food in it. Null if it isn't one. */
        private val parseJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Sparky's v2 search results (`{"foods": [...]}`): each product's serving plus its household serving. */
        fun parseNormalizedFoods(content: String): List<FoodItem> {
            val root = runCatching { parseJson.parseToJsonElement(content).jsonObject }.getOrNull() ?: return emptyList()
            val foods = (root["foods"] as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
            return foods.mapNotNull { el ->
                val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val f = SparkyFood.parse(obj)
                if (f.name == SparkyFood.UNNAMED || f.name.isBlank()) return@mapNotNull null
                val code = f.barcode ?: (obj["provider_external_id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                f.copy(id = code?.let { "off_$it" } ?: stableOffId(f.name, f.brand, f.nutrients.calories), barcode = code).toFoodItem()
            }
        }

        /**
         * The original Open Food Facts search (raw products). Nutrition is per 100 g, so a product with a label
         * serving ("30 g") is described by that serving with its nutrition scaled to match, and a household
         * serving written on the label ("2 cookies (30 g)") is kept.
         */
        fun parseOffProducts(content: String): List<FoodItem> {
            val root = runCatching { parseJson.parseToJsonElement(content).jsonObject }.getOrNull() ?: return emptyList()
            val products = (root["products"] as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
            fun kotlinx.serialization.json.JsonObject.num(k: String) =
                (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.let { it.floatOrNull ?: it.contentOrNull?.toFloatOrNull() }
            fun kotlinx.serialization.json.JsonObject.str(k: String) =
                (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            return products.mapNotNull { el ->
                val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val name = obj.str("product_name") ?: obj.str("product_name_en") ?: return@mapNotNull null
                val brand = obj.str("brands")?.split(',')?.firstOrNull()?.trim()
                val code = obj.str("code")
                val n = obj["nutriments"] as? kotlinx.serialization.json.JsonObject
                val per100 = listOf(
                    n?.num("energy-kcal_100g") ?: n?.num("energy-kcal") ?: 0f,
                    n?.num("proteins_100g") ?: n?.num("proteins") ?: 0f,
                    n?.num("carbohydrates_100g") ?: n?.num("carbohydrates") ?: 0f,
                    n?.num("fat_100g") ?: n?.num("fat") ?: 0f,
                    n?.num("fiber_100g") ?: n?.num("fiber") ?: 0f
                )
                val label = obj.num("serving_quantity")?.takeIf { it > 0f }
                val unit = (obj.str("serving_quantity_unit") ?: "g").lowercase().let { if (it == "ml" || it == "l") "ml" else "g" }
                val size = label ?: 100f
                val scale = size / 100f
                val household = label?.let { parseLabelHousehold(obj.str("serving_size"), it, size) }
                FoodItem(
                    id = code?.let { "off_$it" } ?: stableOffId(name, brand, per100[0]),
                    name = name, brand = brand, barcode = code,
                    servingSize = size, servingUnit = unit,
                    calories = per100[0] * scale, protein = per100[1] * scale, carbs = per100[2] * scale,
                    fat = per100[3] * scale, fiber = per100[4] * scale,
                    householdServings = household?.let { com.bytelog.app.recipes.HouseholdServings.encode(listOf(it)) }
                )
            }
        }

        /** "2 cookies (28 g)" -> 2 cookies, each half of the 28 g serving; null for "28 g" or no label. */
        fun parseLabelHousehold(servingText: String?, labelAmount: Float, referenceSize: Float): com.bytelog.app.recipes.HouseholdServing? {
            val m = Regex("""^\s*([\d.,]+)\s+([^\d(][^(]*?)\s*\([^)]*\)\s*$""").find(servingText ?: return null) ?: return null
            val count = m.groupValues[1].replace(',', '.').toFloatOrNull()?.takeIf { it > 0f } ?: return null
            val unit = com.bytelog.app.recipes.HouseholdServings.singular(m.groupValues[2])
            if (unit.isBlank() || com.bytelog.app.recipes.UnitConverter.canonical(unit) != null) return null
            // The label amount is the whole household serving; the food's reference serving is that same amount
            return com.bytelog.app.recipes.HouseholdServing(unit, (labelAmount / referenceSize) / count, count)
        }

        /** An Open Food Facts product without a barcode, identified by its name, brand and calories. */
        fun stableOffId(name: String, brand: String?, calories: Float): String {
            val key = "${name.trim().lowercase()}|${brand?.trim()?.lowercase().orEmpty()}|${Math.round(calories)}"
            val digest = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            return "off_n" + digest.take(8).joinToString("") { "%02x".format(it) }
        }

        /** [first] then the foods of [second] not already in it (same barcode, or same name and brand). */
        fun mergeFoodResults(first: List<FoodItem>, second: List<FoodItem>): List<FoodItem> {
            val seen = HashSet<String>()
            fun key(f: FoodItem) = (f.barcode?.takeIf { it.isNotBlank() } ?: "${f.name.lowercase()}_${f.brand?.lowercase()}").trim()
            return (first + second).filter { seen.add(key(it)) }
        }

        fun parsePhotoEstimate(content: String): PhotoMealEstimate? = runCatching {
            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(content).jsonObject
            fun kotlinx.serialization.json.JsonObject.f(k: String) = this[k]?.jsonPrimitive?.floatOrNull ?: 0f
            fun kotlinx.serialization.json.JsonObject.s(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
            val items = (root["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                PhotoMealItem(
                    name = o.s("name") ?: o.s("canonical_name") ?: return@mapNotNull null,
                    canonicalName = o.s("canonical_name"),
                    grams = o.f("estimated_grams"),
                    portion = o.s("portion_description"),
                    calories = o.f("calories_kcal"), protein = o.f("protein_g"), carbs = o.f("carbs_g"), fat = o.f("fat_g"),
                    fiber = o.f("fiber_g"),
                    confidence = o.s("item_confidence") ?: "medium"
                )
            }
            val totals = root["totals"] as? kotlinx.serialization.json.JsonObject
            // Older servers only send totals: the meal becomes one item
            val all = items.ifEmpty {
                if (totals == null) return@runCatching null
                listOf(PhotoMealItem(root.s("meal_summary") ?: "Meal", null, totals.f("total_grams"), null,
                    totals.f("calories_kcal"), totals.f("protein_g"), totals.f("carbs_g"), totals.f("fat_g"), totals.f("fiber_g"), "medium"))
            }
            PhotoMealEstimate(
                summary = root.s("meal_summary") ?: "Meal",
                confidence = root.s("overall_confidence") ?: "medium",
                confidenceReason = root.s("confidence_reason"),
                items = all,
                questions = (root["clarifying_questions"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                    .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { q -> q.isNotBlank() } }
            )
        }.getOrNull()

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

/** Sparky answered, and refused (as opposed to the request never getting an answer). */
class SparkyHttpException(val code: Int, message: String) : IOException(message)

/**
 * The id of a diary entry Sparky just created, wherever its answer puts it: the entry itself, wrapped in
 * `data`/`entry`/`foodEntry`, or a one-item list.
 */
internal fun createdEntryId(element: kotlinx.serialization.json.JsonElement?): String? = when (element) {
    is kotlinx.serialization.json.JsonObject -> (element["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: listOf("data", "entry", "foodEntry", "food_entry", "result").firstNotNullOfOrNull { createdEntryId(element[it]) }
    is kotlinx.serialization.json.JsonArray -> element.firstNotNullOfOrNull { createdEntryId(it) }
    else -> null
}

/** A repeatable id for a downloaded entry that came without one. */
internal fun stableEntryId(date: String, obj: kotlinx.serialization.json.JsonObject): String {
    val key = listOf("food_id", "variant_id", "food_name", "meal_type", "quantity", "unit", "created_at")
        .joinToString("|") { (obj[it] as? JsonPrimitive)?.contentOrNull.orEmpty() }
    return "sparky-" + UUID.nameUUIDFromBytes("$date|$key".toByteArray()).toString()
}

/** A number Sparky can read: NaN or infinity (which JSON can't hold) goes as 0 instead of breaking the request. */
internal fun kotlinx.serialization.json.JsonObjectBuilder.putNum(key: String, value: Number) {
    val d = value.toDouble()
    put(key, JsonPrimitive(if (d.isFinite()) value else 0))
}

