package com.bytelog.app.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.reports.BestSet
import com.bytelog.app.data.reports.ExerciseProgressReport
import com.bytelog.app.data.reports.LoggedExercise
import com.bytelog.app.data.reports.ProgressGrouping
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.FluidRadius
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val LBS_PER_KG = 2.20462

/**
 * Exercise Progress, like SparkyFitness's web reports: pick an exercise you've logged and see its estimated 1RM,
 * heaviest weight and volume over time, average weight for each rep count, time under tension and PRs.
 */
@Composable
internal fun ExerciseProgressCard(
    exercises: List<LoggedExercise>,
    selected: LoggedExercise?,
    grouping: ProgressGrouping,
    report: ExerciseProgressReport?,
    loading: Boolean,
    error: String?,
    weightUnit: String,
    onSelect: (LoggedExercise) -> Unit,
    onGrouping: (ProgressGrouping) -> Unit
) {
    val lbs = !weightUnit.equals("kg", ignoreCase = true)
    val unit = if (lbs) "lbs" else "kg"
    fun w(kg: Double) = if (lbs) kg * LBS_PER_KG else kg
    fun weight(kg: Double) = "${fmt(w(kg))} $unit"

    ReportCard(
        title = "Exercise Progress",
        subtitle = selected?.let { s -> report?.takeIf { it.exercise.id == s.id }?.let { "${it.sessions} sessions of ${s.name}" } ?: s.name }
            ?: "Strength over time for one exercise",
        icon = Icons.AutoMirrored.Filled.TrendingUp,
        accent = MacroProtein
    ) {
        if (exercises.isEmpty()) {
            Text(error ?: if (loading) "Loading your exercises…" else "No exercises logged on Sparky yet.", color = ByteTextSecondary, fontSize = 12.sp)
            return@ReportCard
        }
        ExercisePicker(exercises, selected, onSelect)
        Segmented(ProgressGrouping.entries.map { it.label }, ProgressGrouping.entries.indexOf(grouping)) { onGrouping(ProgressGrouping.entries[it]) }

        when {
            loading && report == null -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MacroProtein, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
            }
            error != null && report == null -> Text(error, color = ByteTextSecondary, fontSize = 12.sp)
            report == null || report.sessions == 0 -> Text("No ${selected?.name ?: "sessions"} logged in this range.", color = ByteTextSecondary, fontSize = 12.sp)
            report.totalSets == 0 -> Text(
                "${report.sessions} sessions logged, but none with working sets (reps and weight), so there's no strength trend to show.",
                color = ByteTextSecondary, fontSize = 12.sp
            )
            else -> ProgressBody(report, ::w, ::weight, unit)
        }
    }
}

@Composable
private fun ProgressBody(r: ExerciseProgressReport, w: (Double) -> Double, weight: (Double) -> String, unit: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatChip("Best est. 1RM", r.bestSet?.let { weight(it.estimated1RmKg) } ?: "—", MacroProtein, Modifier.weight(1f))
        StatChip("Heaviest", r.heaviest?.let { "${weight(it.weightKg)} × ${it.reps}" } ?: "—", ByteTomato, Modifier.weight(1f))
        StatChip(
            "1RM change", r.estimated1RmChange?.let { (if (it >= 0) "+" else "") + "${(it * 100).roundToInt()}%" } ?: "—",
            if ((r.estimated1RmChange ?: 0.0) >= 0) MacroCarbs else MacroFat, Modifier.weight(1f)
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatChip("Volume", compact(w(r.totalVolumeKg), unit), ByteCream, Modifier.weight(1f))
        StatChip("Sets / reps", "${r.totalSets} / ${r.totalReps}", ByteCream, Modifier.weight(1f))
        StatChip("Most reps", r.mostReps?.let { "${it.reps} @ ${fmt(w(it.weightKg))}" } ?: "—", ByteCream, Modifier.weight(1f))
    }

    val pts = r.points
    val labels = pts.map { it.start.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)) }
    Trend("Estimated 1RM ($unit)", pts.map { w(it.estimated1RmKg) }, labels, MacroProtein)
    Trend("Heaviest weight ($unit)", pts.map { w(it.maxWeightKg) }, labels, ByteTomato)
    ChartLabel("Volume ($unit)")
    BarChartCanvas(values = pts.map { w(it.volumeKg) }, barColor = MacroCarbs, goalValue = null)
    AxisLabels(labels)

    if (r.repsVsWeight.size >= 2) {
        ChartLabel("Average weight by reps ($unit)")
        val maxW = r.repsVsWeight.maxOf { w(it.second) }.coerceAtLeast(1.0)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            r.repsVsWeight.forEach { (reps, kg) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$reps reps", color = ByteTextSecondary, fontSize = 11.sp, modifier = Modifier.width(56.dp))
                    Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(50)).background(ByteSurfaceVariantDark)) {
                        Box(Modifier.fillMaxWidth((w(kg) / maxW).toFloat().coerceIn(0.04f, 1f)).height(10.dp).clip(RoundedCornerShape(50)).background(MacroProtein))
                    }
                    Text(fmt(w(kg)), color = ByteCream, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp).width(44.dp))
                }
            }
        }
    }

    if (pts.any { it.timeUnderTensionMin > 0.0 }) {
        ChartLabel("Time under tension (min)")
        BarChartCanvas(values = pts.map { it.timeUnderTensionMin }, barColor = MacroFat, goalValue = null)
    }

    ChartLabel("Recent sessions")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        r.recent.forEach { (session, best) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(session.date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)), color = ByteCream, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("${session.sets.size} sets", color = ByteTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(end = 10.dp))
                Text(best?.let { bestLabel(it, weight) } ?: "—", color = MacroProtein, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun bestLabel(b: BestSet, weight: (Double) -> String) = "${weight(b.weightKg)} × ${b.reps}"

@Composable
private fun ChartLabel(text: String) = Text(text, color = ByteTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun AxisLabels(labels: List<String>) {
    if (labels.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(labels.first(), color = ByteTextSecondary, fontSize = 10.sp)
        if (labels.size > 1) Text(labels.last(), color = ByteTextSecondary, fontSize = 10.sp)
    }
}

/** A line through the points (zeros are gaps: periods without a working set), with the latest value. */
@Composable
private fun Trend(title: String, values: List<Double>, labels: List<String>, color: Color) {
    val shown = values.filter { it > 0.0 }
    if (shown.isEmpty()) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ChartLabel(title)
        Spacer(Modifier.weight(1f))
        Text(fmt(shown.last()), color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    val lo = shown.min(); val hi = shown.max()
    val span = (hi - lo).takeIf { it > 0.0 } ?: (hi * 0.1).coerceAtLeast(1.0)
    Canvas(
        Modifier.fillMaxWidth().height(110.dp)
            .semantics { contentDescription = "$title from ${fmt(shown.first())} to ${fmt(shown.last())}" }
    ) {
        val n = values.size
        val x = { i: Int -> if (n == 1) size.width / 2f else size.width * i / (n - 1).toFloat() }
        val y = { v: Double -> (size.height * 0.9f - ((v - lo) / span).toFloat() * size.height * 0.8f) }
        val path = Path()
        var started = false
        values.forEachIndexed { i, v ->
            if (v <= 0.0) return@forEachIndexed
            if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
        }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
        values.forEachIndexed { i, v -> if (v > 0.0) drawCircle(color, 4.dp.toPx(), Offset(x(i), y(v))) }
    }
    AxisLabels(labels)
}

@Composable
private fun ExercisePicker(exercises: List<LoggedExercise>, selected: LoggedExercise?, onSelect: (LoggedExercise) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(FluidRadius.sm)).background(ByteSurfaceVariantDark)
                .clickable { open = true }.padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics { contentDescription = "Exercise: ${selected?.name ?: "none"}. Change exercise" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(selected?.name ?: "Choose an exercise", color = ByteCream, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = ByteTextSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            exercises.forEach { e ->
                DropdownMenuItem(text = { Text(e.name) }, onClick = { open = false; onSelect(e) })
            }
        }
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).clip(RoundedCornerShape(FluidRadius.sm))
            .background(ByteSurfaceVariantDark).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.clip(RoundedCornerShape(FluidRadius.sm)).background(if (on) MacroProtein.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) { Text(label, color = if (on) MacroProtein else ByteTextSecondary, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium) }
        }
    }
}

private fun fmt(v: Double): String = if (v >= 100 || v == Math.floor(v)) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)

private fun compact(v: Double, unit: String): String = when {
    v >= 1_000_000 -> String.format(Locale.US, "%.2fM %s", v / 1_000_000, unit)
    v >= 10_000 -> String.format(Locale.US, "%.1fk %s", v / 1_000, unit)
    v > 0 -> "${v.roundToInt()} $unit"
    else -> "—"
}
