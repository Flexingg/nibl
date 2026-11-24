package com.bytelog.app.ui.mealie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.mealie.MealieConfig
import com.bytelog.app.data.mealie.MealieMealPlanEntry
import com.bytelog.app.data.mealie.MealieRecipeSummary
import com.bytelog.app.data.mealie.MealieRepository
import com.bytelog.app.data.mealie.MealieShoppingItem
import com.bytelog.app.data.mealie.MealieShoppingList
import com.bytelog.app.data.remote.ExternalProviderInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class MealieTab(val label: String) {
    RECIPES("Recipes"),
    MEAL_PLAN("Meal Plan"),
    SHOPPING("Shopping"),
    CONNECT("Connect")
}

data class MealieUiState(
    val selectedTab: MealieTab = MealieTab.RECIPES,
    val config: MealieConfig = MealieConfig(),
    val urlInput: String = "",
    val tokenInput: String = "",
    val connectionStatus: String? = null,
    val connectionSuccess: Boolean? = null,
    val testingConnection: Boolean = false,
    val sparkyMealieProvider: ExternalProviderInfo? = null,
    val checkingSparkyProvider: Boolean = false,

    // Recipes
    val recipeQuery: String = "",
    val recipes: List<MealieRecipeSummary> = emptyList(),
    val loadingRecipes: Boolean = false,
    val importingSlug: String? = null,
    val scrapingUrl: Boolean = false,

    // Meal Plan (7-day window starting today)
    val planStartDate: String = LocalDate.now().toString(),
    val planEndDate: String = LocalDate.now().plusDays(6).toString(),
    val mealPlanEntries: List<MealieMealPlanEntry> = emptyList(),
    val loadingMealPlan: Boolean = false,
    val syncingMealPlan: Boolean = false,

    // Shopping
    val shoppingLists: List<MealieShoppingList> = emptyList(),
    val selectedListId: String? = null,
    val shoppingItems: List<MealieShoppingItem> = emptyList(),
    val loadingShopping: Boolean = false,
    val pushingShopping: Boolean = false,

    // Feedback
    val message: String? = null,
    val error: String? = null,
    val lastImportedTemplateId: String? = null
) {
    val isConnected: Boolean
        get() = config.isDirectConfigured || sparkyMealieProvider != null
}

class MealieViewModel(
    private val repository: MealieRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MealieUiState())
    val state: StateFlow<MealieUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var initialLoaded = false

    init {
        viewModelScope.launch {
            repository.config.collect { cfg ->
                _state.update { s ->
                    s.copy(
                        config = cfg,
                        urlInput = if (s.urlInput.isBlank() && cfg.baseUrl.isNotBlank()) cfg.baseUrl else s.urlInput,
                        tokenInput = if (s.tokenInput.isBlank() && cfg.apiToken.isNotBlank()) cfg.apiToken else s.tokenInput,
                        selectedListId = s.selectedListId ?: cfg.defaultShoppingListId
                    )
                }
                if (!initialLoaded) {
                    initialLoaded = true
                    refreshSparkyStatus()
                    if (cfg.isDirectConfigured) {
                        loadRecipes("")
                        loadMealPlan()
                        loadShoppingLists()
                    }
                }
            }
        }
    }

    fun selectTab(tab: MealieTab) {
        _state.update { it.copy(selectedTab = tab) }
        val s = _state.value
        when (tab) {
            MealieTab.RECIPES -> if (s.recipes.isEmpty() && s.isConnected) loadRecipes(s.recipeQuery)
            MealieTab.MEAL_PLAN -> if (s.mealPlanEntries.isEmpty() && s.config.isDirectConfigured) loadMealPlan()
            MealieTab.SHOPPING -> if (s.shoppingLists.isEmpty() && s.config.isDirectConfigured) loadShoppingLists()
            MealieTab.CONNECT -> refreshSparkyStatus()
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null, error = null) }
    }

    fun consumeImportedTemplateId() {
        _state.update { it.copy(lastImportedTemplateId = null) }
    }

    // --- Connect tab ---

    fun updateUrlInput(value: String) {
        _state.update { it.copy(urlInput = value) }
    }

    fun updateTokenInput(value: String) {
        _state.update { it.copy(tokenInput = value) }
    }

    fun refreshSparkyStatus() {
        viewModelScope.launch {
            _state.update { it.copy(checkingSparkyProvider = true) }
            val provider = repository.getSparkyMealieProvider()
            _state.update { it.copy(sparkyMealieProvider = provider, checkingSparkyProvider = false) }
            if (provider != null && !_state.value.config.isDirectConfigured && _state.value.recipes.isEmpty()) {
                loadRecipes("")
            }
        }
    }

    fun saveAndTestConnection() {
        val url = _state.value.urlInput.trim()
        val token = _state.value.tokenInput.trim()
        viewModelScope.launch {
            _state.update { it.copy(testingConnection = true, connectionStatus = null, connectionSuccess = null, error = null) }
            val res = repository.testDirectConnection(url, token)
            res.onSuccess { info ->
                repository.saveConfig(url, token)
                _state.update {
                    it.copy(
                        testingConnection = false,
                        connectionStatus = info,
                        connectionSuccess = true,
                        message = info
                    )
                }
                loadRecipes(_state.value.recipeQuery)
                loadMealPlan()
                loadShoppingLists()
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        testingConnection = false,
                        connectionStatus = err.message ?: "Connection failed",
                        connectionSuccess = false,
                        error = err.message ?: "Connection failed"
                    )
                }
            }
        }
    }

    fun disconnectDirect() {
        viewModelScope.launch {
            repository.clearConfig()
            _state.update {
                it.copy(
                    urlInput = "",
                    tokenInput = "",
                    connectionStatus = "Disconnected from Direct Mealie.",
                    connectionSuccess = null,
                    mealPlanEntries = emptyList(),
                    shoppingLists = emptyList(),
                    shoppingItems = emptyList(),
                    selectedListId = null,
                    message = "Mealie credentials cleared."
                )
            }
        }
    }

    // --- Recipes tab ---

    fun onRecipeQueryChanged(query: String) {
        _state.update { it.copy(recipeQuery = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            loadRecipes(query)
        }
    }

    fun loadRecipes(query: String = _state.value.recipeQuery) {
        viewModelScope.launch {
            _state.update { it.copy(loadingRecipes = true, error = null) }
            val res = repository.searchRecipes(query = query)
            res.onSuccess { list ->
                _state.update { it.copy(recipes = list, loadingRecipes = false) }
            }.onFailure { err ->
                _state.update { it.copy(loadingRecipes = false, error = err.message ?: "Failed to load Mealie recipes") }
            }
        }
    }

    fun importRecipeToNibl(recipe: MealieRecipeSummary) {
        viewModelScope.launch {
            _state.update { it.copy(importingSlug = recipe.slug, error = null) }
            val res = repository.importRecipeToNibl(recipe.slug, recipe.sparkyProviderId)
            res.onSuccess { templateId ->
                _state.update {
                    it.copy(
                        importingSlug = null,
                        lastImportedTemplateId = templateId,
                        message = "Imported \"${recipe.name}\" to Nibl recipes."
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        importingSlug = null,
                        error = err.message ?: "Failed to import recipe"
                    )
                }
            }
        }
    }

    fun addRecipeToShoppingList(recipe: MealieRecipeSummary, scale: Float = 1f) {
        val listId = _state.value.selectedListId ?: _state.value.shoppingLists.firstOrNull()?.id
        if (listId == null) {
            _state.update { it.copy(error = "Select a Mealie shopping list in the Shopping tab first.") }
            return
        }
        viewModelScope.launch {
            val res = repository.addRecipeToShoppingList(listId, recipe.id, scale)
            res.onSuccess {
                _state.update { it.copy(message = "Added \"${recipe.name}\" ingredients to Mealie shopping list.") }
                if (_state.value.selectedListId == listId) loadShoppingItems(listId)
            }.onFailure { err ->
                _state.update { it.copy(error = err.message ?: "Failed to add recipe to shopping list") }
            }
        }
    }

    fun scrapeUrlToMealie(url: String) {
        val clean = url.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(scrapingUrl = true, error = null) }
            val res = repository.scrapeUrlToMealie(clean)
            res.onSuccess { slug ->
                _state.update {
                    it.copy(
                        scrapingUrl = false,
                        message = "Saved recipe \"$slug\" to Mealie!"
                    )
                }
                loadRecipes(_state.value.recipeQuery)
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        scrapingUrl = false,
                        error = err.message ?: "Failed to scrape URL into Mealie"
                    )
                }
            }
        }
    }

    // --- Meal Plan tab ---

    fun loadMealPlan() {
        val start = _state.value.planStartDate
        val end = _state.value.planEndDate
        viewModelScope.launch {
            _state.update { it.copy(loadingMealPlan = true, error = null) }
            val res = repository.getMealPlans(start, end)
            res.onSuccess { list ->
                _state.update { it.copy(mealPlanEntries = list, loadingMealPlan = false) }
            }.onFailure { err ->
                _state.update { it.copy(loadingMealPlan = false, error = err.message ?: "Failed to load meal plan") }
            }
        }
    }

    fun shiftPlanWeek(deltaDays: Long) {
        val start = runCatching { LocalDate.parse(_state.value.planStartDate) }.getOrDefault(LocalDate.now()).plusDays(deltaDays)
        val end = start.plusDays(6)
        _state.update { it.copy(planStartDate = start.toString(), planEndDate = end.toString()) }
        if (_state.value.config.isDirectConfigured) loadMealPlan()
    }

    fun addMealPlanEntry(date: String, entryType: String, title: String, recipeId: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(syncingMealPlan = true, error = null) }
            val res = repository.createMealPlanEntry(
                date = date,
                entryType = entryType,
                recipeId = recipeId,
                title = title.ifBlank { null }
            )
            res.onSuccess {
                _state.update { it.copy(syncingMealPlan = false, message = "Added meal to Mealie plan.") }
                loadMealPlan()
            }.onFailure { err ->
                _state.update { it.copy(syncingMealPlan = false, error = err.message ?: "Failed to add meal plan entry") }
            }
        }
    }

    fun deleteMealPlanEntry(entry: MealieMealPlanEntry) {
        viewModelScope.launch {
            val res = repository.deleteMealPlanEntry(entry.id)
            res.onSuccess {
                _state.update { s ->
                    s.copy(
                        mealPlanEntries = s.mealPlanEntries.filterNot { it.id == entry.id },
                        message = "Removed \"${entry.displayTitle}\" from Mealie."
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(error = err.message ?: "Failed to delete meal plan entry") }
            }
        }
    }

    fun pullMealPlanToNibl() {
        val start = _state.value.planStartDate
        val end = _state.value.planEndDate
        viewModelScope.launch {
            _state.update { it.copy(syncingMealPlan = true, error = null) }
            val res = repository.pullMealPlanToNibl(start, end)
            res.onSuccess { sync ->
                _state.update { it.copy(syncingMealPlan = false, message = sync.message) }
            }.onFailure { err ->
                _state.update { it.copy(syncingMealPlan = false, error = err.message ?: "Failed to import meal plan") }
            }
        }
    }

    fun pushNiblPlanToMealie() {
        val start = _state.value.planStartDate
        val end = _state.value.planEndDate
        viewModelScope.launch {
            _state.update { it.copy(syncingMealPlan = true, error = null) }
            val res = repository.pushNiblPlanToMealie(start, end)
            res.onSuccess { sync ->
                _state.update { it.copy(syncingMealPlan = false, message = sync.message) }
                loadMealPlan()
            }.onFailure { err ->
                _state.update { it.copy(syncingMealPlan = false, error = err.message ?: "Failed to push Nibl plan") }
            }
        }
    }

    // --- Shopping tab ---

    fun loadShoppingLists() {
        viewModelScope.launch {
            _state.update { it.copy(loadingShopping = true, error = null) }
            val res = repository.getShoppingLists()
            res.onSuccess { lists ->
                val chosen = _state.value.selectedListId?.takeIf { id -> lists.any { it.id == id } }
                    ?: lists.firstOrNull()?.id
                _state.update { it.copy(shoppingLists = lists, selectedListId = chosen, loadingShopping = false) }
                if (chosen != null) {
                    repository.saveDefaultShoppingListId(chosen)
                    loadShoppingItems(chosen)
                }
            }.onFailure { err ->
                _state.update { it.copy(loadingShopping = false, error = err.message ?: "Failed to load shopping lists") }
            }
        }
    }

    fun selectShoppingList(listId: String) {
        _state.update { it.copy(selectedListId = listId) }
        viewModelScope.launch {
            repository.saveDefaultShoppingListId(listId)
            loadShoppingItems(listId)
        }
    }

    fun loadShoppingItems(listId: String = _state.value.selectedListId.orEmpty()) {
        if (listId.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loadingShopping = true, error = null) }
            val res = repository.pullShoppingList(listId)
            res.onSuccess { items ->
                _state.update { it.copy(shoppingItems = items, loadingShopping = false) }
            }.onFailure { err ->
                _state.update { it.copy(loadingShopping = false, error = err.message ?: "Failed to load shopping items") }
            }
        }
    }

    fun toggleShoppingItem(item: MealieShoppingItem) {
        val nextChecked = !item.checked
        // Optimistic update
        _state.update { s ->
            s.copy(
                shoppingItems = s.shoppingItems
                    .map { if (it.id == item.id) it.copy(checked = nextChecked) else it }
                    .sortedWith(compareBy({ it.checked }, { it.position }))
            )
        }
        viewModelScope.launch {
            val res = repository.toggleItem(item, nextChecked)
            res.onFailure { err ->
                // Revert on failure
                _state.update { s ->
                    s.copy(
                        shoppingItems = s.shoppingItems.map { if (it.id == item.id) item else it },
                        error = err.message ?: "Failed to update item"
                    )
                }
            }
        }
    }

    fun addShoppingItem(note: String) {
        val listId = _state.value.selectedListId ?: return
        val clean = note.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            val res = repository.addItem(listId, clean)
            res.onSuccess { added ->
                _state.update { s ->
                    s.copy(
                        shoppingItems = listOf(added) + s.shoppingItems,
                        message = "Added \"$clean\" to Mealie."
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(error = err.message ?: "Failed to add item") }
            }
        }
    }

    fun pushNiblShoppingToMealie() {
        val listId = _state.value.selectedListId ?: return
        viewModelScope.launch {
            _state.update { it.copy(pushingShopping = true, error = null) }
            val res = repository.pushNiblShoppingToMealie(listId)
            res.onSuccess { sync ->
                _state.update { it.copy(pushingShopping = false, message = sync.message) }
                loadShoppingItems(listId)
            }.onFailure { err ->
                _state.update { it.copy(pushingShopping = false, error = err.message ?: "Failed to push Nibl shopping list") }
            }
        }
    }
}
