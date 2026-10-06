package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.recipes.Macros
import com.bytelog.app.ui.recipes.FoodPickerDialog
import com.bytelog.app.ui.recipes.MacroLine
import com.bytelog.app.ui.recipes.Pill
import com.bytelog.app.ui.recipes.byteFieldColors
import com.bytelog.app.ui.recipes.fmt0
import com.bytelog.app.ui.recipes.fmt1
import com.bytelog.app.ui.theme.ByteCardDark
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.MacroProtein

/** One ingredient in the builder: a food and how much of it, in any convertible unit. */
data class BuilderIngredient(val food: FoodItem, val quantity: Float, val unit: String) {
    val macros: Macros
        get() = Macros(food.caloriesFor(quantity, unit), food.proteinFor(quantity, unit), food.carbsFor(quantity, unit), food.fatFor(quantity, unit))
}

/** What the builder hands back on save. */
data class BuiltRecipe(
    val name: String,
    val description: String?,
    val servings: Float,
    val ingredients: List<BuilderIngredient>,
    val directions: String,
    val prepMinutes: Int?,
    val cookMinutes: Int?,
    val fridgeDays: Int?,
    val freezerDays: Int?,
    val notes: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeBuilderSheet(
    sheetState: SheetState,
    searchFoods: suspend (String) -> List<FoodItem>,
    onDismiss: () -> Unit,
    onSaveRecipe: (BuiltRecipe) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    var recipeName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var totalServingsText by remember { mutableStateOf("1") }
    val ingredients = remember { mutableStateListOf<BuilderIngredient>() }
    var showDetails by remember { mutableStateOf(false) }
    var directions by remember { mutableStateOf("") }
    var prep by remember { mutableStateOf("") }
    var cook by remember { mutableStateOf("") }
    var fridge by remember { mutableStateOf("4") }
    var freezer by remember { mutableStateOf("60") }
    var notes by remember { mutableStateOf("") }

    var showFoodPicker by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    val totalServings = totalServingsText.toFloatOrNull()?.takeIf { it > 0f } ?: 1f
    val total = ingredients.fold(Macros()) { acc, i -> acc + i.macros }
    val perServing = total / totalServings

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ByteInkDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(ByteTomato.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Restaurant, null, tint = ByteTomato, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Build Recipe", color = ByteCream, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "Close", tint = ByteTextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = recipeName,
                        onValueChange = { recipeName = it },
                        label = { Text("Recipe name *") },
                        singleLine = true,
                        colors = byteFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = totalServingsText,
                            onValueChange = { totalServingsText = it },
                            label = { Text("Servings") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            colors = byteFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("Description") },
                            singleLine = true,
                            colors = byteFieldColors(),
                            modifier = Modifier.weight(1.6f)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(ByteCardDark).padding(14.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Per serving", color = ByteTomato, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Whole recipe: ${fmt0(total.calories)} kcal", color = ByteTextSecondary, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        MacroLine(perServing)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Ingredients (${ingredients.size})", color = ByteCream, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Pill("+ Add ingredient", MacroProtein) { showFoodPicker = true }
                    }
                }

                itemsIndexed(ingredients) { index, ing ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(ByteCardDark)
                            .clickable { editingIndex = index }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ing.food.name, color = ByteCream, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${fmt1(ing.quantity)} ${ing.unit} · ${fmt0(ing.macros.calories)} kcal · P ${fmt1(Math.round(ing.macros.protein * 10f) / 10f)}g",
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
                            Icon(Icons.Default.Delete, "Remove", tint = ByteTomato, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { showDetails = !showDetails }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Directions & notes", color = ByteCream, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Icon(if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = ByteTextSecondary)
                    }
                    if (showDetails) {
                        OutlinedTextField(
                            value = directions,
                            onValueChange = { directions = it },
                            label = { Text("Directions — one step per line") },
                            supportingText = { Text("End a line with \":\" to start a section. Times like \"bake 25 minutes\" become timers in Cook mode.") },
                            colors = byteFieldColors(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallNumber("Prep min", prep, Modifier.weight(1f)) { prep = it }
                            SmallNumber("Cook min", cook, Modifier.weight(1f)) { cook = it }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallNumber("Fridge days", fridge, Modifier.weight(1f)) { fridge = it }
                            SmallNumber("Freezer days", freezer, Modifier.weight(1f)) { freezer = it }
                        }
                        OutlinedTextField(
                            value = notes, onValueChange = { notes = it }, label = { Text("Notes & tips") },
                            colors = byteFieldColors(), modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    if (recipeName.isNotBlank() && ingredients.isNotEmpty()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSaveRecipe(
                            BuiltRecipe(
                                name = recipeName.trim(),
                                description = description.trim().ifBlank { null },
                                servings = totalServings,
                                ingredients = ingredients.toList(),
                                directions = directions,
                                prepMinutes = prep.toIntOrNull(),
                                cookMinutes = cook.toIntOrNull(),
                                fridgeDays = fridge.toIntOrNull(),
                                freezerDays = freezer.toIntOrNull(),
                                notes = notes.ifBlank { null }
                            )
                        )
                        onDismiss()
                    }
                },
                enabled = recipeName.isNotBlank() && ingredients.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = ByteTomato)
            ) {
                Text("Save Recipe to Sparky Fitness", color = ByteOnTomato, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showFoodPicker) {
        FoodPickerDialog(
            initialQuery = "",
            suggestions = emptyList(),
            search = searchFoods,
            onPick = { food ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                ingredients.add(BuilderIngredient(food, food.servingSize, food.servingUnit))
                showFoodPicker = false
                editingIndex = ingredients.lastIndex
            },
            onDismiss = { showFoodPicker = false }
        )
    }

    editingIndex?.let { idx ->
        val ing = ingredients.getOrNull(idx)
        if (ing == null) {
            editingIndex = null
        } else {
            IngredientAmountDialog(
                ingredient = ing,
                onConfirm = { q, u -> ingredients[idx] = ing.copy(quantity = q, unit = u); editingIndex = null },
                onDismiss = { editingIndex = null }
            )
        }
    }
}

@Composable
private fun SmallNumber(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter(Char::isDigit)) }, singleLine = true,
        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = byteFieldColors(), modifier = modifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IngredientAmountDialog(ingredient: BuilderIngredient, onConfirm: (Float, String) -> Unit, onDismiss: () -> Unit) {
    val food = ingredient.food
    var qty by remember { mutableStateOf(fmt1(ingredient.quantity)) }
    var unit by remember { mutableStateOf(ingredient.unit) }
    val units = listOf(food.servingUnit, "g", "oz", "lb", "ml", "cup", "tbsp", "tsp", "serving").distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ByteSurfaceDark,
        title = { Text(food.name, color = ByteCream, fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = qty, onValueChange = { qty = it }, singleLine = true, label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = byteFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    units.forEach { u -> Pill(u, MacroProtein, filled = unit == u) { unit = u } }
                }
                Spacer(Modifier.height(8.dp))
                val kcal = qty.toFloatOrNull()?.let { food.caloriesFor(it, unit) }
                Text("${kcal?.let { fmt0(it) } ?: "–"} kcal", color = ByteTomato, fontWeight = FontWeight.Bold)
                Text("Reference: ${fmt1(food.servingSize)} ${food.servingUnit} = ${fmt0(food.calories)} kcal", color = ByteTextSecondary, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = { qty.toFloatOrNull()?.takeIf { it > 0f }?.let { onConfirm(it, unit) } }) { Text("Done", color = ByteTomato) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ByteTextSecondary) } }
    )
}
