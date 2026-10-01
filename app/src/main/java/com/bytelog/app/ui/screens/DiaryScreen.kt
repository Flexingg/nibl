package com.bytelog.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.ui.components.BodyweightCard
import com.bytelog.app.ui.components.CopyMealDialog
import com.bytelog.app.ui.components.DailyMacroHeader
import com.bytelog.app.ui.components.EditFoodEntrySheet
import com.bytelog.app.ui.components.HydrationCard
import com.bytelog.app.ui.components.HydrationSheet
import com.bytelog.app.ui.components.MealTemplatesSheet
import com.bytelog.app.ui.components.RecipeBuilderSheet
import com.bytelog.app.ui.components.SaveMealTemplateDialog
import com.bytelog.app.ui.components.WeightLogDialog
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import com.bytelog.app.ui.viewmodel.DiaryViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    viewModel: DiaryViewModel,
    onNavigateToSearch: (mealTypeId: String, mealName: String, date: String) -> Unit,
    onNavigateToBarcode: (mealTypeId: String, mealName: String, date: String) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val selectedDate by viewModel.selectedDate.collectAsState()
    val dailySummary by viewModel.dailySummary.collectAsState()
    val entries by viewModel.entriesForDate.collectAsState()
    val mealTypes by viewModel.mealTypes.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()

    val waterTotalOz by viewModel.waterTotalOz.collectAsState()
    val waterGoalOz by viewModel.targetWaterOz.collectAsState()
    val defaultContainerOz by viewModel.defaultContainerOz.collectAsState()
    val defaultContainerName by viewModel.defaultContainerName.collectAsState()
    val waterLogs by viewModel.waterLogsForDate.collectAsState()
    val waterContainers by viewModel.waterContainers.collectAsState()

    val currentWeight by viewModel.currentWeight.collectAsState()
    val previousWeight by viewModel.previousWeight.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val mealTemplates by viewModel.mealTemplates.collectAsState()
    val availableFoods by viewModel.availableFoods.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        viewModel.syncMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val isToday = remember(selectedDate) {
        selectedDate == LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    }

    data class DisplayMealGroup(
        val mealTypeId: String,
        val mealName: String,
        val calories: Float,
        val entries: List<FoodEntry>
    )

    var editingEntry by remember { mutableStateOf<FoodEntry?>(null) }
    var showHydrationSheet by remember { mutableStateOf(false) }
    var showWeightDialog by remember { mutableStateOf(false) }
    var copyingMealName by remember { mutableStateOf<String?>(null) }
    var savingTemplateMealName by remember { mutableStateOf<String?>(null) }
    var showTemplatesSheet by remember { mutableStateOf(false) }
    var templateTargetMeal by remember { mutableStateOf<DisplayMealGroup?>(null) }
    var showRecipeBuilderSheet by remember { mutableStateOf(false) }

    val templatesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val recipeBuilderSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val loggedMealGroups: List<DisplayMealGroup> = remember(entries, mealTypes) {
        if (entries.isEmpty()) return@remember emptyList()

        val grouped = entries.groupBy { it.mealName.trim().lowercase() }
        grouped.map { (lowerMealKey, mealEntries) ->
            val matchedMealType = mealTypes.find {
                it.name.trim().equals(lowerMealKey, ignoreCase = true) ||
                it.id.equals(mealEntries.first().mealTypeId, ignoreCase = true)
            }
            val displayName = matchedMealType?.name?.trim()?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                ?: mealEntries.first().mealName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val mealId = matchedMealType?.id ?: mealEntries.first().mealTypeId

            DisplayMealGroup(
                mealTypeId = mealId,
                mealName = displayName,
                calories = mealEntries.sumOf { it.calories.toDouble() }.toFloat(),
                entries = mealEntries
            )
        }.sortedBy { group ->
            val sortIdx = mealTypes.indexOfFirst { it.name.trim().equals(group.mealName, ignoreCase = true) }
            if (sortIdx >= 0) sortIdx else 999
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = ByteInkDark,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val defaultMeal = mealTypes.firstOrNull() ?: com.bytelog.app.data.local.model.MealType.DEFAULT_MEAL_TYPES.first()
                    onNavigateToSearch(defaultMeal.id, defaultMeal.name, selectedDate)
                },
                containerColor = ByteTomato,
                contentColor = ByteCream,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search Foods"
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Top App Bar / Date Navigation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Logo & Title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(ByteTomato),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("b", color = ByteCream, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "bytelog",
                        color = ByteCream,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    )
                }

                // Date Switcher Controls & Actions
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.changeDay(-1)
                    }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Day", tint = ByteCream)
                    }

                    Text(
                        text = if (isToday) "Today" else selectedDate,
                        color = ByteCream,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(ByteSurfaceDark)
                            .clickable {
                                viewModel.setDate(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )

                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.changeDay(1)
                    }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next Day", tint = ByteCream)
                    }

                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.copyAllFromYesterday()
                        }
                    ) {
                        Icon(Icons.Default.History, contentDescription = "Copy all yesterday's meals", tint = ByteCreamMuted)
                    }

                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.manualSync()
                        },
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = ByteTomato
                            )
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = "Sync with Sparky", tint = ByteCreamMuted)
                        }
                    }

                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = ByteCreamMuted)
                    }
                }
            }

            // Daily Macro Progress Cards
            DailyMacroHeader(summary = dailySummary)

            Spacer(modifier = Modifier.height(10.dp))

            // Hydration Speed Logging Card (Always in oz)
            HydrationCard(
                currentOz = waterTotalOz,
                targetOz = waterGoalOz,
                defaultOz = defaultContainerOz,
                defaultContainerName = defaultContainerName,
                onLogDefault = {
                    viewModel.logDefaultDrink()
                },
                onOpenCustom = {
                    showHydrationSheet = true
                },
                onUndoLast = {
                    viewModel.undoLastDrink()
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Bodyweight Tracking Card
            BodyweightCard(
                currentEntry = currentWeight,
                previousEntry = previousWeight,
                weightUnit = weightUnit,
                onOpenLogDialog = {
                    showWeightDialog = true
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Meal Categories List (Shows only logged meals, plus quick meal chips to add new ones)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (loggedMealGroups.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(ByteSurfaceDark.copy(alpha = 0.5f))
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "No foods logged for this date",
                                color = ByteCream,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Choose a meal below to start logging, or tap the sync icon above to fetch entries from Sparky Fitness.",
                                color = ByteCreamMuted,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MacroProtein.copy(alpha = 0.15f))
                                        .clickable {
                                            templateTargetMeal = null
                                            showTemplatesSheet = true
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "Templates & Recipes",
                                        color = MacroProtein,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(ByteTomato.copy(alpha = 0.15f))
                                        .clickable {
                                            viewModel.copyAllFromYesterday()
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "Copy Yesterday",
                                        color = ByteTomato,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                } else {
                    items(loggedMealGroups, key = { it.mealName }) { group ->
                        MealCard(
                            mealName = group.mealName,
                            mealCalories = group.calories,
                            entries = group.entries,
                            onAddFood = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNavigateToSearch(group.mealTypeId, group.mealName, selectedDate)
                            },
                            onCopyMeal = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                copyingMealName = group.mealName
                            },
                            onSaveTemplate = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                savingTemplateMealName = group.mealName
                            },
                            onOpenTemplates = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                templateTargetMeal = group
                                showTemplatesSheet = true
                            },
                            onEditEntry = { entry ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                editingEntry = entry
                            },
                            onDuplicateEntry = { entry ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.duplicateEntryToNow(entry)
                                scope.launch {
                                    snackbarHostState.showSnackbar("Duplicated ${entry.foodName}")
                                }
                            },
                            onDeleteEntry = { entry ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.deleteEntry(entry)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Deleted ${entry.foodName}",
                                        actionLabel = "Undo"
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.duplicateEntryToNow(entry)
                                    }
                                }
                            }
                        )
                    }
                }

                // Quick Meal Selection Action Bar
                item {
                    val candidateMeals = remember(mealTypes) {
                        (if (mealTypes.isNotEmpty()) mealTypes else com.bytelog.app.data.local.model.MealType.DEFAULT_MEAL_TYPES)
                            .distinctBy { it.name.trim().lowercase() }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (loggedMealGroups.isEmpty()) "START LOGGING" else "LOG TO ANOTHER MEAL",
                            color = ByteCreamMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MacroProtein.copy(alpha = 0.15f))
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            templateTargetMeal = null
                                            showTemplatesSheet = true
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.RestaurantMenu,
                                            contentDescription = null,
                                            tint = MacroProtein,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Templates",
                                            color = MacroProtein,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            items(candidateMeals, key = { it.id }) { mt ->
                                val cleanName = mt.name.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(ByteSurfaceDark)
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onNavigateToSearch(mt.id, cleanName, selectedDate)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            tint = ByteTomato,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = cleanName,
                                            color = ByteCream,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(72.dp))
                }
            }
        }
    }

    // Edit Food Entry Modal Bottom Sheet
    editingEntry?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { editingEntry = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ByteSurfaceDark
        ) {
            EditFoodEntrySheet(
                entry = entry,
                availableMealTypes = mealTypes,
                onSave = { updatedEntry ->
                    viewModel.updateEntry(updatedEntry)
                    editingEntry = null
                    scope.launch {
                        snackbarHostState.showSnackbar("Updated ${updatedEntry.foodName}")
                    }
                },
                onDelete = { entryToDelete ->
                    viewModel.deleteEntry(entryToDelete)
                    editingEntry = null
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "Deleted ${entryToDelete.foodName}",
                            actionLabel = "Undo"
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.duplicateEntryToNow(entryToDelete)
                        }
                    }
                },
                onDismiss = { editingEntry = null }
            )
        }
    }

    // Hydration Modal Bottom Sheet
    if (showHydrationSheet) {
        ModalBottomSheet(
            onDismissRequest = { showHydrationSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ByteSurfaceDark
        ) {
            HydrationSheet(
                todayLogs = waterLogs,
                containers = waterContainers,
                currentTotalOz = waterTotalOz,
                targetOz = waterGoalOz,
                defaultContainerOz = defaultContainerOz,
                defaultContainerName = defaultContainerName,
                onLogWaterOz = { amount, containerId, containerName ->
                    viewModel.quickAddWaterOz(amount, containerId, containerName)
                },
                onSetDefaultContainer = { oz, name ->
                    viewModel.setDefaultContainer(oz, name)
                },
                onDeleteLog = { log ->
                    viewModel.deleteWaterLog(log)
                },
                onDismiss = { showHydrationSheet = false }
            )
        }
    }

    // Bodyweight Log Dialog
    if (showWeightDialog) {
        WeightLogDialog(
            initialWeight = currentWeight?.weight ?: previousWeight?.weight,
            currentUnit = weightUnit,
            dateString = selectedDate,
            onDismiss = { showWeightDialog = false },
            onSaveWeight = { weight, unit ->
                viewModel.logWeight(weight = weight, unit = unit)
                viewModel.saveWeightUnit(unit)
                showWeightDialog = false
            }
        )
    }

    // Copy Meal Dialog
    copyingMealName?.let { mealName ->
        CopyMealDialog(
            currentMealName = mealName,
            currentDate = selectedDate,
            mealTypes = mealTypes,
            onDismiss = { copyingMealName = null },
            onCopyFromYesterday = { targetMeal ->
                viewModel.copyMealFromYesterday(targetMeal)
                copyingMealName = null
            },
            onCopyCustom = { srcDate, srcMeal, tgtDate, tgtMeal ->
                viewModel.copyMeal(srcDate, srcMeal, tgtDate, tgtMeal)
                copyingMealName = null
            }
        )
    }

    // Save Meal Template Dialog
    savingTemplateMealName?.let { mealName ->
        SaveMealTemplateDialog(
            mealName = mealName,
            dateString = selectedDate,
            onDismiss = { savingTemplateMealName = null },
            onSaveTemplate = { tName, desc ->
                viewModel.saveMealAsTemplate(mealName, desc)
                savingTemplateMealName = null
            }
        )
    }

    // Meal Templates & Recipes Sheet
    if (showTemplatesSheet) {
        val targetName = templateTargetMeal?.mealName ?: mealTypes.firstOrNull()?.name ?: "Meal"
        val targetId = templateTargetMeal?.mealTypeId ?: mealTypes.firstOrNull()?.id
        MealTemplatesSheet(
            sheetState = templatesSheetState,
            templates = mealTemplates,
            targetMealName = targetName,
            onDismiss = { showTemplatesSheet = false },
            onLogTemplate = { template ->
                viewModel.logTemplateToDiary(template, targetName, targetId)
                showTemplatesSheet = false
            },
            onDeleteTemplate = { templateId ->
                viewModel.deleteTemplate(templateId)
            },
            onOpenRecipeBuilder = {
                showTemplatesSheet = false
                showRecipeBuilderSheet = true
            }
        )
    }

    // Recipe Builder Sheet
    if (showRecipeBuilderSheet) {
        RecipeBuilderSheet(
            sheetState = recipeBuilderSheetState,
            availableFoods = availableFoods,
            onDismiss = { showRecipeBuilderSheet = false },
            onSaveRecipe = { name, desc, servingSize, servingUnit, totalServings, ingredients ->
                viewModel.createRecipeTemplate(
                    name = name,
                    description = desc,
                    servingSize = servingSize,
                    servingUnit = servingUnit,
                    totalServings = totalServings,
                    foods = ingredients
                )
                showRecipeBuilderSheet = false
            }
        )
    }
}

@Composable
fun MealCard(
    mealName: String,
    mealCalories: Float,
    entries: List<FoodEntry>,
    onAddFood: () -> Unit,
    onCopyMeal: () -> Unit,
    onSaveTemplate: () -> Unit,
    onOpenTemplates: () -> Unit,
    onEditEntry: (FoodEntry) -> Unit,
    onDuplicateEntry: (FoodEntry) -> Unit,
    onDeleteEntry: (FoodEntry) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ByteSurfaceDark)
            .padding(14.dp)
    ) {
        // Meal Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mealName,
                    color = ByteCream,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${mealCalories.roundToInt()} kcal",
                    color = ByteCreamMuted,
                    fontSize = 12.sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (entries.isNotEmpty()) {
                    IconButton(
                        onClick = onSaveTemplate,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdd,
                            contentDescription = "Save $mealName as Template",
                            tint = ByteCreamMuted,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onCopyMeal,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy $mealName",
                        tint = ByteCreamMuted,
                        modifier = Modifier.size(17.dp)
                    )
                }

                IconButton(
                    onClick = onOpenTemplates,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RestaurantMenu,
                        contentDescription = "Templates & Recipes",
                        tint = ByteCreamMuted,
                        modifier = Modifier.size(17.dp)
                    )
                }

                IconButton(
                    onClick = onAddFood,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(ByteSurfaceVariantDark)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add food to $mealName",
                        tint = ByteTomato,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Entries List
        if (entries.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEach { entry ->
                    SwipeableFoodEntryRow(
                        entry = entry,
                        onEdit = { onEditEntry(entry) },
                        onDuplicate = { onDuplicateEntry(entry) },
                        onDelete = { onDeleteEntry(entry) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableFoodEntryRow(
    entry: FoodEntry,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onDuplicate()
                    false // Don't dismiss, just trigger duplicate
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    true
                }
                else -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color = when (dismissState.targetValue) {
                SwipeToDismissBoxValue.StartToEnd -> ByteTomato
                SwipeToDismissBoxValue.EndToStart -> Color(0xFFEF4444)
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(color)
                    .padding(horizontal = 16.dp),
                contentAlignment = if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd)
                    Alignment.CenterStart else Alignment.CenterEnd
            ) {
                if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate", tint = ByteCream)
                } else {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = ByteCream)
                }
            }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ByteSurfaceVariantDark)
                .clickable(onClick = onEdit)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.foodName,
                    color = ByteCream,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = "${entry.quantity.roundToInt()}${entry.unit} • P: ${entry.protein.roundToInt()}g  C: ${entry.carbs.roundToInt()}g  F: ${entry.fat.roundToInt()}g",
                    color = ByteCreamMuted,
                    fontSize = 11.sp
                )
            }

            Text(
                text = "${entry.calories.roundToInt()} kcal",
                color = ByteTomato,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
