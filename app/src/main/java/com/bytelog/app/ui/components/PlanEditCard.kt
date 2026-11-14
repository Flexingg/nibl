package com.bytelog.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.repository.PlanChangeLine

/**
 * Changes to the meal plan the assistant proposed: each one listed (added, removed, changed), applied with one tap and
 * undone with another.
 */
@Composable
fun PlanEditCard(
    lines: List<PlanChangeLine>,
    applied: Boolean,
    onApply: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlanner: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    var working by remember(applied) { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(shape).background(cs.secondaryContainer.copy(alpha = 0.35f))
            .border(1.dp, cs.secondary.copy(alpha = 0.4f), shape).padding(12.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.EditCalendar, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Plan changes", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${lines.count { it.kind != PlanChangeLine.Kind.MISSING }}", color = cs.onSurfaceVariant, fontSize = 12.sp)
        }
        lines.forEach { l ->
            val (icon, tint) = when (l.kind) {
                PlanChangeLine.Kind.ADD -> Icons.Default.Add to cs.primary
                PlanChangeLine.Kind.REMOVE -> Icons.Default.Remove to cs.error
                PlanChangeLine.Kind.UPDATE -> Icons.Default.Edit to cs.secondary
                PlanChangeLine.Kind.MISSING -> Icons.Default.QuestionMark to cs.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(l.text, color = if (l.kind == PlanChangeLine.Kind.MISSING) cs.onSurfaceVariant else cs.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (applied) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(cs.secondary.copy(alpha = 0.15f)).heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Applied to your plan", color = cs.secondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Undo", color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onUndo).heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 11.dp)
                )
            } else {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(cs.secondary)
                        .clickable(enabled = !working, role = Role.Button) { working = true; onApply() }
                        .heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) { Text(if (working) "Applying…" else "Apply changes", color = cs.onSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        if (applied && onOpenPlanner != null) {
            Text(
                "Open the planner", color = cs.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onOpenPlanner).padding(vertical = 6.dp)
            )
        }
    }
}
