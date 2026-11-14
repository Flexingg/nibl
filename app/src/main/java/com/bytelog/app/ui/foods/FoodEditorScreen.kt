package com.bytelog.app.ui.foods

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.Color
import com.bytelog.app.ui.theme.fluidBackdrop
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.ui.components.ScreenHeader
import com.bytelog.app.ui.components.rememberScrollFraction
import com.bytelog.app.ui.diary.LoadingSpinner
import com.bytelog.app.ui.diary.MorphButton
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import com.bytelog.app.ui.viewmodel.FoodEditorViewModel
import com.bytelog.app.ui.viewmodel.FoodForm
import kotlin.math.roundToInt

private val COMMON_UNITS = listOf("g", "ml", "oz", "cup", "tbsp", "tsp", "piece", "slice", "serving")

/**
 * Edit a food in the Sparky library: name, brand, barcode, its serving and the nutrition for that serving.
 * Saves go straight to Sparky. A food that isn't the user's (public library, OpenFoodFacts) is saved as their copy.
 */
@Composable
fun FoodEditorScreen(
    viewModel: FoodEditorViewModel,
    onBack: () -> Unit,
    onFinished: (FoodItem?) -> Unit
) {
    val s by viewModel.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val scroll = rememberScrollState()
    var showMore by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(s.finished) { if (s.finished) onFinished(s.savedFood) }
    // Open the extra nutrients when the food already has some
    LaunchedEffect(s.original) {
        val o = s.original
        if (listOf(o.fiber, o.sugars, o.saturatedFat, o.sodium, o.cholesterol, o.potassium).any { it.isNotBlank() }) showMore = true
    }

    Scaffold(
        modifier = Modifier.fluidBackdrop(),
        containerColor = Color.Transparent,
        topBar = {
            ScreenHeader(
                title = when {
                    s.loading -> "Food"
                    s.owned -> "Edit food"
                    else -> "Save as my food"
                },
                subtitle = s.food?.name?.takeIf { it.isNotBlank() },
                onBack = onBack,
                fraction = rememberScrollFraction(scroll),
                actions = {
                    if (s.owned) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete food", tint = cs.onSurfaceVariant)
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (s.food != null) {
                Column(
                    Modifier.fillMaxWidth().background(cs.surface).navigationBarsPadding().imePadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val problem = s.form.problem
                    val msg = s.saveError ?: problem?.takeIf { s.dirty }
                    if (msg != null) Text(msg, color = cs.error, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    MorphButton(
                        { if (s.canSave) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.save() } },
                        Modifier.fillMaxWidth().height(56.dp),
                        if (s.canSave) cs.primary else cs.surfaceContainerHighest,
                        description = if (s.owned) "Save to Sparky" else "Save as my food"
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                            if (s.saving) LoadingSpinner(Modifier.size(20.dp), color = cs.onPrimary, strokeWidth = 2.dp)
                            else Icon(if (s.owned) Icons.Default.Check else Icons.Default.ContentCopy, null, tint = if (s.canSave) cs.onPrimary else cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    s.saving -> "Saving to Sparky…"
                                    !s.owned -> "Save as my food"
                                    !s.dirty -> "No changes"
                                    else -> "Save to Sparky"
                                },
                                color = if (s.canSave) cs.onPrimary else cs.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        when {
            s.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingSpinner(Modifier.size(36.dp), color = cs.primary, strokeWidth = 3.dp)
            }
            s.food == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Text("Couldn't open this food", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(s.loadError ?: "", color = cs.onSurfaceVariant, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = viewModel::load) { Text("Try again") }
            }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val f = s.form
                if (!s.owned) {
                    Banner(
                        if (s.food?.sparky == null) "This food came from OpenFoodFacts and isn't in your Sparky library yet. Saving adds your edited version to it."
                        else "This food belongs to the shared library, so Sparky won't let it be changed. Saving makes an edited copy of your own."
                    )
                }

                Section("Food") {
                    Field("Name", f.name, { v -> viewModel.edit { it.copy(name = v) } }, capitalize = true)
                    Field("Brand", f.brand, { v -> viewModel.edit { it.copy(brand = v) } }, placeholder = "Optional", capitalize = true)
                    Field("Barcode", f.barcode, { v -> viewModel.edit { it.copy(barcode = v.filter(Char::isDigit)) } }, placeholder = "Optional", keyboard = KeyboardType.Number)
                }

                Section("Serving") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Field("Size", f.servingSize, { v -> viewModel.edit { it.copy(servingSize = v) } }, keyboard = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                        Field("Unit", f.servingUnit, { v -> viewModel.edit { it.copy(servingUnit = v) } }, modifier = Modifier.weight(1f))
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(end = 8.dp)) {
                        items(COMMON_UNITS, key = { it }) { u ->
                            val sel = u.equals(f.servingUnit.trim(), ignoreCase = true)
                            val bg by animateColorAsState(if (sel) cs.secondaryContainer else cs.surfaceContainerHighest, label = "unit")
                            Box(
                                Modifier.clip(RoundedCornerShape(50)).background(bg)
                                    .clickable { viewModel.edit { it.copy(servingUnit = u) } }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) { Text(u, color = if (sel) cs.onSecondaryContainer else cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }

                Section("Nutrition per ${f.servingSize.ifBlank { "?" }} ${f.servingUnit}") {
                    Field("Calories", f.calories, { v -> viewModel.edit { it.copy(calories = v) } }, keyboard = KeyboardType.Decimal, suffix = "kcal", big = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field("Protein", f.protein, { v -> viewModel.edit { it.copy(protein = v) } }, keyboard = KeyboardType.Decimal, suffix = "g", accent = MacroProtein, modifier = Modifier.weight(1f))
                        Field("Carbs", f.carbs, { v -> viewModel.edit { it.copy(carbs = v) } }, keyboard = KeyboardType.Decimal, suffix = "g", accent = MacroCarbs, modifier = Modifier.weight(1f))
                        Field("Fat", f.fat, { v -> viewModel.edit { it.copy(fat = v) } }, keyboard = KeyboardType.Decimal, suffix = "g", accent = MacroFat, modifier = Modifier.weight(1f))
                    }
                    AnimatedVisibility(f.macrosDisagree) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.tertiaryContainer).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, null, tint = cs.onTertiaryContainer, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "The macros add up to about ${f.macroCalories.roundToInt()} kcal. Worth a second look?",
                                color = cs.onTertiaryContainer, fontSize = 13.sp
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { showMore = !showMore }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("More nutrients", color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Icon(if (showMore) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = cs.primary)
                    }
                    AnimatedVisibility(showMore, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Field("Fiber", f.fiber, { v -> viewModel.edit { it.copy(fiber = v) } }, keyboard = KeyboardType.Decimal, suffix = "g", modifier = Modifier.weight(1f))
                                Field("Sugars", f.sugars, { v -> viewModel.edit { it.copy(sugars = v) } }, keyboard = KeyboardType.Decimal, suffix = "g", modifier = Modifier.weight(1f))
                                Field("Sat. fat", f.saturatedFat, { v -> viewModel.edit { it.copy(saturatedFat = v) } }, keyboard = KeyboardType.Decimal, suffix = "g", modifier = Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Field("Sodium", f.sodium, { v -> viewModel.edit { it.copy(sodium = v) } }, keyboard = KeyboardType.Decimal, suffix = "mg", modifier = Modifier.weight(1f))
                                Field("Cholesterol", f.cholesterol, { v -> viewModel.edit { it.copy(cholesterol = v) } }, keyboard = KeyboardType.Decimal, suffix = "mg", modifier = Modifier.weight(1f))
                                Field("Potassium", f.potassium, { v -> viewModel.edit { it.copy(potassium = v) } }, keyboard = KeyboardType.Decimal, suffix = "mg", modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                if (viewModel.hasPrices && s.food != null) {
                    val pr by viewModel.price.collectAsState()
                    Section("Where you buy it") {
                        com.bytelog.app.ui.components.PriceForm(
                            pr.draft, viewModel::editPrice,
                            hint = "Optional. Used for the shopping list's costs" + if (s.owned) ", and saved on this food in Sparky." else "."
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(pr.status.orEmpty(), color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::savePrice, enabled = pr.dirty && !pr.saving && pr.draft.problem == null) {
                                Text(if (pr.saving) "Saving…" else "Save price")
                            }
                        }
                    }
                }

                if (s.owned) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer)
                            .clickable { viewModel.setUpdatePastEntries(!s.updatePastEntries) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Update past diary entries", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Entries already logged keep their old numbers unless this is on. Amounts stay the same.",
                                color = cs.onSurfaceVariant, fontSize = 12.5.sp
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = s.updatePastEntries,
                            onCheckedChange = viewModel::setUpdatePastEntries,
                            modifier = Modifier.semantics { contentDescription = "Update past diary entries" }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${s.form.name.ifBlank { "this food" }}?") },
            text = { Text("Hiding keeps it in Sparky but out of search. Deleting removes it from your library and meals. Your diary entries stay either way.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete(hideOnly = false) }) { Text("Delete", color = cs.error) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                    TextButton(onClick = { confirmDelete = false; viewModel.delete(hideOnly = true) }) { Text("Hide") }
                }
            }
        )
    }
}

@Composable
private fun Banner(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.secondaryContainer).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.ContentCopy, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = cs.onSecondaryContainer, fontSize = 13.5.sp)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title.uppercase(), color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    suffix: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    capitalize: Boolean = false,
    big: Boolean = false,
    accent: androidx.compose.ui.graphics.Color? = null
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (accent != null) { Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(accent)); Spacer(Modifier.width(5.dp)) }
            Text(label, color = cs.onSurfaceVariant, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder != null) Text(placeholder, color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontSize = if (big) 24.sp else 16.sp)
                BasicTextField(
                    value = value,
                    onValueChange = { raw -> onValue(if (keyboard == KeyboardType.Decimal) raw.replace(',', '.').filter { it.isDigit() || it == '.' } else raw) },
                    singleLine = true,
                    textStyle = TextStyle(color = cs.onSurface, fontSize = if (big) 24.sp else 16.sp, fontWeight = if (big) FontWeight.SemiBold else FontWeight.SemiBold),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboard, imeAction = ImeAction.Next,
                        capitalization = if (capitalize) KeyboardCapitalization.Words else KeyboardCapitalization.None
                    ),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
                )
            }
            if (suffix != null) Text(suffix, color = cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        }
    }
}
