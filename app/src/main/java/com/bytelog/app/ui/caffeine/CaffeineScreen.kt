package com.bytelog.app.ui.caffeine

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.caffeine.CaffeineDose
import com.bytelog.app.data.caffeine.CaffeineMath
import com.bytelog.app.data.caffeine.CaffeinePreset
import com.bytelog.app.data.caffeine.CaffeineSettings
import com.bytelog.app.data.caffeine.CaffeineStatus
import com.bytelog.app.data.caffeine.DefaultCaffeinePresets
import com.bytelog.app.ui.components.ScreenHeader
import com.bytelog.app.ui.components.rememberScrollFraction
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.LocalByteExtraColors
import com.bytelog.app.ui.theme.MacroCarbs
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full-screen Caffeine Tracker featuring:
 * - 24-hour exponential decay curve (Compose Canvas) with current time marker, bedtime line & 100mg sleep threshold
 * - Quick-log preset grid (8 common drinks)
 * - Custom mg + time logger
 * - Today's dose list
 * - Half-life (2–8h) & target bedtime settings dialog
 */
@Composable
fun CaffeineScreen(
    viewModel: CaffeineViewModel,
    onBack: () -> Unit,
    defaultMealTypeId: String = ""
) {
    val status by viewModel.status.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()

    LaunchedEffect(message) {
        if (message != null) {
            delay(3200)
            viewModel.clearMessage()
        }
    }

    CaffeineScreenContent(
        status = status,
        settings = settings,
        selectedDate = selectedDate,
        isLoading = isLoading,
        message = message,
        onBack = onBack,
        onRefresh = { viewModel.load(selectedDate) },
        onLogPreset = { preset ->
            viewModel.logPreset(preset = preset, mealTypeId = defaultMealTypeId)
        },
        onLogCustom = { name, mg, timeHHmm ->
            viewModel.logCustom(name = name, mg = mg, timeHHmm = timeHHmm, mealTypeId = defaultMealTypeId)
        },
        onUpdateSettings = { halfLife, bedtime ->
            viewModel.updateSettings(halfLife, bedtime)
        }
    )
}

@Composable
fun CaffeineScreenContent(
    status: CaffeineStatus?,
    settings: CaffeineSettings,
    selectedDate: String,
    isLoading: Boolean,
    message: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLogPreset: (CaffeinePreset) -> Unit,
    onLogCustom: (name: String, mg: Float, timeHHmm: String) -> Unit,
    onUpdateSettings: (halfLifeHours: Float, targetBedtime: String) -> Unit
) {
    val scrollState = rememberScrollState()
    val scrollFraction = rememberScrollFraction(scrollState)
    val haptic = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val extras = LocalByteExtraColors.current
    val accent = MacroCarbs

    var showSettingsDialog by remember { mutableStateOf(false) }

    val halfLife = status?.halfLifeHours ?: settings.halfLifeHours
    val bedtime = status?.targetBedtime?.take(5)?.ifBlank { settings.targetBedtime } ?: settings.targetBedtime
    val halfLifeFormatted = String.format(Locale.US, "%.1f", halfLife)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ByteInkDark)
            .navigationBarsPadding()
    ) {
        ScreenHeader(
            title = "Caffeine",
            subtitle = "Half-life ${halfLifeFormatted}h · Bedtime $bedtime",
            onBack = onBack,
            fraction = scrollFraction,
            actions = {
                IconButton(onClick = onRefresh) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = accent
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh caffeine",
                            tint = ByteCreamMuted
                        )
                    }
                }
                IconButton(onClick = { showSettingsDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Caffeine settings",
                        tint = ByteCream
                    )
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (message != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(accent.copy(alpha = 0.16f))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = message,
                        color = ByteCream,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 1. Summary metrics strip
            SummaryMetricsRow(status = status, accent = accent, successColor = extras.success, errorColor = cs.error)

            // 2. 24-Hour Exponential Decay Curve
            DecayCurveCard(
                status = status,
                selectedDate = selectedDate,
                halfLifeHours = halfLife,
                targetBedtime = bedtime,
                accent = accent,
                errorColor = cs.error
            )

            // 3. Quick-log preset grid
            QuickLogPresetsCard(
                accent = accent,
                onSelectPreset = { preset ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onLogPreset(preset)
                }
            )

            // 4. Custom mg + time logger
            CustomCaffeineLoggerCard(
                accent = accent,
                onLog = { name, mg, time ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onLogCustom(name, mg, time)
                }
            )

            // 5. Today's doses list
            TodayDosesCard(
                doses = status?.doses.orEmpty(),
                selectedDate = selectedDate,
                accent = accent
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSettingsDialog) {
        CaffeineSettingsDialog(
            initialHalfLife = halfLife,
            initialBedtime = bedtime,
            onDismiss = { showSettingsDialog = false },
            onSave = { newHalfLife, newBedtime ->
                showSettingsDialog = false
                onUpdateSettings(newHalfLife, newBedtime)
            }
        )
    }
}

@Composable
private fun SummaryMetricsRow(
    status: CaffeineStatus?,
    accent: Color,
    successColor: Color,
    errorColor: Color
) {
    val activeNow = status?.activeMgNow?.roundToInt() ?: 0
    val totalToday = status?.totalTodayMg?.roundToInt() ?: 0
    val atBedtime = status?.atBedtimeMg?.roundToInt() ?: 0
    val threshold = (status?.thresholdMg ?: 100f).roundToInt()
    val bedtimeColor = if (atBedtime >= threshold) errorColor else if (atBedtime >= (threshold * 0.7f)) accent else successColor

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile(
                label = "ACTIVE NOW",
                value = "$activeNow mg",
                valueColor = accent,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "TODAY TOTAL",
                value = "$totalToday mg",
                valueColor = ByteCream,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "AT BEDTIME",
                value = "$atBedtime mg",
                valueColor = bedtimeColor,
                modifier = Modifier.weight(1f)
            )
        }

        val cutoffLabel = when {
            status == null -> "Log a drink to see your sleep cutoff"
            atBedtime >= threshold -> "Projected bedtime level ($atBedtime mg) exceeds $threshold mg sleep limit"
            !status.latestSafeDoseTime.isNullOrBlank() ->
                "Latest safe 200 mg dose: ${status.latestSafeDoseTime.take(5)} · Headroom ${status.bedtimeHeadroomMg.roundToInt()} mg"
            else -> "Past safe cutoff for a full dose before bedtime"
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ByteSurfaceDark)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Bedtime,
                contentDescription = null,
                tint = bedtimeColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = cutoffLabel,
                color = ByteCream,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(ByteSurfaceDark)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            color = ByteCreamMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            maxLines = 1
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DecayCurveCard(
    status: CaffeineStatus?,
    selectedDate: String,
    halfLifeHours: Float,
    targetBedtime: String,
    accent: Color,
    errorColor: Color
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val localDate = remember(selectedDate) {
        runCatching { LocalDate.parse(selectedDate.take(10)) }.getOrDefault(LocalDate.now())
    }
    val startMs = remember(localDate) {
        LocalDateTime.of(localDate, LocalTime.MIDNIGHT).atZone(zoneId).toInstant().toEpochMilli()
    }
    val endMs = remember(startMs) { startMs + 24L * 3_600_000L }
    val bedtimeMs = remember(localDate, targetBedtime, status?.bedtimeAt) {
        status?.bedtimeAt?.let { CaffeineMath.parseDoseEpochMs(it, localDate, zoneId) }
            ?: CaffeineMath.computeBedtimeEpochMs(localDate, targetBedtime, zoneId)
    }
    val nowMs = System.currentTimeMillis()
    val doses = status?.doses.orEmpty()
    val thresholdMg = status?.thresholdMg ?: 100f

    val curvePoints = remember(doses, startMs, endMs, halfLifeHours) {
        CaffeineMath.buildDecayCurve(
            doses = doses,
            startEpochMs = startMs,
            endEpochMs = endMs,
            halfLifeHours = halfLifeHours,
            steps = 48,
            fallbackDate = localDate,
            zoneId = zoneId
        )
    }

    val peakMg = remember(curvePoints, thresholdMg) {
        val maxVal = curvePoints.maxOfOrNull { it.second } ?: 0f
        max(maxVal * 1.15f, max(thresholdMg * 1.4f, 150f))
    }

    val surfaceVariant = ByteSurfaceVariantDark
    val mutedColor = ByteCreamMuted
    val creamColor = ByteCream

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ByteSurfaceDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "24-HOUR DECAY CURVE",
                color = ByteCreamMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Text(
                text = "Sleep limit ${thresholdMg.roundToInt()} mg",
                color = errorColor.copy(alpha = 0.9f),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val padTop = 10.dp.toPx()
                val padBottom = 8.dp.toPx()
                val chartW = size.width
                val chartH = (size.height - padTop - padBottom).coerceAtLeast(1f)

                fun xForMs(ms: Long): Float {
                    val frac = ((ms - startMs).toDouble() / (endMs - startMs).toDouble()).toFloat().coerceIn(0f, 1f)
                    return frac * chartW
                }

                fun yForMg(mg: Float): Float {
                    val frac = (mg / peakMg).coerceIn(0f, 1f)
                    return padTop + chartH * (1f - frac)
                }

                // Subtle horizontal baseline
                drawLine(
                    color = surfaceVariant,
                    start = Offset(0f, padTop + chartH),
                    end = Offset(chartW, padTop + chartH),
                    strokeWidth = 1.dp.toPx()
                )

                // Horizontal 100mg sleep threshold dashed line
                val thresholdY = yForMg(thresholdMg)
                drawLine(
                    color = errorColor.copy(alpha = 0.65f),
                    start = Offset(0f, thresholdY),
                    end = Offset(chartW, thresholdY),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                )

                // Vertical bedtime marker line
                if (bedtimeMs in startMs..endMs) {
                    val bedX = xForMs(bedtimeMs)
                    drawLine(
                        color = mutedColor.copy(alpha = 0.55f),
                        start = Offset(bedX, padTop),
                        end = Offset(bedX, padTop + chartH),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    )
                }

                // Exponential decay curve + gradient fill
                if (curvePoints.size >= 2) {
                    val strokePath = Path()
                    val fillPath = Path()
                    curvePoints.forEachIndexed { idx, (ms, mg) ->
                        val x = xForMs(ms)
                        val y = yForMg(mg)
                        if (idx == 0) {
                            strokePath.moveTo(x, y)
                            fillPath.moveTo(x, padTop + chartH)
                            fillPath.lineTo(x, y)
                        } else {
                            strokePath.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                    }
                    val lastX = xForMs(curvePoints.last().first)
                    fillPath.lineTo(lastX, padTop + chartH)
                    fillPath.close()

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(accent.copy(alpha = 0.32f), accent.copy(alpha = 0.02f)),
                            startY = padTop,
                            endY = padTop + chartH
                        )
                    )
                    drawPath(
                        path = strokePath,
                        color = accent,
                        style = Stroke(
                            width = 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // Bedtime projection dot
                if (bedtimeMs in startMs..endMs) {
                    val bedX = xForMs(bedtimeMs)
                    val bedMg = status?.atBedtimeMg ?: CaffeineMath.activeAt(doses, bedtimeMs, halfLifeHours, localDate, zoneId)
                    val bedY = yForMg(bedMg)
                    drawCircle(
                        color = if (bedMg >= thresholdMg) errorColor else creamColor,
                        radius = 4.5.dp.toPx(),
                        center = Offset(bedX, bedY)
                    )
                }

                // Current time marker ("Now")
                if (nowMs in startMs..endMs) {
                    val nowX = xForMs(nowMs)
                    val nowMg = status?.activeMgNow ?: CaffeineMath.activeAt(doses, nowMs, halfLifeHours, localDate, zoneId)
                    val nowY = yForMg(nowMg)
                    drawLine(
                        color = creamColor.copy(alpha = 0.35f),
                        start = Offset(nowX, padTop),
                        end = Offset(nowX, padTop + chartH),
                        strokeWidth = 1.2.dp.toPx()
                    )
                    drawCircle(
                        color = accent.copy(alpha = 0.28f),
                        radius = 10.dp.toPx(),
                        center = Offset(nowX, nowY)
                    )
                    drawCircle(
                        color = accent,
                        radius = 5.dp.toPx(),
                        center = Offset(nowX, nowY)
                    )
                }
            }
        }

        // X-axis time labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("12a", "6a", "12p", "6p", "12a").forEach { tick ->
                Text(
                    text = tick,
                    color = ByteCreamMuted,
                    fontSize = 11.sp
                )
            }
        }

        // Legend row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendDot(color = accent, label = "Now")
            LegendDot(color = ByteCreamMuted, label = "Bedtime ($targetBedtime)")
            LegendDot(color = errorColor, label = "${thresholdMg.roundToInt()} mg limit")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            color = ByteCreamMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun QuickLogPresetsCard(
    accent: Color,
    onSelectPreset: (CaffeinePreset) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ByteSurfaceDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "QUICK LOG",
            color = ByteCreamMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )

        val rows = remember { DefaultCaffeinePresets.chunked(2) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pair.forEach { preset ->
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(ByteSurfaceVariantDark)
                                .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
                                .clickable { onSelectPreset(preset) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(text = preset.emoji, fontSize = 18.sp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.name,
                                    color = ByteCream,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${preset.mg.roundToInt()} mg",
                                    color = accent,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    if (pair.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomCaffeineLoggerCard(
    accent: Color,
    onLog: (name: String, mg: Float, timeHHmm: String) -> Unit
) {
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var name by remember { mutableStateOf("Coffee") }
    var mgText by remember { mutableStateOf("100") }
    var timeText by remember { mutableStateOf(LocalTime.now().format(formatter)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ByteSurfaceDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "CUSTOM DOSE",
            color = ByteCreamMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Drink / source") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = mgText,
                onValueChange = { mgText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("Caffeine (mg)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = timeText,
                onValueChange = { timeText = it.take(5) },
                label = { Text("Time (HH:mm)") },
                singleLine = true,
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Set to now",
                        tint = ByteCreamMuted,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { timeText = LocalTime.now().format(formatter) }
                    )
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            )
        }

        Button(
            onClick = {
                val parsedMg = mgText.toFloatOrNull() ?: return@Button
                onLog(name.trim().ifBlank { "Custom Caffeine" }, parsedMg, timeText.trim())
            },
            enabled = (mgText.toFloatOrNull() ?: 0f) > 0f,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ByteTomato,
                contentColor = ByteOnTomato
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Log Custom Dose",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun TodayDosesCard(
    doses: List<CaffeineDose>,
    selectedDate: String,
    accent: Color
) {
    val localDate = remember(selectedDate) {
        runCatching { LocalDate.parse(selectedDate.take(10)) }.getOrDefault(LocalDate.now())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ByteSurfaceDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TODAY'S DOSES",
                color = ByteCreamMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Text(
                text = "${doses.size} logged",
                color = ByteCreamMuted,
                fontSize = 12.sp
            )
        }

        if (doses.isEmpty()) {
            Text(
                text = "No caffeine logged yet for $selectedDate.",
                color = ByteCreamMuted,
                fontSize = 13.sp
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                doses.forEach { dose ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(ByteSurfaceVariantDark)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalCafe,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(16.dp)
                            )
                            Column {
                                Text(
                                    text = dose.name,
                                    color = ByteCream,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = CaffeineMath.formatDoseTime(dose.at, localDate),
                                        color = ByteCreamMuted,
                                        fontSize = 12.sp
                                    )
                                    if (dose.isEstimated) {
                                        Text(
                                            text = "Est.",
                                            color = accent,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = "${dose.mg.roundToInt()} mg",
                            color = accent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CaffeineSettingsDialog(
    initialHalfLife: Float,
    initialBedtime: String,
    onDismiss: () -> Unit,
    onSave: (halfLifeHours: Float, targetBedtime: String) -> Unit
) {
    val accent = MacroCarbs
    var halfLife by remember { mutableFloatStateOf(initialHalfLife.coerceIn(2f, 8f)) }
    var bedtime by remember { mutableStateOf(initialBedtime.take(5).ifBlank { "22:30" }) }
    val bedtimePresets = remember { listOf("21:30", "22:00", "22:30", "23:00", "23:30") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ByteSurfaceDark,
        title = {
            Text(
                text = "Caffeine Settings",
                color = ByteCream,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Caffeine half-life",
                            color = ByteCream,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${String.format(Locale.US, "%.1f", halfLife)} hours",
                            color = accent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = halfLife,
                        onValueChange = { raw ->
                            // Snap to 0.5h increments between 2.0h and 8.0h
                            halfLife = ((raw * 2f).roundToInt() / 2f).coerceIn(2f, 8f)
                        },
                        valueRange = 2f..8f,
                        steps = 11,
                        colors = SliderDefaults.colors(
                            thumbColor = accent,
                            activeTrackColor = accent
                        )
                    )
                    Text(
                        text = "Typical adult half-life is ~5.0h (fast: 3h, slow: 7h).",
                        color = ByteCreamMuted,
                        fontSize = 11.5.sp
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = bedtime,
                        onValueChange = { bedtime = it.take(5) },
                        label = { Text("Target bedtime (HH:mm)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        bedtimePresets.forEach { preset ->
                            val selected = bedtime == preset
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) accent.copy(alpha = 0.22f) else ByteSurfaceVariantDark)
                                    .clickable { bedtime = preset }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = preset,
                                    color = if (selected) accent else ByteCreamMuted,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(halfLife, bedtime.trim().ifBlank { "22:30" }) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = ByteTomato,
                    contentColor = ByteOnTomato
                )
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = ByteCreamMuted)
            }
        }
    )
}
