package com.bytelog.app.ui.recipes

import android.app.Activity
import androidx.compose.ui.graphics.Color
import com.bytelog.app.ui.theme.fluidBackdrop
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.recipes.Macros
import com.bytelog.app.recipes.TemplateNutrition
import com.bytelog.app.recipes.TimerDetector
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
import kotlinx.coroutines.delay

private data class CookTimer(val id: Long, val label: String, val totalSeconds: Int, val endsAt: Long, val rang: Boolean = false)

/**
 * Hands-free cooking view: big type, screen kept on, tap-anywhere checklists for
 * ingredients and steps, and one-tap timers for durations found in the steps.
 * Timers run while this screen is open; they do not fire if the app is closed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CookModeScreen(viewModel: RecipesViewModel, templateId: String, onClose: () -> Unit) {
    val recipeFlow = remember(templateId) { viewModel.recipe(templateId) }
    val recipe by recipeFlow.collectAsState(initial = null)
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var scale by rememberSaveable { mutableStateOf(1f) }
    val checkedIngredients = rememberSaveable(saver = intSetSaver()) { mutableStateListOf<Int>() }
    val checkedSteps = rememberSaveable(saver = intSetSaver()) { mutableStateListOf<Int>() }
    val timers = remember { mutableStateListOf<CookTimer>() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showBatch by remember { mutableStateOf(false) }

    LaunchedEffect(timers.size) {
        while (timers.isNotEmpty()) {
            now = System.currentTimeMillis()
            timers.forEachIndexed { i, t ->
                if (!t.rang && now >= t.endsAt) {
                    timers[i] = t.copy(rang = true)
                    alert(context)
                }
            }
            delay(500)
        }
    }

    Scaffold(
        modifier = Modifier.fluidBackdrop(), containerColor = Color.Transparent) { pad ->
        val r = recipe ?: return@Scaffold
        val rows = remember(r.template.foodsJson) { TemplateNutrition.parseRows(r.template.foodsJson) }
        val steps = r.steps

        Column(Modifier.fillMaxSize().padding(pad)) {
            com.bytelog.app.ui.components.ScreenHeader(
                title = r.template.name,
                subtitle = "${checkedSteps.size} of ${steps.size} steps done · screen stays on",
                onBack = onClose,
                statusBarPadding = false,
                actions = { TextButton(onClick = { showBatch = true }) { Text("Done", color = ByteTomato, fontWeight = FontWeight.Bold) } }
            )

            if (timers.isNotEmpty()) {
                LazyRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(timers, key = { it.id }) { t ->
                        val left = ((t.endsAt - now) / 1000).toInt().coerceAtLeast(0)
                        Row(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (t.rang) ByteTomato else ByteSurfaceVariantDark)
                                .clickable { timers.remove(t) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Timer, null, tint = if (t.rang) ByteOnTomato else ByteCream, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (t.rang) "${t.label} — done! (tap to clear)" else "${TimerDetector.format(left)} · ${t.label}",
                                color = if (t.rang) ByteOnTomato else ByteCream, fontSize = 15.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text("Scale", color = ByteTextSecondary, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.5f, 1f, 1.5f, 2f, 3f, 4f).forEach { s -> Pill("${fmt1(s)}×", MacroCarbs, filled = scale == s) { scale = s } }
                    }
                    Text("Makes ${fmt1(r.servings * scale)} servings", color = ByteTextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Ingredients", color = ByteCream, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                itemsIndexed(rows) { i, row ->
                    val done = i in checkedIngredients
                    CheckRow(done = done, onToggle = { if (done) checkedIngredients.remove(i) else checkedIngredients.add(i) }) {
                        Text(
                            "${fmt1(Math.round(row.quantity * scale * 10f) / 10f)} ${row.unit}  ${row.name}",
                            color = if (done) ByteTextSecondary else ByteCream, fontSize = 18.sp,
                            textDecoration = if (done) TextDecoration.LineThrough else null
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    Text("Steps", color = ByteCream, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (steps.isEmpty()) Text("This recipe has no directions yet. Add them from the recipe page.", color = ByteTextSecondary)
                }
                var lastSection: String? = null
                steps.forEachIndexed { i, step ->
                    val header = step.section?.takeIf { it != lastSection }
                    lastSection = step.section
                    item(key = "step$i") {
                        if (header != null) Text(header, color = MacroCarbs, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        val done = i in checkedSteps
                        CheckRow(done = done, onToggle = { if (done) checkedSteps.remove(i) else checkedSteps.add(i) }) {
                            Column {
                                Text("${i + 1}. ${step.text}", color = if (done) ByteTextSecondary else ByteCream, fontSize = 19.sp, lineHeight = 26.sp)
                                val found = remember(step.text) { TimerDetector.detect(step.text) }
                                if (found.isNotEmpty() && !done) {
                                    Spacer(Modifier.height(6.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        found.forEach { t ->
                                            Pill("⏱ ${t.label}", MacroProtein) {
                                                val id = System.nanoTime()
                                                timers.add(CookTimer(id, t.label, t.seconds, System.currentTimeMillis() + t.seconds * 1000L))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { showBatch = true },
                        colors = ButtonDefaults.buttonColors(containerColor = ByteTomato),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text("Finished cooking — start a batch", color = ByteOnTomato, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close without a batch", color = ByteTextSecondary) }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }

        if (showBatch) {
            StartBatchDialog(
                name = r.template.name,
                recipeTotal = r.total ?: Macros(),
                recipeServings = r.servings,
                initialScale = scale,
                fridgeDays = r.details?.fridgeDays,
                freezerDays = r.details?.freezerDays,
                onConfirm = { name, total, portions, cooked, s, location, shelf ->
                    viewModel.startBatch(name, r.template.id, total, portions, cooked, s, location, shelf, r.template.foodsJson)
                    showBatch = false
                    onClose()
                },
                onDismiss = { showBatch = false }
            )
        }
    }
}

@Composable
private fun CheckRow(done: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (done) ByteSurfaceDark.copy(alpha = 0.5f) else ByteSurfaceDark)
            .clickable(onClick = onToggle)
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
            tint = if (done) MacroProtein else ByteTextSecondary, modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

private fun intSetSaver() = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<Int>, Int>(
    save = { it.toList() },
    restore = { mutableStateListOf<Int>().apply { addAll(it) } }
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun alert(context: Context) {
    runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 600), -1))
    }
    runCatching {
        // Notification sound, not the alarm tone: alarm tones can loop with no way to stop them here.
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        RingtoneManager.getRingtone(context, uri)?.play()
    }
}
