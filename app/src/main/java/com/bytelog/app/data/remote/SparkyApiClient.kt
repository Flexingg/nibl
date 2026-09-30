package com.bytelog.app.data.remote

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
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
import java.util.concurrent.TimeUnit

class SparkyApiClient(
    private var baseUrl: String = "http://10.0.2.2:3000",
    private var authToken: String? = null,
    private var apiKey: String? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
            authToken?.let {
                builder.header("Authorization", "Bearer $it")
            }
            apiKey?.let {
                builder.header("X-API-Key", it)
            }
            builder.header("Accept", "application/json")
            chain.proceed(builder.build())
        }
        .build()

    fun updateConfig(url: String, token: String?, key: String?) {
        this.baseUrl = url.trimEnd('/')
        this.authToken = token
        this.apiKey = key
    }

    /**
     * Authenticate via Better Auth Email/Password.
     */
    suspend fun signInEmail(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = """{"email":"$email","password":"$password"}"""
            val body = payload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/auth/sign-in/email")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respBody = response.body?.string().orEmpty()
                    // Extract token or session cookie
                    val setCookie = response.header("set-cookie")
                    val token = setCookie?.split(";")?.firstOrNull() ?: "session_active"
                    authToken = token
                    Result.success(token)
                } else {
                    Result.failure(IOException("Sign-in failed with HTTP ${response.code}"))
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
                            name = obj["name"]?.jsonPrimitive?.content ?: "Meal $index",
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
     */
    suspend fun searchFoods(query: String): Result<List<FoodItem>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/foods/foods-paginated?searchTerm=$encodedQuery&currentPage=1&itemsPerPage=25")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string().orEmpty()
                    val root = json.parseToJsonElement(content).jsonObject
                    val foodsArray = root["foods"]?.jsonArray ?: json.parseToJsonElement(content).jsonArray
                    val items = foodsArray.map { el ->
                        val obj = el.jsonObject
                        FoodItem(
                            id = obj["id"]?.jsonPrimitive?.content ?: java.util.UUID.randomUUID().toString(),
                            name = obj["name"]?.jsonPrimitive?.content ?: "Unknown Food",
                            brand = obj["brand"]?.jsonPrimitive?.content,
                            barcode = obj["barcode"]?.jsonPrimitive?.content,
                            servingSize = obj["serving_size"]?.jsonPrimitive?.floatOrNull ?: 100f,
                            servingUnit = obj["serving_unit"]?.jsonPrimitive?.content ?: "g",
                            calories = obj["calories"]?.jsonPrimitive?.floatOrNull ?: 0f,
                            protein = obj["protein"]?.jsonPrimitive?.floatOrNull ?: 0f,
                            carbs = obj["carbs"]?.jsonPrimitive?.floatOrNull ?: 0f,
                            fat = obj["fat"]?.jsonPrimitive?.floatOrNull ?: 0f,
                            fiber = obj["dietary_fiber"]?.jsonPrimitive?.floatOrNull ?: 0f,
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
                    val item = FoodItem(
                        id = obj["id"]?.jsonPrimitive?.content ?: java.util.UUID.randomUUID().toString(),
                        name = obj["name"]?.jsonPrimitive?.content ?: "Scanned Product",
                        brand = obj["brand"]?.jsonPrimitive?.content,
                        barcode = barcode,
                        servingSize = obj["serving_size"]?.jsonPrimitive?.floatOrNull ?: 100f,
                        servingUnit = obj["serving_unit"]?.jsonPrimitive?.content ?: "g",
                        calories = obj["calories"]?.jsonPrimitive?.floatOrNull ?: 0f,
                        protein = obj["protein"]?.jsonPrimitive?.floatOrNull ?: 0f,
                        carbs = obj["carbs"]?.jsonPrimitive?.floatOrNull ?: 0f,
                        fat = obj["fat"]?.jsonPrimitive?.floatOrNull ?: 0f,
                        fiber = obj["dietary_fiber"]?.jsonPrimitive?.floatOrNull ?: 0f
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
     * Fetch diary entries for date (YYYY-MM-DD).
     */
    suspend fun fetchEntriesForDate(date: String): Result<List<FoodEntry>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/foods/food-entries/$date")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string().orEmpty()
                    val array = json.parseToJsonElement(content).jsonArray
                    val entries = array.map { el ->
                        val obj = el.jsonObject
                        FoodEntry(
                            id = obj["id"]?.jsonPrimitive?.content ?: java.util.UUID.randomUUID().toString(),
                            foodId = obj["food_id"]?.jsonPrimitive?.content,
                            foodName = obj["food_name"]?.jsonPrimitive?.content ?: "Food Item",
                            brandName = obj["brand_name"]?.jsonPrimitive?.content,
                            mealTypeId = obj["meal_type_id"]?.jsonPrimitive?.content ?: "snacks",
                            mealName = obj["meal_type"]?.jsonPrimitive?.content ?: "Meal",
                            quantity = obj["quantity"]?.jsonPrimitive?.floatOrNull ?: 1f,
                            unit = obj["unit"]?.jsonPrimitive?.content ?: "g",
                            calories = obj["calories"]?.jsonPrimitive?.floatOrNull ?: 0f,
                            protein = obj["protein"]?.jsonPrimitive?.floatOrNull ?: 0f,
                            carbs = obj["carbs"]?.jsonPrimitive?.floatOrNull ?: 0f,
                            fat = obj["fat"]?.jsonPrimitive?.floatOrNull ?: 0f,
                            entryDate = date,
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
            val payload = buildString {
                append("{")
                append("\"id\":\"${entry.id}\",")
                append("\"meal_type_id\":\"${entry.mealTypeId}\",")
                append("\"quantity\":${entry.quantity},")
                append("\"unit\":\"${entry.unit}\",")
                append("\"entry_date\":\"${entry.entryDate}\",")
                entry.entryTime?.let { append("\"entry_time\":\"$it\",") }
                entry.foodId?.let { append("\"food_id\":\"$it\",") }
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
                    Result.failure(IOException("Create food entry failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
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
