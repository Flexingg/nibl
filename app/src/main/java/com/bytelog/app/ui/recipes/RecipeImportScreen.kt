package com.bytelog.app.ui.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.recipes.IngredientMatch
import com.bytelog.app.recipes.MatchSource
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroProtein
import com.bytelog.app.ui.viewmodel.ImportState
import com.bytelog.app.ui.viewmodel.RecipesViewModel
import kotlin.math.abs

private val OkGreen = Color(0xFF4ADE80)

@Composable
fun RecipeImportScreen(
    viewModel: RecipesViewModel,
    initialInput: String?,
    onBack: () -> Unit,
    onSaved: (templateId: String) -> Unit
) {
    val state by viewModel.importState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(initialInput) {
        if (!initialInput.isNullOrBlank()) viewModel.startImport(initialInput)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = ByteInkDark) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                IconButton(onClick = { viewModel.resetImport(); onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ByteCream)
                }
                Text(
                    if (state is ImportState.Review) "Review recipe" else "Import a recipe",
                    color = ByteCream, fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
            }
            when (val s = state) {
                ImportState.Idle -> ImportInput(onSubmit = viewModel::startImport, onBrowse = viewModel::openBrowser)
                is ImportState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = ByteTomato)
                        Spacer(Modifier.height(12.dp))
                        Text(s.message, color = ByteTextSecondary)
                    }
                }
                is ImportState.NeedsBrowser -> Column {
                    Text(s.reason, color = ByteTomato, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Open the page in ByteLog's built-in browser instead. It loads the site like a normal browser, so " +
                            "\"are you human\" checks and cookie walls work, and the recipe is read once it appears.",
                        color = ByteTextSecondary, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.openBrowser(s.url) },
                        colors = ButtonDefaults.buttonColors(containerColor = ByteTomato),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) { Text("Open in built-in browser", color = ByteCream, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(16.dp))
                    ImportInput(onSubmit = viewModel::startImport, onBrowse = viewModel::openBrowser)
                }
                is ImportState.Browsing -> RecipeBrowser(
                    url = s.url,
                    onRecipe = viewModel::onBrowserRecipe,
                    onClose = { viewModel.closeBrowser(s.url) }
                )
                is ImportState.Error -> Column {
                    Text(s.message, color = ByteTomato, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    ImportInput(onSubmit = viewModel::startImport, onBrowse = viewModel::openBrowser)
                }
                is ImportState.Review -> ImportReview(s, viewModel, onSaved)
            }
        }
    }
}

@Composable
private fun ImportInput(onSubmit: (String) -> Unit, onBrowse: (String) -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    Column {
        Text(
            "Paste a link to a recipe page, or paste the recipe text itself. You can also share a page to ByteLog from your browser.",
            color = ByteTextSecondary, fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Recipe link or text") },
            colors = byteFieldColors(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Paste", MacroProtein) { clipboard.getText()?.text?.let { text = it } }
            val link = com.bytelog.app.recipes.RecipeExtractor.findUrl(text)
            if (link != null) Pill("Open in browser", MacroCarbs) { onBrowse(link) }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onSubmit(text) },
            enabled = text.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = ByteTomato),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Default.ContentPaste, null, tint = ByteCream, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Read recipe", color = ByteCream, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ImportReview(s: ImportState.Review, viewModel: RecipesViewModel, onSaved: (String) -> Unit) {
    var picking by remember { mutableStateOf<IngredientMatch?>(null) }
    var editingAmount by remember { mutableStateOf<IngredientMatch?>(null) }
    var servingsText by remember(s.recipe) { mutableStateOf(fmt1(s.servings)) }
    var fridgeText by remember { mutableStateOf("4") }
    var freezerText by remember { mutableStateOf("60") }
    val sorted = remember(s.matches) {
        s.matches.sortedBy { m -> when { !m.include -> 3; m.food == null -> 0; m.needsReview -> 1; else -> 2 } }
    }
    val unresolved = s.unresolved.size

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(
                value = s.name, onValueChange = viewModel::setImportName, singleLine = true,
                label = { Text("Name") }, colors = byteFieldColors(), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = servingsText,
                    onValueChange = { v ->
                        servingsText = v
                        v.toFloatOrNull()?.let(viewModel::setImportServings)
                    },
                    label = { Text("Servings") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = byteFieldColors(), modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = fridgeText, onValueChange = { fridgeText = it.filter(Char::isDigit) },
                    label = { Text("Fridge days") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = byteFieldColors(), modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = freezerText, onValueChange = { freezerText = it.filter(Char::isDigit) },
                    label = { Text("Freezer days") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = byteFieldColors(), modifier = Modifier.weight(1f)
                )
            }
            val meta = listOfNotNull(
                s.recipe.yieldText,
                s.recipe.prepMinutes?.let { "prep $it min" },
                s.recipe.cookMinutes?.let { "cook $it min" },
                "${s.recipe.steps.size} steps"
            ).joinToString(" · ")
            Text(meta, color = ByteTextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(10.dp))

            // Totals and sanity check against the site's own nutrition
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ByteSurfaceDark).padding(12.dp)) {
                Text("Per serving", color = ByteTextSecondary, fontSize = 12.sp)
                MacroLine(s.perServing, size = 13)
                s.recipe.siteNutrition?.calories?.let { site ->
                    val ours = s.perServing.calories
                    val off = if (site > 0f) abs(ours - site) / site else 0f
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (off > 0.2f) Icon(Icons.Default.Warning, null, tint = MacroCarbs, modifier = Modifier.size(14.dp).padding(end = 4.dp))
                        Text(
                            "Site says ${fmt0(site)} kcal" + if (off > 0.2f) " — ${fmt0(off * 100)}% apart, check the highlighted matches" else " — close match",
                            color = if (off > 0.2f) MacroCarbs else OkGreen, fontSize = 12.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (unresolved == 0) "All ingredients matched" else "$unresolved need a look",
                    color = if (unresolved == 0) OkGreen else MacroCarbs, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (unresolved > 0) {
                    if (s.aiRunning) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = ByteTomato, strokeWidth = 2.dp)
                    } else {
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(ByteTomato.copy(alpha = 0.15f))
                                .clickable { viewModel.runAiAssist() }.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, tint = ByteTomato, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Ask Sparky AI (beta)", color = ByteTomato, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Text("Tap a row to change the food, tap the amount to edit it. Untick lines you won't use.", color = ByteTextSecondary, fontSize = 11.sp)
        }

        items(sorted, key = { it.key }) { m ->
            IngredientRow(
                m = m,
                onToggle = { viewModel.setInclude(m.key, it) },
                onPick = { picking = m },
                onAmount = { editingAmount = m },
                onConfirm = { viewModel.markReviewed(m.key) }
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.saveImport(fridgeText.toIntOrNull(), freezerText.toIntOrNull(), onSaved)
                },
                enabled = !s.saving && s.matches.any { it.include && it.food != null },
                colors = ButtonDefaults.buttonColors(containerColor = ByteTomato),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                if (s.saving) CircularProgressIndicator(Modifier.size(18.dp), color = ByteCream, strokeWidth = 2.dp)
                else Text("Save recipe to Sparky", color = ByteCream, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    picking?.let { m ->
        FoodPickerDialog(
            initialQuery = m.parsed.name.ifBlank { m.parsed.raw },
            suggestions = m.candidates,
            search = viewModel::searchFoods,
            onPick = { food -> viewModel.chooseFood(m.key, food); picking = null },
            onDismiss = { picking = null }
        )
    }
    editingAmount?.let { m ->
        AmountDialog(
            m = m,
            onConfirm = { q, u -> viewModel.setAmount(m.key, q, u); editingAmount = null },
            onDismiss = { editingAmount = null }
        )
    }
}

@Composable
private fun IngredientRow(
    m: IngredientMatch,
    onToggle: (Boolean) -> Unit,
    onPick: () -> Unit,
    onAmount: () -> Unit,
    onConfirm: () -> Unit
) {
    val color = when {
        !m.include -> ByteSurfaceVariantDark
        m.food == null -> ByteTomato
        m.needsReview -> MacroCarbs
        else -> OkGreen
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ByteSurfaceDark).clickable(onClick = onPick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).height(72.dp).background(color))
        Checkbox(
            checked = m.include, onCheckedChange = onToggle,
            colors = CheckboxDefaults.colors(checkedColor = ByteTomato, uncheckedColor = ByteTextSecondary)
        )
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(m.parsed.raw, color = ByteTextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                m.food?.name ?: "No match — tap to choose",
                color = if (m.food == null) ByteTomato else ByteCream,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (m.food != null) {
                val src = when (m.source) {
                    MatchSource.ALIAS -> "remembered"
                    MatchSource.AI -> "Sparky AI"
                    MatchSource.MANUAL -> "your pick"
                    MatchSource.LIBRARY -> "your foods"
                    MatchSource.SEARCH -> "search"
                    MatchSource.NONE -> ""
                }
                Text("${fmt0(m.macros.calories)} kcal · P ${fmt0(m.macros.protein)}g · $src", color = ByteTextSecondary, fontSize = 11.sp)
            }
        }
        if (m.food != null) {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 10.dp)) {
                val unitLabel = if (m.unit == "serving" && m.quantity != 1f) "servings" else m.unit
                Text(
                    "${fmt1(Math.round(m.quantity * 10f) / 10f)} $unitLabel",
                    color = MacroProtein, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAmount).padding(4.dp)
                )
                m.grams?.takeIf { m.unit != "g" }?.let { Text("≈${fmt0(it)} g", color = ByteTextSecondary, fontSize = 10.sp) }
                if (m.needsReview && m.include) {
                    Text("Looks right", color = OkGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onConfirm).padding(4.dp))
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AmountDialog(m: IngredientMatch, onConfirm: (Float, String) -> Unit, onDismiss: () -> Unit) {
    val food = m.food ?: return
    var qty by remember { mutableStateOf(fmt1(Math.round(m.quantity * 10f) / 10f)) }
    var unit by remember { mutableStateOf(m.unit) }
    val units = listOf("g", "oz", "lb", "ml", "cup", "tbsp", "tsp", "serving", food.servingUnit).distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ByteSurfaceDark,
        title = { Text(food.name, color = ByteCream, fontSize = 16.sp) },
        text = {
            Column {
                Text("Line: ${m.parsed.raw}", color = ByteTextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = qty, onValueChange = { qty = it }, singleLine = true, label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = byteFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    units.forEach { u -> Pill(u, MacroProtein, filled = unit == u) { unit = u } }
                }
                Spacer(Modifier.height(8.dp))
                val preview = qty.toFloatOrNull()?.let { food.caloriesFor(it, unit) }
                Text("${preview?.let { fmt0(it) } ?: "–"} kcal", color = ByteTomato, fontWeight = FontWeight.Bold)
                Text("Reference: ${fmt1(food.servingSize)} ${food.servingUnit} = ${fmt0(food.calories)} kcal", color = ByteTextSecondary, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { qty.toFloatOrNull()?.let { onConfirm(it, unit) } }) { Text("Done", color = ByteTomato) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ByteTextSecondary) } }
    )
}
