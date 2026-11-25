package com.bytelog.app.ui.components

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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** When food was eaten: a day, and a time of day if known. */
data class EatenAt(val date: LocalDate, val time: LocalTime?)

object EatenAtText {
    private val stored = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** "8:15 AM" (or "08:15" on a 24-hour phone). */
    fun time(t: LocalTime, use24h: Boolean): String =
        t.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", Locale.US))

    /** "Today · 8:15 AM", "Yesterday", "Tue, Nov 24 · 7:30 PM"; [nowWhenToday] shows "now" for today with no time set. */
    fun label(at: EatenAt, today: LocalDate, use24h: Boolean, nowWhenToday: Boolean = false): String {
        val day = when (at.date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            today.plusDays(1) -> "Tomorrow"
            else -> at.date.format(DateTimeFormatter.ofPattern(if (at.date.year == today.year) "EEE, MMM d" else "MMM d, yyyy", Locale.US))
        }
        val time = at.time?.let { time(it, use24h) } ?: if (nowWhenToday && at.date == today) "now" else null
        return if (time != null) "$day · $time" else day
    }

    /** How an entry stores its time ("HH:mm:ss"). */
    fun stored(t: LocalTime?): String? = t?.withSecond(0)?.withNano(0)?.format(stored)

    /** An entry's stored time ("HH:mm", "HH:mm:ss" or a full timestamp), or null. */
    fun parse(raw: String?): LocalTime? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val clock = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?""").find(if (s.contains('T')) s.substringAfter('T') else s) ?: return null
        val (h, m, sec) = clock.destructured
        return runCatching { LocalTime.of(h.toInt(), m.toInt(), sec.ifEmpty { "0" }.toInt()) }.getOrNull()
    }
}

/**
 * Picks when food was eaten: quick days (yesterday, today, tomorrow), any day from a calendar, and a time typed
 * in or set to now. "No time" leaves the time off, which is how most of Sparky's older entries are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhenPickerSheet(
    initial: EatenAt,
    onDone: (EatenAt) -> Unit,
    onDismiss: () -> Unit,
    title: String = "When did you eat it?",
    /** Offer "No time" (food can count for just the day; a fast always has a time). */
    allowNoTime: Boolean = true
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val use24h = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    var date by remember { mutableStateOf(initial.date) }
    var hasTime by remember { mutableStateOf(initial.time != null || !allowNoTime) }
    val start = initial.time ?: LocalTime.now()
    val timeState = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = use24h)
    var setNow by remember { mutableStateOf(0) }
    LaunchedEffect(setNow) {
        if (setNow > 0) LocalTime.now().let { timeState.hour = it.hour; timeState.minute = it.minute }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(title, color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

            TargetDatePicker(
                date = date.toString(),
                onDate = { date = LocalDate.parse(it) },
                sourceDate = date.toString(),
                accent = cs.primary,
                showSameDay = false
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Time", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Choice("Now", false) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); hasTime = true; setNow++
                }
                if (allowNoTime) {
                    Spacer(Modifier.width(6.dp))
                    Choice("No time", !hasTime) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); hasTime = !hasTime }
                }
            }
            if (hasTime) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimeInput(state = timeState) }
            } else {
                Text("No time of day: the food just counts for the day.", color = cs.onSurfaceVariant, fontSize = 13.sp)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDone(EatenAt(date, if (hasTime) LocalTime.of(timeState.hour, timeState.minute) else null))
                }) { Text("Done") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) cs.secondaryContainer else cs.surfaceContainerHighest)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { role = Role.Button; this.selected = selected }
    ) {
        Text(label, color = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

/**
 * The tappable "when" line: a clock and "Today · 8:15 AM". Opens [WhenPickerSheet].
 */
@Composable
fun WhenChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.clip(RoundedCornerShape(50)).background(cs.surfaceContainerHighest).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Schedule, contentDescription = null, tint = cs.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
