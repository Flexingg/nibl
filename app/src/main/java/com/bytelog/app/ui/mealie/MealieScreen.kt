package com.bytelog.app.ui.mealie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.mealie.MealieMealPlanEntry
import com.bytelog.app.data.mealie.MealieRecipeSummary
import com.bytelog.app.data.mealie.MealieShoppingItem
import com.bytelog.app.data.mealie.MealieSource
import com.bytelog.app.ui.components.ScreenHeader
import com.bytelog.app.ui.theme.fluidBackdrop
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Full Mealie integration hub: Recipes, 7-day Meal Plan, Shopping Lists, and Connection settings.
 */
@Composable
fun MealieScreen(
    viewModel: MealieViewModel,
    onBack: () -> Unit,
    onOpenRecipe: (String) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    val snackbarHostState = remember { SnackbarHostState() }

    var showScrapeDialog by remember { mutableStateOf(false) }
    var showAddPlanDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.message, state.error, state.lastImportedTemplateId) {
        val importedId = state.lastImportedTemplateId
        val msg = state.error ?: state.message
        if (msg != null) {
            val actionLabel = if (importedId != null && state.error == null) "Open" else null
            val res = snackbarHostState.showSnackbar(message = msg, actionLabel = actionLabel)
            if (res == SnackbarResult.ActionPerformed && importedId != null) {
                onOpenRecipe(importedId)
            }
            viewModel.clearMessage()
            if (importedId != null) viewModel.consumeImportedTemplateId()
        }
    }

    val subtitle = when {
        state.config.isDirectConfigured -> state.config.normalizedUrl.removePrefix("https://").removePrefix("http://")
        state.sparkyMealieProvider != null -> "Connected via Sparky (${state.sparkyMealieProvider!!.name})"
        else -> "Recipes, meal plans & shopping"
    }

    Scaffold(
        modifier = Modifier.fluidBackdrop(),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                ScreenHeader(
                    title = "Mealie",
                    subtitle = subtitle,
                    onBack = onBack,
                    actions = {
                        IconButton(onClick = {
                            when (state.selectedTab) {
                                MealieTab.RECIPES -> viewModel.loadRecipes()
                                MealieTab.MEAL_PLAN -> viewModel.loadMealPlan()
                                MealieTab.SHOPPING -> viewModel.loadShoppingLists()
                                MealieTab.CONNECT -> viewModel.refreshSparkyStatus()
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = cs.onSurface)
                        }
                    }
                )
                MealieTabBar(
                    selected = state.selectedTab,
                    onSelect = viewModel::selectTab
                )
            }
        },
        floatingActionButton = {
            if (state.selectedTab == MealieTab.RECIPES && state.config.isDirectConfigured) {
                FloatingActionButton(
                    onClick = { showScrapeDialog = true },
                    containerColor = cs.primaryContainer,
                    contentColor = cs.onPrimaryContainer
                ) {
                    Icon(Icons.Default.Link, contentDescription = "Scrape URL to Mealie")
                }
            } else if (state.selectedTab == MealieTab.MEAL_PLAN && state.config.isDirectConfigured) {
                FloatingActionButton(
                    onClick = { showAddPlanDialog = true },
                    containerColor = cs.primaryContainer,
                    contentColor = cs.onPrimaryContainer
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Mealie meal plan entry")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (state.selectedTab) {
                MealieTab.RECIPES -> RecipesTab(
                    state = state,
                    onQueryChange = viewModel::onRecipeQueryChanged,
                    onImport = viewModel::importRecipeToNibl,
                    onAddToShopping = viewModel::addRecipeToShoppingList,
                    onOpenConnect = { viewModel.selectTab(MealieTab.CONNECT) }
                )

                MealieTab.MEAL_PLAN -> MealPlanTab(
                    state = state,
                    onShiftWeek = viewModel::shiftPlanWeek,
                    onImportToNibl = viewModel::pullMealPlanToNibl,
                    onPushNiblPlan = viewModel::pushNiblPlanToMealie,
                    onDeleteEntry = viewModel::deleteMealPlanEntry,
                    onOpenConnect = { viewModel.selectTab(MealieTab.CONNECT) }
                )

                MealieTab.SHOPPING -> ShoppingTab(
                    state = state,
                    onSelectList = viewModel::selectShoppingList,
                    onToggleItem = viewModel::toggleShoppingItem,
                    onAddItem = viewModel::addShoppingItem,
                    onPushNiblShopping = viewModel::pushNiblShoppingToMealie,
                    onOpenConnect = { viewModel.selectTab(MealieTab.CONNECT) }
                )

                MealieTab.CONNECT -> ConnectTab(
                    state = state,
                    onUrlChange = viewModel::updateUrlInput,
                    onTokenChange = viewModel::updateTokenInput,
                    onSaveAndTest = viewModel::saveAndTestConnection,
                    onDisconnect = viewModel::disconnectDirect,
                    onRefreshSparky = viewModel::refreshSparkyStatus
                )
            }
        }
    }

    if (showScrapeDialog) {
        ScrapeUrlDialog(
            scraping = state.scrapingUrl,
            onDismiss = { if (!state.scrapingUrl) showScrapeDialog = false },
            onScrape = { url ->
                viewModel.scrapeUrlToMealie(url)
                showScrapeDialog = false
            }
        )
    }

    if (showAddPlanDialog) {
        AddMealPlanDialog(
            startDate = state.planStartDate,
            recipes = state.recipes,
            onDismiss = { showAddPlanDialog = false },
            onAdd = { date, type, title, recipeId ->
                viewModel.addMealPlanEntry(date, type, title, recipeId)
                showAddPlanDialog = false
            }
        )
    }
}

@Composable
private fun MealieTabBar(
    selected: MealieTab,
    onSelect: (MealieTab) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MealieTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) cs.primaryContainer else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tab.label,
                    color = if (active) cs.onPrimaryContainer else cs.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// --- Tab 1: Recipes ---

@Composable
private fun RecipesTab(
    state: MealieUiState,
    onQueryChange: (String) -> Unit,
    onImport: (MealieRecipeSummary) -> Unit,
    onAddToShopping: (MealieRecipeSummary) -> Unit,
    onOpenConnect: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    if (!state.isConnected) {
        EmptyConnectionCard(
            title = "Connect Mealie",
            body = "Add your Mealie URL and API token in the Connect tab, or enable a Mealie provider in Sparky Fitness to browse and import recipes.",
            onOpenConnect = onOpenConnect
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "search") {
            OutlinedTextField(
                value = state.recipeQuery,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search Mealie recipes...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.recipeQuery.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
        }

        if (state.loadingRecipes) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (state.recipes.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(cs.surfaceContainer)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.RestaurantMenu, contentDescription = null, tint = cs.primary, modifier = Modifier.size(28.dp))
                    Text("No recipes found", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.recipeQuery.isBlank()) "Your Mealie library looks empty, or tap the link button to scrape a recipe URL."
                        else "No Mealie recipes matched \"${state.recipeQuery}\".",
                        color = cs.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            items(state.recipes, key = { "${it.source}_${it.id}_${it.slug}" }) { recipe ->
                MealieRecipeCard(
                    recipe = recipe,
                    importing = state.importingSlug == recipe.slug,
                    canAddToShopping = state.config.isDirectConfigured && state.shoppingLists.isNotEmpty(),
                    onImport = { onImport(recipe) },
                    onAddToShopping = { onAddToShopping(recipe) }
                )
            }
        }
    }
}

@Composable
private fun MealieRecipeCard(
    recipe: MealieRecipeSummary,
    importing: Boolean,
    canAddToShopping: Boolean,
    onImport: () -> Unit,
    onAddToShopping: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = recipe.name,
                color = cs.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (recipe.source == MealieSource.DIRECT) cs.primaryContainer else cs.secondaryContainer)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (recipe.source == MealieSource.DIRECT) "Mealie" else "Sparky",
                    color = if (recipe.source == MealieSource.DIRECT) cs.onPrimaryContainer else cs.onSecondaryContainer,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        recipe.description?.takeIf { it.isNotBlank() }?.let { desc ->
            Text(
                text = desc,
                color = cs.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        val meta = buildList {
            val s = recipe.servings
            add(if (s % 1f == 0f) "${s.toInt()} servings" else "%.1f servings".format(s))
            recipe.calories?.let { add("${it.roundToInt()} kcal") }
            recipe.totalTime?.takeIf { it.isNotBlank() }?.let { add(it) }
                ?: recipe.cookTime?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (meta.isNotEmpty()) {
            Text(
                text = meta.joinToString(" · "),
                color = cs.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onImport,
                enabled = !importing,
                modifier = Modifier.weight(1f)
            ) {
                if (importing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Importing...")
                } else {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import to Nibl", maxLines = 1)
                }
            }
            if (canAddToShopping) {
                OutlinedButton(onClick = onAddToShopping) {
                    Icon(Icons.Default.AddShoppingCart, contentDescription = "Add to Shopping List", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// --- Tab 2: Meal Plan ---

@Composable
private fun MealPlanTab(
    state: MealieUiState,
    onShiftWeek: (Long) -> Unit,
    onImportToNibl: () -> Unit,
    onPushNiblPlan: () -> Unit,
    onDeleteEntry: (MealieMealPlanEntry) -> Unit,
    onOpenConnect: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    if (!state.config.isDirectConfigured) {
        EmptyConnectionCard(
            title = "Direct Mealie required",
            body = "Connect directly with your Mealie URL and API token to view, edit, and sync 7-day household meal plans with Nibl's planner.",
            onOpenConnect = onOpenConnect
        )
        return
    }

    val grouped = remember(state.mealPlanEntries) {
        state.mealPlanEntries.groupBy { it.date }.toSortedMap()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "range") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(cs.surfaceContainer)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { onShiftWeek(-7) }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous week")
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${formatShortDate(state.planStartDate)} – ${formatShortDate(state.planEndDate)}",
                            color = cs.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${state.mealPlanEntries.size} planned meal${if (state.mealPlanEntries.size == 1) "" else "s"}",
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = { onShiftWeek(7) }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next week")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onImportToNibl,
                        enabled = !state.syncingMealPlan,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import to Nibl", maxLines = 1, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onPushNiblPlan,
                        enabled = !state.syncingMealPlan,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Push Nibl Plan", maxLines = 1, fontSize = 12.sp)
                    }
                }
            }
        }

        if (state.loadingMealPlan) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (grouped.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(cs.surfaceContainer)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = cs.primary, modifier = Modifier.size(28.dp))
                    Text("No meals planned this week", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Tap + to schedule a meal in Mealie, or push your Nibl planner.", color = cs.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        } else {
            grouped.forEach { (date, entries) ->
                item(key = "day_$date") {
                    Text(
                        text = formatDayHeading(date),
                        color = cs.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                    )
                }
                items(entries, key = { it.id }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(cs.surfaceContainer)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.entryType.replaceFirstChar { it.uppercase() },
                                color = cs.tertiary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = entry.displayTitle,
                                color = cs.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            entry.text?.takeIf { it.isNotBlank() && it != entry.displayTitle }?.let { note ->
                                Text(
                                    text = note,
                                    color = cs.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(onClick = { onDeleteEntry(entry) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete entry", tint = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// --- Tab 3: Shopping ---

@Composable
private fun ShoppingTab(
    state: MealieUiState,
    onSelectList: (String) -> Unit,
    onToggleItem: (MealieShoppingItem) -> Unit,
    onAddItem: (String) -> Unit,
    onPushNiblShopping: () -> Unit,
    onOpenConnect: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    if (!state.config.isDirectConfigured) {
        EmptyConnectionCard(
            title = "Direct Mealie required",
            body = "Connect your Mealie instance in the Connect tab to check off Mealie shopping lists and push Nibl's weekly groceries.",
            onOpenConnect = onOpenConnect
        )
        return
    }

    var newItemText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.shoppingLists.isNotEmpty()) {
            item(key = "lists") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.shoppingLists.forEach { list ->
                        FilterChip(
                            selected = list.id == state.selectedListId,
                            onClick = { onSelectList(list.id) },
                            label = { Text(list.name) }
                        )
                    }
                }
            }
        }

        if (state.selectedListId != null) {
            item(key = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newItemText,
                            onValueChange = { newItemText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Add item to Mealie...") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (newItemText.isNotBlank()) {
                                    onAddItem(newItemText)
                                    newItemText = ""
                                }
                            })
                        )
                        FilledTonalButton(
                            onClick = {
                                if (newItemText.isNotBlank()) {
                                    onAddItem(newItemText)
                                    newItemText = ""
                                }
                            },
                            enabled = newItemText.isNotBlank()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add item")
                        }
                    }

                    OutlinedButton(
                        onClick = onPushNiblShopping,
                        enabled = !state.pushingShopping,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.pushingShopping) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Pushing Nibl list...")
                        } else {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Push Nibl List to Mealie")
                        }
                    }
                }
            }
        }

        if (state.loadingShopping) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (state.shoppingLists.isEmpty()) {
            item(key = "no_lists") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(cs.surfaceContainer)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = cs.primary, modifier = Modifier.size(28.dp))
                    Text("No shopping lists in Mealie", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Create a shopping list in Mealie and tap Refresh.", color = cs.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        } else if (state.shoppingItems.isEmpty()) {
            item(key = "empty_items") {
                Text(
                    text = "This Mealie shopping list is empty.",
                    color = cs.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        } else {
            items(state.shoppingItems, key = { it.id }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(cs.surfaceContainer)
                        .clickable { onToggleItem(item) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = item.checked,
                        onCheckedChange = { onToggleItem(item) }
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            text = item.label,
                            color = if (item.checked) cs.onSurfaceVariant else cs.onSurface,
                            fontSize = 15.sp,
                            textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None
                        )
                        item.labelName?.takeIf { it.isNotBlank() }?.let { aisle ->
                            Text(aisle, color = cs.onSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// --- Tab 4: Connect ---

@Composable
private fun ConnectTab(
    state: MealieUiState,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSaveAndTest: () -> Unit,
    onDisconnect: () -> Unit,
    onRefreshSparky: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var showToken by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "direct_card") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(cs.surfaceContainer)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (state.config.isDirectConfigured) cs.primaryContainer else cs.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (state.config.isDirectConfigured) Icons.Default.CloudDone else Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = if (state.config.isDirectConfigured) cs.onPrimaryContainer else cs.onSurfaceVariant
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Direct Mealie Instance", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (state.config.isDirectConfigured) "Token stored encrypted via Android Keystore"
                            else "Enables full recipes, meal plans, shopping & URL scraping",
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }

                OutlinedTextField(
                    value = state.urlInput,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Mealie Server URL") },
                    placeholder = { Text("https://mealie.yourdomain.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(14.dp)
                )

                OutlinedTextField(
                    value = state.tokenInput,
                    onValueChange = onTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Mealie API Token") },
                    placeholder = { Text("Long-lived token from User Profile → Manage API Tokens") },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                imageVector = if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showToken) "Hide token" else "Show token"
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp)
                )

                state.connectionStatus?.let { status ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when (state.connectionSuccess) {
                                    true -> cs.primaryContainer.copy(alpha = 0.5f)
                                    false -> cs.errorContainer.copy(alpha = 0.6f)
                                    null -> cs.surfaceContainerHigh
                                }
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.connectionSuccess == true) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            text = status,
                            color = if (state.connectionSuccess == false) cs.onErrorContainer else cs.onSurface,
                            fontSize = 13.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onSaveAndTest,
                        enabled = !state.testingConnection && state.urlInput.isNotBlank() && state.tokenInput.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (state.testingConnection) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("Testing...")
                        } else {
                            Text("Save & Test Connection")
                        }
                    }
                    if (state.config.isDirectConfigured) {
                        OutlinedButton(onClick = onDisconnect) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }

        item(key = "sparky_status") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(cs.surfaceContainer)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sparky Mealie Provider", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onRefreshSparky, enabled = !state.checkingSparkyProvider) {
                        Text(if (state.checkingSparkyProvider) "Checking..." else "Check")
                    }
                }
                val provider = state.sparkyMealieProvider
                Text(
                    text = if (provider != null) {
                        "Active on Sparky (${provider.name}). Recipe browsing and Sparky nutrition sync-back are available even without Direct Mealie credentials."
                    } else {
                        "No active Mealie provider detected on your Sparky Fitness server. You can still use Direct Mealie above."
                    },
                    color = cs.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
    }
}

// --- Shared helpers & dialogs ---

@Composable
private fun EmptyConnectionCard(
    title: String,
    body: String,
    onOpenConnect: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(cs.surfaceContainer)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Default.RestaurantMenu, contentDescription = null, tint = cs.primary, modifier = Modifier.size(32.dp))
        Text(title, color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = cs.onSurfaceVariant, fontSize = 13.sp)
        FilledTonalButton(onClick = onOpenConnect) {
            Text("Open Connect Tab")
        }
    }
}

@Composable
private fun ScrapeUrlDialog(
    scraping: Boolean,
    onDismiss: () -> Unit,
    onScrape: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scrape URL to Mealie") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste any recipe web link and Mealie will import its ingredients, instructions, and photo.",
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onScrape(url) },
                enabled = !scraping && (url.trim().startsWith("http://") || url.trim().startsWith("https://"))
            ) {
                Text(if (scraping) "Scraping..." else "Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !scraping) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun AddMealPlanDialog(
    startDate: String,
    recipes: List<MealieRecipeSummary>,
    onDismiss: () -> Unit,
    onAdd: (date: String, entryType: String, title: String, recipeId: String?) -> Unit
) {
    val start = remember(startDate) { runCatching { LocalDate.parse(startDate) }.getOrDefault(LocalDate.now()) }
    val days = remember(start) { (0L..6L).map { start.plusDays(it).toString() } }
    val mealTypes = listOf("breakfast", "lunch", "dinner", "snack", "side")

    var selectedDate by remember { mutableStateOf(days.first()) }
    var selectedType by remember { mutableStateOf("dinner") }
    var title by remember { mutableStateOf("") }
    var selectedRecipe by remember { mutableStateOf<MealieRecipeSummary?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Mealie Plan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Day", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    days.forEach { d ->
                        FilterChip(
                            selected = selectedDate == d,
                            onClick = { selectedDate = d },
                            label = { Text(formatShortDate(d), fontSize = 12.sp) }
                        )
                    }
                }

                Text("Meal", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    mealTypes.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(type.replaceFirstChar { it.uppercase() }, fontSize = 12.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        if (selectedRecipe != null && it != selectedRecipe?.name) selectedRecipe = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Meal title or note") },
                    singleLine = true
                )

                if (recipes.isNotEmpty()) {
                    Text("Or pick a Mealie recipe", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        recipes.take(12).forEach { r ->
                            FilterChip(
                                selected = selectedRecipe?.id == r.id,
                                onClick = {
                                    selectedRecipe = r
                                    title = r.name
                                },
                                label = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(selectedDate, selectedType, title, selectedRecipe?.id) },
                enabled = title.isNotBlank() || selectedRecipe != null
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private val shortDateFmt = DateTimeFormatter.ofPattern("EEE M/d", Locale.US)
private val fullDayFmt = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)

private fun formatShortDate(iso: String): String =
    runCatching { LocalDate.parse(iso.take(10)).format(shortDateFmt) }.getOrDefault(iso)

private fun formatDayHeading(iso: String): String =
    runCatching { LocalDate.parse(iso.take(10)).format(fullDayFmt) }.getOrDefault(iso)
