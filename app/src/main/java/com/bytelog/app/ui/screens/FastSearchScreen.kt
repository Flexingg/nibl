package com.bytelog.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import com.bytelog.app.ui.theme.fluidBackdrop
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import com.bytelog.app.ui.components.pressBounce
import com.bytelog.app.ui.components.popIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import com.bytelog.app.ui.logging.Portion
import com.bytelog.app.ui.logging.Portions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.ui.components.CustomFoodDialog
import com.bytelog.app.ui.components.GenerateFoodAiDialog
import com.bytelog.app.ui.components.MultiAddTray
import com.bytelog.app.ui.components.QuickAddDialog
import com.bytelog.app.ui.components.VoiceLogDialog
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroProtein
import com.bytelog.app.ui.viewmodel.FastSearchViewModel
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.DocumentScanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastSearchScreen(
    viewModel: FastSearchViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToBarcode: () -> Unit,
    onEditFood: (String) -> Unit = {},
    /** Opened from "Log from photo": the camera starts straight away. */
    startWithPhoto: Boolean = false
) {
    val availableMealTypes by viewModel.availableMealTypes.collectAsState()
    val selectedMealName by viewModel.selectedMealName.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val mealResults by viewModel.mealResults.collectAsState()
    val recentFoods by viewModel.recentFoods.collectAsState()
    val favoriteFoods by viewModel.favoriteFoods.collectAsState()
    val suggestedFoods by viewModel.suggestedFoods.collectAsState()
    val stagedEntries by viewModel.stagedEntries.collectAsState()
    val isSearchingRemote by viewModel.isSearchingRemote.collectAsState()
    val activeStagedId by viewModel.activeStagedId.collectAsState()
    val planDays by viewModel.planDays.collectAsState()
    var showPlanDays by remember { mutableStateOf(false) }
    if (showPlanDays) {
        com.bytelog.app.ui.components.PlanDaysDialog(
            title = "Plan on which days?",
            note = "Everything in the tray goes on each day you pick, planned for $selectedMealName. It's ticked off as you eat it.",
            initial = planDays.ifEmpty { setOfNotNull(viewModel.targetDate.takeIf { com.bytelog.app.data.repository.PlanRepository.isFuture(it) }) },
            confirmLabel = { n -> "Plan on $n day${if (n == 1) "" else "s"}" },
            onConfirm = { viewModel.setPlanDays(it); showPlanDays = false },
            onDismiss = { showPlanDays = false }
        )
    }
    val lastPortions by viewModel.lastPortions.collectAsState()
    val together by viewModel.together.collectAsState()

    // The tab last used (Suggested, Recents or Favorites) is where the next search opens
    val context = androidx.compose.ui.platform.LocalContext.current
    val tabPrefs = remember { context.getSharedPreferences("bytelog_search", android.content.Context.MODE_PRIVATE) }
    var selectedTabIndex by remember { mutableIntStateOf(runCatching { tabPrefs.getInt("tab", 0) }.getOrDefault(0).coerceIn(0, 2)) }
    LaunchedEffect(selectedTabIndex) { runCatching { tabPrefs.edit().putInt("tab", selectedTabIndex).apply() } }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var searchFocused by remember { mutableStateOf(false) }
    var showQuickAddDialog by remember { mutableStateOf(false) }
    var showVoiceLogDialog by remember { mutableStateOf(false) }
    var showCustomFoodDialog by remember { mutableStateOf(false) }
    var showAiGenerateDialog by remember { mutableStateOf(false) }
    val isGeneratingAi by viewModel.isGeneratingAi.collectAsState()
    val aiGenerationError by viewModel.aiGenerationError.collectAsState()

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // The keyboard would cover the suggestions, so it only opens by itself when there is nothing to suggest yet
    LaunchedEffect(Unit) {
        if (viewModel.shouldFocusSearchOnOpen()) {
            runCatching { focusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    val haptic = LocalHapticFeedback.current

    // Photo logging: the AI lists each food on the plate; the chosen ones join the tray
    val photoState by viewModel.photoState.collectAsState()
    val photoCapture = com.bytelog.app.ui.logging.rememberMealPhotoCapture(
        onPhoto = { focusManager.clearFocus(); viewModel.estimatePhoto(it) },
        onError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
    )
    // Nutrition label: the photo is read by Sparky's AI and fills the custom food form
    val labelScan by viewModel.labelScan.collectAsState()
    val labelCapture = com.bytelog.app.ui.logging.rememberMealPhotoCapture(
        onPhoto = { viewModel.scanLabel(it) },
        onError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
    )
    var photoStarted by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startWithPhoto) {
        if (startWithPhoto && !photoStarted) { photoStarted = true; photoCapture.takePhoto() }
    }
    com.bytelog.app.ui.logging.PhotoEstimateSheet(
        state = photoState,
        mealName = selectedMealName,
        onAdd = { items ->
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.stagePhotoItems(items)
        },
        onRetake = photoCapture.takePhoto,
        onGallery = photoCapture.pickFromGallery,
        onReestimate = { viewModel.reestimatePhoto(it) },
        onDismiss = { viewModel.dismissPhoto() }
    )

    // Right after something is added (nothing typed yet): what you usually have with it, one tap each
    val trayFoodIds = remember(stagedEntries) { stagedEntries.mapNotNull { it.foodId }.toSet() }
    val togetherPicks = remember(together, lastPortions, trayFoodIds) {
        together?.foods.orEmpty().filter { it.id !in trayFoodIds }.distinctBy { it.id }.take(6).map { com.bytelog.app.ui.components.TogetherPick(it, viewModel.portionFor(it)) }
    }
    LaunchedEffect(together) { together?.foods?.let { viewModel.prefetchPortions(it) } }

    // Leaving with food in the tray asks first, rather than dropping it
    var confirmLeave by remember { mutableStateOf(false) }
    val leave: () -> Unit = { if (stagedEntries.isNotEmpty()) confirmLeave = true else onNavigateBack() }
    androidx.activity.compose.BackHandler(enabled = stagedEntries.isNotEmpty()) { confirmLeave = true }
    if (confirmLeave) {
        com.bytelog.app.ui.components.LeaveTrayDialog(
            count = stagedEntries.size, verb = if (viewModel.isPlanning) "Plan" else "Log", mealName = selectedMealName,
            onLog = { confirmLeave = false; viewModel.commitStagedTray { onNavigateBack() } },
            onDiscard = { confirmLeave = false; onNavigateBack() },
            onKeepAdding = { confirmLeave = false }
        )
    }

    Scaffold(
        modifier = Modifier.fluidBackdrop(),
        containerColor = Color.Transparent,
        bottomBar = {
            MultiAddTray(
                stagedEntries = stagedEntries,
                targetMealName = selectedMealName,
                onLogAll = {
                    viewModel.commitStagedTray {
                        onNavigateBack()
                    }
                },
                onRemoveEntry = { viewModel.removeStagedEntry(it) },
                onEditEntry = { entry ->
                    focusManager.clearFocus()
                    viewModel.selectStagedEntry(entry.id)
                },
                activeEntryId = activeStagedId,
                foodFor = viewModel::foodFor,
                unitsFor = viewModel::unitOptionsFor,
                onPortionChange = viewModel::updateStagedPortion,
                // While typing a search the tray shrinks to its chips and button so results stay visible
                compact = searchFocused,
                editableFoodId = viewModel::editableFoodId,
                onEditFood = { focusManager.clearFocus(); onEditFood(it) },
                meals = availableMealTypes.distinctBy { it.name.trim().lowercase() }.map { mt ->
                    mt.id to mt.name.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                },
                onPickMeal = { id, name -> viewModel.setTargetMeal(id, name) },
                verb = if (viewModel.isPlanning) "Plan" else "Log",
                onPickDays = if (viewModel.canPlan) ({ focusManager.clearFocus(); showPlanDays = true }) else null,
                planDayCount = planDays.size,
                suggestions = if (searchQuery.isBlank() && togetherPicks.isNotEmpty()) ({
                    com.bytelog.app.ui.components.TogetherStrip(
                        label = together?.label.orEmpty(), picks = togetherPicks,
                        onAdd = { viewModel.tapFood(it) }
                    )
                }) else null,
                // Search sits at the bottom, by the tray and the keyboard, so typing, picking and logging stay in thumb reach
                header = {
                TextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = {
                        Text("Search foods or brands…", color = ByteCreamMuted, fontSize = 16.sp)
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = ByteTomato
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = ByteCreamMuted)
                            }
                        }
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        cursorColor = ByteTomato,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { searchFocused = it.isFocused }
                )
                }
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Expressive header: title, a big pill search field and the other ways to add food
            com.bytelog.app.ui.components.ScreenHeader(
                title = "Log food",
                subtitle = if (viewModel.isPlanning) {
                    val day = runCatching { java.time.LocalDate.parse(viewModel.targetDate.take(10)) }.getOrNull()
                    "Planning for " + (day?.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d")) ?: "later") + ". Tick it off when you eat it"
                } else "Search, scan, speak or add your own",
                onBack = leave,
                statusBarPadding = false
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val cs = MaterialTheme.colorScheme
                com.bytelog.app.ui.diary.ConnectedButtonGroup(
                    listOf(
                        com.bytelog.app.ui.diary.GroupButton(
                            label = "Scan", icon = Icons.Default.QrCodeScanner, description = "Scan", weight = 1.1f,
                            container = cs.primaryContainer, content = cs.onPrimaryContainer, onClick = onNavigateToBarcode
                        ),
                        com.bytelog.app.ui.diary.GroupButton(
                            icon = Icons.Default.PhotoCamera, description = "Log from photo", weight = 0.8f,
                            container = cs.secondaryContainer, content = cs.onSecondaryContainer,
                            onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); photoCapture.takePhoto() }
                        ),
                        com.bytelog.app.ui.diary.GroupButton(
                            icon = Icons.Default.Mic, description = "Voice Log", weight = 0.8f,
                            container = cs.surfaceContainerHighest, content = cs.onSurface,
                            onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); showVoiceLogDialog = true }
                        ),
                        com.bytelog.app.ui.diary.GroupButton(
                            icon = Icons.Default.PostAdd, description = "Quick Add", weight = 0.8f,
                            container = cs.surfaceContainerHighest, content = cs.onSurface,
                            onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); showQuickAddDialog = true }
                        ),
                        com.bytelog.app.ui.diary.GroupButton(
                            icon = Icons.Default.Fastfood, description = "New Custom Food", weight = 0.8f,
                            container = cs.surfaceContainerHighest, content = cs.onSurface,
                            onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); showCustomFoodDialog = true }
                        )
                    )
                )
            }
            Spacer(Modifier.height(4.dp))

            // Meal Picker Selector Chips (Deduplicated)
            val distinctMealTypes = remember(availableMealTypes) {
                availableMealTypes.distinctBy { it.name.trim().lowercase() }
            }
            if (distinctMealTypes.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(distinctMealTypes, key = { it.id }) { mt ->
                        val cleanName = mt.name.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        val isSelected = cleanName.equals(selectedMealName, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) ByteTomato else ByteSurfaceDark)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.setTargetMeal(mt.id, cleanName)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = cleanName,
                                color = if (isSelected) ByteOnTomato else ByteCreamMuted,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Remote Database Searching Indicator
            AnimatedVisibility(visible = isSearchingRemote) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.bytelog.app.ui.diary.LoadingSpinner(
                        modifier = Modifier.size(12.dp),
                        color = ByteTomato,
                        strokeWidth = 1.5.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Searching online databases (OpenFoodFacts & Sparky)…",
                        color = ByteCreamMuted,
                        fontSize = 11.sp
                    )
                }
            }

            // Zero-Query Context Tabs (when search query is blank)
            if (searchQuery.isBlank()) {
                val tabs = listOf("Suggested", "Recents", "Favorites")
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = Color.Transparent,
                    contentColor = ByteCream,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                            color = ByteTomato
                        )
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 13.sp,
                                    fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        )
                    }
                }
            }

            // Food List
            val searching = searchQuery.isNotBlank()
            val searchFoods = remember(searchResults) { searchResults.all.map { it.food } }
            val timesLogged = remember(searchResults) { searchResults.logged.associate { it.food.id to it.timesLogged } }
            // While searching, foods you've logged form their own group above everything else
            val loggedCount = if (searching) searchResults.logged.size else 0
            // List keys are food ids, so each food once
            val displayFoods = when {
                searching -> searchFoods
                selectedTabIndex == 0 -> suggestedFoods.distinctBy { it.id }
                selectedTabIndex == 1 -> recentFoods.distinctBy { it.id }
                else -> favoriteFoods.distinctBy { it.id }
            }

            LaunchedEffect(displayFoods) { viewModel.prefetchPortions(displayFoods) }
            val inTray = remember(stagedEntries) { stagedEntries.mapNotNull { it.foodId }.toSet() }

            val poppedResults = remember { mutableSetOf<Any>() }
            // One food result (shared by the groups below)
            val foodResult: @Composable (Int, FoodItem) -> Unit = { index, food ->
                // Results read as segmented groups (yours, then the rest): round outer corners, tight inner ones.
                val outer = 24.dp; val inner = 6.dp
                val groupStart = index == 0 || index == loggedCount
                val groupEnd = index == displayFoods.lastIndex || index == loggedCount - 1
                val rowShape = RoundedCornerShape(
                    topStart = if (groupStart) outer else inner, topEnd = if (groupStart) outer else inner,
                    bottomStart = if (groupEnd) outer else inner, bottomEnd = if (groupEnd) outer else inner
                )
                val header = when {
                    loggedCount == 0 -> null
                    index == 0 -> "YOU'VE LOGGED"
                    index == loggedCount -> "MORE FOODS"
                    else -> null
                }
                if (header != null) {
                    Text(
                        header, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 6.dp, top = if (index == 0) 0.dp else 12.dp, bottom = 6.dp)
                    )
                }
                FoodSearchRow(
                    shape = rowShape,
                    modifier = Modifier.popIn(food.id, poppedResults, (index.coerceAtMost(8) * 25).toLong()),
                    timesLogged = if (searching) timesLogged[food.id] ?: 0 else 0,
                    food = food,
                    portion = remember(food, lastPortions) { viewModel.portionFor(food) },
                    inTray = food.id in inTray,
                    onQuickAdd = {
                        // One tap: in the tray at the usual amount and the search cleared. While typing, the keyboard
                        // stays up so the next food is a search away (a chip in the tray adjusts the amount);
                        // from the lists the amount editor opens as before.
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.tapFood(food)
                        if (searchQuery.isNotBlank()) viewModel.setSearchQuery("")
                        if (!searchFocused) focusManager.clearFocus()
                    },
                    onEdit = { onEditFood(food.id) },
                    onToggleFavorite = {
                        viewModel.toggleFavorite(food)
                    },
                    onSwipeLog = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.logNow(food) { logged ->
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                val r = snackbarHostState.showSnackbar(
                                    "${if (viewModel.isPlanning) "Planned" else "Logged"} ${food.name} to $selectedMealName", actionLabel = "Undo",
                                    duration = androidx.compose.material3.SnackbarDuration.Short
                                )
                                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) viewModel.undoLogged(logged)
                            }
                        }
                    }
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (searchQuery.isNotBlank() && displayFoods.isEmpty() && mealResults.isEmpty()) {
                    item {
                        if (isSearchingRemote) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    com.bytelog.app.ui.diary.LoadingSpinner(
                                        color = ByteTomato,
                                        modifier = Modifier.size(32.dp),
                                        strokeWidth = 3.dp
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "Searching food databases…",
                                        color = ByteCream,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Querying OpenFoodFacts & custom foods on Sparky",
                                        color = ByteCreamMuted,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                ) {
                                    // Highlighted AI Generation Card
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(24.dp))
                                            .background(ByteSurfaceDark)
                                            .border(1.dp, ByteTomato.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                                            .padding(18.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.AutoAwesome,
                                                    contentDescription = null,
                                                    tint = ByteTomato,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "No results for \"$searchQuery\"",
                                                    color = ByteCream,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "Let Sparky AI estimate realistic macros and create this food in your library.",
                                                color = ByteCreamMuted,
                                                fontSize = 12.sp,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(14.dp))
                                            Button(
                                                onClick = { showAiGenerateDialog = true },
                                                colors = ButtonDefaults.buttonColors(containerColor = ByteTomato),
                                                shape = RoundedCornerShape(50),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.AutoAwesome,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Generate with AI",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Secondary options: Manual Custom Food & Barcode Scan
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedButton(
                                            // Not in any database: read it off the packet
                                            onClick = { showCustomFoodDialog = true; labelCapture.takePhoto() },
                                            shape = RoundedCornerShape(50),
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ByteCream)
                                        ) {
                                            Icon(Icons.Default.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Scan Label", fontSize = 12.sp)
                                        }
                                        OutlinedButton(
                                            onClick = onNavigateToBarcode,
                                            shape = RoundedCornerShape(50),
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ByteCream)
                                        ) {
                                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Scan Barcode", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Foods you've logged, then your meals and recipes, then everything else
                    itemsIndexed(displayFoods.take(loggedCount), key = { _, f -> f.id }) { index, food -> foodResult(index, food) }
                    if (searching && mealResults.isNotEmpty()) {
                        item(key = "meals_header") {
                            Text(
                                "YOUR MEALS & RECIPES", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 6.dp, top = if (loggedCount > 0) 12.dp else 0.dp, bottom = 6.dp)
                            )
                        }
                        itemsIndexed(mealResults, key = { _, t -> "meal:" + t.id }) { index, template ->
                            val outer = 24.dp; val inner = 6.dp
                            MealSearchRow(
                                template = template,
                                shape = RoundedCornerShape(
                                    topStart = if (index == 0) outer else inner, topEnd = if (index == 0) outer else inner,
                                    bottomStart = if (index == mealResults.lastIndex) outer else inner, bottomEnd = if (index == mealResults.lastIndex) outer else inner
                                ),
                                onAdd = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val added = viewModel.stageTemplate(template)
                                    if (added > 0) {
                                        viewModel.setSearchQuery("")
                                        if (!searchFocused) focusManager.clearFocus()
                                    } else scope.launch { snackbarHostState.showSnackbar("${template.name} has no foods with nutrition to add") }
                                }
                            )
                        }
                    }
                    itemsIndexed(displayFoods.drop(loggedCount), key = { _, f -> f.id }) { index, food -> foodResult(index + loggedCount, food) }
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }

    // Quick Add Dialog
    if (showQuickAddDialog) {
        QuickAddDialog(
            mealTypeId = viewModel.targetMealTypeId,
            mealName = viewModel.targetMealName,
            date = viewModel.targetDate,
            onDismiss = { showQuickAddDialog = false },
            onConfirm = { entry ->
                viewModel.stageEntries(listOf(entry))
                showQuickAddDialog = false
            }
        )
    }

    // Voice Log Dialog
    if (showVoiceLogDialog) {
        VoiceLogDialog(
            mealTypeId = viewModel.targetMealTypeId,
            mealName = viewModel.targetMealName,
            date = viewModel.targetDate,
            onDismiss = { showVoiceLogDialog = false },
            onParsedEntries = { entries ->
                viewModel.stageEntries(entries)
                showVoiceLogDialog = false
            }
        )
    }

    // Custom Food Dialog
    if (showCustomFoodDialog) {
        CustomFoodDialog(
            onDismiss = { showCustomFoodDialog = false; viewModel.clearLabelScan() },
            labelScan = labelScan,
            onScanLabel = { labelCapture.takePhoto() },
            onPickLabel = { labelCapture.pickFromGallery() },
            onCreateCustomFood = { name, brand, servingSize, servingUnit, calories, protein, carbs, fat, fiber, household ->
                viewModel.createCustomFood(
                    name = name,
                    brand = brand,
                    servingSize = servingSize,
                    servingUnit = servingUnit,
                    calories = calories,
                    protein = protein,
                    carbs = carbs,
                    fat = fat,
                    fiber = fiber,
                    autoStage = true,
                    household = household
                )
                showCustomFoodDialog = false
            }
        )
    }

    // AI Food Generation Dialog
    if (showAiGenerateDialog) {
        GenerateFoodAiDialog(
            initialName = searchQuery,
            isGenerating = isGeneratingAi,
            errorMessage = aiGenerationError,
            onDismiss = { showAiGenerateDialog = false },
            onGenerate = { name, description, imageBase64, imageMimeType ->
                viewModel.generateFoodWithAi(
                    name = name,
                    description = description,
                    imageBase64 = imageBase64,
                    imageMimeType = imageMimeType,
                    autoStage = true,
                    onSuccess = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showAiGenerateDialog = false
                    }
                )
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FoodSearchRow(
    food: FoodItem,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    modifier: Modifier = Modifier,
    portion: Portion = Portions.defaultFor(food),
    inTray: Boolean = false,
    onQuickAdd: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit = {},
    /** Swipe right: log it now at this amount, without the tray. */
    onSwipeLog: (() -> Unit)? = null,
    /** How many times you've logged it, shown while searching. */
    timesLogged: Int = 0
) {
    if (onSwipeLog == null) {
        FoodSearchRowContent(food, shape, modifier, portion, inTray, onQuickAdd, onToggleFavorite, onEdit, timesLogged)
        return
    }
    val cs = MaterialTheme.colorScheme
    val latestLog = androidx.compose.runtime.rememberUpdatedState(onSwipeLog)
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd) latestLog.value()
            false // snaps back; the food is logged, not removed from the list
        },
        positionalThreshold = { it * 0.35f }
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            // Only while swiping: rows in the tray are tinted see-through
            if (state.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd) Row(
                Modifier.fillMaxSize().clip(shape).background(cs.primary).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Log now · ${Portions.describe(food, portion)}", color = cs.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    ) {
        FoodSearchRowContent(food, shape, Modifier, portion, inTray, onQuickAdd, onToggleFavorite, onEdit, timesLogged)
    }
}

/** A saved meal or recipe in search results: one tap puts a serving's foods in the tray. */
@Composable
private fun MealSearchRow(template: com.bytelog.app.data.local.model.MealTemplate, shape: androidx.compose.ui.graphics.Shape, onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val rows = remember(template.foodsJson) { com.bytelog.app.recipes.TemplateNutrition.parseRows(template.foodsJson) }
    val servings = template.totalServings.takeIf { it > 0f } ?: 1f
    val kcal = remember(template.foodsJson, servings) {
        ((com.bytelog.app.recipes.TemplateNutrition.totals(template.foodsJson)?.calories ?: template.calories) / servings).roundToInt()
    }
    val recipe = servings > 1f
    Row(
        Modifier
            .fillMaxWidth()
            .pressBounce(0.98f)
            .clip(shape)
            .background(cs.surfaceContainer)
            .clickable(onClickLabel = "Add to tray", onClick = onAdd)
            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.RestaurantMenu, contentDescription = null, tint = cs.onTertiaryContainer, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(template.name, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(
                (if (recipe) "Recipe · 1 of ${servings.roundToInt()} servings" else "Meal") + " · ${rows.size} ${if (rows.size == 1) "food" else "foods"}",
                color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1
            )
        }
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(cs.primaryContainer).padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$kcal kcal", color = cs.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.Add, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FoodSearchRowContent(
    food: FoodItem,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier,
    portion: Portion,
    inTray: Boolean,
    onQuickAdd: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    timesLogged: Int = 0
) {
    val haptic = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val kcal = remember(food, portion) { Portions.macros(food, portion).calories.roundToInt() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressBounce(0.98f)
            .clip(shape)
            .background(if (inTray) cs.secondaryContainer.copy(alpha = 0.55f) else cs.surfaceContainer)
            // No double-tap handler: a single tap adds instantly instead of waiting to rule out a second tap
            .combinedClickable(
                onClick = onQuickAdd,
                // Long press opens the food itself for editing; the heart handles favourites
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEdit()
                },
                onLongClickLabel = "Edit food"
            )
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = food.name,
                    color = cs.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (food.id.startsWith("off_")) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(cs.primary.copy(alpha = 0.18f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(text = "DB", color = cs.primary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text(
                text = (if (timesLogged > 0) "Logged ${timesLogged}× • " else "") + "${food.brand ?: "Generic"} • ${Portions.describe(food, portion)}",
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Calories for the amount a tap adds, with a + (or a check once it is in the tray)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (inTray) cs.primary else cs.primaryContainer)
                    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$kcal kcal",
                    color = if (inTray) cs.onPrimary else cs.onPrimaryContainer,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (inTray) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = if (inTray) "In tray" else null,
                    tint = if (inTray) cs.onPrimary else cs.onPrimaryContainer,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (food.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (food.isFavorite) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
