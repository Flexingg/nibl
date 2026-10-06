package com.bytelog.app.ui.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.RecipeDetails
import com.bytelog.app.data.repository.RecipeRepository
import com.bytelog.app.data.repository.RecipeView
import com.bytelog.app.recipes.TemplateNutrition
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroProtein
import com.bytelog.app.ui.viewmodel.RecipesViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun RecipeDetailScreen(
    viewModel: RecipesViewModel,
    templateId: String,
    onBack: () -> Unit,
    onCook: () -> Unit
) {
    val recipeFlow = remember(templateId) { viewModel.recipe(templateId) }
    val recipe by recipeFlow.collectAsState(initial = null)
    val mealTypes by viewModel.mealTypes.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf(false) }
    var startingBatch by remember { mutableStateOf(false) }
    var logging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = ByteInkDark) { pad ->
        val r = recipe
        if (r == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Recipe not found. It may still be syncing.", color = ByteTextSecondary)
            }
            return@Scaffold
        }
        RecipeDetailContent(
            r = r,
            modifier = Modifier.padding(pad),
            onBack = onBack,
            onCook = onCook,
            onStartBatch = { startingBatch = true },
            onLog = { logging = true },
            onEdit = { editing = true }
        )
        if (editing) {
            RecipeDetailsEditor(
                details = r.details ?: RecipeDetails(templateId = r.template.id),
                onSave = { viewModel.saveDetails(it); editing = false },
                onDismiss = { editing = false }
            )
        }
        if (startingBatch) {
            StartBatchDialog(
                name = r.template.name,
                recipeTotal = r.total ?: com.bytelog.app.recipes.Macros(),
                recipeServings = r.servings,
                fridgeDays = r.details?.fridgeDays,
                freezerDays = r.details?.freezerDays,
                onConfirm = { name, total, portions, cooked, scale, location, shelf ->
                    viewModel.startBatch(name, r.template.id, total, portions, cooked, scale, location, shelf, r.template.foodsJson)
                    startingBatch = false
                },
                onDismiss = { startingBatch = false }
            )
        }
        if (logging) {
            LogServingsDialog(
                r = r,
                mealTypes = mealTypes,
                onConfirm = { servings, meal ->
                    viewModel.logRecipeServings(r, servings, LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE), meal)
                    logging = false
                },
                onDismiss = { logging = false }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeDetailContent(
    r: RecipeView,
    modifier: Modifier,
    onBack: () -> Unit,
    onCook: () -> Unit,
    onStartBatch: () -> Unit,
    onLog: () -> Unit,
    onEdit: () -> Unit
) {
    val uri = LocalUriHandler.current
    val rows = remember(r.template.foodsJson) { TemplateNutrition.parseRows(r.template.foodsJson) }
    val steps = r.steps
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ByteCream) }
                Text(r.template.name, color = ByteCream, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit directions", tint = ByteTextSecondary) }
            }
            r.template.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = ByteTextSecondary, fontSize = 13.sp) }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ByteSurfaceDark).padding(12.dp)) {
                Text("Per serving · makes ${fmt1(r.servings)}", color = ByteTextSecondary, fontSize = 12.sp)
                r.perServing?.let { MacroLine(it, size = 13) } ?: Text("No nutrition on this template yet.", color = ByteTextSecondary, fontSize = 12.sp)
                val meta = listOfNotNull(
                    r.details?.prepTimeMinutes?.let { "Prep $it min" },
                    r.details?.cookTimeMinutes?.let { "Cook $it min" },
                    r.details?.fridgeDays?.let { "Fridge $it days" },
                    r.details?.freezerDays?.let { "Freezer $it days" }
                )
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = ByteTextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                r.details?.sourceUrl?.let { url ->
                    Text(
                        "Open original recipe",
                        color = MacroProtein, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp))
                            .clickable { runCatching { uri.openUri(url) } }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCook, colors = ButtonDefaults.buttonColors(containerColor = ByteTomato), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.PlayArrow, null, tint = ByteOnTomato, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cook", color = ByteOnTomato, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onStartBatch, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Kitchen, null, tint = MacroProtein, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Start batch", color = MacroProtein, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onLog, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Restaurant, null, tint = MacroCarbs, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Log servings", color = MacroCarbs, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Ingredients", color = ByteCream, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        items(rows) { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("${fmt1(row.quantity)} ${row.unit}", color = MacroProtein, fontSize = 13.sp, modifier = Modifier.width(90.dp))
                Text(row.name, color = ByteCream, fontSize = 13.sp)
            }
        }
        item {
            Spacer(Modifier.height(14.dp))
            Text("Directions", color = ByteCream, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (steps.isEmpty()) {
                Text("No directions yet. Tap the pencil to add them.", color = ByteTextSecondary, fontSize = 13.sp)
            }
        }
        var lastSection: String? = null
        steps.forEachIndexed { i, step ->
            val header = step.section?.takeIf { it != lastSection }
            lastSection = step.section
            item {
                if (header != null) Text(header, color = MacroCarbs, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(ByteSurfaceVariantDark), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = ByteCream, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(step.text, color = ByteCream, fontSize = 14.sp)
                }
            }
        }
        item {
            listOfNotNull(r.details?.notes, r.details?.storageNotes?.let { "Storage: $it" }).filter { it.isNotBlank() }.forEach {
                Spacer(Modifier.height(10.dp))
                Text(it, color = ByteTextSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogServingsDialog(r: RecipeView, mealTypes: List<MealType>, onConfirm: (Float, MealType) -> Unit, onDismiss: () -> Unit) {
    var servings by remember { mutableStateOf(1f) }
    var meal by remember { mutableStateOf(RecipesViewModel.mealForNow(mealTypes)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ByteSurfaceDark,
        title = { Text("Log ${r.template.name}", color = ByteCream) },
        text = {
            Column {
                Text("Servings (recipe makes ${fmt1(r.servings)})", color = ByteTextSecondary, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 1f, 1.5f, 2f).forEach { v -> Pill(fmt1(v), MacroCarbs, filled = servings == v) { servings = v } }
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    mealTypes.forEach { t -> Pill(t.name, ByteTomato, filled = meal?.id == t.id) { meal = t } }
                }
                r.perServing?.let {
                    Spacer(Modifier.height(10.dp))
                    MacroLine(it * servings)
                }
            }
        },
        confirmButton = { TextButton(enabled = meal != null, onClick = { meal?.let { onConfirm(servings, it) } }) { Text("Log", color = ByteTomato) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ByteTextSecondary) } }
    )
}

/** Edit directions, times, storage life and notes for a recipe. */
@Composable
fun RecipeDetailsEditor(details: RecipeDetails, onSave: (RecipeDetails) -> Unit, onDismiss: () -> Unit) {
    var stepsText by remember { mutableStateOf(RecipeRepository.stepsToText(RecipeRepository.decodeSteps(details.stepsJson))) }
    var prep by remember { mutableStateOf(details.prepTimeMinutes?.toString() ?: "") }
    var cook by remember { mutableStateOf(details.cookTimeMinutes?.toString() ?: "") }
    var fridge by remember { mutableStateOf(details.fridgeDays?.toString() ?: "") }
    var freezer by remember { mutableStateOf(details.freezerDays?.toString() ?: "") }
    var notes by remember { mutableStateOf(details.notes ?: "") }
    var storage by remember { mutableStateOf(details.storageNotes ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ByteSurfaceDark,
        title = { Text("Directions & notes", color = ByteCream) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = stepsText, onValueChange = { stepsText = it },
                    label = { Text("Directions — one step per line") },
                    supportingText = { Text("End a line with \":\" to start a section, e.g. \"For the sauce:\"") },
                    colors = byteFieldColors(), modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Prep min", prep, Modifier.weight(1f)) { prep = it }
                    NumberField("Cook min", cook, Modifier.weight(1f)) { cook = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Fridge days", fridge, Modifier.weight(1f)) { fridge = it }
                    NumberField("Freezer days", freezer, Modifier.weight(1f)) { freezer = it }
                }
                OutlinedTextField(value = storage, onValueChange = { storage = it }, label = { Text("Storage notes") }, colors = byteFieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes & tips") }, colors = byteFieldColors(), modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    details.copy(
                        stepsJson = RecipeRepository.encodeSteps(RecipeRepository.stepsFromText(stepsText)),
                        prepTimeMinutes = prep.toIntOrNull(),
                        cookTimeMinutes = cook.toIntOrNull(),
                        fridgeDays = fridge.toIntOrNull(),
                        freezerDays = freezer.toIntOrNull(),
                        notes = notes.ifBlank { null },
                        storageNotes = storage.ifBlank { null }
                    )
                )
            }) { Text("Save", color = ByteTomato) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ByteTextSecondary) } }
    )
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter(Char::isDigit)) }, singleLine = true,
        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = byteFieldColors(), modifier = modifier
    )
}
