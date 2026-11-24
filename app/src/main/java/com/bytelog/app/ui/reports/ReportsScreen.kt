package com.bytelog.app.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.reports.ActivitySleepReportData
import com.bytelog.app.data.reports.CaffeineReportData
import com.bytelog.app.data.reports.CompareMetric
import com.bytelog.app.data.reports.CompareResult
import com.bytelog.app.data.reports.FastingReportData
import com.bytelog.app.data.reports.HydrationReportData
import com.bytelog.app.data.reports.MicronutrientCatalog
import com.bytelog.app.data.reports.MicronutrientTrend
import com.bytelog.app.data.reports.NutritionReportData
import com.bytelog.app.data.reports.ReportRange
import com.bytelog.app.data.reports.WorkoutsReportData
import com.bytelog.app.data.reports.ReportSectionId
import com.bytelog.app.data.reports.WeightTdeeReportData
import com.bytelog.app.ui.components.ScreenHeader
import com.bytelog.app.ui.components.rememberScrollFraction
import com.bytelog.app.ui.theme.ByteCardDark
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.FluidRadius
import com.bytelog.app.ui.theme.LocalByteExtraColors
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Detailed Reports & Analytics screen with customizable section ordering, date range chips,
 * Compose Canvas charts, and offline-first Room + Health Connect fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    viewModel: ReportsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val scrollFraction = rememberScrollFraction(listState)
    var showCustomizeSheet by remember { mutableStateOf(false) }

    val subtitle = state.snapshot?.let { snap ->
        val mode = if (snap.isOfflineFallback) " · Local cache" else ""
        "${snap.startDate} to ${snap.endDate}$mode"
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ByteInkDark
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            ScreenHeader(
                title = "Reports",
                subtitle = subtitle,
                onBack = onBack,
                fraction = scrollFraction,
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh reports", tint = ByteCream)
                    }
                    IconButton(onClick = { showCustomizeSheet = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "Customize reports", tint = ByteCream)
                    }
                }
            )

            // Range Selector Bar (7D / 14D / 30D / 90D)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(FluidRadius.md))
                    .background(ByteSurfaceVariantDark)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                ReportRange.entries.forEach { range ->
                    val selected = state.range == range
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(FluidRadius.sm))
                            .background(if (selected) ByteTomato else Color.Transparent)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.selectRange(range)
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = range.label,
                            color = if (selected) ByteOnTomato else ByteTextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            if (state.isLoading && state.snapshot == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ByteTomato)
                }
            } else if (state.error != null && state.snapshot == null) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(state.error ?: "Unable to load reports", color = ByteTextSecondary, fontSize = 14.sp)
                }
            } else {
                val snap = state.snapshot
                val visibleSections = state.customization.visible

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (snap != null) {
                        items(visibleSections, key = { it.name }) { section ->
                            when (section) {
                                ReportSectionId.NUTRITION_MACROS -> NutritionMacrosCard(snap.nutrition)
                                ReportSectionId.MICRONUTRIENTS -> MicronutrientsCard(
                                    micronutrients = snap.micronutrients,
                                    pinnedKeys = state.customization.pinnedMicronutrients
                                )
                                ReportSectionId.HYDRATION -> HydrationReportCard(snap.hydration)
                                ReportSectionId.WEIGHT_TDEE -> WeightTdeeReportCard(snap.weightTdee)
                                ReportSectionId.CAFFEINE -> CaffeineReportCard(snap.caffeine)
                                ReportSectionId.FASTING -> FastingReportCard(snap.fasting)
                                ReportSectionId.WORKOUTS -> WorkoutsReportCard(snap.workouts, snap.weightTdee.weightUnit)
                                ReportSectionId.ACTIVITY_SLEEP -> ActivitySleepReportCard(snap.activitySleep)
                                ReportSectionId.COMPARE -> MetricCompareCard(
                                    metricA = state.compareMetricA,
                                    metricB = state.compareMetricB,
                                    result = state.compareResult,
                                    isLoading = state.isCompareLoading,
                                    onSelectMetrics = { a, b -> viewModel.selectCompareMetrics(a, b) }
                                )
                            }
                        }
                    }

                    item { Spacer(Modifier.navigationBarsPadding()) }
                }
            }
        }
    }

    if (showCustomizeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showCustomizeSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            CustomizeReportsSheet(
                saved = state.customization,
                onChange = { viewModel.updateCustomization(it) }
            )
        }
    }
}

// ---------- Shared Card Shell & Primitives ----------

@Composable
private fun ReportCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluidRadius.lg))
            .background(ByteCardDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = ByteCream, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = ByteTextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        content()
    }
}

@Composable
private fun StatChip(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(FluidRadius.sm))
            .background(ByteSurfaceVariantDark.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, color = ByteTextSecondary, fontSize = 10.sp, maxLines = 1)
        Text(value, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun BarChartCanvas(
    values: List<Double>,
    barColor: Color,
    goalValue: Double? = null,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(120.dp)
) {
    val cream = ByteCream
    val maxVal = maxOf(
        values.maxOrNull() ?: 0.0,
        goalValue ?: 0.0,
        1.0
    )
    Canvas(modifier = modifier) {
        val count = values.size.coerceAtLeast(1)
        val slotW = size.width / count
        val barW = (slotW * 0.58f).coerceIn(3f, 22f)
        val usableH = size.height * 0.88f

        if (goalValue != null && goalValue > 0.0) {
            val goalY = size.height - ((goalValue / maxVal).toFloat() * usableH)
            drawLine(
                color = cream.copy(alpha = 0.25f),
                start = Offset(0f, goalY),
                end = Offset(size.width, goalY),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
            )
        }

        values.forEachIndexed { idx, v ->
            val cx = slotW * idx + slotW / 2f
            val h = if (v > 0.0) ((v / maxVal).toFloat() * usableH).coerceAtLeast(4f) else 2f
            val color = if (v > 0.0) barColor else barColor.copy(alpha = 0.14f)
            drawRoundRect(
                color = color,
                topLeft = Offset(cx - barW / 2f, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

// ---------- 1. Nutrition & Macros ----------

@Composable
private fun NutritionMacrosCard(data: NutritionReportData) {
    val extra = LocalByteExtraColors.current
    ReportCard(
        title = "Nutrition & Macros",
        subtitle = "${data.daysLogged} logged days · Goal ${data.targetCalories.roundToInt()} kcal",
        icon = Icons.Default.LocalFireDepartment,
        accent = ByteTomato
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("Avg kcal", "${data.avgCalories.roundToInt()}", ByteTomato, Modifier.weight(1f))
            StatChip("Adherence", "${data.goalAdherencePct.roundToInt()}%", extra.success, Modifier.weight(1f))
            StatChip("Protein", "${data.avgProtein.roundToInt()}g", MacroProtein, Modifier.weight(1f))
        }

        BarChartCanvas(
            values = data.days.map { it.calories },
            barColor = ByteTomato,
            goalValue = data.targetCalories
        )

        // Average P / C / F split bar
        val pCal = data.avgProtein * 4.0
        val cCal = data.avgCarbs * 4.0
        val fCal = data.avgFat * 9.0
        val totalMacroCal = (pCal + cCal + fCal).coerceAtLeast(1.0)
        val pFrac = (pCal / totalMacroCal).toFloat()
        val cFrac = (cCal / totalMacroCal).toFloat()
        val fFrac = (fCal / totalMacroCal).toFloat()

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(ByteSurfaceVariantDark)
            ) {
                if (pFrac > 0f) Box(Modifier.weight(pFrac.coerceAtLeast(0.01f)).fillMaxSize().background(MacroProtein))
                if (cFrac > 0f) Box(Modifier.weight(cFrac.coerceAtLeast(0.01f)).fillMaxSize().background(MacroCarbs))
                if (fFrac > 0f) Box(Modifier.weight(fFrac.coerceAtLeast(0.01f)).fillMaxSize().background(MacroFat))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("P ${(pFrac * 100).roundToInt()}% (${data.avgProtein.roundToInt()}g)", color = MacroProtein, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text("C ${(cFrac * 100).roundToInt()}% (${data.avgCarbs.roundToInt()}g)", color = MacroCarbs, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text("F ${(fFrac * 100).roundToInt()}% (${data.avgFat.roundToInt()}g)", color = MacroFat, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ---------- 2. Micronutrients ----------

@Composable
private fun MicronutrientsCard(
    micronutrients: Map<String, MicronutrientTrend>,
    pinnedKeys: Set<String>
) {
    val pinned = MicronutrientCatalog.ALL.filter { it.key in pinnedKeys }
        .mapNotNull { micronutrients[it.key] }

    ReportCard(
        title = "Micronutrients",
        subtitle = "${pinned.size} pinned daily averages",
        icon = Icons.Default.Science,
        accent = MacroProtein
    ) {
        if (pinned.all { it.dailyAvg <= 0.0 }) {
            Text(
                "Connect to SparkyFitness to view daily micronutrient trends.",
                color = ByteTextSecondary,
                fontSize = 12.sp
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pinned.forEach { item ->
                    val pct = if (item.def.dailyTarget > 0) ((item.dailyAvg / item.def.dailyTarget) * 100).roundToInt() else 0
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(Modifier.width(96.dp)) {
                            Text(item.def.label, color = ByteCream, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(
                                "${formatCompact(item.dailyAvg)} ${item.def.unit} ($pct%)",
                                color = ByteTextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                        BarChartCanvas(
                            values = item.dailyValues.map { it.second },
                            barColor = MacroProtein,
                            goalValue = item.def.dailyTarget,
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                        )
                    }
                }
            }
        }
    }
}

// ---------- 3. Hydration ----------

@Composable
private fun HydrationReportCard(data: HydrationReportData) {
    val extra = LocalByteExtraColors.current
    val avgOz = (data.avgMl / 29.5735).roundToInt()
    val goalOz = (data.goalMl / 29.5735).roundToInt()

    ReportCard(
        title = "Hydration",
        subtitle = "Daily goal ${goalOz} oz (${data.goalMl.roundToInt()} ml)",
        icon = Icons.Default.WaterDrop,
        accent = extra.water
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("Daily Avg", "${avgOz} oz", extra.water, Modifier.weight(1f))
            StatChip("Goal Streak", "${data.currentStreakDays}d", extra.success, Modifier.weight(1f))
            StatChip("Days Met", "${data.daysMetGoal}", ByteCream, Modifier.weight(1f))
        }

        BarChartCanvas(
            values = data.dailyWaterMl.map { it.second },
            barColor = extra.water,
            goalValue = data.goalMl
        )
    }
}

// ---------- 4. Weight & TDEE ----------

@Composable
private fun WeightTdeeReportCard(data: WeightTdeeReportData) {
    val extra = LocalByteExtraColors.current
    val tdeeText = data.adaptiveTdee?.takeIf { !it.isFallback }?.let { "${it.tdee.roundToInt()} kcal" } ?: "Calibrating"
    val deltaText = data.deltaWeight?.let {
        val sign = if (it >= 0f) "+" else ""
        String.format(Locale.US, "%s%.1f %s", sign, it, data.weightUnit)
    } ?: "—"
    val rateText = data.weeklyRate?.let {
        val sign = if (it >= 0f) "+" else ""
        String.format(Locale.US, "%s%.2f/wk", sign, it)
    } ?: "—"

    ReportCard(
        title = "Weight & TDEE",
        subtitle = data.latestBodyFatPct?.let {
            String.format(Locale.US, "Body fat %.1f%% · %s", it, data.adaptiveTdee?.confidence ?: "")
        } ?: (data.adaptiveTdee?.explanation ?: "Weight trend & expenditure"),
        icon = Icons.AutoMirrored.Filled.ShowChart,
        accent = MacroCarbs
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("Delta", deltaText, MacroCarbs, Modifier.weight(1f))
            StatChip("Rate", rateText, ByteCream, Modifier.weight(1f))
            StatChip("TDEE", tdeeText, extra.success, Modifier.weight(1f))
        }

        val validWeights = data.points.mapNotNull { it.smoothedWeight ?: it.actualWeight }
        if (validWeights.size >= 2) {
            val minW = validWeights.min() - 1f
            val maxW = validWeights.max() + 1f
            val span = (maxW - minW).coerceAtLeast(2f)
            val lineColor = MacroCarbs
            val cardBg = ByteCardDark

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                val count = data.points.size.coerceAtLeast(1)
                val slotW = size.width / count
                val pts = mutableListOf<Offset>()
                data.points.forEachIndexed { idx, p ->
                    val w = p.smoothedWeight ?: p.actualWeight
                    if (w != null) {
                        val x = slotW * idx + slotW / 2f
                        val norm = ((w - minW) / span).coerceIn(0f, 1f)
                        val y = size.height * 0.12f + (1f - norm) * (size.height * 0.76f)
                        pts += Offset(x, y)
                    }
                }
                if (pts.size >= 2) {
                    val path = Path().apply {
                        moveTo(pts.first().x, pts.first().y)
                        for (i in 1 until pts.size) {
                            val p0 = pts[i - 1]
                            val p1 = pts[i]
                            val midX = (p0.x + p1.x) / 2f
                            cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                        }
                    }
                    drawPath(path, color = lineColor, style = Stroke(width = 4f, cap = StrokeCap.Round))
                }
                data.points.forEachIndexed { idx, p ->
                    p.actualWeight?.let { w ->
                        val x = slotW * idx + slotW / 2f
                        val norm = ((w - minW) / span).coerceIn(0f, 1f)
                        val y = size.height * 0.12f + (1f - norm) * (size.height * 0.76f)
                        drawCircle(color = cardBg, radius = 6f, center = Offset(x, y))
                        drawCircle(color = lineColor, radius = 4f, center = Offset(x, y))
                    }
                }
            }
        } else {
            Text("Log at least 2 weigh-ins to view your weight trend.", color = ByteTextSecondary, fontSize = 12.sp)
        }
    }
}

// ---------- 5. Caffeine ----------

@Composable
private fun CaffeineReportCard(data: CaffeineReportData) {
    ReportCard(
        title = "Caffeine",
        subtitle = data.peakDate?.let { "Peak on $it" } ?: "Daily caffeine intake",
        icon = Icons.Default.Coffee,
        accent = MacroCarbs
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("Daily Avg", "${data.avgMg.roundToInt()} mg", MacroCarbs, Modifier.weight(1f))
            StatChip("Peak Day", "${data.peakMg.roundToInt()} mg", ByteTomato, Modifier.weight(1f))
        }
        if (data.peakMg > 0.0) {
            BarChartCanvas(
                values = data.dailyCaffeineMg.map { it.second },
                barColor = MacroCarbs,
                goalValue = 400.0
            )
        } else {
            Text("No caffeine logged in this range.", color = ByteTextSecondary, fontSize = 12.sp)
        }
    }
}

// ---------- 6. Fasting ----------

@Composable
private fun FastingReportCard(data: FastingReportData) {
    val extra = LocalByteExtraColors.current
    ReportCard(
        title = "Fasting",
        subtitle = "Completed fasts & daily fasting hours",
        icon = Icons.Default.Timer,
        accent = extra.success
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("Completed", "${data.completedCount}", extra.success, Modifier.weight(1f))
            StatChip(
                "Avg Duration",
                if (data.avgDurationHours > 0) String.format(Locale.US, "%.1f hrs", data.avgDurationHours) else "—",
                ByteCream,
                Modifier.weight(1f)
            )
        }
        if (data.dailyHours.any { it.second > 0.0 }) {
            BarChartCanvas(
                values = data.dailyHours.map { it.second },
                barColor = extra.success,
                goalValue = 16.0
            )
        } else {
            Text("No fasting sessions recorded in this range.", color = ByteTextSecondary, fontSize = 12.sp)
        }
    }
}

// ---------- 6b. Lifting & Workouts ----------

@Composable
private fun WorkoutsReportCard(data: WorkoutsReportData, weightUnit: String) {
    val numFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val unitLabel = if (weightUnit.equals("kg", ignoreCase = true)) "kg" else "lbs"
    val volumeLabel = when {
        data.totalVolume >= 1_000_000.0 -> String.format(Locale.US, "%.2fM %s", data.totalVolume / 1_000_000.0, unitLabel)
        data.totalVolume >= 1_000.0 -> String.format(Locale.US, "%.1fk %s", data.totalVolume / 1_000.0, unitLabel)
        data.totalVolume > 0.0 -> "${data.totalVolume.roundToInt()} $unitLabel"
        else -> "—"
    }

    ReportCard(
        title = "Lifting & Workouts",
        subtitle = if (data.totalWorkouts > 0) "${data.totalWorkouts} workout days logged" else "Volume, muscle sets & PRs",
        icon = androidx.compose.material.icons.Icons.Default.FitnessCenter,
        accent = ByteTomato
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("Workouts", "${data.totalWorkouts}", ByteTomato, Modifier.weight(1f))
            StatChip("Volume", volumeLabel, MacroProtein, Modifier.weight(1f))
            StatChip("Sets / Reps", "${data.totalSets} / ${numFmt.format(data.totalReps)}", ByteCream, Modifier.weight(1f))
        }

        if (data.totalDurationMinutes > 0 || data.totalCaloriesBurned > 0.0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip(
                    "Duration",
                    if (data.totalDurationMinutes >= 60) "${data.totalDurationMinutes / 60}h ${data.totalDurationMinutes % 60}m"
                    else "${data.totalDurationMinutes}m",
                    MacroCarbs,
                    Modifier.weight(1f)
                )
                StatChip(
                    "Burned",
                    "${numFmt.format(data.totalCaloriesBurned.roundToInt())} kcal",
                    MacroFat,
                    Modifier.weight(1f)
                )
            }
        }

        val hasDailyVolume = data.dailyVolume.any { it.second > 0.0 }
        val hasDailySessions = data.dailySessions.any { it.second > 0 }
        if (hasDailyVolume) {
            Text("Daily lifting volume ($unitLabel)", color = ByteTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            BarChartCanvas(
                values = data.dailyVolume.map { it.second },
                barColor = ByteTomato,
                goalValue = null
            )
        } else if (hasDailySessions) {
            Text("Daily workout sessions", color = ByteTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            BarChartCanvas(
                values = data.dailySessions.map { it.second.toDouble() },
                barColor = ByteTomato,
                goalValue = null
            )
        } else {
            Text("No workouts logged in this range.", color = ByteTextSecondary, fontSize = 12.sp)
        }

        if (data.muscleGroups.isNotEmpty()) {
            val topMuscles = data.muscleGroups.take(6)
            val maxSets = topMuscles.maxOf { it.sets }.coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sets by muscle group", color = ByteTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                topMuscles.forEach { mg ->
                    val frac = (mg.sets.toFloat() / maxSets.toFloat()).coerceIn(0.05f, 1f)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = mg.muscle.replaceFirstChar { it.uppercase() },
                                color = ByteCream,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${mg.sets} sets",
                                color = ByteTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(ByteSurfaceVariantDark)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(frac)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MacroProtein)
                            )
                        }
                    }
                }
            }
        }

        if (data.topPrs.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Top personal records", color = ByteTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                data.topPrs.take(5).forEach { pr ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ByteSurfaceVariantDark)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = pr.exerciseName,
                            color = ByteCream,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${pr.maxWeight.roundToInt()} $unitLabel × ${pr.maxReps}  (1RM ~${pr.estimated1Rm.roundToInt()})",
                            color = ByteTomato,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ---------- 7. Activity & Sleep (Health Connect) ----------

@Composable
private fun ActivitySleepReportCard(data: ActivitySleepReportData) {
    val extra = LocalByteExtraColors.current
    val numFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val hasAny = data.dailySnapshots.any { it.steps > 0 || it.sleepMinutes > 0 || it.activeCaloriesKcal > 0 }

    ReportCard(
        title = "Activity & Sleep",
        subtitle = "Health Connect steps, active burn, sleep & resting HR",
        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
        accent = extra.success
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("Avg Steps", if (data.avgSteps > 0) numFmt.format(data.avgSteps) else "—", extra.success, Modifier.weight(1f))
            StatChip("Active kcal", if (data.avgActiveCalories > 0) "${data.avgActiveCalories.roundToInt()}" else "—", ByteTomato, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip(
                "Avg Sleep",
                if (data.avgSleepMinutes > 0) "${data.avgSleepMinutes / 60}h ${data.avgSleepMinutes % 60}m" else "—",
                MacroProtein,
                Modifier.weight(1f)
            )
            StatChip(
                "Resting HR",
                data.avgRestingHr?.let { "$it bpm" } ?: "—",
                MacroFat,
                Modifier.weight(1f)
            )
        }

        if (hasAny) {
            Text("Daily Steps", color = ByteTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            BarChartCanvas(
                values = data.dailySnapshots.map { it.steps.toDouble() },
                barColor = extra.success,
                goalValue = 8000.0,
                modifier = Modifier.fillMaxWidth().height(90.dp)
            )
            if (data.dailySnapshots.any { it.sleepMinutes > 0 }) {
                Text("Sleep Duration (hrs)", color = ByteTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                BarChartCanvas(
                    values = data.dailySnapshots.map { it.sleepMinutes / 60.0 },
                    barColor = MacroProtein,
                    goalValue = 8.0,
                    modifier = Modifier.fillMaxWidth().height(80.dp)
                )
            }
        } else {
            Text(
                "Sync Health Connect in Settings to view steps, sleep, and resting heart rate.",
                color = ByteTextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

// ---------- 8. Metric Compare ----------

@Composable
private fun MetricCompareCard(
    metricA: CompareMetric,
    metricB: CompareMetric,
    result: CompareResult?,
    isLoading: Boolean,
    onSelectMetrics: (CompareMetric, CompareMetric) -> Unit
) {
    val colorA = ByteTomato
    val colorB = MacroProtein

    ReportCard(
        title = "Metric Compare",
        subtitle = "Overlay normalized trends & Pearson correlation",
        icon = Icons.Default.CompareArrows,
        accent = ByteTomato
    ) {
        // Metric A selector strip
        MetricChipStrip(
            prefix = "A:",
            selected = metricA,
            accent = colorA,
            onSelect = { onSelectMetrics(it, metricB) }
        )
        // Metric B selector strip
        MetricChipStrip(
            prefix = "B:",
            selected = metricB,
            accent = colorB,
            onSelect = { onSelectMetrics(metricA, it) }
        )

        if (isLoading && result == null) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = ByteTomato)
            }
        } else if (result != null && result.points.size >= 2) {
            val valsA = result.points.mapNotNull { it.valueA }
            val valsB = result.points.mapNotNull { it.valueB }

            if (valsA.size >= 2 || valsB.size >= 2) {
                val minA = valsA.minOrNull() ?: 0.0
                val spanA = ((valsA.maxOrNull() ?: 1.0) - minA).coerceAtLeast(1e-6)
                val minB = valsB.minOrNull() ?: 0.0
                val spanB = ((valsB.maxOrNull() ?: 1.0) - minB).coerceAtLeast(1e-6)

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                ) {
                    val count = result.points.size.coerceAtLeast(1)
                    val slotW = size.width / count

                    fun drawSeries(extractor: (Int) -> Double?, minV: Double, spanV: Double, color: Color) {
                        val pts = mutableListOf<Offset>()
                        for (i in 0 until count) {
                            val v = extractor(i) ?: continue
                            val x = slotW * i + slotW / 2f
                            val norm = ((v - minV) / spanV).toFloat().coerceIn(0f, 1f)
                            val y = size.height * 0.1f + (1f - norm) * (size.height * 0.8f)
                            pts += Offset(x, y)
                        }
                        if (pts.size >= 2) {
                            val path = Path().apply {
                                moveTo(pts.first().x, pts.first().y)
                                for (j in 1 until pts.size) {
                                    val p0 = pts[j - 1]
                                    val p1 = pts[j]
                                    val midX = (p0.x + p1.x) / 2f
                                    cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                                }
                            }
                            drawPath(path, color = color, style = Stroke(width = 4f, cap = StrokeCap.Round))
                        }
                    }

                    drawSeries({ result.points[it].valueA }, minA, spanA, colorA)
                    drawSeries({ result.points[it].valueB }, minB, spanB, colorB)
                }
            }

            // Legend + Correlation Summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colorA))
                    Spacer(Modifier.width(5.dp))
                    Text(metricA.label, color = ByteCream, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colorB))
                    Spacer(Modifier.width(5.dp))
                    Text(metricB.label, color = ByteCream, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }

            Text(result.summary, color = ByteTextSecondary, fontSize = 12.sp)
        } else {
            Text("Not enough data to compare ${metricA.label} and ${metricB.label}.", color = ByteTextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MetricChipStrip(
    prefix: String,
    selected: CompareMetric,
    accent: Color,
    onSelect: (CompareMetric) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(prefix, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        CompareMetric.entries.forEach { m ->
            val isSel = m == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSel) accent.copy(alpha = 0.22f) else ByteSurfaceVariantDark)
                    .clickable { onSelect(m) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = m.label,
                    color = if (isSel) accent else ByteTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

private fun formatCompact(v: Double): String =
    if (v >= 100.0) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)
