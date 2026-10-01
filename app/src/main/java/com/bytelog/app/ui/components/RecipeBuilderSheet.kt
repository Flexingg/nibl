package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.TemplateIngredient
import com.bytelog.app.ui.theme.ByteCardDark
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeBuilderSheet(
    sheetState: SheetState,
    availableFoods: List<FoodItem>,
    onDismiss: () -> Unit,
    onSaveRecipe: (
        name: String,
        description: String?,
        servingSize: Float,
        servingUnit: String,
        totalServings: Float,
        ingredients: List<TemplateIngredient>
    ) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    var recipeName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var totalServingsText by remember { mutableStateOf("1") }
    val ingredients = remember { mutableStateListOf<TemplateIngredient>() }

    var showFoodPicker by remember { mutableStateOf(false) }

    val totalServings = totalServingsText.toFloatOrNull() ?: 1f
    val totalCals = ingredients.sumOf { it.calories.toDouble() }.toFloat()
    val totalProt = ingredients.sumOf { it.protein.toDouble() }.toFloat()
    val totalCarbs = ingredients.sumOf { it.carbs.toDouble() }.toFloat()
    val totalFat = ingredients.sumOf { it.fat.toDouble() }.toFloat()

    val perServingCals = if (totalServings > 0f) totalCals / totalServings else 0f
    val perServingProt = if (totalServings > 0f) totalProt / totalServings else 0f
    val perServingCarbs = if (totalServings > 0f) totalCarbs / totalServings else 0f
    val perServingFat = if (totalServings > 0f) totalFat / totalServings else 0f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ByteInkDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ByteTomato.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restaurant,
                            contentDescription = null,
                            tint = ByteTomato,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Build Recipe Template",
                        color = ByteCream,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = ByteTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    // Recipe Name
                    OutlinedTextField(
                        value = recipeName,
                        onValueChange = { recipeName = it },
                        label = { Text("Recipe Name *", color = ByteTomato) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ByteCream,
                            unfocusedTextColor = ByteCream,
                            focusedBorderColor = ByteTomato,
                            unfocusedBorderColor = ByteSurfaceVariantDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = totalServingsText,
                            onValueChange = { totalServingsText = it },
                            label = { Text("Yield (servings)", color = ByteTextSecondary) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = ByteCream,
                                unfocusedTextColor = ByteCream,
                                focusedBorderColor = ByteTomato,
                                unfocusedBorderColor = ByteSurfaceVariantDark
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("Notes (Optional)", color = ByteTextSecondary) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = ByteCream,
                                unfocusedTextColor = ByteCream,
                                focusedBorderColor = ByteTomato,
                                unfocusedBorderColor = ByteSurfaceVariantDark
                            ),
                            modifier = Modifier.weight(1.5f)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Real-time macro summary card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(ByteCardDark)
                            .padding(14.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Per Serving (${String.format(Locale.US, "%.0f", perServingCals)} kcal)",
                                    color = ByteTomato,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Total: ${String.format(Locale.US, "%.0f", totalCals)} kcal",
                                    color = ByteTextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "P: ${String.format(Locale.US, "%.1f", perServingProt)}g",
                                    color = MacroProtein,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "C: ${String.format(Locale.US, "%.1f", perServingCarbs)}g",
                                    color = MacroCarbs,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "F: ${String.format(Locale.US, "%.1f", perServingFat)}g",
                                    color = MacroFat,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ingredients (${ingredients.size})",
                            color = ByteCream,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MacroProtein.copy(alpha = 0.15f))
                                .clickable { showFoodPicker = !showFoodPicker }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = MacroProtein,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Add Ingredient",
                                    color = MacroProtein,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Food picker dropdown / list if open
                if (showFoodPicker) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(ByteCardDark)
                                .border(1.dp, ByteSurfaceVariantDark, RoundedCornerShape(12.dp))
                                .padding(10.dp)
                        ) {
                            Column {
                                Text(
                                    text = "Pick from Recent / Saved Foods:",
                                    color = ByteTextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                if (availableFoods.isEmpty()) {
                                    Text(
                                        text = "No saved foods yet. Search and log foods first or create custom foods.",
                                        color = ByteTextSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                } else {
                                    availableFoods.take(8).forEach { food ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    ingredients.add(
                                                        TemplateIngredient(
                                                            foodId = food.id,
                                                            foodName = food.name,
                                                            quantity = food.servingSize,
                                                            unit = food.servingUnit,
                                                            calories = food.calories,
                                                            protein = food.protein,
                                                            carbs = food.carbs,
                                                            fat = food.fat
                                                        )
                                                    )
                                                    showFoodPicker = false
                                                }
                                                .padding(vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(food.name, color = ByteCream, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                                Text("${food.servingSize.toInt()} ${food.servingUnit} • ${food.calories.toInt()} kcal", color = ByteTextSecondary, fontSize = 11.sp)
                                            }
                                            Icon(Icons.Default.Add, contentDescription = "Add", tint = MacroProtein, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // List of added ingredients
                itemsIndexed(ingredients) { index, ing ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ByteCardDark)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ing.foodName,
                                    color = ByteCream,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${String.format(Locale.US, "%.0f", ing.quantity)} ${ing.unit} • ${String.format(Locale.US, "%.0f", ing.calories)} kcal • P: ${String.format(Locale.US, "%.1f", ing.protein)}g",
                                    color = ByteTextSecondary,
                                    fontSize = 11.sp
                                )
                            }

                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    ingredients.removeAt(index)
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove",
                                    tint = ByteTomato,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Save Recipe Button
            Button(
                onClick = {
                    if (recipeName.isNotBlank() && ingredients.isNotEmpty()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSaveRecipe(
                            recipeName.trim(),
                            description.ifBlank { null }?.trim(),
                            1f,
                            "serving",
                            totalServings,
                            ingredients.toList()
                        )
                        onDismiss()
                    }
                },
                enabled = recipeName.isNotBlank() && ingredients.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ByteTomato)
            ) {
                Text(
                    text = "Save Recipe to Sparky Fitness",
                    color = ByteCream,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
