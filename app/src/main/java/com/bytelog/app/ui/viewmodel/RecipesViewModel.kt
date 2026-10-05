package com.bytelog.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.RecipeDetails
import com.bytelog.app.data.repository.BatchStatus
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.MealPrepRepository
import com.bytelog.app.data.repository.MealTemplateRepository
import com.bytelog.app.data.repository.RecipeDraft
import com.bytelog.app.data.repository.RecipeFetchException
import com.bytelog.app.data.repository.RecipeRepository
import com.bytelog.app.data.repository.RecipeView
import com.bytelog.app.recipes.ImportedRecipe
import com.bytelog.app.recipes.IngredientLineParser
import com.bytelog.app.recipes.IngredientMatch
import com.bytelog.app.recipes.IngredientMatching
import com.bytelog.app.recipes.Macros
import com.bytelog.app.recipes.MatchSource
import com.bytelog.app.recipes.ParsedIngredient
import com.bytelog.app.recipes.RecipeExtractor
import com.bytelog.app.recipes.RecipeTextParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime

/** State of the recipe import flow. */
sealed interface ImportState {
    data object Idle : ImportState
    data class Loading(val message: String) : ImportState
    data class Error(val message: String, val url: String? = null) : ImportState

    /** The quick import failed in a way a real browser may get past: offer the in-app browser. */
    data class NeedsBrowser(val url: String, val reason: String) : ImportState

    /** The in-app browser is open on [url]; its page is read once a recipe shows up. */
    data class Browsing(val url: String) : ImportState
    data class Review(
        val recipe: ImportedRecipe,
        val name: String,
        val servings: Float,
        val matches: List<IngredientMatch>,
        val aiRunning: Boolean = false,
        val saving: Boolean = false
    ) : ImportState {
        val total: Macros get() = matches.filter { it.include }.fold(Macros()) { acc, m -> acc + m.macros }
        val perServing: Macros get() = total / servings.coerceAtLeast(1f)
        val unresolved: List<IngredientMatch> get() = matches.filter { it.include && (it.food == null || it.needsReview) }
    }
}

class RecipesViewModel(
    private val recipeRepository: RecipeRepository,
    private val mealPrepRepository: MealPrepRepository,
    private val mealTemplateRepository: MealTemplateRepository,
    private val foodRepository: FoodRepository
) : ViewModel() {

    val recipes: StateFlow<List<RecipeView>> = recipeRepository.recipes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeBatches: StateFlow<List<BatchStatus>> = mealPrepRepository.activeBatches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mealTypes: StateFlow<List<MealType>> = foodRepository.getAllMealTypes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MealType.DEFAULT_MEAL_TYPES)

    fun recipe(templateId: String): Flow<RecipeView?> = recipeRepository.recipe(templateId)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    /** A recipe link shared into the app from a browser, waiting to be imported. */
    private val _pendingSharedText = MutableStateFlow<String?>(null)
    val pendingSharedText: StateFlow<String?> = _pendingSharedText.asStateFlow()

    fun onSharedText(text: String) {
        _pendingSharedText.value = text
    }

    fun consumeSharedText(): String? = _pendingSharedText.value.also { _pendingSharedText.value = null }

    // --- Import ---

    fun resetImport() {
        _importState.value = ImportState.Idle
    }

    /** Accepts a URL, shared text containing a URL, or a pasted recipe. */
    fun startImport(input: String) {
        val url = RecipeExtractor.findUrl(input)
        viewModelScope.launch {
            val recipe = if (url != null && input.trim().length < url.length + 200) {
                _importState.value = ImportState.Loading("Reading recipe from ${hostOf(url)}…")
                recipeRepository.fetchRecipe(url).getOrElse {
                    val canBrowse = (it as? RecipeFetchException)?.canTryBrowser == true
                    _importState.value = if (canBrowse) {
                        ImportState.NeedsBrowser(url, it.message ?: "Couldn't read that page.")
                    } else {
                        ImportState.Error(it.message ?: "Couldn't read that page.", url)
                    }
                    return@launch
                }
            } else {
                RecipeTextParser.parse(input).also {
                    if (it.ingredientLines.isEmpty()) {
                        _importState.value = ImportState.Error("Couldn't find an ingredient list. Put each ingredient on its own line, under an \"Ingredients\" heading.")
                        return@launch
                    }
                }
            }
            matchAndReview(recipe)
        }
    }

    /** Opens the in-app browser on [url] (from the failure prompt, or straight from a link). */
    fun openBrowser(url: String) {
        _importState.value = ImportState.Browsing(url)
    }

    /** Leaves the in-app browser and returns to the prompt with the original reason. */
    fun closeBrowser(url: String) {
        _importState.value = ImportState.NeedsBrowser(url, "You can try again, or paste the recipe text instead.")
    }

    /** Called with a recipe read from the page the in-app browser is showing. */
    fun onBrowserRecipe(recipe: ImportedRecipe) {
        viewModelScope.launch { matchAndReview(recipe) }
    }

    private suspend fun matchAndReview(recipe: ImportedRecipe) {
        _importState.value = ImportState.Loading("Matching ${recipe.ingredientLines.size} ingredients to foods…")
        val matches = recipeRepository.matchIngredients(recipe.ingredientLines)
        _importState.value = ImportState.Review(
            recipe = recipe,
            name = recipe.name,
            servings = (recipe.yieldServings ?: 4.0).toFloat().coerceAtLeast(1f),
            matches = matches
        )
    }

    private fun updateReview(transform: (ImportState.Review) -> ImportState.Review) {
        _importState.update { s -> if (s is ImportState.Review) transform(s) else s }
    }

    fun setImportName(name: String) = updateReview { it.copy(name = name) }

    fun setImportServings(servings: Float) = updateReview { it.copy(servings = servings.coerceAtLeast(1f)) }

    fun setInclude(key: String, include: Boolean) = updateReview { r ->
        r.copy(matches = r.matches.map { if (it.key == key) it.copy(include = include) else it })
    }

    fun chooseFood(key: String, food: FoodItem) = updateReview { r ->
        r.copy(matches = r.matches.map { m ->
            if (m.key == key) IngredientMatching.matchWith(m.parsed, food, MatchSource.MANUAL, m.candidates, key = m.key) else m
        })
    }

    fun setAmount(key: String, quantity: Float, unit: String) = updateReview { r ->
        r.copy(matches = r.matches.map { if (it.key == key) it.copy(quantity = quantity, unit = unit, needsReview = false) else it })
    }

    fun markReviewed(key: String) = updateReview { r ->
        r.copy(matches = r.matches.map { if (it.key == key) it.copy(needsReview = false) else it })
    }

    suspend fun searchFoods(query: String): List<FoodItem> = recipeRepository.searchFoods(query)

    /** Proof of concept: send unresolved lines to Sparky AI and re-match them. */
    fun runAiAssist() {
        val review = _importState.value as? ImportState.Review ?: return
        val targets = review.unresolved.filter { it.source != MatchSource.MANUAL }
        if (targets.isEmpty()) {
            _messages.tryEmit("Nothing left to resolve.")
            return
        }
        updateReview { it.copy(aiRunning = true) }
        viewModelScope.launch {
            val res = recipeRepository.aiParseIngredients(targets.map { it.parsed.raw })
            val parsed = res.getOrElse {
                updateReview { r -> r.copy(aiRunning = false) }
                _messages.tryEmit("Sparky AI couldn't parse these: ${it.message}")
                return@launch
            }
            val rematched = targets.mapIndexedNotNull { i, m ->
                val ai = parsed.getOrNull(i)?.takeIf { it.name.isNotBlank() } ?: return@mapIndexedNotNull null
                // Keep a deterministic weight if we had one; the AI fills the gaps.
                val merged: ParsedIngredient = ai.copy(raw = m.parsed.raw, grams = m.parsed.grams ?: ai.grams, optional = m.parsed.optional)
                val match = recipeRepository.matchParsed(merged, key = m.key)
                m.key to (if (match.source != MatchSource.NONE) match.copy(source = MatchSource.AI) else match)
            }.toMap()
            updateReview { r -> r.copy(aiRunning = false, matches = r.matches.map { rematched[it.key] ?: it }) }
            _messages.tryEmit("Sparky AI re-parsed ${rematched.size} of ${targets.size} lines.")
        }
    }

    fun saveImport(fridgeDays: Int?, freezerDays: Int?, onSaved: (String) -> Unit) {
        val r = _importState.value as? ImportState.Review ?: return
        updateReview { it.copy(saving = true) }
        viewModelScope.launch {
            val result = recipeRepository.saveRecipe(
                RecipeDraft(
                    name = r.name.ifBlank { r.recipe.name },
                    description = r.recipe.description,
                    servings = r.servings,
                    ingredients = r.matches,
                    steps = r.recipe.steps,
                    prepMinutes = r.recipe.prepMinutes,
                    cookMinutes = r.recipe.cookMinutes,
                    sourceUrl = r.recipe.sourceUrl,
                    imageUrl = r.recipe.imageUrl,
                    fridgeDays = fridgeDays,
                    freezerDays = freezerDays,
                    rawLines = r.recipe.ingredientLines,
                    siteNutrition = r.recipe.siteNutrition
                )
            )
            result.fold(
                onSuccess = { id ->
                    _importState.value = ImportState.Idle
                    _messages.tryEmit("Saved \"${r.name}\" to Sparky.")
                    onSaved(id)
                },
                onFailure = {
                    updateReview { s -> s.copy(saving = false) }
                    _messages.tryEmit("Couldn't save: ${it.message}")
                }
            )
        }
    }

    /** Saves a recipe from the builder through the same path as imports (snapshot, details, aliases). */
    fun saveBuiltRecipe(built: com.bytelog.app.ui.components.BuiltRecipe) {
        viewModelScope.launch {
            val matches = built.ingredients.map { ing ->
                IngredientMatch(
                    parsed = ParsedIngredient(raw = ing.food.name, quantity = ing.quantity.toDouble(), unit = ing.unit, name = ing.food.name),
                    food = ing.food,
                    quantity = ing.quantity,
                    unit = ing.unit,
                    source = MatchSource.MANUAL,
                    needsReview = false
                )
            }
            val res = recipeRepository.saveRecipe(
                RecipeDraft(
                    name = built.name,
                    description = built.description,
                    servings = built.servings,
                    ingredients = matches,
                    steps = RecipeRepository.stepsFromText(built.directions),
                    prepMinutes = built.prepMinutes,
                    cookMinutes = built.cookMinutes,
                    notes = built.notes,
                    fridgeDays = built.fridgeDays,
                    freezerDays = built.freezerDays
                )
            )
            _messages.tryEmit(
                res.fold({ "Recipe \"${built.name}\" saved to Sparky." }, { "Couldn't save recipe: ${it.message}" })
            )
        }
    }

    // --- Recipe details ---

    fun saveDetails(details: RecipeDetails) {
        viewModelScope.launch {
            recipeRepository.saveDetails(details)
            _messages.tryEmit("Recipe notes saved.")
        }
    }

    fun logRecipeServings(recipe: RecipeView, servings: Float, date: String, meal: MealType) {
        viewModelScope.launch {
            val res = mealTemplateRepository.logTemplateToDiary(
                templateId = recipe.template.id,
                mealType = meal.name,
                mealTypeId = meal.id,
                date = date,
                quantity = servings
            )
            if (res.isSuccess) {
                foodRepository.syncDateWithServer(date)
                _messages.tryEmit("Logged ${formatAmount(servings)} serving(s) of ${recipe.template.name} to ${meal.name}.")
            } else {
                _messages.tryEmit("Couldn't log: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    // --- Batches ---

    fun startBatch(
        name: String,
        templateId: String?,
        total: Macros,
        portions: Int?,
        cookedWeightG: Float?,
        scale: Float,
        location: String,
        shelfLifeDays: Int?,
        ingredientsJson: String = "[]"
    ) {
        viewModelScope.launch {
            mealPrepRepository.startBatch(name, templateId, total, portions, cookedWeightG, scale, location, shelfLifeDays, ingredientsJson)
            _messages.tryEmit("\"$name\" is in the ${location}.")
        }
    }

    fun logFromBatch(status: BatchStatus, date: String, meal: MealType, portions: Float? = null, grams: Float? = null) {
        viewModelScope.launch {
            val entry = mealPrepRepository.logFromBatch(status.batch.id, date, meal.id, meal.name, portions, grams)
            if (entry == null) {
                _messages.tryEmit("Couldn't log that amount.")
            } else {
                val amount = if (entry.unit == "g") "${entry.quantity.toInt()} g" else "${formatAmount(entry.quantity)} portion"
                _messages.tryEmit("Logged $amount of ${status.batch.name} to ${meal.name} (${entry.calories.toInt()} kcal).")
            }
        }
    }

    fun moveBatch(batchId: String, location: String, shelfLifeDays: Int?) =
        viewModelScope.launch { mealPrepRepository.moveTo(batchId, location, shelfLifeDays) }

    fun discardBatch(batchId: String) = viewModelScope.launch {
        mealPrepRepository.discardRemaining(batchId)
        _messages.tryEmit("Leftovers tossed. Nothing was logged.")
    }

    fun archiveBatch(batchId: String) = viewModelScope.launch { mealPrepRepository.archive(batchId) }

    fun deleteBatch(batchId: String) = viewModelScope.launch {
        mealPrepRepository.deleteBatch(batchId)
        _messages.tryEmit("Batch removed. Diary entries were kept.")
    }

    companion object {
        /** Picks the meal that fits the current time of day from the user's meal types. */
        fun mealForNow(mealTypes: List<MealType>, now: LocalTime = LocalTime.now()): MealType? {
            if (mealTypes.isEmpty()) return null
            val wanted = when {
                now.isBefore(LocalTime.of(10, 30)) -> listOf("breakfast")
                now.isBefore(LocalTime.of(14, 30)) -> listOf("lunch")
                now.isBefore(LocalTime.of(17, 0)) -> listOf("snack", "snacks")
                else -> listOf("dinner", "supper")
            }
            return mealTypes.firstOrNull { t -> wanted.any { t.name.trim().lowercase().startsWith(it) } } ?: mealTypes.first()
        }

        fun formatAmount(v: Float): String =
            if (v % 1f == 0f) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)

        private fun hostOf(url: String): String = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: "the page"
    }
}
