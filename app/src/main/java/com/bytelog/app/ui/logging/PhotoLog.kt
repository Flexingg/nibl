package com.bytelog.app.ui.logging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bytelog.app.data.remote.PhotoMealEstimate
import com.bytelog.app.data.remote.PhotoMealItem
import com.bytelog.app.ui.diary.LoadingSpinner
import com.bytelog.app.ui.diary.MorphButton
import com.bytelog.app.ui.viewmodel.FastSearchViewModel.PhotoState
import com.bytelog.app.util.ImageUtils
import java.io.File
import kotlin.math.roundToInt

/** Starts the camera (asking for permission first if needed) or the gallery, and hands back the photo. */
class MealPhotoCapture internal constructor(val takePhoto: () -> Unit, val pickFromGallery: () -> Unit)

@Composable
fun rememberMealPhotoCapture(onPhoto: (Bitmap) -> Unit, onError: (String) -> Unit): MealPhotoCapture {
    val context = LocalContext.current
    val latestPhoto = rememberUpdatedState(onPhoto)
    val latestError = rememberUpdatedState(onError)
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    fun deliver(uri: Uri) {
        val bmp = ImageUtils.loadScaledFromUri(context, uri)
        if (bmp != null) latestPhoto.value(bmp) else latestError.value("Couldn't open that photo")
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingUri
        if (ok && uri != null) deliver(uri)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::deliver) }

    fun launchCamera() {
        val uri = photoUri(context)
        pendingUri = uri
        runCatching { camera.launch(uri) }.onFailure { gallery.launch("image/*") }
    }
    // The app declares the camera permission (for the barcode scanner), so the camera app needs it granted too
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else latestError.value("Camera permission is needed to take a photo. You can pick one from your gallery instead.")
    }
    return remember {
        MealPhotoCapture(
            takePhoto = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                else permission.launch(Manifest.permission.CAMERA)
            },
            pickFromGallery = { gallery.launch("image/*") }
        )
    }
}

private fun photoUri(context: Context): Uri {
    val dir = File(context.cacheDir, "meal_photos").apply { mkdirs() }
    val file = File(dir, "meal.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
}

/** The estimate as a bottom sheet over the search screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoEstimateSheet(
    state: PhotoState,
    mealName: String,
    onAdd: (List<PhotoMealItem>) -> Unit,
    onRetake: () -> Unit,
    onGallery: () -> Unit,
    onReestimate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (state is PhotoState.Idle) return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PhotoEstimateContent(state, mealName, onAdd, onRetake, onGallery, onReestimate, Modifier.navigationBarsPadding())
    }
}

/**
 * The photo, then each food the AI saw with its weight and calories. Everything starts ticked; untick what
 * isn't there, add a detail the photo can't show and estimate again, or add the rest to the tray.
 */
@Composable
fun PhotoEstimateContent(
    state: PhotoState,
    mealName: String,
    onAdd: (List<PhotoMealItem>) -> Unit,
    onRetake: () -> Unit,
    onGallery: () -> Unit,
    onReestimate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val photo = when (state) {
        is PhotoState.Estimating -> state.photo
        is PhotoState.Ready -> state.photo
        is PhotoState.Failed -> state.photo
        PhotoState.Idle -> null
    }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = cs.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Log from a photo", color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        if (photo != null) {
            Image(
                photo.asImageBitmap(), contentDescription = "Meal photo", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(24.dp))
            )
        }
        when (state) {
            is PhotoState.Estimating, PhotoState.Idle -> Row(
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                LoadingSpinner(modifier = Modifier.size(22.dp), color = cs.primary, strokeWidth = 2.5.dp)
                Spacer(Modifier.width(12.dp))
                Text("Sparky is looking at your plate…", color = cs.onSurfaceVariant, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            is PhotoState.Failed -> {
                Text(state.message, color = cs.error, fontSize = 14.sp)
                PhotoActions(onRetake, onGallery, retry = { onReestimate("") })
            }
            is PhotoState.Ready -> ReadyEstimate(state.estimate, mealName, onAdd, onRetake, onGallery, onReestimate)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ReadyEstimate(
    estimate: PhotoMealEstimate,
    mealName: String,
    onAdd: (List<PhotoMealItem>) -> Unit,
    onRetake: () -> Unit,
    onGallery: () -> Unit,
    onReestimate: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    // Each food's guesses, best first; the one shown is [choice], possibly edited into [current]
    val guesses = remember(estimate) { estimate.items.map { item -> listOf(item.copy(alternatives = emptyList())) + item.alternatives } }
    val choice = remember(estimate) { mutableStateListOf(*Array(estimate.items.size) { 0 }) }
    val current = remember(estimate) { mutableStateListOf(*guesses.map { it.first() }.toTypedArray()) }
    val edited = remember(estimate) { mutableStateListOf<Int>() }
    var picked by remember(estimate) { mutableStateOf(estimate.items.indices.toSet()) }
    var note by remember(estimate) { mutableStateOf("") }
    var editing by remember(estimate) { mutableStateOf<Int?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(estimate.summary, color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        ConfidenceChip(estimate.confidence)
    }
    estimate.confidenceReason?.let { Text(it, color = cs.onSurfaceVariant, fontSize = 12.5.sp) }
    estimate.fallbackNote?.let { Text(it, color = cs.tertiary, fontSize = 12.sp) }
    if (guesses.any { it.size > 1 }) {
        Text(
            "${estimate.estimatedBy ?: "Sparky"} gave a few guesses for some foods. Tap the closest one, or the pencil to fix it.",
            color = cs.onSurfaceVariant, fontSize = 12.5.sp
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        current.forEachIndexed { i, item ->
            val on = i in picked
            val first = i == 0; val last = i == current.lastIndex
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = if (first) 20.dp else 6.dp, topEnd = if (first) 20.dp else 6.dp, bottomStart = if (last) 20.dp else 6.dp, bottomEnd = if (last) 20.dp else 6.dp))
                    .background(if (on) cs.surfaceContainerHigh else cs.surfaceContainer)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { picked = if (on) picked - i else picked + i }
                        .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
                        .semantics { contentDescription = "${item.name}, ${if (on) "included" else "left out"}" },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (on) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, contentDescription = null,
                        tint = if (on) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name.replaceFirstChar { it.titlecase() }, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            listOf(amountText(item), "P ${item.protein.roundToInt()} · C ${item.carbs.roundToInt()} · F ${item.fat.roundToInt()}").filter { it.isNotBlank() }.joinToString("  ·  "),
                            color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        val why = if (i in edited) "Edited by you" else item.reason
                        if (!why.isNullOrBlank()) Text(why, color = cs.onSurfaceVariant, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (i !in edited && item.confidence.equals("low", ignoreCase = true)) {
                        Text("unsure", color = cs.tertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
                    }
                    Text("${item.calories.roundToInt()}", color = cs.primary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(" kcal", color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { editing = i }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit ${item.name}", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
                val options = guesses[i]
                if (options.size > 1) {
                    // The guesses differ in what the food is or in how much; label each by what differs
                    val sameName = options.map { it.name.lowercase() }.distinct().size == 1
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 44.dp, end = 12.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        options.forEachIndexed { g, guess ->
                            val selected = choice[i] == g && i !in edited
                            val label = (if (sameName) amountText(guess).ifBlank { guess.name } else guess.name) + " · ${guess.calories.roundToInt()} kcal"
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (selected) cs.primary else cs.surfaceContainerHighest)
                                    .clickable {
                                        choice[i] = g; current[i] = guess; edited.remove(i)
                                        picked = picked + i
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .semantics { contentDescription = "Guess ${g + 1}: $label${if (selected) ", chosen" else ""}" }
                            ) {
                                Text(label, color = if (selected) cs.onPrimary else cs.onSurface, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { i ->
        EditGuessDialog(
            item = current[i],
            onSave = { changed -> current[i] = changed; if (i !in edited) edited.add(i); picked = picked + i; editing = null },
            onDismiss = { editing = null }
        )
    }

    // What would make it more accurate, and a place to say it
    if (estimate.questions.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            estimate.questions.forEach { q -> Text("• $q", color = cs.onSurfaceVariant, fontSize = 12.5.sp) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            placeholder = { Text("Add a detail, e.g. cooked in butter", fontSize = 13.sp) },
            singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        MorphButton({ onReestimate(note) }, Modifier.size(52.dp), cs.surfaceContainerHighest, description = "Estimate again") {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = cs.onSurface)
        }
    }

    val chosen = current.filterIndexed { i, _ -> i in picked }
    val kcal = chosen.sumOf { it.calories.toDouble() }.roundToInt()
    MorphButton(
        { if (chosen.isNotEmpty()) onAdd(chosen) },
        Modifier.fillMaxWidth().height(56.dp),
        if (chosen.isNotEmpty()) cs.primary else cs.surfaceContainerHighest,
        description = "Add ${chosen.size} to tray"
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (chosen.isEmpty()) "Pick at least one food" else "Add ${chosen.size} for $mealName · $kcal kcal",
                color = if (chosen.isNotEmpty()) cs.onPrimary else cs.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
            )
        }
    }
    PhotoActions(onRetake, onGallery, retry = null)
}

/** [item] at [grams], its nutrition scaled with the weight. Null when either weight is unknown. */
internal fun scaleToGrams(item: PhotoMealItem, grams: Float): PhotoMealItem? {
    if (item.grams <= 0f || grams <= 0f) return null
    val k = grams / item.grams
    return item.copy(grams = grams, calories = item.calories * k, protein = item.protein * k, carbs = item.carbs * k, fat = item.fat * k, fiber = item.fiber * k)
}

internal fun amountText(item: PhotoMealItem): String =
    listOfNotNull(item.grams.takeIf { it > 0f }?.let { "${it.roundToInt()} g" }, item.portion?.takeIf { it.isNotBlank() }).joinToString(" · ")

/**
 * Fix a guess: its name, weight and nutrition. Changing the weight scales the nutrition with it; the numbers can
 * still be typed over afterwards.
 */
@Composable
private fun EditGuessDialog(item: PhotoMealItem, onSave: (PhotoMealItem) -> Unit, onDismiss: () -> Unit) {
    fun fmt(v: Float) = if (v == kotlin.math.floor(v)) v.toInt().toString() else "%.1f".format(java.util.Locale.US, v)
    var name by remember(item) { mutableStateOf(item.name) }
    var grams by remember(item) { mutableStateOf(if (item.grams > 0f) fmt(item.grams) else "") }
    var kcal by remember(item) { mutableStateOf(fmt(item.calories)) }
    var protein by remember(item) { mutableStateOf(fmt(item.protein)) }
    var carbs by remember(item) { mutableStateOf(fmt(item.carbs)) }
    var fat by remember(item) { mutableStateOf(fmt(item.fat)) }

    fun onGrams(text: String) {
        grams = text
        val scaled = scaleToGrams(item, text.toFloatOrNull() ?: return) ?: return
        kcal = fmt(scaled.calories); protein = fmt(scaled.protein); carbs = fmt(scaled.carbs); fat = fmt(scaled.fat)
    }

    val valid = name.isNotBlank() && kcal.toFloatOrNull() != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit food") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                NumberField("Weight (g)", grams, ::onGrams, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("kcal", kcal, { kcal = it }, Modifier.weight(1f))
                    NumberField("Protein", protein, { protein = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Carbs", carbs, { carbs = it }, Modifier.weight(1f))
                    NumberField("Fat", fat, { fat = it }, Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(item.copy(
                    name = name.trim(),
                    grams = grams.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f,
                    // The household amount no longer matches a typed weight
                    portion = if (grams.toFloatOrNull() == item.grams || grams.isBlank()) item.portion else null,
                    calories = kcal.toFloatOrNull()?.coerceAtLeast(0f) ?: item.calories,
                    protein = protein.toFloatOrNull()?.coerceAtLeast(0f) ?: item.protein,
                    carbs = carbs.toFloatOrNull()?.coerceAtLeast(0f) ?: item.carbs,
                    fat = fat.toFloatOrNull()?.coerceAtLeast(0f) ?: item.fat,
                    confidence = "high", alternatives = emptyList()
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

@Composable
private fun PhotoActions(onRetake: () -> Unit, onGallery: () -> Unit, retry: (() -> Unit)?) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onRetake) {
            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Retake")
        }
        TextButton(onClick = onGallery) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Gallery")
        }
        if (retry != null) TextButton(onClick = retry) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Try again")
        }
    }
}

@Composable
private fun ConfidenceChip(confidence: String) {
    val cs = MaterialTheme.colorScheme
    val (label, bg, fg) = when (confidence.lowercase()) {
        "high" -> Triple("Confident", cs.secondaryContainer, cs.onSecondaryContainer)
        "low" -> Triple("Rough guess", cs.errorContainer, cs.onErrorContainer)
        else -> Triple("Good guess", cs.tertiaryContainer, cs.onTertiaryContainer)
    }
    Box(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
