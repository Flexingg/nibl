package com.bytelog.app.ui.fasting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.fasting.FastingLog
import com.bytelog.app.data.fasting.FastingPreferences
import com.bytelog.app.data.fasting.FastingProtocol
import com.bytelog.app.data.fasting.FastingStats
import com.bytelog.app.data.fasting.MetabolicZone
import com.bytelog.app.data.fasting.formatFastingMinutes
import com.bytelog.app.data.fasting.formatFastingTimer
import com.bytelog.app.ui.components.ScreenHeader
import com.bytelog.app.ui.components.animateBouncy
import com.bytelog.app.ui.components.rememberScrollFraction
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.LocalByteExtraColors
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastingScreen(
    viewModel: FastingViewModel,
    onBack: () -> Unit
) {
    val activeFast by viewModel.activeFast.collectAsState()
    val history by viewModel.history.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val preferences by viewModel.preferences.collectAsState()
    val selectedProtocol by viewModel.selectedProtocol.collectAsState()
    val customTargetHours by viewModel.customTargetHours.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scrollFraction = rememberScrollFraction(listState)

    var showEditStartDialog by remember { mutableStateOf(false) }
    var showPreferencesSheet by remember { mutableStateOf(false) }
    var pendingDeleteFast by remember { mutableStateOf<FastingLog?>(null) }
    var showEndConfirmDialog by remember { mutableStateOf(false) }

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activeFast?.id, activeFast?.startTime) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000L)
        }
    }

    LaunchedEffect(statusMessage) {
        val msg = statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusMessage()
    }

    val isFasting = activeFast != null && activeFast!!.isActive
    val nowInstant = Instant.ofEpochMilli(nowMillis)
    val elapsedMs = if (isFasting) activeFast!!.elapsedMillis(nowInstant) else 0L
    val targetMs = if (isFasting) {
        activeFast!!.targetDurationMillis()
    } else {
        val hours = if (selectedProtocol == FastingProtocol.CUSTOM) customTargetHours else selectedProtocol.fastHours
        (hours * 3_600_000L).toLong().coerceAtLeast(3_600_000L)
    }
    val rawProgress = if (isFasting && targetMs > 0L) (elapsedMs.toFloat() / targetMs.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedProgress = animateBouncy(rawProgress, "fasting_ring_progress").coerceIn(0f, 1f)
    val elapsedHours = elapsedMs / 3_600_000f
    val currentZone = MetabolicZone.forElapsedHours(elapsedHours)
    val zoneColor = metabolicZoneColor(currentZone)

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ScreenHeader(
                title = "Fasting",
                subtitle = if (isFasting) "${currentZone.label} • ${FastingProtocol.fromCode(activeFast?.fastingType).label}" else "Intermittent Fasting",
                onBack = onBack,
                fraction = scrollFraction,
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = ByteCreamMuted
                        )
                    }
                    IconButton(onClick = { showPreferencesSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Fasting Preferences",
                            tint = ByteCream
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Live Circular Fasting Progress Ring Card
            item(key = "ring") {
                FastingProgressRingCard(
                    isFasting = isFasting,
                    elapsedMs = elapsedMs,
                    targetMs = targetMs,
                    progress = animatedProgress,
                    rawPercent = (rawProgress * 100f).roundToInt(),
                    activeFast = activeFast,
                    selectedProtocol = selectedProtocol,
                    customTargetHours = customTargetHours,
                    zone = currentZone,
                    zoneColor = zoneColor,
                    onStartFast = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.startFast()
                    },
                    onEndFast = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showEndConfirmDialog = true
                    },
                    onEditStartTime = { showEditStartDialog = true }
                )
            }

            // 2. Protocol Selector Chips
            item(key = "protocols") {
                ProtocolSelectorCard(
                    selectedProtocol = selectedProtocol,
                    customTargetHours = customTargetHours,
                    isFasting = isFasting,
                    onSelectProtocol = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.selectProtocol(it)
                    },
                    onCustomHoursChange = { viewModel.setCustomTargetHours(it) }
                )
            }

            // 3. Stats Summary Row
            item(key = "stats") {
                FastingStatsRow(stats = stats)
            }

            // 4. Metabolic Zone Timeline
            item(key = "zones") {
                MetabolicZoneTimelineCard(
                    isFasting = isFasting,
                    elapsedHours = elapsedHours,
                    currentZone = currentZone
                )
            }

            // 5. History Section Header
            item(key = "history_header") {
                Text(
                    text = "RECENT FASTS",
                    color = ByteCreamMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            if (history.isEmpty()) {
                item(key = "history_empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(ByteSurfaceDark)
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No completed fasts yet. Start a fast above to track your metabolic progress.",
                            color = ByteCreamMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                items(history, key = { it.id }) { log ->
                    FastingHistoryRow(
                        log = log,
                        onDelete = { pendingDeleteFast = log }
                    )
                }
            }

            item(key = "bottom_spacer") {
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // Edit Start Time Dialog
    if (showEditStartDialog && activeFast != null) {
        EditStartTimeDialog(
            currentStart = activeFast!!.startInstant() ?: Instant.now(),
            onDismiss = { showEditStartDialog = false },
            onConfirm = { newStart ->
                showEditStartDialog = false
                viewModel.adjustActiveStartTime(newStart)
            }
        )
    }

    // End Fast Confirmation Dialog
    if (showEndConfirmDialog && activeFast != null) {
        AlertDialog(
            onDismissRequest = { showEndConfirmDialog = false },
            title = { Text("End Fast?") },
            text = {
                Text("Log this ${formatFastingTimer(elapsedMs)} fast as completed?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEndConfirmDialog = false
                        viewModel.endActiveFast()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ByteTomato, contentColor = ByteOnTomato)
                ) {
                    Text("End Fast")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndConfirmDialog = false }) {
                    Text("Keep Fasting")
                }
            }
        )
    }

    // Delete History Confirmation Dialog
    pendingDeleteFast?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteFast = null },
            title = { Text("Delete Fast?") },
            text = { Text("Remove this fast from your fasting history?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = toDelete.id
                        pendingDeleteFast = null
                        viewModel.deleteFast(id)
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteFast = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Fasting Preferences Bottom Sheet
    if (showPreferencesSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showPreferencesSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            FastingPreferencesSheetContent(
                initial = preferences,
                onSave = { updated ->
                    showPreferencesSheet = false
                    viewModel.updatePreferences(updated)
                },
                onDismiss = { showPreferencesSheet = false }
            )
        }
    }
}

@Composable
private fun FastingProgressRingCard(
    isFasting: Boolean,
    elapsedMs: Long,
    targetMs: Long,
    progress: Float,
    rawPercent: Int,
    activeFast: FastingLog?,
    selectedProtocol: FastingProtocol,
    customTargetHours: Float,
    zone: MetabolicZone,
    zoneColor: Color,
    onStartFast: () -> Unit,
    onEndFast: () -> Unit,
    onEditStartTime: () -> Unit
) {
    val trackColor = ByteSurfaceVariantDark
    val ringAccent = if (isFasting) zoneColor else ByteTomato
    val successColor = LocalByteExtraColors.current.success

    val targetLabel = remember(isFasting, activeFast?.targetEndTime, elapsedMs, targetMs, selectedProtocol, customTargetHours) {
        if (isFasting) {
            val targetInstant = activeFast?.targetEndInstant()
                ?: activeFast?.startInstant()?.plusMillis(targetMs)
            if (elapsedMs >= targetMs) {
                val extraMins = ((elapsedMs - targetMs) / 60_000L).toInt()
                "Goal reached (+${formatFastingMinutes(extraMins)})"
            } else if (targetInstant != null) {
                "Goal: ${formatRelativeDayTime(targetInstant)}"
            } else {
                "Target: ${formatFastingMinutes((targetMs / 60_000L).toInt())}"
            }
        } else {
            val hrs = if (selectedProtocol == FastingProtocol.CUSTOM) customTargetHours.roundToInt() else selectedProtocol.fastHours.roundToInt()
            "Target: ${hrs}h (${selectedProtocol.label})"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(ByteSurfaceDark)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Circular Ring
        Box(
            modifier = Modifier.size(224.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 16.dp.toPx()
                val inset = strokeWidth / 2f + 4.dp.toPx()
                val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                val topLeft = Offset(inset, inset)

                // Background track
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Progress sweep arc
                val sweep = (progress * 360f).coerceIn(0f, 360f)
                if (sweep > 0.5f) {
                    val brush = Brush.sweepGradient(
                        colors = listOf(ringAccent.copy(alpha = 0.75f), ringAccent, successColor)
                    )
                    drawArc(
                        brush = brush,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )

                    // Tip indicator dot
                    val angleRad = (-90.0 + sweep) * PI / 180.0
                    val radius = arcSize.width / 2f
                    val cx = size.width / 2f + (radius * cos(angleRad)).toFloat()
                    val cy = size.height / 2f + (radius * sin(angleRad)).toFloat()
                    drawCircle(
                        color = Color.White,
                        radius = strokeWidth * 0.28f,
                        center = Offset(cx, cy)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isFasting) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(zoneColor.copy(alpha = 0.16f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalFireDepartment,
                                contentDescription = null,
                                tint = zoneColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = zone.label,
                                color = zoneColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Text(
                        text = "READY TO FAST",
                        color = ByteCreamMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                Text(
                    text = if (isFasting) formatFastingTimer(elapsedMs) else "00:00:00",
                    color = ByteCream,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )

                Text(
                    text = if (isFasting) "$rawPercent% • $targetLabel" else targetLabel,
                    color = ByteCreamMuted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Start time subtitle + Edit button when active
        if (isFasting) {
            val startInstant = activeFast?.startInstant()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ByteSurfaceVariantDark)
                    .clickable(onClick = onEditStartTime)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Started",
                        color = ByteCreamMuted,
                        fontSize = 11.sp
                    )
                    Text(
                        text = startInstant?.let(::formatRelativeDayTime) ?: activeFast?.startTime.orEmpty(),
                        color = ByteCream,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit start time",
                        tint = ByteTomato,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Edit Start",
                        color = ByteTomato,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Primary Start / End Action Button
        Button(
            onClick = if (isFasting) onEndFast else onStartFast,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isFasting) ByteSurfaceVariantDark else ByteTomato,
                contentColor = if (isFasting) ByteCream else ByteOnTomato
            )
        ) {
            Icon(
                imageVector = if (isFasting) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isFasting) "End Fast" else "Start Fast",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ProtocolSelectorCard(
    selectedProtocol: FastingProtocol,
    customTargetHours: Float,
    isFasting: Boolean,
    onSelectProtocol: (FastingProtocol) -> Unit,
    onCustomHoursChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ByteSurfaceDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PROTOCOL",
                color = ByteCreamMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )
            Text(
                text = if (selectedProtocol == FastingProtocol.CUSTOM) {
                    "${customTargetHours.roundToInt()}h fast"
                } else {
                    "${selectedProtocol.fastHours.roundToInt()}h fast / ${selectedProtocol.eatHours.roundToInt()}h eat"
                },
                color = ByteCreamMuted,
                fontSize = 12.sp
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FastingProtocol.entries.forEach { protocol ->
                val selected = protocol == selectedProtocol
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) ByteTomato else ByteSurfaceVariantDark)
                        .clickable(enabled = !isFasting) { onSelectProtocol(protocol) }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = protocol.label,
                        color = if (selected) ByteOnTomato else ByteCream,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        AnimatedVisibility(visible = selectedProtocol == FastingProtocol.CUSTOM && !isFasting) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Custom target", color = ByteCream, fontSize = 13.sp)
                    Text(
                        "${customTargetHours.roundToInt()} hours",
                        color = ByteTomato,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = customTargetHours,
                    onValueChange = { onCustomHoursChange(it.roundToInt().toFloat()) },
                    valueRange = 8f..48f,
                    colors = SliderDefaults.colors(
                        thumbColor = ByteTomato,
                        activeTrackColor = ByteTomato
                    )
                )
            }
        }
    }
}

@Composable
private fun FastingStatsRow(stats: FastingStats) {
    val totalHours = (stats.totalMinutesFasted / 60f).roundToInt()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatTile(
            label = "TOTAL FASTS",
            value = "${stats.totalCompletedFasts}",
            modifier = Modifier.weight(1f)
        )
        StatTile(
            label = "TOTAL HOURS",
            value = "${totalHours}h",
            modifier = Modifier.weight(1f)
        )
        StatTile(
            label = "AVG FAST",
            value = formatFastingMinutes(stats.averageDurationMinutes),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ByteSurfaceDark)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = value,
            color = ByteCream,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = ByteCreamMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun MetabolicZoneTimelineCard(
    isFasting: Boolean,
    elapsedHours: Float,
    currentZone: MetabolicZone
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ByteSurfaceDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "METABOLIC ZONES",
            color = ByteCreamMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp
        )

        MetabolicZone.entries.forEachIndexed { index, zone ->
            val color = metabolicZoneColor(zone)
            val reached = isFasting && elapsedHours >= zone.minHours
            val active = isFasting && zone == currentZone

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (active) color.copy(alpha = 0.12f) else Color.Transparent)
                    .then(
                        if (active) Modifier.border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        else Modifier
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (reached) color else ByteSurfaceVariantDark),
                    contentAlignment = Alignment.Center
                ) {
                    if (reached) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(13.dp)
                        )
                    } else {
                        Text(
                            text = "${index + 1}",
                            color = ByteCreamMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = zone.label,
                            color = if (active) color else ByteCream,
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold
                        )
                        Text(
                            text = "${zone.minHours.roundToInt()}h+",
                            color = if (reached) color else ByteCreamMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = zone.description,
                        color = ByteCreamMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun FastingHistoryRow(
    log: FastingLog,
    onDelete: () -> Unit
) {
    val startInstant = log.startInstant()
    val endInstant = log.endInstant()
    val durationMins = log.durationMinutes ?: (log.elapsedMillis() / 60_000L).toInt()
    val protocolLabel = FastingProtocol.fromCode(log.fastingType).label
    val zone = MetabolicZone.forElapsedHours(durationMins / 60f)
    val zoneColor = metabolicZoneColor(zone)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ByteSurfaceDark)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = formatFastingMinutes(durationMins),
                    color = ByteCream,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(zoneColor.copy(alpha = 0.16f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = protocolLabel,
                        color = zoneColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            val rangeText = buildString {
                if (startInstant != null) {
                    append(formatShortDateTime(startInstant))
                } else {
                    append(log.startTime.take(16))
                }
                if (endInstant != null) {
                    append(" → ")
                    append(formatShortDateTime(endInstant))
                }
            }
            Text(
                text = rangeText,
                color = ByteCreamMuted,
                fontSize = 12.sp
            )
        }

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "Delete fast",
                tint = ByteCreamMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun EditStartTimeDialog(
    currentStart: Instant,
    onDismiss: () -> Unit,
    onConfirm: (Instant) -> Unit
) {
    var offsetMinutes by remember { mutableIntStateOf(0) }
    val previewStart = remember(currentStart, offsetMinutes) {
        val candidate = currentStart.plusSeconds(offsetMinutes * 60L)
        val now = Instant.now()
        if (candidate.isAfter(now)) now else candidate
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust Start Time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "New start: ${formatRelativeDayTime(previewStart)}",
                    color = ByteCream,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )

                // Quick rewind presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(-30 to "-30m", -60 to "-1h", -120 to "-2h", -240 to "-4h").forEach { (delta, label) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ByteSurfaceVariantDark)
                                .clickable { offsetMinutes += delta }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = ByteCream, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Fine +/- 15m stepper
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { offsetMinutes -= 15 }) {
                        Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("15m")
                    }
                    Text(
                        text = if (offsetMinutes == 0) "Unchanged" else "${if (offsetMinutes > 0) "+" else ""}${offsetMinutes}m",
                        color = ByteCreamMuted,
                        fontSize = 13.sp
                    )
                    OutlinedButton(onClick = { offsetMinutes += 15 }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("15m")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(previewStart) },
                colors = ButtonDefaults.buttonColors(containerColor = ByteTomato, contentColor = ByteOnTomato)
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun FastingPreferencesSheetContent(
    initial: FastingPreferences,
    onSave: (FastingPreferences) -> Unit,
    onDismiss: () -> Unit
) {
    var autoCalculate by remember { mutableStateOf(initial.autoCalculate) }
    var defaultProtocol by remember { mutableStateOf(FastingProtocol.fromCode(initial.defaultProtocol)) }
    var preEndAlertMinutes by remember { mutableIntStateOf(initial.preEndAlertMinutes) }
    var calorieThreshold by remember { mutableFloatStateOf(initial.calorieThreshold.toFloat()) }
    var eatingWindowAlert by remember { mutableStateOf(initial.eatingWindowAlert) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Fasting Preferences",
            color = ByteCream,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        // Auto-calculate from meals toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Auto-calculate from meals", color = ByteCream, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Automatically infer fasting windows from your logged food timestamps",
                    color = ByteCreamMuted,
                    fontSize = 12.sp
                )
            }
            Switch(checked = autoCalculate, onCheckedChange = { autoCalculate = it })
        }

        HorizontalDivider(color = ByteSurfaceVariantDark)

        // Default protocol
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Default protocol", color = ByteCream, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FastingProtocol.entries.filter { it != FastingProtocol.CUSTOM }.forEach { protocol ->
                    val selected = protocol == defaultProtocol
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) ByteTomato else ByteSurfaceVariantDark)
                            .clickable { defaultProtocol = protocol }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = protocol.label,
                            color = if (selected) ByteOnTomato else ByteCream,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Pre-end alert minutes
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pre-end alert", color = ByteCream, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0 to "Off", 15 to "15m", 30 to "30m", 60 to "60m").forEach { (mins, label) ->
                    val selected = preEndAlertMinutes == mins
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) ByteTomato else ByteSurfaceVariantDark)
                            .clickable { preEndAlertMinutes = mins }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (selected) ByteOnTomato else ByteCream,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Calorie threshold
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Fast-breaking calorie threshold", color = ByteCream, fontSize = 14.sp)
                Text("${calorieThreshold.roundToInt()} kcal", color = ByteTomato, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = calorieThreshold,
                onValueChange = { calorieThreshold = it },
                valueRange = 0f..50f,
                steps = 9,
                colors = SliderDefaults.colors(thumbColor = ByteTomato, activeTrackColor = ByteTomato)
            )
        }

        // Eating window alert toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Eating window alert", color = ByteCream, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Notify when your eating window is about to close", color = ByteCreamMuted, fontSize = 12.sp)
            }
            Switch(checked = eatingWindowAlert, onCheckedChange = { eatingWindowAlert = it })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Cancel")
            }
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            autoCalculate = autoCalculate,
                            defaultProtocol = defaultProtocol.code,
                            targetFastingHours = defaultProtocol.fastHours,
                            targetEatingHours = defaultProtocol.eatHours,
                            calorieThreshold = calorieThreshold.roundToInt(),
                            preEndAlertMinutes = preEndAlertMinutes,
                            eatingWindowAlert = eatingWindowAlert
                        )
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ByteTomato, contentColor = ByteOnTomato)
            ) {
                Text("Save")
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

private fun formatRelativeDayTime(instant: Instant): String {
    val zone = ZoneId.systemDefault()
    val zoned = instant.atZone(zone)
    val today = LocalDate.now(zone)
    val dayLabel = when (zoned.toLocalDate()) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        today.plusDays(1) -> "Tomorrow"
        else -> zoned.format(dateFormatter)
    }
    return "$dayLabel, ${zoned.format(timeFormatter)}"
}

private fun formatShortDateTime(instant: Instant): String {
    val zoned = instant.atZone(ZoneId.systemDefault())
    return "${zoned.format(dateFormatter)} ${zoned.format(timeFormatter)}"
}
