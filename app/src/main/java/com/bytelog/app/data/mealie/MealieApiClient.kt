package com.bytelog.app.data.mealie

import com.bytelog.app.data.remote.SparkyApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Direct Mealie v1/v2 REST API client plus Sparky-proxied Mealie recipe-import writeback helpers.
 */
class MealieApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun cleanBase(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    private fun authedRequest(url: String, token: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${token.trim()}")

    /**
     * Tests reachability (`GET /api/app/about`) and authentication (`GET /api/users/self`).
     */
    suspend fun testConnection(baseUrl: String, token: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = cleanBase(baseUrl)
            require(base.startsWith("http://") || base.startsWith("https://")) {
                "Enter a valid http:// or https:// Mealie URL."
            }
            require(token.isNotBlank()) { "API token is required." }

            var version: String? = null
            val aboutReq = Request.Builder()
                .url("$base/api/app/about")
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(aboutReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = runCatching { json.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonObject }.getOrNull()
                    version = obj?.str("version")
                }
            }

            val selfReq = authedRequest("$base/api/users/self", token).get().build()
            httpClient.newCall(selfReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("Mealie authentication failed (HTTP ${resp.code}). Check your API token.")
                }
                val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                val username = obj?.str("username") ?: obj?.str("fullName") ?: obj?.str("email") ?: "connected"
                val vSuffix = version?.let { " · Mealie v$it" }.orEmpty()
                "Connected as $username$vSuffix"
            }
        }
    }

    /**
     * Searches or lists recipes from Mealie (`GET /api/recipes`).
     */
    suspend fun searchRecipes(
        baseUrl: String,
        token: String,
        query: String = "",
        page: Int = 1,
        perPage: Int = 30
    ): Result<List<MealieRecipeSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = cleanBase(baseUrl)
            val url = buildString {
                append(base).append("/api/recipes?page=").append(page.coerceAtLeast(1))
                append("&perPage=").append(perPage.coerceIn(1, 100))
                if (query.isNotBlank()) {
                    append("&search=").append(encode(query.trim()))
                }
            }
            val req = authedRequest(url, token).get().build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Mealie recipes request failed (HTTP ${resp.code})")
                val root = json.parseToJsonElement(body)
                val items = extractArray(root, listOf("items", "recipes", "data")) ?: return@runCatching emptyList()
                items.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    parseRecipeSummary(base, obj)
                }
            }
        }
    }

    /**
     * Fetches full recipe details including ingredients, instructions, and nutrition (`GET /api/recipes/{slug}`).
     */
    suspend fun getRecipe(baseUrl: String, token: String, slug: String): Result<MealieRecipeDetail> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = cleanBase(baseUrl)
                val req = authedRequest("$base/api/recipes/${encode(slug.trim())}", token).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IOException("Failed to fetch Mealie recipe '$slug' (HTTP ${resp.code})")
                    val obj = json.parseToJsonElement(body) as? JsonObject
                        ?: throw IOException("Unexpected Mealie recipe payload")
                    parseRecipeDetail(base, obj)
                }
            }
        }

    /**
     * Imports a recipe URL into Mealie via its web scraper (`POST /api/recipes/create/url`).
     * Returns the created recipe slug.
     */
    suspend fun createRecipeFromUrl(baseUrl: String, token: String, url: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = cleanBase(baseUrl)
                val payload = buildJsonObject {
                    put("url", url.trim())
                    put("includeTags", true)
                }.toString()
                val req = authedRequest("$base/api/recipes/create/url", token)
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty().trim()
                    if (!resp.isSuccessful) {
                        throw IOException("Mealie URL import failed (HTTP ${resp.code}): ${body.take(180)}")
                    }
                    // Mealie returns a quoted JSON string slug (e.g. "chicken-tikka-masala") or an object with slug
                    val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
                    when (parsed) {
                        is JsonPrimitive -> parsed.contentOrNull?.trim('"').orEmpty()
                        is JsonObject -> parsed.str("slug") ?: parsed.str("id") ?: body.trim('"')
                        else -> body.trim('"')
                    }.ifBlank { throw IOException("Mealie did not return a recipe slug.") }
                }
            }
        }

    /**
     * Updates nutrition on a Mealie recipe (`PATCH /api/recipes/{slug}`).
     */
    suspend fun patchRecipeNutrition(
        baseUrl: String,
        token: String,
        slug: String,
        calories: Float?,
        protein: Float?,
        carbs: Float?,
        fat: Float?,
        fiber: Float? = null,
        sodium: Float? = null,
        sugar: Float? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val base = cleanBase(baseUrl)
            val payload = buildJsonObject {
                put("nutrition", buildJsonObject {
                    calories?.let { put("calories", formatMacro(it)) }
                    protein?.let { put("proteinContent", formatMacro(it)) }
                    carbs?.let { put("carbohydrateContent", formatMacro(it)) }
                    fat?.let { put("fatContent", formatMacro(it)) }
                    fiber?.let { put("fiberContent", formatMacro(it)) }
                    sodium?.let { put("sodiumContent", formatMacro(it)) }
                    sugar?.let { put("sugarContent", formatMacro(it)) }
                })
            }.toString()
            val req = authedRequest("$base/api/recipes/${encode(slug.trim())}", token)
                .patch(payload.toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Failed to patch Mealie nutrition (HTTP ${resp.code})")
                }
                true
            }
        }
    }

    /**
     * Fetches Mealie household meal plans between [startDate] and [endDate] (`YYYY-MM-DD`).
     */
    suspend fun getMealPlans(
        baseUrl: String,
        token: String,
        startDate: String,
        endDate: String
    ): Result<List<MealieMealPlanEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = cleanBase(baseUrl)
            val url = "$base/api/households/mealplans?start_date=${encode(startDate)}&end_date=${encode(endDate)}&perPage=200"
            val req = authedRequest(url, token).get().build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Failed to fetch Mealie meal plans (HTTP ${resp.code})")
                val root = json.parseToJsonElement(body)
                val items = extractArray(root, listOf("items", "data")) ?: return@runCatching emptyList()
                items.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    parseMealPlanEntry(obj)
                }.sortedWith(compareBy({ it.date }, { mealOrder(it.entryType) }))
            }
        }
    }

    /**
     * Creates a new Mealie meal plan entry (`POST /api/households/mealplans`).
     */
    suspend fun createMealPlan(
        baseUrl: String,
        token: String,
        date: String,
        entryType: String,
        recipeId: String? = null,
        title: String? = null,
        text: String? = null
    ): Result<MealieMealPlanEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val base = cleanBase(baseUrl)
            val payload = buildJsonObject {
                put("date", date.take(10))
                put("entryType", normalizeEntryType(entryType))
                if (!recipeId.isNullOrBlank()) put("recipeId", recipeId)
                put("title", title?.trim().orEmpty())
                put("text", text?.trim().orEmpty())
            }.toString()
            val req = authedRequest("$base/api/households/mealplans", token)
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Failed to create Mealie meal plan (HTTP ${resp.code})")
                val obj = json.parseToJsonElement(body) as? JsonObject
                    ?: throw IOException("Unexpected Mealie meal plan response")
                parseMealPlanEntry(obj) ?: MealieMealPlanEntry(
                    id = obj.str("id").orEmpty(),
                    date = date.take(10),
                    entryType = normalizeEntryType(entryType),
                    title = title.orEmpty(),
                    text = text,
                    recipeId = recipeId
                )
            }
        }
    }

    /**
     * Deletes a Mealie meal plan entry (`DELETE /api/households/mealplans/{id}`).
     */
    suspend fun deleteMealPlan(baseUrl: String, token: String, id: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = cleanBase(baseUrl)
                val req = authedRequest("$base/api/households/mealplans/${encode(id)}", token)
                    .delete()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Failed to delete Mealie meal plan (HTTP ${resp.code})")
                    true
                }
            }
        }

    /**
     * Lists household shopping lists (`GET /api/households/shopping/lists`).
     */
    suspend fun getShoppingLists(baseUrl: String, token: String): Result<List<MealieShoppingList>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = cleanBase(baseUrl)
                val req = authedRequest("$base/api/households/shopping/lists?perPage=100", token).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IOException("Failed to fetch Mealie shopping lists (HTTP ${resp.code})")
                    val root = json.parseToJsonElement(body)
                    val items = extractArray(root, listOf("items", "data")) ?: return@runCatching emptyList()
                    items.mapNotNull { el ->
                        val obj = el as? JsonObject ?: return@mapNotNull null
                        val id = obj.str("id") ?: return@mapNotNull null
                        val name = obj.str("name") ?: "Shopping List"
                        val listItems = obj["listItems"] as? JsonArray
                        MealieShoppingList(
                            id = id,
                            name = name,
                            itemCount = listItems?.size ?: (obj.intVal("itemCount") ?: 0)
                        )
                    }
                }
            }
        }

    /**
     * Fetches shopping items for a given Mealie list (`GET /api/households/shopping/items?queryFilter=shoppingListId=...`).
     */
    suspend fun getShoppingItems(
        baseUrl: String,
        token: String,
        listId: String
    ): Result<List<MealieShoppingItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = cleanBase(baseUrl)
            val filter = encode("shoppingListId=$listId")
            val req = authedRequest("$base/api/households/shopping/items?queryFilter=$filter&perPage=500", token)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Failed to fetch Mealie shopping items (HTTP ${resp.code})")
                val root = json.parseToJsonElement(body)
                val items = extractArray(root, listOf("items", "data")) ?: return@runCatching emptyList()
                items.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    parseShoppingItem(obj, listId)
                }.sortedWith(compareBy({ it.checked }, { it.position }))
            }
        }
    }

    /**
     * Adds an item to a Mealie shopping list (`POST /api/households/shopping/items`).
     */
    suspend fun addShoppingItem(
        baseUrl: String,
        token: String,
        listId: String,
        note: String,
        quantity: Float = 1f
    ): Result<MealieShoppingItem> = withContext(Dispatchers.IO) {
        runCatching {
            val base = cleanBase(baseUrl)
            val payload = buildJsonObject {
                put("shoppingListId", listId)
                put("note", note.trim())
                put("quantity", quantity.coerceAtLeast(0.01f))
                put("checked", false)
                put("isFood", false)
            }.toString()
            val req = authedRequest("$base/api/households/shopping/items", token)
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Failed to add shopping item (HTTP ${resp.code})")
                val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
                val obj = when (root) {
                    is JsonObject -> (root["createdItems"] as? JsonArray)?.firstOrNull() as? JsonObject ?: root
                    else -> null
                }
                obj?.let { parseShoppingItem(it, listId) } ?: MealieShoppingItem(
                    id = java.util.UUID.randomUUID().toString(),
                    listId = listId,
                    note = note.trim(),
                    display = note.trim(),
                    quantity = quantity,
                    checked = false
                )
            }
        }
    }

    /**
     * Toggles a Mealie shopping item's checked state (`PUT /api/households/shopping/items/{id}`).
     */
    suspend fun toggleShoppingItem(
        baseUrl: String,
        token: String,
        itemId: String,
        checked: Boolean,
        listId: String? = null,
        note: String? = null,
        quantity: Float = 1f
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val base = cleanBase(baseUrl)
            val payload = buildJsonObject {
                put("id", itemId)
                if (!listId.isNullOrBlank()) put("shoppingListId", listId)
                if (note != null) put("note", note)
                put("quantity", quantity)
                put("checked", checked)
            }.toString()
            val req = authedRequest("$base/api/households/shopping/items/${encode(itemId)}", token)
                .put(payload.toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Failed to update shopping item (HTTP ${resp.code})")
                true
            }
        }
    }

    /**
     * Adds all ingredients of a Mealie recipe to a Mealie shopping list (`POST /api/households/shopping/lists/{id}/recipe/{recipeId}`).
     */
    suspend fun addRecipeToShoppingList(
        baseUrl: String,
        token: String,
        listId: String,
        recipeId: String,
        scale: Float = 1f
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val base = cleanBase(baseUrl)
            val payload = buildJsonObject {
                put("recipeIncrementQuantity", scale.coerceAtLeast(0.25f))
            }.toString()
            val req = authedRequest(
                "$base/api/households/shopping/lists/${encode(listId)}/recipe/${encode(recipeId)}",
                token
            )
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Failed to add recipe to shopping list (HTTP ${resp.code})")
                true
            }
        }
    }

    // --- Sparky-proxied Mealie Recipe Import & Nutrition Sync-back ---

    /**
     * Previews a Mealie recipe import via Sparky (`POST /api/v2/recipe-imports/mealie/preview`).
     */
    suspend fun sparkyPreviewMealieRecipe(
        sparkyApi: SparkyApiClient,
        slug: String,
        providerId: String? = null
    ): Result<MealieImportPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val base = sparkyApi.currentBaseUrl().trimEnd('/')
            val payload = buildJsonObject {
                put("slug", slug.trim())
                if (!providerId.isNullOrBlank()) put("providerId", providerId)
            }.toString()
            val req = Request.Builder()
                .url("$base/api/v2/recipe-imports/mealie/preview")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            sparkyApi.httpClient().newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Sparky Mealie preview failed (HTTP ${resp.code})")
                val obj = sparkyApi.jsonCodec().parseToJsonElement(body) as? JsonObject
                    ?: throw IOException("Unexpected Sparky preview payload")
                val data = (obj["recipe"] as? JsonObject) ?: (obj["data"] as? JsonObject) ?: obj
                val ingredients = (data["ingredients"] as? JsonArray)?.mapNotNull { el ->
                    when (el) {
                        is JsonPrimitive -> el.contentOrNull
                        is JsonObject -> el.str("display") ?: el.str("note") ?: el.str("name")
                        else -> null
                    }
                }.orEmpty()
                val nutrition = (data["nutrition"] as? JsonObject) ?: data
                MealieImportPreview(
                    name = data.str("name") ?: slug,
                    slug = data.str("slug") ?: slug,
                    servings = data.num("servings") ?: data.num("recipe_servings") ?: 1f,
                    ingredients = ingredients,
                    calories = nutrition.num("calories"),
                    protein = nutrition.num("protein"),
                    carbs = nutrition.num("carbs"),
                    fat = nutrition.num("fat"),
                    rawPayload = body
                )
            }
        }
    }

    /**
     * Saves a previewed Mealie recipe into Sparky (`POST /api/v2/recipe-imports/mealie/save`).
     * Returns the created Sparky meal/template ID.
     */
    suspend fun sparkySaveMealieRecipe(
        sparkyApi: SparkyApiClient,
        payloadJson: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = sparkyApi.currentBaseUrl().trimEnd('/')
            val req = Request.Builder()
                .url("$base/api/v2/recipe-imports/mealie/save")
                .post(payloadJson.toRequestBody(jsonMediaType))
                .build()
            sparkyApi.httpClient().newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Sparky Mealie save failed (HTTP ${resp.code})")
                val obj = runCatching { sparkyApi.jsonCodec().parseToJsonElement(body) as? JsonObject }.getOrNull()
                obj?.str("mealId")
                    ?: obj?.str("id")
                    ?: (obj?.get("meal") as? JsonObject)?.str("id")
                    ?: (obj?.get("data") as? JsonObject)?.str("id")
                    ?: throw IOException("Sparky did not return a saved meal ID.")
            }
        }
    }

    /**
     * Triggers Sparky nutrition writeback to Mealie for a previously imported meal (`POST /api/v2/recipe-imports/{mealId}/sync-nutrition`).
     */
    suspend fun sparkySyncRecipeNutrition(
        sparkyApi: SparkyApiClient,
        mealId: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val base = sparkyApi.currentBaseUrl().trimEnd('/')
            val req = Request.Builder()
                .url("$base/api/v2/recipe-imports/${encode(mealId)}/sync-nutrition")
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            sparkyApi.httpClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Sparky nutrition sync-back failed (HTTP ${resp.code})")
                true
            }
        }
    }

    // --- Parsing helpers ---

    private fun parseRecipeSummary(baseUrl: String, obj: JsonObject): MealieRecipeSummary? {
        val slug = obj.str("slug") ?: return null
        val id = obj.str("id") ?: slug
        val name = obj.str("name") ?: slug
        val nutrition = obj["nutrition"] as? JsonObject
        return MealieRecipeSummary(
            id = id,
            slug = slug,
            name = name,
            description = obj.str("description"),
            servings = parseServings(obj),
            prepTime = obj.str("prepTime"),
            cookTime = obj.str("performTime") ?: obj.str("cookTime"),
            totalTime = obj.str("totalTime"),
            imageUrl = recipeImageUrl(baseUrl, id, obj.str("image")),
            tags = parseNamedList(obj["tags"]),
            categories = parseNamedList(obj["recipeCategory"]),
            calories = nutrition?.macroNum("calories"),
            source = MealieSource.DIRECT
        )
    }

    private fun parseRecipeDetail(baseUrl: String, obj: JsonObject): MealieRecipeDetail {
        val slug = obj.str("slug").orEmpty()
        val id = obj.str("id") ?: slug
        val ingredients = (obj["recipeIngredient"] as? JsonArray)?.mapNotNull { el ->
            val ing = el as? JsonObject ?: return@mapNotNull null
            val unitObj = ing["unit"] as? JsonObject
            val foodObj = ing["food"] as? JsonObject
            val qty = ing.num("quantity")
            val unit = unitObj?.str("name") ?: ing.str("unit")
            val food = foodObj?.str("name") ?: ing.str("food")
            val note = ing.str("note")
            val display = ing.str("display")
                ?: listOfNotNull(
                    qty?.takeIf { it > 0f }?.let { formatMacro(it) },
                    unit,
                    food,
                    note
                ).joinToString(" ").trim()
            if (display.isBlank()) return@mapNotNull null
            MealieIngredient(
                display = display,
                quantity = qty,
                unit = unit,
                food = food,
                note = note,
                title = ing.str("title")
            )
        }.orEmpty()

        val instructions = (obj["recipeInstructions"] as? JsonArray)?.mapNotNull { el ->
            val step = el as? JsonObject ?: return@mapNotNull null
            val text = step.str("text") ?: return@mapNotNull null
            MealieInstruction(
                title = step.str("title"),
                text = text
            )
        }.orEmpty()

        val nutritionObj = obj["nutrition"] as? JsonObject
        val nutrition = nutritionObj?.let { n ->
            MealieNutrition(
                calories = n.macroNum("calories"),
                protein = n.macroNum("proteinContent") ?: n.macroNum("protein"),
                carbs = n.macroNum("carbohydrateContent") ?: n.macroNum("carbs"),
                fat = n.macroNum("fatContent") ?: n.macroNum("fat"),
                fiber = n.macroNum("fiberContent") ?: n.macroNum("fiber"),
                sodium = n.macroNum("sodiumContent") ?: n.macroNum("sodium"),
                sugar = n.macroNum("sugarContent") ?: n.macroNum("sugar")
            ).takeIf { it.hasAny }
        }

        val notes = (obj["notes"] as? JsonArray)?.mapNotNull { el ->
            val n = el as? JsonObject ?: return@mapNotNull null
            val title = n.str("title")
            val text = n.str("text") ?: return@mapNotNull null
            if (!title.isNullOrBlank()) "$title: $text" else text
        }.orEmpty()

        return MealieRecipeDetail(
            id = id,
            slug = slug,
            name = obj.str("name") ?: slug,
            description = obj.str("description"),
            servings = parseServings(obj),
            prepTime = obj.str("prepTime"),
            cookTime = obj.str("performTime") ?: obj.str("cookTime"),
            totalTime = obj.str("totalTime"),
            imageUrl = recipeImageUrl(baseUrl, id, obj.str("image")),
            originalUrl = obj.str("orgURL"),
            ingredients = ingredients,
            instructions = instructions,
            nutrition = nutrition,
            notes = notes,
            tags = parseNamedList(obj["tags"]),
            source = MealieSource.DIRECT
        )
    }

    private fun parseMealPlanEntry(obj: JsonObject): MealieMealPlanEntry? {
        val id = obj.str("id") ?: (obj["id"] as? JsonPrimitive)?.intOrNull?.toString() ?: return null
        val date = obj.str("date")?.take(10) ?: return null
        val recipe = obj["recipe"] as? JsonObject
        return MealieMealPlanEntry(
            id = id,
            date = date,
            entryType = (obj.str("entryType") ?: "dinner").lowercase(),
            title = obj.str("title").orEmpty(),
            text = obj.str("text"),
            recipeId = obj.str("recipeId") ?: recipe?.str("id"),
            recipeSlug = recipe?.str("slug"),
            recipeName = recipe?.str("name")
        )
    }

    private fun parseShoppingItem(obj: JsonObject, fallbackListId: String): MealieShoppingItem? {
        val id = obj.str("id") ?: return null
        val foodObj = obj["food"] as? JsonObject
        val unitObj = obj["unit"] as? JsonObject
        val labelObj = obj["label"] as? JsonObject
        return MealieShoppingItem(
            id = id,
            listId = obj.str("shoppingListId") ?: fallbackListId,
            note = obj.str("note").orEmpty(),
            display = obj.str("display").orEmpty(),
            quantity = obj.num("quantity") ?: 1f,
            checked = obj.bool("checked") ?: false,
            position = obj.intVal("position") ?: 0,
            foodName = foodObj?.str("name"),
            unitName = unitObj?.str("name"),
            labelName = labelObj?.str("name")
        )
    }

    private fun parseServings(obj: JsonObject): Float {
        obj.num("recipeServings")?.takeIf { it > 0f }?.let { return it }
        obj.num("recipeYieldQuantity")?.takeIf { it > 0f }?.let { return it }
        val yieldStr = obj.str("recipeYield")
        if (!yieldStr.isNullOrBlank()) {
            NUMBER_REGEX.find(yieldStr)?.value?.toFloatOrNull()?.takeIf { it > 0f }?.let { return it }
        }
        return 1f
    }

    private fun recipeImageUrl(baseUrl: String, recipeId: String, image: String?): String? {
        if (recipeId.isBlank()) return null
        if (!image.isNullOrBlank() && (image.startsWith("http://") || image.startsWith("https://"))) {
            return image
        }
        return "$baseUrl/api/media/recipes/$recipeId/images/min-original.webp"
    }

    private fun parseNamedList(el: JsonElement?): List<String> =
        (el as? JsonArray)?.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }
                is JsonObject -> item.str("name") ?: item.str("slug")
                else -> null
            }
        }.orEmpty()

    private fun extractArray(root: JsonElement, keys: List<String>): JsonArray? {
        if (root is JsonArray) return root
        if (root is JsonObject) {
            for (k in keys) {
                (root[k] as? JsonArray)?.let { return it }
            }
        }
        return null
    }

    private fun normalizeEntryType(raw: String): String = when (raw.trim().lowercase()) {
        "breakfast" -> "breakfast"
        "lunch" -> "lunch"
        "dinner", "supper" -> "dinner"
        "side", "sides" -> "side"
        "snack", "snacks" -> "snack"
        else -> "dinner"
    }

    private fun mealOrder(entryType: String): Int = when (entryType.lowercase()) {
        "breakfast" -> 0
        "lunch" -> 1
        "dinner" -> 2
        "side" -> 3
        "snack" -> 4
        else -> 5
    }

    private fun formatMacro(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(value)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    private fun JsonObject.num(key: String): Float? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.let { it.floatOrNull ?: it.contentOrNull?.toFloatOrNull() }

    private fun JsonObject.macroNum(key: String): Float? {
        val prim = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
        prim.floatOrNull?.let { return it }
        val text = prim.contentOrNull?.trim() ?: return null
        return NUMBER_REGEX.find(text)?.value?.toFloatOrNull()
    }

    private fun JsonObject.intVal(key: String): Int? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    companion object {
        private val NUMBER_REGEX = Regex("""\d+(?:\.\d+)?""")
    }
}
