package com.bytelog.app.data.remote

import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.recipes.HouseholdServings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import okhttp3.Request
import java.net.URLEncoder

data class ExternalProviderInfo(
    val id: String,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val supportsBarcode: Boolean
)

data class ExternalFoodResult(
    val item: FoodItem,
    val providerType: String,
    val providerId: String?,
    val externalId: String,
    val needsDetailFetch: Boolean,
    val imageUrl: String?,
    val caffeineMg: Float?,
    val recipeServings: Float?
)

/**
 * Client for Sparky Fitness Server's v2 external food providers
 * (USDA, FatSecret, Mealie, Tandoor, Yazio, Norish, SwissFood, Canadian Nutrient File, etc.).
 */
class SparkyExternalProviders(private val api: SparkyApiClient) {

    /**
     * Fetches active food and recipe providers configured on the connected Sparky server.
     */
    suspend fun listActiveFoodProviders(): List<ExternalProviderInfo> = withContext(Dispatchers.IO) {
        val baseUrl = api.currentBaseUrl().trimEnd('/')
        if (baseUrl.isBlank()) return@withContext emptyList()

        val req = Request.Builder()
            .url("$baseUrl/api/external-providers")
            .get()
            .build()

        try {
            api.httpClient().newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext emptyList()

                val root = api.jsonCodec().parseToJsonElement(body)
                val array = extractArray(root, listOf("providers", "data", "items")) ?: return@withContext emptyList()

                array.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val id = obj.str("id") ?: return@mapNotNull null
                    val rawType = (obj.str("provider_type") ?: obj.str("type") ?: return@mapNotNull null).lowercase()
                    val isActive = obj.bool("is_active") ?: obj.bool("isActive") ?: false
                    if (!isActive || rawType !in FOOD_PROVIDER_TYPES) return@mapNotNull null

                    val name = obj.str("provider_name")
                        ?: obj.str("name")
                        ?: obj.str("display_name")
                        ?: defaultDisplayName(rawType)
                    val supportsBarcode = obj.bool("supports_barcode")
                        ?: obj.bool("supportsBarcode")
                        ?: (rawType in BARCODE_PROVIDER_TYPES)

                    ExternalProviderInfo(
                        id = id,
                        name = name,
                        type = rawType,
                        isActive = true,
                        supportsBarcode = supportsBarcode
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Searches a specific external provider through Sparky's v2 normalized search endpoint.
     * Note: `usda` and `fatsecret` search results return summary rows and set `needsDetailFetch = true`.
     */
    suspend fun searchProvider(
        providerType: String,
        query: String,
        providerId: String? = null,
        page: Int = 1,
        pageSize: Int = 20
    ): List<ExternalFoodResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val cleanType = providerType.trim().lowercase()
        val baseUrl = api.currentBaseUrl().trimEnd('/')
        if (cleanQuery.isBlank() || cleanType.isBlank() || baseUrl.isBlank()) {
            return@withContext emptyList()
        }

        val url = buildString {
            append(baseUrl)
            append("/api/v2/foods/search/")
            append(encode(cleanType))
            append("?query=").append(encode(cleanQuery))
            append("&page=").append(page.coerceAtLeast(1))
            append("&pageSize=").append(pageSize.coerceIn(1, 100))
            append("&autoScale=true")
            if (!providerId.isNullOrBlank()) {
                append("&providerId=").append(encode(providerId))
            }
        }

        val req = Request.Builder().url(url).get().build()
        try {
            api.httpClient().newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext emptyList()

                val root = api.jsonCodec().parseToJsonElement(body)
                val array = extractArray(root, listOf("foods", "results", "items", "data"))
                    ?: return@withContext emptyList()

                val requiresDetail = cleanType in DETAIL_FETCH_PROVIDERS
                array.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    parseNormalizedFood(
                        obj = obj,
                        fallbackProviderType = cleanType,
                        fallbackProviderId = providerId,
                        needsDetailFetch = requiresDetail
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Fetches full nutritional details, variants, serving sizes, and caffeine for an external food item.
     */
    suspend fun fetchFoodDetail(
        providerType: String,
        externalId: String,
        providerId: String? = null
    ): ExternalFoodResult? = withContext(Dispatchers.IO) {
        val cleanType = providerType.trim().lowercase()
        val cleanExtId = externalId.trim()
        val baseUrl = api.currentBaseUrl().trimEnd('/')
        if (cleanType.isBlank() || cleanExtId.isBlank() || baseUrl.isBlank()) {
            return@withContext null
        }

        val url = buildString {
            append(baseUrl)
            append("/api/v2/foods/details/")
            append(encode(cleanType))
            append('/')
            append(encode(cleanExtId))
            if (!providerId.isNullOrBlank()) {
                append("?providerId=").append(encode(providerId))
            }
        }

        val req = Request.Builder().url(url).get().build()
        try {
            api.httpClient().newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext null

                val root = api.jsonCodec().parseToJsonElement(body)
                val obj = unwrapFoodObject(root) ?: return@withContext null
                parseNormalizedFood(
                    obj = obj,
                    fallbackProviderType = cleanType,
                    fallbackProviderId = providerId,
                    needsDetailFetch = false,
                    fallbackExternalId = cleanExtId
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Looks up a barcode across Sparky's v2 barcode endpoint (optionally targeting a specific providerId).
     */
    suspend fun lookupBarcodeV2(
        barcode: String,
        providerId: String? = null
    ): ExternalFoodResult? = withContext(Dispatchers.IO) {
        val cleanBarcode = barcode.trim()
        val baseUrl = api.currentBaseUrl().trimEnd('/')
        if (cleanBarcode.isBlank() || baseUrl.isBlank()) return@withContext null

        val url = buildString {
            append(baseUrl)
            append("/api/v2/foods/barcode/")
            append(encode(cleanBarcode))
            if (!providerId.isNullOrBlank()) {
                append("?providerId=").append(encode(providerId))
            }
        }

        val req = Request.Builder().url(url).get().build()
        try {
            api.httpClient().newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext null

                val root = api.jsonCodec().parseToJsonElement(body)
                val obj = unwrapFoodObject(root) ?: return@withContext null
                parseNormalizedFood(
                    obj = obj,
                    fallbackProviderType = obj.str("provider_type")?.lowercase() ?: "barcode",
                    fallbackProviderId = providerId,
                    needsDetailFetch = false,
                    fallbackBarcode = cleanBarcode
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Searches USDA, FatSecret, and all other enabled food providers in parallel and merges results cleanly.
     */
    suspend fun searchAllConfiguredProviders(
        query: String,
        enabledProviders: List<ExternalProviderInfo>
    ): List<ExternalFoodResult> = coroutineScope {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank() || enabledProviders.isEmpty()) return@coroutineScope emptyList()

        val active = enabledProviders
            .filter { it.isActive && it.type.lowercase() in FOOD_PROVIDER_TYPES }
            .distinctBy { "${it.type.lowercase()}:${it.id}" }

        if (active.isEmpty()) return@coroutineScope emptyList()

        val deferred = active.map { provider ->
            async(Dispatchers.IO) {
                runCatching {
                    searchProvider(
                        providerType = provider.type,
                        query = cleanQuery,
                        providerId = provider.id,
                        page = 1,
                        pageSize = 20
                    )
                }.getOrDefault(emptyList())
            }
        }

        val all = deferred.awaitAll().flatten()
        val seen = LinkedHashSet<String>()
        val merged = ArrayList<ExternalFoodResult>(all.size)
        for (res in all) {
            val key = "${res.providerType.lowercase()}:${res.externalId.ifBlank { res.item.id }}"
            if (seen.add(key)) {
                merged.add(res)
            }
        }
        merged
    }

    private fun parseNormalizedFood(
        obj: JsonObject,
        fallbackProviderType: String,
        fallbackProviderId: String?,
        needsDetailFetch: Boolean,
        fallbackExternalId: String? = null,
        fallbackBarcode: String? = null
    ): ExternalFoodResult? {
        val name = obj.str("name") ?: obj.str("food_name") ?: return null
        val providerType = (obj.str("provider_type") ?: fallbackProviderType).lowercase()
        val providerId = obj.str("provider_id") ?: fallbackProviderId
        val externalId = obj.str("provider_external_id")
            ?: obj.str("external_id")
            ?: obj.str("fdc_id")
            ?: fallbackExternalId
            ?: obj.str("id")
            ?: return null

        val defaultVariant = (obj["default_variant"] as? JsonObject) ?: obj
        val variants = (obj["variants"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

        val servingSize = defaultVariant.num("serving_size")
            ?: obj.num("serving_size")
            ?: 100f
        val servingUnit = defaultVariant.str("serving_unit")
            ?: obj.str("serving_unit")
            ?: "g"
        val calories = defaultVariant.num("calories") ?: obj.num("calories") ?: 0f
        val protein = defaultVariant.num("protein") ?: obj.num("protein") ?: 0f
        val carbs = defaultVariant.num("carbs") ?: obj.num("carbs") ?: 0f
        val fat = defaultVariant.num("fat") ?: obj.num("fat") ?: 0f
        val fiber = defaultVariant.num("dietary_fiber")
            ?: obj.num("dietary_fiber")
            ?: defaultVariant.num("fiber")
            ?: obj.num("fiber")
            ?: 0f
        val caffeineMg = defaultVariant.num("caffeine_mg")
            ?: obj.num("caffeine_mg")
            ?: defaultVariant.num("caffeine")
            ?: obj.num("caffeine")
        val recipeServings = obj.num("recipe_servings")
            ?: obj.num("servings")
            ?: defaultVariant.num("recipe_servings")
        val imageUrl = obj.str("image_url")
            ?: obj.str("image")
            ?: defaultVariant.str("image_url")
        val brand = obj.str("brand") ?: obj.str("brand_name")
        val barcode = obj.str("barcode") ?: fallbackBarcode

        val households = SparkyFood.householdsFrom(defaultVariant, variants, name)
        val foodId = obj.str("id")?.takeIf { it.isNotBlank() }
            ?: "ext_${providerType}_$externalId"

        val item = FoodItem(
            id = foodId,
            name = name,
            brand = brand,
            barcode = barcode,
            servingSize = if (servingSize > 0f) servingSize else 100f,
            servingUnit = servingUnit,
            calories = calories,
            protein = protein,
            carbs = carbs,
            fat = fat,
            fiber = fiber,
            householdServings = HouseholdServings.encode(households)
        )

        return ExternalFoodResult(
            item = item,
            providerType = providerType,
            providerId = providerId,
            externalId = externalId,
            needsDetailFetch = needsDetailFetch,
            imageUrl = imageUrl,
            caffeineMg = caffeineMg,
            recipeServings = recipeServings
        )
    }

    private fun extractArray(root: JsonElement, keys: List<String>): JsonArray? {
        if (root is JsonArray) return root
        if (root is JsonObject) {
            for (k in keys) {
                val child = root[k]
                if (child is JsonArray) return child
                if (child is JsonObject) {
                    for (inner in keys) {
                        val nested = child[inner]
                        if (nested is JsonArray) return nested
                    }
                }
            }
        }
        return null
    }

    private fun unwrapFoodObject(root: JsonElement): JsonObject? {
        if (root is JsonObject) {
            (root["food"] as? JsonObject)?.let { return it }
            (root["data"] as? JsonObject)?.let { dataObj ->
                (dataObj["food"] as? JsonObject)?.let { return it }
                return dataObj
            }
            (root["foods"] as? JsonArray)?.firstOrNull()?.let { first ->
                if (first is JsonObject) return first
            }
            return root
        }
        if (root is JsonArray) {
            return root.firstOrNull() as? JsonObject
        }
        return null
    }

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

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    private fun defaultDisplayName(type: String): String = when (type) {
        "usda" -> "USDA FoodData Central"
        "fatsecret" -> "FatSecret"
        "mealie" -> "Mealie"
        "tandoor" -> "Tandoor"
        "yazio" -> "Yazio"
        "norish" -> "Norish"
        "swissfood" -> "Swiss Food Composition"
        "canadian-nutrient-file" -> "Canadian Nutrient File"
        "livsmedelsverket" -> "Livsmedelsverket"
        "cooktool" -> "CookTool"
        "nutrientgraph" -> "NutrientGraph"
        "food-info" -> "Food Info"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    companion object {
        val FOOD_PROVIDER_TYPES: Set<String> = setOf(
            "usda",
            "fatsecret",
            "mealie",
            "tandoor",
            "yazio",
            "norish",
            "swissfood",
            "canadian-nutrient-file",
            "livsmedelsverket",
            "cooktool",
            "nutrientgraph",
            "food-info"
        )

        val DETAIL_FETCH_PROVIDERS: Set<String> = setOf("usda", "fatsecret")

        private val BARCODE_PROVIDER_TYPES: Set<String> = setOf(
            "usda",
            "fatsecret",
            "yazio",
            "food-info",
            "openfoodfacts"
        )
    }
}
