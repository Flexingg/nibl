package com.bytelog.app.data.mealie

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bytelog.app.data.local.model.RecipeDetails
import com.bytelog.app.data.local.model.TemplateIngredient
import com.bytelog.app.data.remote.ExternalProviderInfo
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.remote.SparkyExternalProviders
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.MealPlanDraft
import com.bytelog.app.data.repository.MealTemplateRepository
import com.bytelog.app.data.repository.PlanItem
import com.bytelog.app.data.repository.PlanRepository
import com.bytelog.app.data.repository.RecipeDraft
import com.bytelog.app.data.repository.RecipeRepository
import com.bytelog.app.data.repository.ShoppingList
import com.bytelog.app.data.repository.dataStore
import com.bytelog.app.recipes.RecipeStep
import com.bytelog.app.recipes.SiteNutrition
import com.bytelog.app.recipes.TimerDetector
import com.bytelog.app.research.KeystoreSecretCipher
import com.bytelog.app.research.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDate

/**
 * Coordinates Direct Mealie instance access (URL + Keystore-encrypted API token in DataStore)
 * with Sparky-proxied Mealie fallback, Nibl's Recipe/Cook Mode library, Meal Planner, and Shopping List.
 */
class MealieRepository(
    private val context: Context,
    private val mealieApi: MealieApiClient,
    private val sparkyApi: SparkyApiClient,
    private val externalProviders: SparkyExternalProviders,
    private val recipeRepository: RecipeRepository,
    private val mealTemplateRepository: MealTemplateRepository,
    private val planRepository: PlanRepository,
    private val foodRepository: FoodRepository,
    private val cipher: SecretCipher = KeystoreSecretCipher()
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    val config: Flow<MealieConfig> = context.dataStore.data.map { prefs ->
        val url = prefs[KEY_MEALIE_URL].orEmpty()
        val encrypted = prefs[KEY_MEALIE_TOKEN]
        val token = encrypted?.let { cipher.decrypt(it) }.orEmpty()
        val defaultListId = prefs[KEY_MEALIE_SHOPPING_LIST_ID]
        MealieConfig(
            baseUrl = url,
            apiToken = token,
            defaultShoppingListId = defaultListId
        )
    }

    suspend fun currentConfig(): MealieConfig = config.first()

    suspend fun saveConfig(baseUrl: String, apiToken: String) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        val cleanToken = apiToken.trim()
        context.dataStore.edit { prefs ->
            if (cleanUrl.isBlank()) prefs.remove(KEY_MEALIE_URL) else prefs[KEY_MEALIE_URL] = cleanUrl
            if (cleanToken.isBlank()) prefs.remove(KEY_MEALIE_TOKEN) else prefs[KEY_MEALIE_TOKEN] = cipher.encrypt(cleanToken)
        }
    }

    suspend fun clearConfig() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_MEALIE_URL)
            prefs.remove(KEY_MEALIE_TOKEN)
            prefs.remove(KEY_MEALIE_SHOPPING_LIST_ID)
        }
    }

    suspend fun saveDefaultShoppingListId(listId: String?) {
        context.dataStore.edit { prefs ->
            if (listId.isNullOrBlank()) prefs.remove(KEY_MEALIE_SHOPPING_LIST_ID)
            else prefs[KEY_MEALIE_SHOPPING_LIST_ID] = listId
        }
    }

    suspend fun testDirectConnection(baseUrl: String, apiToken: String): Result<String> =
        mealieApi.testConnection(baseUrl, apiToken)

    /**
     * Checks whether the connected Sparky Fitness server has an active Mealie external provider configured.
     */
    suspend fun getSparkyMealieProvider(): ExternalProviderInfo? = withContext(Dispatchers.IO) {
        runCatching {
            externalProviders.listActiveFoodProviders()
                .firstOrNull { it.type.equals("mealie", ignoreCase = true) && it.isActive }
        }.getOrNull()
    }

    // --- Recipes ---

    /**
     * Browses/searches Mealie recipes using Direct Mealie when configured,
     * falling back to Sparky's Mealie external provider when Direct Mealie is not set up.
     */
    suspend fun searchRecipes(query: String = "", page: Int = 1, perPage: Int = 30): Result<List<MealieRecipeSummary>> =
        withContext(Dispatchers.IO) {
            val cfg = currentConfig()
            if (cfg.isDirectConfigured) {
                return@withContext mealieApi.searchRecipes(cfg.normalizedUrl, cfg.apiToken, query, page, perPage)
            }

            // Fallback to Sparky-configured Mealie provider
            val sparkyMealie = getSparkyMealieProvider()
                ?: return@withContext Result.failure(
                    IOException("Mealie is not connected. Add your Mealie URL & API token in Connect, or enable Mealie on Sparky.")
                )
            runCatching {
                val effectiveQuery = query.trim().ifBlank { "a" }
                val results = externalProviders.searchProvider(
                    providerType = "mealie",
                    query = effectiveQuery,
                    providerId = sparkyMealie.id,
                    page = page,
                    pageSize = perPage
                )
                results.map { res ->
                    MealieRecipeSummary(
                        id = res.externalId,
                        slug = res.externalId,
                        name = res.item.name,
                        servings = res.recipeServings ?: 1f,
                        imageUrl = res.imageUrl,
                        calories = res.item.calories.takeIf { it > 0f },
                        source = MealieSource.SPARKY,
                        sparkyProviderId = sparkyMealie.id
                    )
                }
            }
        }

    suspend fun getRecipeDetail(slug: String, sparkyProviderId: String? = null): Result<MealieRecipeDetail> =
        withContext(Dispatchers.IO) {
            val cfg = currentConfig()
            if (cfg.isDirectConfigured) {
                return@withContext mealieApi.getRecipe(cfg.normalizedUrl, cfg.apiToken, slug)
            }

            // Fallback: use Sparky Mealie preview + food details
            runCatching {
                val preview = mealieApi.sparkyPreviewMealieRecipe(sparkyApi, slug, sparkyProviderId).getOrNull()
                val detail = externalProviders.fetchFoodDetail("mealie", slug, sparkyProviderId)
                if (preview == null && detail == null) {
                    throw IOException("Couldn't fetch Mealie recipe '$slug' via Sparky.")
                }
                val item = detail?.item
                val nutrition = MealieNutrition(
                    calories = preview?.calories ?: item?.calories?.takeIf { it > 0f },
                    protein = preview?.protein ?: item?.protein?.takeIf { it > 0f },
                    carbs = preview?.carbs ?: item?.carbs?.takeIf { it > 0f },
                    fat = preview?.fat ?: item?.fat?.takeIf { it > 0f },
                    fiber = item?.fiber?.takeIf { it > 0f }
                ).takeIf { it.hasAny }

                MealieRecipeDetail(
                    id = detail?.externalId ?: slug,
                    slug = preview?.slug ?: slug,
                    name = preview?.name ?: item?.name ?: slug,
                    servings = preview?.servings ?: detail?.recipeServings ?: 1f,
                    imageUrl = detail?.imageUrl,
                    ingredients = preview?.ingredients.orEmpty().map { MealieIngredient(display = it) },
                    nutrition = nutrition,
                    source = MealieSource.SPARKY
                )
            }
        }

    /**
     * Imports a Mealie recipe into Nibl as a [com.bytelog.app.data.local.model.MealTemplate] + [RecipeDetails]
     * so it opens directly in Nibl's Recipe Detail & Cook Mode.
     * Returns the created Nibl templateId.
     */
    suspend fun importRecipeToNibl(slug: String, sparkyProviderId: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val detail = getRecipeDetail(slug, sparkyProviderId).getOrThrow()
                val rawLines = detail.ingredients.map { it.display }.filter { it.isNotBlank() }
                val steps = detail.instructions.mapNotNull { inst ->
                    val text = inst.text.trim()
                    if (text.isBlank()) null
                    else RecipeStep(
                        text = text,
                        section = inst.title?.takeIf { it.isNotBlank() },
                        timerSeconds = TimerDetector.firstTimerSeconds(text)
                    )
                }
                val prepMinutes = MealieDurationParser.toMinutes(detail.prepTime)
                val cookMinutes = MealieDurationParser.toMinutes(detail.cookTime)
                val servings = detail.servings.takeIf { it > 0f } ?: 1f
                val siteNutrition = detail.nutrition?.let { n ->
                    SiteNutrition(
                        calories = n.calories,
                        protein = n.protein,
                        carbs = n.carbs,
                        fat = n.fat
                    )
                }
                val combinedNotes = detail.notes.joinToString("\n\n").takeIf { it.isNotBlank() }

                val matched = if (rawLines.isNotEmpty()) recipeRepository.matchIngredients(rawLines) else emptyList()
                val hasMatchedFood = matched.any { it.include && it.food != null }

                if (hasMatchedFood) {
                    val draft = RecipeDraft(
                        name = detail.name,
                        description = detail.description,
                        servings = servings,
                        ingredients = matched,
                        steps = steps,
                        prepMinutes = prepMinutes,
                        cookMinutes = cookMinutes,
                        sourceUrl = detail.originalUrl,
                        imageUrl = detail.imageUrl,
                        notes = combinedNotes,
                        rawLines = rawLines,
                        siteNutrition = siteNutrition
                    )
                    return@runCatching recipeRepository.saveRecipe(draft).getOrThrow()
                }

                // Fallback when Mealie ingredients couldn't be matched to foods yet:
                // create a custom food holding the recipe's per-serving nutrition so the template & Cook Mode still work.
                val perServingCal = detail.nutrition?.calories ?: 0f
                val perServingPro = detail.nutrition?.protein ?: 0f
                val perServingCarbs = detail.nutrition?.carbs ?: 0f
                val perServingFat = detail.nutrition?.fat ?: 0f
                val perServingFiber = detail.nutrition?.fiber ?: 0f

                val recipeFood = foodRepository.createCustomFood(
                    name = detail.name,
                    brand = "Mealie",
                    servingSize = 1f,
                    servingUnit = "serving",
                    calories = perServingCal,
                    protein = perServingPro,
                    carbs = perServingCarbs,
                    fat = perServingFat,
                    fiber = perServingFiber
                ).getOrThrow()

                val templateFood = TemplateIngredient(
                    foodId = recipeFood.id,
                    foodName = recipeFood.name,
                    quantity = servings,
                    unit = "serving",
                    calories = perServingCal * servings,
                    protein = perServingPro * servings,
                    carbs = perServingCarbs * servings,
                    fat = perServingFat * servings
                )

                val templateId = mealTemplateRepository.createRecipeTemplate(
                    name = detail.name.trim(),
                    description = detail.description?.take(240),
                    servingSize = 1f,
                    servingUnit = "serving",
                    totalServings = servings,
                    foods = listOf(templateFood),
                    notes = combinedNotes
                ).getOrThrow()

                recipeRepository.saveDetails(
                    RecipeDetails(
                        templateId = templateId,
                        prepTimeMinutes = prepMinutes,
                        cookTimeMinutes = cookMinutes,
                        sourceUrl = detail.originalUrl,
                        imageUrl = detail.imageUrl,
                        stepsJson = RecipeRepository.encodeSteps(steps),
                        notes = combinedNotes,
                        rawIngredientLinesJson = json.encodeToString(ListSerializer(String.serializer()), rawLines),
                        siteNutritionJson = siteNutrition?.let { json.encodeToString(SiteNutrition.serializer(), it) }
                    )
                )
                templateId
            }
        }

    /**
     * Scrapes a recipe URL into the connected Mealie instance (`POST /api/recipes/create/url`).
     */
    suspend fun scrapeUrlToMealie(url: String): Result<String> = withContext(Dispatchers.IO) {
        val cfg = requireDirectConfig().getOrElse { return@withContext Result.failure(it) }
        mealieApi.createRecipeFromUrl(cfg.normalizedUrl, cfg.apiToken, url)
    }

    /**
     * Patches nutrition directly back to Mealie (`PATCH /api/recipes/{slug}`).
     */
    suspend fun syncNutritionToMealie(
        slug: String,
        calories: Float?,
        protein: Float?,
        carbs: Float?,
        fat: Float?,
        fiber: Float? = null,
        sodium: Float? = null,
        sugar: Float? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val cfg = requireDirectConfig().getOrElse { return@withContext Result.failure(it) }
        mealieApi.patchRecipeNutrition(
            baseUrl = cfg.normalizedUrl,
            token = cfg.apiToken,
            slug = slug,
            calories = calories,
            protein = protein,
            carbs = carbs,
            fat = fat,
            fiber = fiber,
            sodium = sodium,
            sugar = sugar
        )
    }

    /**
     * Triggers Sparky's Mealie nutrition sync-back endpoint (`POST /api/v2/recipe-imports/{mealId}/sync-nutrition`).
     */
    suspend fun syncSparkyRecipeNutrition(mealId: String): Result<Boolean> =
        mealieApi.sparkySyncRecipeNutrition(sparkyApi, mealId)

    // --- Meal Plan ---

    suspend fun getMealPlans(startDate: String, endDate: String): Result<List<MealieMealPlanEntry>> =
        withContext(Dispatchers.IO) {
            val cfg = requireDirectConfig().getOrElse { return@withContext Result.failure(it) }
            mealieApi.getMealPlans(cfg.normalizedUrl, cfg.apiToken, startDate, endDate)
        }

    suspend fun createMealPlanEntry(
        date: String,
        entryType: String,
        recipeId: String? = null,
        title: String? = null,
        text: String? = null
    ): Result<MealieMealPlanEntry> = withContext(Dispatchers.IO) {
        val cfg = requireDirectConfig().getOrElse { return@withContext Result.failure(it) }
        mealieApi.createMealPlan(cfg.normalizedUrl, cfg.apiToken, date, entryType, recipeId, title, text)
    }

    suspend fun deleteMealPlanEntry(id: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val cfg = requireDirectConfig().getOrElse { return@withContext Result.failure(it) }
        mealieApi.deleteMealPlan(cfg.normalizedUrl, cfg.apiToken, id)
    }

    /**
     * Pulls Mealie meal plan entries for [startDate]..[endDate] into Nibl's [PlanRepository],
     * skipping entries already planned for the same day/meal/name.
     */
    suspend fun pullMealPlanToNibl(startDate: String, endDate: String): Result<MealieSyncResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cfg = requireDirectConfig().getOrThrow()
                val remote = mealieApi.getMealPlans(cfg.normalizedUrl, cfg.apiToken, startDate, endDate).getOrThrow()
                if (remote.isEmpty()) {
                    return@runCatching MealieSyncResult(
                        success = true,
                        importedCount = 0,
                        message = "No Mealie meals found between $startDate and $endDate."
                    )
                }

                val existing = planRepository.between(startDate, endDate).first()
                val existingKeys = existing.map {
                    "${it.entryDate}|${it.mealName.lowercase()}|${it.foodName.lowercase()}"
                }.toMutableSet()

                val nutritionCache = HashMap<String, MealieNutrition?>()
                val planItems = mutableListOf<PlanItem>()
                var skipped = 0

                for (entry in remote) {
                    val mealName = toNiblMealName(entry.entryType)
                    val foodName = entry.displayTitle
                    val key = "${entry.date}|${mealName.lowercase()}|${foodName.lowercase()}"
                    if (!existingKeys.add(key)) {
                        skipped++
                        continue
                    }

                    val nutrition = entry.recipeSlug?.let { slug ->
                        nutritionCache.getOrPut(slug) {
                            mealieApi.getRecipe(cfg.normalizedUrl, cfg.apiToken, slug).getOrNull()?.nutrition
                        }
                    }

                    planItems += PlanItem(
                        date = entry.date,
                        meal = mealName,
                        food = foodName,
                        brand = "Mealie",
                        quantity = 1f,
                        unit = "serving",
                        calories = nutrition?.calories ?: 0f,
                        protein = nutrition?.protein ?: 0f,
                        carbs = nutrition?.carbs ?: 0f,
                        fat = nutrition?.fat ?: 0f
                    )
                }

                if (planItems.isNotEmpty()) {
                    planRepository.addPlan(MealPlanDraft(planItems))
                }

                MealieSyncResult(
                    success = true,
                    importedCount = planItems.size,
                    skippedCount = skipped,
                    message = if (planItems.isEmpty()) {
                        "All ${remote.size} Mealie meals are already in your Nibl planner."
                    } else {
                        "Imported ${planItems.size} meal${if (planItems.size == 1) "" else "s"} to Nibl planner" +
                            if (skipped > 0) " ($skipped skipped)." else "."
                    }
                )
            }
        }

    /**
     * Pushes Nibl's planned meals for [startDate]..[endDate] to Mealie's household meal plan.
     */
    suspend fun pushNiblPlanToMealie(startDate: String, endDate: String): Result<MealieSyncResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cfg = requireDirectConfig().getOrThrow()
                val localPlanned = planRepository.between(startDate, endDate).first()
                if (localPlanned.isEmpty()) {
                    return@runCatching MealieSyncResult(
                        success = true,
                        pushedCount = 0,
                        message = "No Nibl planned meals found between $startDate and $endDate."
                    )
                }

                val remoteExisting = mealieApi.getMealPlans(cfg.normalizedUrl, cfg.apiToken, startDate, endDate)
                    .getOrDefault(emptyList())
                val remoteKeys = remoteExisting.map {
                    "${it.date}|${it.entryType.lowercase()}|${it.displayTitle.lowercase()}"
                }.toMutableSet()

                var pushed = 0
                var skipped = 0
                for (entry in localPlanned) {
                    val mealieType = toMealieEntryType(entry.mealName)
                    val title = entry.foodName.trim()
                    val key = "${entry.entryDate}|$mealieType|${title.lowercase()}"
                    if (!remoteKeys.add(key)) {
                        skipped++
                        continue
                    }
                    val note = "${formatQty(entry.quantity)} ${entry.unit} · ${entry.calories.toInt()} kcal"
                    val res = mealieApi.createMealPlan(
                        baseUrl = cfg.normalizedUrl,
                        token = cfg.apiToken,
                        date = entry.entryDate,
                        entryType = mealieType,
                        title = title,
                        text = note
                    )
                    if (res.isSuccess) pushed++ else skipped++
                }

                MealieSyncResult(
                    success = true,
                    pushedCount = pushed,
                    skippedCount = skipped,
                    message = if (pushed == 0) {
                        "All Nibl planned meals are already in Mealie."
                    } else {
                        "Pushed $pushed meal${if (pushed == 1) "" else "s"} to Mealie" +
                            if (skipped > 0) " ($skipped skipped)." else "."
                    }
                )
            }
        }

    // --- Shopping Lists ---

    suspend fun getShoppingLists(): Result<List<MealieShoppingList>> = withContext(Dispatchers.IO) {
        val cfg = requireDirectConfig().getOrElse { return@withContext Result.failure(it) }
        mealieApi.getShoppingLists(cfg.normalizedUrl, cfg.apiToken)
    }

    suspend fun pullShoppingList(listId: String): Result<List<MealieShoppingItem>> = withContext(Dispatchers.IO) {
        val cfg = requireDirectConfig().getOrElse { return@withContext Result.failure(it) }
        mealieApi.getShoppingItems(cfg.normalizedUrl, cfg.apiToken, listId)
    }

    suspend fun toggleItem(item: MealieShoppingItem, checked: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        val cfg = requireDirectConfig().getOrElse { return@withContext Result.failure(it) }
        mealieApi.toggleShoppingItem(
            baseUrl = cfg.normalizedUrl,
            token = cfg.apiToken,
            itemId = item.id,
            checked = checked,
            listId = item.listId,
            note = item.note.ifBlank { item.label },
            quantity = item.quantity
        )
    }

    suspend fun addItem(listId: String, note: String, quantity: Float = 1f): Result<MealieShoppingItem> =
        withContext(Dispatchers.IO) {
            val cfg = requireDirectConfig().getOrElse { return@withContext Result.failure(it) }
            mealieApi.addShoppingItem(cfg.normalizedUrl, cfg.apiToken, listId, note, quantity)
        }

    suspend fun addRecipeToShoppingList(listId: String, recipeId: String, scale: Float = 1f): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val cfg = requireDirectConfig().getOrElse { return@withContext Result.failure(it) }
            mealieApi.addRecipeToShoppingList(cfg.normalizedUrl, cfg.apiToken, listId, recipeId, scale)
        }

    /**
     * Builds Nibl's upcoming shopping list for the next [days] days and pushes missing items to a Mealie shopping list.
     */
    suspend fun pushNiblShoppingToMealie(
        listId: String,
        days: Int = 7,
        today: LocalDate = LocalDate.now()
    ): Result<MealieSyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = requireDirectConfig().getOrThrow()
            val start = today.toString()
            val end = today.plusDays((days - 1).coerceAtLeast(0).toLong()).toString()
            val planned = planRepository.between(start, end).first()
            val niblList = ShoppingList.build(planned)
            if (niblList.items.isEmpty()) {
                return@runCatching MealieSyncResult(
                    success = true,
                    pushedCount = 0,
                    message = "Your Nibl shopping list for the next $days days is empty."
                )
            }

            val existing = mealieApi.getShoppingItems(cfg.normalizedUrl, cfg.apiToken, listId).getOrDefault(emptyList())
            val existingLabels = existing
                .filter { !it.checked }
                .map { it.label.lowercase() }
                .toMutableSet()

            var pushed = 0
            var skipped = 0
            for (item in niblList.items) {
                val note = if (item.amountLabel.isNotBlank()) "${item.name} (${item.amountLabel})" else item.name
                val normalized = note.lowercase()
                if (existingLabels.any { it.contains(item.name.lowercase()) } || !existingLabels.add(normalized)) {
                    skipped++
                    continue
                }
                val res = mealieApi.addShoppingItem(cfg.normalizedUrl, cfg.apiToken, listId, note, 1f)
                if (res.isSuccess) pushed++ else skipped++
            }

            MealieSyncResult(
                success = true,
                pushedCount = pushed,
                skippedCount = skipped,
                message = if (pushed == 0) {
                    "All Nibl shopping items are already on this Mealie list."
                } else {
                    "Pushed $pushed item${if (pushed == 1) "" else "s"} to Mealie" +
                        if (skipped > 0) " ($skipped already listed)." else "."
                }
            )
        }
    }

    // --- Helpers ---

    private suspend fun requireDirectConfig(): Result<MealieConfig> {
        val cfg = currentConfig()
        return if (cfg.isDirectConfigured) {
            Result.success(cfg)
        } else {
            Result.failure(IOException("Connect your Mealie instance in the Connect tab first."))
        }
    }

    private fun toNiblMealName(entryType: String): String = when (entryType.lowercase()) {
        "breakfast" -> "Breakfast"
        "lunch" -> "Lunch"
        "dinner" -> "Dinner"
        "side", "snack", "snacks" -> "Snacks"
        else -> "Dinner"
    }

    private fun toMealieEntryType(mealName: String): String = when (mealName.trim().lowercase()) {
        "breakfast" -> "breakfast"
        "lunch" -> "lunch"
        "dinner" -> "dinner"
        "snack", "snacks" -> "snack"
        else -> "dinner"
    }

    private fun formatQty(q: Float): String =
        if (q % 1f == 0f) q.toInt().toString() else "%.1f".format(q)

    companion object {
        val KEY_MEALIE_URL = stringPreferencesKey("mealie_server_url")
        val KEY_MEALIE_TOKEN = stringPreferencesKey("mealie_api_token_enc")
        val KEY_MEALIE_SHOPPING_LIST_ID = stringPreferencesKey("mealie_default_shopping_list_id")
    }
}
