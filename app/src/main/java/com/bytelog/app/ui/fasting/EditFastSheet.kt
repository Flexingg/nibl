package com.bytelog.app.ui.fasting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.ui.components.EatenAt
import com.bytelog.app.ui.components.EatenAtText
import com.bytelog.app.ui.components.WhenPickerSheet
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Checks for an edited fast. */
object FastEdit {
    /** What's wrong with these times, or null when they can be saved. */
    fun problem(start: Instant, end: Instant?, now: Instant = Instant.now()): String? = when {
        start.isAfter(now) -> "A fast can't start in the future."
        end != null && end.isAfter(now) -> "A finished fast can't end in the future."
        end != null && !end.isAfter(start) -> "It has to end after it starts."
        end != null && Duration.between(start, end).toDays() >= 14 -> "That's over two weeks. Check the dates."
        else -> null
    }

    /** "16h 30m" */
    fun length(start: Instant, end: Instant): String {
        val m = Duration.between(start, end).toMinutes().coerceAtLeast(0)
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }
}

/**
 * Edit a fast's start and end: each opens the date and time picker. The length updates as you go, and times that
 * can't be right (ending before it starts, in the future) can't be saved. An ongoing fast only has a start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFastSheet(
    initialStart: Instant,
    initialEnd: Instant?,
    onSave: (start: Instant, end: Instant?) -> Unit,
    onDismiss: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault()
) {
    val cs = MaterialTheme.colorScheme
    val use24h = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    var start by remember { mutableStateOf(initialStart) }
    var end by remember { mutableStateOf(initialEnd) }
    var picking by remember { mutableStateOf<String?>(null) }
    val problem = FastEdit.problem(start, end)

    fun toAt(i: Instant) = LocalDateTime.ofInstant(i, zone).let { EatenAt(it.toLocalDate(), it.toLocalTime().withSecond(0).withNano(0)) }
    fun fromAt(at: EatenAt): Instant = (at.time ?: java.time.LocalTime.MIDNIGHT).atDate(at.date).atZone(zone).toInstant()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Edit fast", color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            TimeRow("Started", EatenAtText.label(toAt(start), LocalDate.now(zone), use24h)) { picking = "start" }
            end?.let { e -> TimeRow("Ended", EatenAtText.label(toAt(e), LocalDate.now(zone), use24h)) { picking = "end" } }
            Text(
                problem ?: end?.let { "Length: ${FastEdit.length(start, it)}" } ?: "Still going",
                color = if (problem != null) cs.error else cs.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(start, end) }, enabled = problem == null && (start != initialStart || end != initialEnd)) { Text("Save") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    when (picking) {
        "start" -> WhenPickerSheet(toAt(start), onDone = { start = fromAt(it); picking = null }, onDismiss = { picking = null },
            title = "When did it start?", allowNoTime = false)
        "end" -> end?.let { e ->
            WhenPickerSheet(toAt(e), onDone = { end = fromAt(it); picking = null }, onDismiss = { picking = null },
                title = "When did it end?", allowNoTime = false)
        }
    }
}

@Composable
private fun TimeRow(label: String, value: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHighest)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp).semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = cs.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.width(72.dp))
        Text(value, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("Change", color = cs.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
