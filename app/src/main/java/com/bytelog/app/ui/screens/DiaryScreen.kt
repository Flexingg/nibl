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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.bytelog.app.ui.components.DailyMacroHeader
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

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val isToday = remember(selectedDate) {
        selectedDate == LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = ByteInkDark,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val defaultMeal = mealTypes.firstOrNull() ?: com.bytelog.app.data.local.model.MealType.DEFAULT_MEAL_TYPES.first()
                    onNavigateToBarcode(defaultMeal.id, defaultMeal.name, selectedDate)
                },
                containerColor = ByteTomato,
                contentColor = ByteCream,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = "Scan Barcode"
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

                // Date Switcher Controls
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

                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = ByteCreamMuted)
                    }
                }
            }

            // Daily Macro Progress Cards
            DailyMacroHeader(summary = dailySummary)

            Spacer(modifier = Modifier.height(14.dp))

            // Meal Categories List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(mealTypes, key = { it.id }) { mealType ->
                    val mealEntries = entries.filter { it.mealTypeId == mealType.id }
                    val mealCalories = mealEntries.sumOf { it.calories.toDouble() }.toFloat()

                    MealCard(
                        mealName = mealType.name,
                        mealCalories = mealCalories,
                        entries = mealEntries,
                        onAddFood = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNavigateToSearch(mealType.id, mealType.name, selectedDate)
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

                item {
                    Spacer(modifier = Modifier.height(72.dp))
                }
            }
        }
    }
}

@Composable
fun MealCard(
    mealName: String,
    mealCalories: Float,
    entries: List<FoodEntry>,
    onAddFood: () -> Unit,
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
            Column {
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

            IconButton(
                onClick = onAddFood,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(ByteSurfaceVariantDark)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add food to $mealName",
                    tint = ByteTomato,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Entries List
        if (entries.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEach { entry ->
                    SwipeableFoodEntryRow(
                        entry = entry,
                        onDuplicate = { onDuplicateEntry(entry) },
                        onDelete = { onDeleteEntry(entry) }
                    )
                }
            }
        }
    }
}

@Composable
fun SwipeableFoodEntryRow(
    entry: FoodEntry,
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
