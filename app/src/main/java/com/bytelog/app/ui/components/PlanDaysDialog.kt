package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The next [days] days from [start], as YYYY-MM-DD. */
fun upcomingDays(start: LocalDate = LocalDate.now(), days: Int = 14): List<LocalDate> = (0 until days).map { start.plusDays(it.toLong()) }

/** "Mon 17", "Tue 18"… with today and tomorrow named. */
fun shortDay(d: LocalDate, today: LocalDate = LocalDate.now()): String = when (d) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> d.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()))
}

/**
 * Pick several days at once: to plan the same food on each of them, or copy a day's plan to them. Quick picks for
 * the next few days, weekdays and the whole week.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanDaysDialog(
    title: String,
    initial: Set<String>,
    confirmLabel: (Int) -> String,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    start: LocalDate = LocalDate.now(),
    /** A day that can't be picked (the day being copied from). */
    exclude: String? = null,
    note: String? = null
) {
    val cs = MaterialTheme.colorScheme
    val days = remember(start) { upcomingDays(start) }
    var picked by remember { mutableStateOf(initial.filter { d -> days.any { it.toString() == d } }.toSet()) }
    val pickable = days.filter { it.toString() != exclude }
    fun preset(ds: List<LocalDate>) { picked = ds.map { it.toString() }.toSet() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                note?.let { Text(it, color = cs.onSurfaceVariant, fontSize = 13.sp) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val tomorrow = start.plusDays(1)
                    Chip("Next 3 days", false) { preset(pickable.filter { !it.isBefore(tomorrow) }.take(3)) }
                    Chip("Weekdays", false) { preset(pickable.filter { !it.isBefore(tomorrow) }.take(7).filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }) }
                    Chip("Next 7 days", false) { preset(pickable.filter { !it.isBefore(tomorrow) }.take(7)) }
                    if (picked.isNotEmpty()) Chip("Clear", false) { picked = emptySet() }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    pickable.forEach { d ->
                        val key = d.toString()
                        val on = key in picked
                        DayChip(shortDay(d, start), on) { picked = if (on) picked - key else picked + key }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }, enabled = picked.isNotEmpty() || initial.isNotEmpty()) {
                Text(if (picked.isEmpty()) "Just the one day" else confirmLabel(picked.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        label,
        color = if (on) cs.onPrimary else cs.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) cs.primary else cs.primary.copy(alpha = 0.1f))
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun DayChip(label: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Text(
        label,
        color = if (on) cs.onPrimary else cs.onSurface, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier.clip(shape).background(if (on) cs.primary else cs.surfaceContainerHigh)
            .border(1.dp, if (on) cs.primary else cs.outlineVariant, shape)
            .semantics { selected = on }
            .clickable(role = Role.Checkbox, onClick = onClick)
            .widthIn(min = 72.dp).heightIn(min = 40.dp).padding(horizontal = 10.dp, vertical = 10.dp)
    )
}
