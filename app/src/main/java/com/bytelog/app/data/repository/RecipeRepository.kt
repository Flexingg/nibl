package com.bytelog.app.data.repository

import com.bytelog.app.data.local.db.FoodDao
import com.bytelog.app.data.local.db.IngredientAliasDao
import com.bytelog.app.data.local.db.RecipeDetailsDao
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.IngredientAlias
import com.bytelog.app.data.local.model.MealTemplate
import com.bytelog.app.data.local.model.RecipeDetails
import com.bytelog.app.data.local.model.TemplateIngredient
import com.bytelog.app.data.remote.ChatMessageInput
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.recipes.ImportedRecipe
import com.bytelog.app.recipes.IngredientLineParser
import com.bytelog.app.recipes.IngredientMatch
import com.bytelog.app.recipes.IngredientMatching
import com.bytelog.app.recipes.Macros
import com.bytelog.app.recipes.MatchSource
import com.bytelog.app.recipes.ParsedIngredient
import com.bytelog.app.recipes.RecipeExtractor
import com.bytelog.app.recipes.RecipeStep
import com.bytelog.app.recipes.SiteNutrition
import com.bytelog.app.recipes.TemplateNutrition
import com.bytelog.app.recipes.UnitConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Everything needed to save a reviewed recipe. */
data class RecipeDraft(
    val name: String,
    val description: String? = null,
    val servings: Float,
    val ingredients: List<IngredientMatch>,
    val steps: List<RecipeStep> = emptyList(),
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val sourceUrl: String? = null,
    val imageUrl: String? = null,
    val notes: String? = null,
    val fridgeDays: Int? = null,
    val freezerDays: Int? = null,
    val storageNotes: String? = null,
    val rawLines: List<String> = emptyList(),
    val siteNutrition: SiteNutrition? = null
)

/** A template joined with its local recipe extras and whole-recipe totals. */
data class RecipeView(
    val template: MealTemplate,
    val details: RecipeDetails?,
    val total: Macros?
) {
    val servings: Float get() = template.totalServings.takeIf { it > 0f } ?: 1f
    val perServing: Macros? get() = total?.div(servings)
    val steps: List<RecipeStep> get() = RecipeRepository.decodeSteps(details?.stepsJson)
}

class RecipeRepository(
    private val recipeDetailsDao: RecipeDetailsDao,
    private val ingredientAliasDao: IngredientAliasDao,
    private val foodDao: FoodDao,
    private val foodRepository: FoodRepository,
    private val mealTemplateRepository: MealTemplateRepository,
    private val sparkyApiClient: SparkyApiClient
) {
    // A separate client with no Sparky auth headers: recipe sites must never see the user's token.
    private val webClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    val recipes: Flow<List<RecipeView>> = kotlinx.coroutines.flow.combine(
        mealTemplateRepository.templates,
        recipeDetailsDao.getAll()
    ) { templates, details ->
        val byId = details.associateBy { it.templateId }
        templates.map { t -> RecipeView(t, byId[t.id], TemplateNutrition.totals(t.foodsJson)) }
    }

    fun recipe(templateId: String): Flow<RecipeView?> = recipes.map { list -> list.firstOrNull { it.template.id == templateId } }

    suspend fun saveDetails(details: RecipeDetails) = withContext(Dispatchers.IO) {
        recipeDetailsDao.upsert(details.copy(updatedAt = System.currentTimeMillis()))
    }

    // --- Import: fetching and parsing ---

    suspend fun fetchRecipe(url: String): Result<ImportedRecipe> = withContext(Dispatchers.IO) {
        try {
            val clean = url.trim()
            if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
                return@withContext Result.failure(IOException("That doesn't look like a web link."))
            }
            val request = Request.Builder()
                .url(clean)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()
            webClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("The site answered HTTP ${response.code}."))
                }
                val body = response.body ?: return@withContext Result.failure(IOException("The page was empty."))
                val bytes = body.byteStream().use { readCapped(it, MAX_PAGE_BYTES) }
                val html = String(bytes, body.contentType()?.charset() ?: Charsets.UTF_8)
                val finalUrl = response.request.url.toString()
                val recipe = RecipeExtractor.extract(html, finalUrl)
                    ?: return@withContext Result.failure(IOException("No recipe data found on this page. Try pasting the recipe text instead."))
                if (recipe.ingredientLines.isEmpty()) {
                    return@withContext Result.failure(IOException("Found \"${recipe.name}\" but no ingredient list. Try pasting the recipe text instead."))
                }
                Result.success(recipe)
            }
        } catch (e: Exception) {
            Result.failure(IOException("Couldn't load the page: ${e.message ?: e.javaClass.simpleName}", e))
        }
    }

    // --- Import: matching ingredients to foods ---

    suspend fun matchIngredients(lines: List<String>): List<IngredientMatch> = coroutineScope {
        val gate = Semaphore(4)
        lines.map { line ->
            async(Dispatchers.IO) { gate.withPermit { matchParsed(IngredientLineParser.parse(line)) } }
        }.awaitAll()
    }

    suspend fun matchParsed(parsed: ParsedIngredient, key: String? = null): IngredientMatch = withContext(Dispatchers.IO) {
        val base = IngredientMatch(key = key ?: java.util.UUID.randomUUID().toString(), parsed = parsed, include = !parsed.optional)
        if (parsed.name.isBlank()) return@withContext base

        // 1. Remembered choice for this ingredient name.
        ingredientAliasDao.get(IngredientMatching.normalize(parsed.name))?.let { alias ->
            return@withContext IngredientMatching.matchWith(parsed, alias.toFoodItem(), MatchSource.ALIAS, key = base.key)
        }

        // 2. The user's cached food library, then 3. Sparky / OpenFoodFacts search.
        val candidates = mutableListOf<FoodItem>()
        for (q in IngredientMatching.searchQueries(parsed.name)) {
            candidates += foodDao.searchFoods(q).first()
            if (candidates.any { IngredientMatching.score(parsed.name, it) >= 0.75f }) break
        }
        var source = MatchSource.LIBRARY
        if (candidates.none { IngredientMatching.score(parsed.name, it) >= 0.75f }) {
            for (q in IngredientMatching.searchQueries(parsed.name).take(2)) {
                val remote = sparkyApiClient.searchFoods(q).getOrNull().orEmpty()
                candidates += remote
                if (remote.any { IngredientMatching.score(parsed.name, it) >= 0.6f }) {
                    source = MatchSource.SEARCH
                    break
                }
            }
            if (candidates.isNotEmpty() && source != MatchSource.SEARCH) source = MatchSource.SEARCH
        }
        val ranked = candidates.distinctBy { it.id }
            .filter { it.calories > 0f || it.protein > 0f }
            .sortedByDescending { IngredientMatching.score(parsed.name, it) }
            .take(8)
        val best = ranked.firstOrNull { IngredientMatching.score(parsed.name, it) >= 0.45f }
            ?: return@withContext base.copy(candidates = ranked)
        IngredientMatching.matchWith(parsed, best, source, ranked, key = base.key)
    }

    suspend fun searchFoods(query: String): List<FoodItem> = withContext(Dispatchers.IO) {
        val local = foodDao.searchFoods(query.trim()).first()
        val remote = sparkyApiClient.searchFoods(query).getOrNull().orEmpty()
        (local + remote).distinctBy { it.id }.take(40)
    }

    /**
     * Proof of concept: asks Sparky's AI to normalise ingredient lines the deterministic parser
     * could not resolve. Uses the chat endpoint restricted to read-only report tools so it cannot
     * log anything. Note: the exchange appears in the user's Sparky chat history.
     */
    suspend fun aiParseIngredients(lines: List<String>): Result<List<ParsedIngredient>> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext Result.success(emptyList())
        val prompt = buildString {
            append("You are a recipe ingredient parser. Do not call any tools and do not log anything. ")
            append("For each ingredient line below, return the core food name (no prep words), the quantity as a number, ")
            append("the unit (g, kg, oz, lb, ml, l, tsp, tbsp, cup, or a count word like clove/can/piece), and your best estimate of the weight in grams. ")
            append("Reply with ONLY a JSON array, one object per line in the same order, like ")
            append("[{\"raw\":\"...\",\"name\":\"chicken breast\",\"quantity\":1.5,\"unit\":\"lb\",\"grams\":680}].\n\nLines:\n")
            lines.forEachIndexed { i, l -> append(i + 1).append(". ").append(l).append('\n') }
        }
        val res = sparkyApiClient.sendChatMessage(listOf(ChatMessageInput("user", prompt)), toolCategories = listOf("reports"))
        res.mapCatching { output ->
            val text = output.content
            val jsonText = Regex("""```(?:json)?\s*(\[[\s\S]*?])\s*```""").find(text)?.groupValues?.get(1)
                ?: text.substring(text.indexOf('[').coerceAtLeast(0), (text.lastIndexOf(']') + 1).coerceAtLeast(0))
            val arr = Json.parseToJsonElement(jsonText).jsonArray
            arr.mapIndexedNotNull { i, el ->
                val o = el as? JsonObject ?: return@mapIndexedNotNull null
                fun s(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                fun d(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
                val unit = s("unit")?.let { UnitConverter.canonical(it) ?: it.lowercase() }
                ParsedIngredient(
                    raw = lines.getOrNull(i) ?: s("raw").orEmpty(),
                    quantity = d("quantity"),
                    unit = unit,
                    name = s("name").orEmpty(),
                    grams = d("grams"),
                    confidence = ParsedIngredient.Confidence.MEDIUM
                )
            }
        }
    }

    // --- Saving ---

    /**
     * Saves a reviewed recipe: creates any foods Sparky doesn't have yet, creates the
     * template with a nutrition snapshot per ingredient, stores directions locally and
     * remembers each ingredient → food choice.
     */
    suspend fun saveRecipe(draft: RecipeDraft): Result<String> = withContext(Dispatchers.IO) {
        try {
            val included = draft.ingredients.filter { it.include && it.food != null }
            if (included.isEmpty()) return@withContext Result.failure(IOException("Match at least one ingredient to a food."))

            val ingredients = included.map { m ->
                val food = ensureSparkyFood(m.food!!)
                val macros = m.copy(food = food).macros
                TemplateIngredient(
                    foodId = food.id,
                    foodName = food.name,
                    quantity = m.quantity,
                    unit = m.unit,
                    calories = macros.calories,
                    protein = macros.protein,
                    carbs = macros.carbs,
                    fat = macros.fat
                ) to (m to food)
            }

            val templateId = mealTemplateRepository.createRecipeTemplate(
                name = draft.name.trim(),
                description = draft.description?.take(240),
                servingSize = 1f,
                servingUnit = "serving",
                totalServings = draft.servings.coerceAtLeast(1f),
                foods = ingredients.map { it.first },
                notes = sparkyNotes(draft)
            ).getOrElse { return@withContext Result.failure(it) }

            recipeDetailsDao.upsert(
                RecipeDetails(
                    templateId = templateId,
                    prepTimeMinutes = draft.prepMinutes,
                    cookTimeMinutes = draft.cookMinutes,
                    sourceUrl = draft.sourceUrl,
                    imageUrl = draft.imageUrl,
                    stepsJson = encodeSteps(draft.steps),
                    notes = draft.notes,
                    fridgeDays = draft.fridgeDays,
                    freezerDays = draft.freezerDays,
                    storageNotes = draft.storageNotes,
                    rawIngredientLinesJson = json.encodeToString(ListSerializer(String.serializer()), draft.rawLines),
                    siteNutritionJson = draft.siteNutrition?.let { json.encodeToString(SiteNutrition.serializer(), it) }
                )
            )

            ingredients.forEach { (_, pair) ->
                val (m, food) = pair
                if (m.parsed.name.isNotBlank()) rememberAlias(m.parsed.name, food)
            }
            Result.success(templateId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rememberAlias(ingredientName: String, food: FoodItem) = withContext(Dispatchers.IO) {
        ingredientAliasDao.upsert(
            IngredientAlias(
                normalizedName = IngredientMatching.normalize(ingredientName),
                foodId = food.id,
                foodName = food.name,
                servingSize = food.servingSize,
                servingUnit = food.servingUnit,
                calories = food.calories,
                protein = food.protein,
                carbs = food.carbs,
                fat = food.fat
            )
        )
    }

    /**
     * Template ingredients must reference foods that exist in Sparky. Search results from
     * OpenFoodFacts are proxied, not stored, so copy them into Sparky as custom foods first.
     */
    private suspend fun ensureSparkyFood(food: FoodItem): FoodItem {
        if (isSparkyFoodId(food.id)) return food
        val created = foodRepository.createCustomFood(
            name = food.name,
            brand = food.brand,
            servingSize = food.servingSize,
            servingUnit = food.servingUnit,
            calories = food.calories,
            protein = food.protein,
            carbs = food.carbs,
            fat = food.fat,
            fiber = food.fiber
        ).getOrElse { throw IOException("Couldn't add ${food.name} to Sparky: ${it.message}", it) }
        return created
    }

    /** Short plain-text summary stored in the Sparky template's notes so the web app shows directions too. */
    private fun sparkyNotes(draft: RecipeDraft): String? {
        if (draft.steps.isEmpty() && draft.sourceUrl == null && draft.notes.isNullOrBlank()) return null
        return buildString {
            draft.sourceUrl?.let { append("Source: ").append(it).append("\n\n") }
            if (draft.steps.isNotEmpty()) {
                append("Directions:\n")
                var section: String? = null
                var n = 1
                draft.steps.forEach { step ->
                    if (step.section != null && step.section != section) {
                        section = step.section
                        append('\n').append(step.section).append(":\n")
                    }
                    append(n++).append(". ").append(step.text).append('\n')
                }
            }
            draft.notes?.takeIf { it.isNotBlank() }?.let { append("\nNotes:\n").append(it).append('\n') }
            draft.storageNotes?.takeIf { it.isNotBlank() }?.let { append("\nStorage: ").append(it).append('\n') }
        }.take(4000)
    }

    private fun IngredientAlias.toFoodItem() = FoodItem(
        id = foodId,
        name = foodName,
        servingSize = servingSize,
        servingUnit = servingUnit,
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat
    )

    companion object {
        private const val MAX_PAGE_BYTES = 3 * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun isSparkyFoodId(id: String): Boolean = uuid.matches(id)

        /** Reads at most [max] bytes (InputStream.readNBytes needs API 33). */
        private fun readCapped(input: java.io.InputStream, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (out.size() < max) {
                val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        fun encodeSteps(steps: List<RecipeStep>): String = json.encodeToString(ListSerializer(RecipeStep.serializer()), steps)

        fun decodeSteps(stepsJson: String?): List<RecipeStep> = try {
            if (stepsJson.isNullOrBlank()) emptyList() else json.decodeFromString(ListSerializer(RecipeStep.serializer()), stepsJson)
        } catch (_: Exception) {
            emptyList()
        }

        /** "Directions" text area ⇄ steps: one step per line, a line ending in ":" starts a section. */
        fun stepsFromText(text: String): List<RecipeStep> {
            var section: String? = null
            return text.lines().map { it.trim() }.filter { it.isNotBlank() }.mapNotNull { line ->
                if (line.endsWith(":") && line.length < 60) {
                    section = line.removeSuffix(":").trim(); null
                } else {
                    val clean = line.replace(Regex("""^(step\s*)?\d+[.):]\s*""", RegexOption.IGNORE_CASE), "")
                    RecipeStep(clean, section, com.bytelog.app.recipes.TimerDetector.firstTimerSeconds(clean))
                }
            }
        }

        fun stepsToText(steps: List<RecipeStep>): String = buildString {
            var section: String? = null
            steps.forEach { s ->
                if (s.section != null && s.section != section) {
                    section = s.section
                    if (isNotEmpty()) append('\n')
                    append(s.section).append(":\n")
                }
                append(s.text).append('\n')
            }
        }.trim()
    }
}
