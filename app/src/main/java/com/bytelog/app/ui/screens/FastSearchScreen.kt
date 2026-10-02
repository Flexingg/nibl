package com.bytelog.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.ui.components.CustomFoodDialog
import com.bytelog.app.ui.components.GenerateFoodAiDialog
import com.bytelog.app.ui.components.MultiAddTray
import com.bytelog.app.ui.components.PortionEditorSheet
import com.bytelog.app.ui.components.QuickAddDialog
import com.bytelog.app.ui.components.VoiceLogDialog
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroProtein
import com.bytelog.app.ui.viewmodel.FastSearchViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastSearchScreen(
    viewModel: FastSearchViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToBarcode: () -> Unit
) {
    val availableMealTypes by viewModel.availableMealTypes.collectAsState()
    val selectedMealName by viewModel.selectedMealName.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val recentFoods by viewModel.recentFoods.collectAsState()
    val favoriteFoods by viewModel.favoriteFoods.collectAsState()
    val suggestedFoods by viewModel.suggestedFoods.collectAsState()
    val stagedEntries by viewModel.stagedEntries.collectAsState()
    val isSearchingRemote by viewModel.isSearchingRemote.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var selectedFoodForPortion by remember { mutableStateOf<FoodItem?>(null) }
    var activeEntryToEdit by remember { mutableStateOf<FoodEntry?>(null) }
    var showQuickAddDialog by remember { mutableStateOf(false) }
    var showVoiceLogDialog by remember { mutableStateOf(false) }
    var showCustomFoodDialog by remember { mutableStateOf(false) }
    var showAiGenerateDialog by remember { mutableStateOf(false) }
    val isGeneratingAi by viewModel.isGeneratingAi.collectAsState()
    val aiGenerationError by viewModel.aiGenerationError.collectAsState()

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        delay(200)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val haptic = LocalHapticFeedback.current

    Scaffold(
        containerColor = ByteInkDark,
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
                    activeEntryToEdit = entry
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = ByteCream
                    )
                }

                // Search Input Field
                TextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = {
                        Text("Search foods or brands…", color = ByteCreamMuted, fontSize = 15.sp)
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
                        focusedContainerColor = ByteSurfaceDark,
                        unfocusedContainerColor = ByteSurfaceDark,
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        cursorColor = ByteTomato,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                )

                // Quick Action Icons
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showCustomFoodDialog = true
                }) {
                    Icon(Icons.Default.Fastfood, contentDescription = "New Custom Food", tint = ByteCream)
                }

                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showQuickAddDialog = true
                }) {
                    Icon(Icons.Default.PostAdd, contentDescription = "Quick Add", tint = ByteCream)
                }

                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showVoiceLogDialog = true
                }) {
                    Icon(Icons.Default.Mic, contentDescription = "Voice Log", tint = ByteCream)
                }

                IconButton(onClick = onNavigateToBarcode) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan", tint = ByteTomato)
                }
            }

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
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) ByteTomato else ByteSurfaceDark)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.setTargetMeal(mt.id, cleanName)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = cleanName,
                                color = if (isSelected) ByteCream else ByteCreamMuted,
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
                    CircularProgressIndicator(
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
                    containerColor = ByteInkDark,
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
            val displayFoods = when {
                searchQuery.isNotBlank() -> searchResults
                selectedTabIndex == 0 -> suggestedFoods
                selectedTabIndex == 1 -> recentFoods
                else -> favoriteFoods
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (searchQuery.isNotBlank() && displayFoods.isEmpty()) {
                    item {
                        if (isSearchingRemote) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
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
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(ByteSurfaceDark)
                                            .border(1.dp, ByteTomato.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
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
                                                shape = RoundedCornerShape(12.dp),
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
                                            onClick = { showCustomFoodDialog = true },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ByteCream)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Custom Food", fontSize = 12.sp)
                                        }
                                        OutlinedButton(
                                            onClick = onNavigateToBarcode,
                                            shape = RoundedCornerShape(12.dp),
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
                    items(displayFoods, key = { it.id }) { food ->
                        FoodSearchRow(
                            food = food,
                            onQuickAdd = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.stageFood(food)
                            },
                            onDoubleTapQuickAdd = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.stageFood(food, quantity = food.servingSize * 2f)
                            },
                            onAdjustPortion = {
                                selectedFoodForPortion = food
                            },
                            onToggleFavorite = {
                                viewModel.toggleFavorite(food)
                            }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }

    // Portion Editor Bottom Sheet
    selectedFoodForPortion?.let { food ->
        ModalBottomSheet(
            onDismissRequest = { selectedFoodForPortion = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ByteSurfaceDark
        ) {
            PortionEditorSheet(
                food = food,
                initialQuantity = food.servingSize,
                initialUnit = food.servingUnit,
                onConfirm = { qty, unit ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.stageFood(food, qty, unit)
                    selectedFoodForPortion = null
                },
                onDismiss = { selectedFoodForPortion = null }
            )
        }
    }

    // Edit Existing Staged Entry Bottom Sheet
    activeEntryToEdit?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { activeEntryToEdit = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ByteSurfaceDark
        ) {
            val dummyFood = FoodItem(
                id = entry.foodId ?: entry.id,
                name = entry.foodName,
                brand = entry.brandName,
                servingSize = entry.quantity,
                servingUnit = entry.unit,
                calories = entry.calories,
                protein = entry.protein,
                carbs = entry.carbs,
                fat = entry.fat
            )
            PortionEditorSheet(
                food = dummyFood,
                initialQuantity = entry.quantity,
                initialUnit = entry.unit,
                onConfirm = { qty, unit ->
                    viewModel.updateStagedEntry(entry, qty, unit)
                    activeEntryToEdit = null
                },
                onDismiss = { activeEntryToEdit = null }
            )
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
            onDismiss = { showCustomFoodDialog = false },
            onCreateCustomFood = { name, brand, servingSize, servingUnit, calories, protein, carbs, fat, fiber ->
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
                    autoStage = true
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
    onQuickAdd: () -> Unit,
    onDoubleTapQuickAdd: () -> Unit,
    onAdjustPortion: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ByteSurfaceDark)
            .combinedClickable(
                onClick = onQuickAdd,
                onDoubleClick = onDoubleTapQuickAdd,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleFavorite()
                }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = food.name,
                    color = ByteCream,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (food.id.startsWith("off_")) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ByteTomato.copy(alpha = 0.18f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "DB",
                            color = ByteTomato,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
            Text(
                text = "${food.brand ?: "Generic"} • ${food.servingSize.roundToInt()}${food.servingUnit} • P: ${food.protein.roundToInt()}g  C: ${food.carbs.roundToInt()}g  F: ${food.fat.roundToInt()}g",
                color = ByteCreamMuted,
                fontSize = 11.sp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Calories Tag (Tappable to tweak portion)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ByteSurfaceVariantDark)
                    .clickable(onClick = onAdjustPortion)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "${food.calories.roundToInt()} kcal",
                    color = ByteTomato,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (food.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (food.isFavorite) ByteTomato else ByteCreamMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
