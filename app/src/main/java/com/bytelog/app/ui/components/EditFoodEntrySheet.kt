package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun EditFoodEntrySheet(
    entry: FoodEntry,
    availableMealTypes: List<MealType>,
    onSave: (updatedEntry: FoodEntry) -> Unit,
    onDelete: (entry: FoodEntry) -> Unit,
    onCopy: ((entry: FoodEntry) -> Unit)? = null,
    entryPhotos: com.bytelog.app.data.remote.SparkyEntryPhotos? = null,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }
    val today = remember { LocalDate.now().format(dateFormatter) }
    val tomorrow = remember { LocalDate.now().plusDays(1).format(dateFormatter) }
    val yesterday = remember { LocalDate.now().minusDays(1).format(dateFormatter) }

    var quantityText by remember {
        mutableStateOf(
            if (entry.quantity % 1f == 0f) entry.quantity.toInt().toString() else entry.quantity.toString()
        )
    }
    var selectedUnit by remember { mutableStateOf(entry.unit) }
    var selectedDate by remember { mutableStateOf(entry.entryDate) }
    var selectedTime by remember { mutableStateOf(com.bytelog.app.ui.components.EatenAtText.parse(entry.entryTime)) }
    var showWhenPicker by remember { mutableStateOf(false) }
    var selectedMealTypeId by remember { mutableStateOf(entry.mealTypeId) }
    var selectedMealName by remember { mutableStateOf(entry.mealName) }

    val currentQty = quantityText.toFloatOrNull() ?: 0f
    val baseQty = if (entry.quantity > 0f) entry.quantity else 100f
    val scaleFactor = if (baseQty > 0f) currentQty / baseQty else 1f

    val calculatedCalories = (entry.calories * scaleFactor).coerceAtLeast(0f)
    val calculatedProtein = (entry.protein * scaleFactor).coerceAtLeast(0f)
    val calculatedCarbs = (entry.carbs * scaleFactor).coerceAtLeast(0f)
    val calculatedFat = (entry.fat * scaleFactor).coerceAtLeast(0f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(ByteSurfaceDark)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Top Bar: Food Name & Dismiss
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.foodName,
                    color = ByteCream,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2
                )
                entry.brandName?.let {
                    Text(
                        text = it,
                        color = ByteCreamMuted,
                        fontSize = 13.sp
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = ByteCream
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Meal Reassignment Selector Chips
        if (availableMealTypes.isNotEmpty()) {
            Text(
                text = "MEAL CATEGORY",
                color = ByteCreamMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(availableMealTypes, key = { it.id }) { mt ->
                    val cleanName = mt.name.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    val isSelected = mt.id == selectedMealTypeId || mt.name.equals(selectedMealName, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) ByteTomato else ByteSurfaceVariantDark)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedMealTypeId = mt.id
                                selectedMealName = cleanName
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
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
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Date Selector Chips & Field (Move to date)
        Text(
            text = "WHEN",
            color = ByteCreamMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        // The day and time it was eaten, as one control: tap to change either
        run {
            val use24h = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)
            val day = runCatching { LocalDate.parse(selectedDate.trim().take(10)) }.getOrDefault(LocalDate.now())
            val at = com.bytelog.app.ui.components.EatenAt(day, selectedTime)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ByteSurfaceVariantDark)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showWhenPicker = true
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CalendarToday, contentDescription = null, tint = ByteTomato, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    com.bytelog.app.ui.components.EatenAtText.label(at, LocalDate.now(), use24h),
                    color = ByteCream, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                )
                Text(if (selectedTime == null) "Add time" else "Change", color = ByteTomato, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (showWhenPicker) {
                com.bytelog.app.ui.components.WhenPickerSheet(
                    initial = at,
                    onDone = { picked ->
                        selectedDate = picked.date.format(dateFormatter)
                        selectedTime = picked.time
                        showWhenPicker = false
                    },
                    onDismiss = { showWhenPicker = false }
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        // Live Calculated Calories & Macros
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ByteSurfaceVariantDark)
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "${calculatedCalories.roundToInt()} kcal",
                    color = ByteTomato,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Live Calculated",
                    color = ByteCreamMuted,
                    fontSize = 11.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${calculatedProtein.roundToInt()}g",
                        color = MacroProtein,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Protein", color = ByteCreamMuted, fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${calculatedCarbs.roundToInt()}g",
                        color = MacroCarbs,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Carbs", color = ByteCreamMuted, fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${calculatedFat.roundToInt()}g",
                        color = MacroFat,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Fat", color = ByteCreamMuted, fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Quantity Display & Unit Selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (quantityText.isEmpty()) "0" else quantityText,
                color = ByteCream,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp)
            )

            // Unit Selector Pills
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("g", "oz", "serving", "ml").forEach { unit ->
                    val isSelected = selectedUnit.equals(unit, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) ByteTomato else ByteSurfaceVariantDark)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedUnit = unit
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = unit,
                            color = if (isSelected) ByteOnTomato else ByteCreamMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Custom In-App Numpad
        CustomNumpad(
            currentValue = quantityText,
            onValueChange = { quantityText = it },
            onQuickAddGrams = { add ->
                val q = (quantityText.toFloatOrNull() ?: 0f) + add
                quantityText = if (q % 1f == 0f) q.toInt().toString() else q.toString()
            },
            onMultiply = { factor ->
                val base = if (entry.quantity > 0f) entry.quantity else 100f
                val q = base * factor
                quantityText = if (q % 1f == 0f) q.toInt().toString() else q.toString()
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (entryPhotos != null) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var currentPhotos by remember(entry.id) {
                mutableStateOf(entryPhotos.getCachedPhotos(ctx, entry.id))
            }
            FoodEntryPhotosSection(
                entryId = entry.id,
                initialImages = currentPhotos,
                entryPhotos = entryPhotos,
                onImagesChanged = { updated ->
                    currentPhotos = updated
                    entryPhotos.setCachedPhotos(ctx, entry.id, updated)
                }
            )
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Action Buttons: Delete, Copy & Save Changes
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Delete Entry Button
            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDelete(entry)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFEF4444)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Delete",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFEF4444)
                )
            }

            // Copy Entry Button
            onCopy?.let { copyCallback ->
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val finalQty = quantityText.toFloatOrNull() ?: entry.quantity
                        val updated = entry.copy(
                            quantity = finalQty,
                            unit = selectedUnit,
                            entryDate = selectedDate.trim(),
                        entryTime = com.bytelog.app.ui.components.EatenAtText.stored(selectedTime),
                            mealTypeId = selectedMealTypeId,
                            mealName = selectedMealName,
                            calories = calculatedCalories,
                            protein = calculatedProtein,
                            carbs = calculatedCarbs,
                            fat = calculatedFat
                        )
                        copyCallback(updated)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MacroProtein
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MacroProtein.copy(alpha = 0.5f)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = MacroProtein,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Copy",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MacroProtein
                    )
                }
            }

            // Save Changes Button
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val finalQty = quantityText.toFloatOrNull() ?: entry.quantity
                    val updated = entry.copy(
                        quantity = finalQty,
                        unit = selectedUnit,
                        entryDate = selectedDate.trim(),
                        entryTime = com.bytelog.app.ui.components.EatenAtText.stored(selectedTime),
                        mealTypeId = selectedMealTypeId,
                        mealName = selectedMealName,
                        calories = calculatedCalories,
                        protein = calculatedProtein,
                        carbs = calculatedCarbs,
                        fat = calculatedFat
                    )
                    onSave(updated)
                },
                modifier = Modifier
                    .weight(if (onCopy != null) 1.6f else 2f)
                    .height(48.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = ByteTomato),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Save",
                    tint = ByteOnTomato,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Save",
                    color = ByteOnTomato,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
