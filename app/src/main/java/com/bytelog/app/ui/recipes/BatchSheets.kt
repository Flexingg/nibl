package com.bytelog.app.ui.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.PrepBatch
import com.bytelog.app.data.repository.BatchStatus
import com.bytelog.app.data.repository.MealPrepRepository
import com.bytelog.app.recipes.BatchMath
import com.bytelog.app.recipes.Macros
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroProtein

/** Log a custom amount from a batch, or manage it (freezer, toss, archive, delete). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BatchSheet(
    status: BatchStatus,
    mealTypes: List<MealType>,
    defaultMeal: MealType?,
    onLog: (meal: MealType, portions: Float?, grams: Float?) -> Unit,
    onMove: (location: String, shelfLifeDays: Int?) -> Unit,
    onDiscard: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val batch = status.batch
    val canPortion = batch.portions != null
    val canWeigh = batch.isWeighed
    var byWeight by remember { mutableStateOf(!canPortion) }
    var portions by remember { mutableStateOf(1f) }
    var gramsText by remember { mutableStateOf(status.gramsPerPortion?.let { fmt0(it) } ?: "") }
    var bowlText by remember { mutableStateOf("") }
    var meal by remember { mutableStateOf(defaultMeal ?: mealTypes.firstOrNull()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val grams = (gramsText.toFloatOrNull() ?: 0f) - (bowlText.toFloatOrNull() ?: 0f)
    val preview = if (byWeight) {
        BatchMath.macrosFor(status.total, batch.portions, batch.cookedWeightG, null, grams.takeIf { it > 0f })
    } else {
        BatchMath.macrosFor(status.total, batch.portions, batch.cookedWeightG, portions, null)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ByteInkDark
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Text(batch.name, color = ByteCream, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("${MealPrepRepository.batchLabel(batch)} · ${remainingLabel(status)}", color = ByteTextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                if (canPortion) "Per portion" else "Per 100 g",
                color = ByteTextSecondary, fontSize = 11.sp
            )
            MacroLine(status.perPortion)

            if (!status.isEmpty) {
                Spacer(Modifier.height(16.dp))
                if (canPortion && canWeigh) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Portions", MacroProtein, filled = !byWeight) { byWeight = false }
                        Pill("Weigh it", MacroProtein, filled = byWeight) { byWeight = true }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (!byWeight) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.5f, 1f, 1.5f, 2f).forEach { p ->
                            Pill(fmt1(p), MacroCarbs, filled = portions == p) { portions = p }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = gramsText,
                            onValueChange = { gramsText = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("On the scale (g)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = byteFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = bowlText,
                            onValueChange = { bowlText = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Bowl (g)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = byteFieldColors(),
                            modifier = Modifier.weight(0.7f)
                        )
                    }
                    if (!canWeigh) {
                        Text("Add a cooked weight to this batch to log by grams.", color = ByteTomato, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Log to", color = ByteTextSecondary, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    mealTypes.forEach { t -> Pill(t.name, ByteTomato, filled = meal?.id == t.id) { meal = t } }
                }
                Spacer(Modifier.height(12.dp))
                MacroLine(preview, size = 13)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val m = meal ?: return@Button
                        if (byWeight) onLog(m, null, grams) else onLog(m, portions, null)
                    },
                    enabled = meal != null && (if (byWeight) canWeigh && grams > 0f else portions > 0f),
                    colors = ButtonDefaults.buttonColors(containerColor = ByteTomato),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Log to ${meal?.name ?: "diary"}", color = ByteOnTomato, fontWeight = FontWeight.Bold) }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = ByteSurfaceVariantDark)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (batch.location == PrepBatch.LOCATION_FRIDGE) {
                    Pill("Move to freezer", MacroProtein) { onMove(PrepBatch.LOCATION_FREEZER, MealPrepRepository.DEFAULT_FREEZER_DAYS) }
                } else {
                    Pill("Thaw to fridge", MacroProtein) { onMove(PrepBatch.LOCATION_FRIDGE, 2) }
                }
                if (status.isEmpty) {
                    Pill("Archive", MacroCarbs) { onArchive() }
                } else {
                    Pill("Toss the rest", ByteTomato) { onDiscard() }
                    Pill("Mark finished", MacroCarbs) { onArchive() }
                }
                Pill("Delete batch", ByteTextSecondary) { confirmDelete = true }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = ByteSurfaceDark,
            title = { Text("Delete this batch?", color = ByteCream) },
            text = { Text("Portions you already logged stay in your diary.", color = ByteTextSecondary) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = ByteTomato) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = ByteTextSecondary) } }
        )
    }
}

/**
 * Start a meal-prep batch. Portions default to recipe servings × scale, so cooking 3× a
 * 10-serving recipe gives 30 portions. Cooked weight is optional and enables weighed logging.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartBatchDialog(
    name: String,
    recipeTotal: Macros,
    recipeServings: Float,
    initialScale: Float = 1f,
    fridgeDays: Int?,
    freezerDays: Int?,
    onConfirm: (name: String, total: Macros, portions: Int?, cookedWeightG: Float?, scale: Float, location: String, shelfLifeDays: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var batchName by remember { mutableStateOf(name) }
    var scale by remember { mutableStateOf(initialScale) }
    var portionsText by remember { mutableStateOf(BatchMath.defaultPortions(recipeServings, initialScale).toString()) }
    var portionsEdited by remember { mutableStateOf(false) }
    var weightText by remember { mutableStateOf("") }
    var potText by remember { mutableStateOf("") }
    var location by remember { mutableStateOf(PrepBatch.LOCATION_FRIDGE) }

    val total = recipeTotal * scale
    val portions = portionsText.toIntOrNull()?.takeIf { it > 0 }
    val cooked = ((weightText.toFloatOrNull() ?: 0f) - (potText.toFloatOrNull() ?: 0f)).takeIf { it > 0f }
    val shelf = if (location == PrepBatch.LOCATION_FREEZER) freezerDays ?: MealPrepRepository.DEFAULT_FREEZER_DAYS
    else fridgeDays ?: MealPrepRepository.DEFAULT_FRIDGE_DAYS

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ByteSurfaceDark,
        title = { Text("Start a batch", color = ByteCream, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = batchName, onValueChange = { batchName = it }, singleLine = true,
                    label = { Text("Name") }, colors = byteFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text("How much did you make?", color = ByteTextSecondary, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 1f, 1.5f, 2f, 3f, 4f).forEach { s ->
                        Pill("${fmt1(s)}×", MacroCarbs, filled = scale == s) {
                            scale = s
                            if (!portionsEdited) portionsText = BatchMath.defaultPortions(recipeServings, s).toString()
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = portionsText,
                        onValueChange = { portionsText = it.filter(Char::isDigit); portionsEdited = true },
                        label = { Text("Portions") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = byteFieldColors(), modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = { weightText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Cooked g") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = byteFieldColors(), modifier = Modifier.weight(1f)
                    )
                }
                if (weightText.isNotBlank()) {
                    OutlinedTextField(
                        value = potText,
                        onValueChange = { potText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Empty pot / dish weight (g), optional") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = byteFieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    "Leave portions empty to log only by weight. Add the cooked weight to scoop and weigh any amount.",
                    color = ByteTextSecondary, fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Fridge", MacroProtein, filled = location == PrepBatch.LOCATION_FRIDGE) { location = PrepBatch.LOCATION_FRIDGE }
                    Pill("Freezer", MacroProtein, filled = location == PrepBatch.LOCATION_FREEZER) { location = PrepBatch.LOCATION_FREEZER }
                }
                Text("Best within $shelf days", color = ByteTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ByteSurfaceVariantDark).padding(10.dp)
                ) {
                    Text("Whole batch", color = ByteTextSecondary, fontSize = 11.sp)
                    MacroLine(total)
                    when {
                        portions != null -> {
                            Text("Per portion (${portions})", color = ByteTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                            MacroLine(total / portions.toFloat())
                        }
                        cooked != null -> {
                            Text("Per 100 g", color = ByteTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                            MacroLine(total * (100f / cooked))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = portions != null || cooked != null,
                onClick = { onConfirm(batchName, total, portions, cooked, scale, location, shelf) }
            ) { Text("Start batch", color = ByteTomato, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ByteTextSecondary) } }
    )
}
