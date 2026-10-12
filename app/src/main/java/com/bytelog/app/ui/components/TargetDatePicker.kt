package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How a date reads next to today: "Today", "Yesterday", "Tomorrow", "3 days ago", "in 5 days". */
fun relativeDayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        days == -1L -> "Yesterday"
        days > 0 -> "in $days days"
        else -> "${-days} days ago"
    }
}

/** The calendar works in UTC midnights; a date picked there is the same calendar day here. */
internal fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
internal fun Long.fromPickerMillis(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/**
 * Target date for moving or copying: quick buttons for the usual days, then the chosen date shown on a card that
 * opens a calendar, with arrows to step a day at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetDatePicker(
    date: String,
    onDate: (String) -> Unit,
    sourceDate: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val today = remember { LocalDate.now() }
    val fmt = remember { DateTimeFormatter.ISO_LOCAL_DATE }
    val current = remember(date) { runCatching { LocalDate.parse(date.trim()) }.getOrDefault(today) }
    var showCalendar by remember { mutableStateOf(false) }
    fun pick(d: LocalDate) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onDate(d.format(fmt)) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Quick days
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                "Today" to today,
                "Tomorrow" to today.plusDays(1),
                "Yesterday" to today.minusDays(1),
                "Same Day" to runCatching { LocalDate.parse(sourceDate) }.getOrDefault(today)
            ).distinctBy { it.second }.forEach { (label, d) ->
                val selected = current == d
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (selected) accent else cs.surfaceContainerHighest)
                        .clickable { pick(d) }.padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label, color = if (selected) cs.onPrimary else cs.onSurfaceVariant, fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        // Any day: arrows and a calendar
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { pick(current.minusDays(1)) }, modifier = Modifier.semantics { contentDescription = "Previous day" }) {
                Icon(Icons.Default.ChevronLeft, null, tint = cs.onSurface)
            }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHighest)
                    .clickable { showCalendar = true }.padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics { contentDescription = "Pick a date" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CalendarMonth, null, tint = accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        current.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())),
                        color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold
                    )
                    Text(relativeDayLabel(current, today), color = cs.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            IconButton(onClick = { pick(current.plusDays(1)) }, modifier = Modifier.semantics { contentDescription = "Next day" }) {
                Icon(Icons.Default.ChevronRight, null, tint = cs.onSurface)
            }
        }
    }

    if (showCalendar) {
        val state = rememberDatePickerState(initialSelectedDateMillis = current.toPickerMillis())
        DatePickerDialog(
            onDismissRequest = { showCalendar = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { pick(it.fromPickerMillis()) }
                    showCalendar = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showCalendar = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}
