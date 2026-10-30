package com.bytelog.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bytelog.app.ui.theme.ByteCardDark
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein

@Composable
fun CustomFoodDialog(
    onDismiss: () -> Unit,
    onCreateCustomFood: (
        name: String,
        brand: String?,
        servingSize: Float,
        servingUnit: String,
        calories: Float,
        protein: Float,
        carbs: Float,
        fat: Float,
        fiber: Float?,
        household: com.bytelog.app.recipes.HouseholdServing?
    ) -> Unit,
    /** Reading a nutrition label: the fields fill in from the photo when it's read. */
    labelScan: com.bytelog.app.ui.viewmodel.FastSearchViewModel.LabelScanState = com.bytelog.app.ui.viewmodel.FastSearchViewModel.LabelScanState.Idle,
    onScanLabel: (() -> Unit)? = null,
    onPickLabel: (() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        CustomFoodForm(onDismiss, onCreateCustomFood, labelScan, onScanLabel, onPickLabel)
    }
}

/** The custom food form itself (the dialog's content), with the label scan at the top. */
@Composable
fun CustomFoodForm(
    onDismiss: () -> Unit,
    onCreateCustomFood: (
        name: String,
        brand: String?,
        servingSize: Float,
        servingUnit: String,
        calories: Float,
        protein: Float,
        carbs: Float,
        fat: Float,
        fiber: Float?,
        household: com.bytelog.app.recipes.HouseholdServing?
    ) -> Unit,
    /** Reading a nutrition label: the fields fill in from the photo when it's read. */
    labelScan: com.bytelog.app.ui.viewmodel.FastSearchViewModel.LabelScanState = com.bytelog.app.ui.viewmodel.FastSearchViewModel.LabelScanState.Idle,
    onScanLabel: (() -> Unit)? = null,
    onPickLabel: (() -> Unit)? = null) {

    val haptic = LocalHapticFeedback.current

    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var servingSize by remember { mutableStateOf("100") }
    var servingUnit by remember { mutableStateOf("g") }
    var calories by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var fiber by remember { mutableStateOf("") }
    // "A serving is 2 cookies": optional, for foods counted in pieces
    var pieces by remember { mutableStateOf("") }
    var pieceUnit by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    // A label that's been read fills the form (only what it showed); everything stays editable
    androidx.compose.runtime.LaunchedEffect(labelScan) {
        val read = (labelScan as? com.bytelog.app.ui.viewmodel.FastSearchViewModel.LabelScanState.Read)?.scan ?: return@LaunchedEffect
        fun f(v: Float?) = v?.let { com.bytelog.app.ui.logging.formatQuantity(it) }
        read.name?.let { if (name.isBlank()) name = it }
        read.brand?.let { if (brand.isBlank()) brand = it }
        f(read.servingSize)?.let { servingSize = it }
        read.servingUnit?.let { servingUnit = it }
        f(read.calories)?.let { calories = it }
        f(read.protein)?.let { protein = it }
        f(read.carbs)?.let { carbs = it }
        f(read.fat)?.let { fat = it }
        f(read.fiber)?.let { fiber = it }
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = ByteCardDark,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Fastfood,
                        contentDescription = null,
                        tint = ByteTomato,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "New Custom Food",
                        color = ByteCream,
                        fontSize = 17.sp,
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

            if (onScanLabel != null) {
                LabelScanRow(labelScan, onScanLabel, onPickLabel)
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Food Name
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Food Name *", color = ByteTomato) },
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

            // Brand
            OutlinedTextField(
                value = brand,
                onValueChange = { brand = it },
                label = { Text("Brand (Optional)", color = ByteTextSecondary) },
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

            // Serving Size and Unit Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = servingSize,
                    onValueChange = { servingSize = it },
                    label = { Text("Serving Size", color = ByteTextSecondary) },
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
                    value = servingUnit,
                    onValueChange = { servingUnit = it },
                    label = { Text("Unit (g, serving)", color = ByteTextSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = ByteTomato,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Pieces in a serving, so it can be logged as "1 cookie"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("A serving is", color = ByteTextSecondary, fontSize = 13.sp)
                OutlinedTextField(
                    value = pieces,
                    onValueChange = { pieces = it },
                    label = { Text("How many", color = ByteTextSecondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = ByteTomato,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.weight(0.8f)
                )
                OutlinedTextField(
                    value = pieceUnit,
                    onValueChange = { pieceUnit = it },
                    label = { Text("cookies, slices…", color = ByteTextSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = ByteTomato,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.weight(1.2f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Calories
            OutlinedTextField(
                value = calories,
                onValueChange = { calories = it },
                label = { Text("Calories (kcal) *", color = ByteTomato) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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

            // Macros Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = protein,
                    onValueChange = { protein = it },
                    label = { Text("Prot (g)", color = MacroProtein) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = MacroProtein,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.weight(1f)
                )

                OutlinedTextField(
                    value = carbs,
                    onValueChange = { carbs = it },
                    label = { Text("Carb (g)", color = MacroCarbs) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = MacroCarbs,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.weight(1f)
                )

                OutlinedTextField(
                    value = fat,
                    onValueChange = { fat = it },
                    label = { Text("Fat (g)", color = MacroFat) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = MacroFat,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Fiber (Optional)
            OutlinedTextField(
                value = fiber,
                onValueChange = { fiber = it },
                label = { Text("Fiber (g) (Optional)", color = ByteTextSecondary) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = ByteCream,
                    unfocusedTextColor = ByteCream,
                    focusedBorderColor = ByteTomato,
                    unfocusedBorderColor = ByteSurfaceVariantDark
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    if (name.isNotBlank() && calories.isNotBlank()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isSaving = true
                        onCreateCustomFood(
                            name.trim(),
                            brand.ifBlank { null }?.trim(),
                            servingSize.toFloatOrNull() ?: 100f,
                            servingUnit.ifBlank { "g" }.trim(),
                            calories.toFloatOrNull() ?: 0f,
                            protein.toFloatOrNull() ?: 0f,
                            carbs.toFloatOrNull() ?: 0f,
                            fat.toFloatOrNull() ?: 0f,
                            fiber.toFloatOrNull(),
                            pieces.toFloatOrNull()?.takeIf { it > 0f && pieceUnit.isNotBlank() }?.let { count ->
                                // The whole serving is [count] pieces
                                com.bytelog.app.recipes.HouseholdServing(com.bytelog.app.recipes.HouseholdServings.singular(pieceUnit), 1f / count, count)
                            }
                        )
                        onDismiss()
                    }
                },
                enabled = !isSaving && name.isNotBlank() && calories.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = ByteTomato)
            ) {
                if (isSaving) {
                    com.bytelog.app.ui.diary.LoadingSpinner(modifier = Modifier.size(20.dp), color = ByteOnTomato)
                } else {
                    Text(
                        text = "Save Custom Food to Sparky",
                        color = ByteOnTomato,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** "Scan nutrition label": camera or gallery, then reading, read or failed. */
@Composable
private fun LabelScanRow(
    state: com.bytelog.app.ui.viewmodel.FastSearchViewModel.LabelScanState,
    onScan: () -> Unit,
    onPick: (() -> Unit)?
) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onScan,
                enabled = state !is com.bytelog.app.ui.viewmodel.FastSearchViewModel.LabelScanState.Reading,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = cs.secondaryContainer, contentColor = cs.onSecondaryContainer),
                modifier = Modifier.weight(1f)
            ) {
                Icon(androidx.compose.material.icons.Icons.Default.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Scan nutrition label", fontWeight = FontWeight.SemiBold)
            }
            if (onPick != null) {
                IconButton(onClick = onPick) {
                    Icon(androidx.compose.material.icons.Icons.Default.PhotoLibrary, contentDescription = "Pick a label photo", tint = cs.onSurfaceVariant)
                }
            }
        }
        when (state) {
            is com.bytelog.app.ui.viewmodel.FastSearchViewModel.LabelScanState.Reading -> Row(verticalAlignment = Alignment.CenterVertically) {
                com.bytelog.app.ui.diary.LoadingSpinner(modifier = Modifier.size(16.dp), color = ByteTomato)
                Spacer(Modifier.size(8.dp))
                Text("Reading the label…", color = ByteTextSecondary, fontSize = 13.sp)
            }
            is com.bytelog.app.ui.viewmodel.FastSearchViewModel.LabelScanState.Read ->
                Text("Filled in from the label. Check the numbers, and add how many pieces a serving is if it says.", color = ByteTextSecondary, fontSize = 12.sp)
            is com.bytelog.app.ui.viewmodel.FastSearchViewModel.LabelScanState.Failed ->
                Text(state.message, color = ByteTomato, fontSize = 12.sp)
            else -> Text("Take a photo of the nutrition facts and the form fills itself in.", color = ByteTextSecondary, fontSize = 12.sp)
        }
    }
}
