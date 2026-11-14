package com.bytelog.app.ui.diary

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.PlannedEntry
import com.bytelog.app.ui.components.pressBounce
import com.bytelog.app.ui.logging.Portion
import kotlin.math.roundToInt

/**
 * Food planned for the day: what it adds up to with what's already logged, and each item faded until it's ticked
 * off as eaten. On a day still ahead it can only be planned or removed.
 */
@Composable
fun PlannedCard(
    planned: List<PlannedEntry>,
    loggedCalories: Float,
    calorieGoal: Float,
    ahead: Boolean,
    onEat: (List<PlannedEntry>) -> Unit,
    onRemove: (PlannedEntry) -> Unit,
    modifier: Modifier = Modifier,
    /** Copies the day's plan to other days; null hides the button. */
    onCopyToDays: (() -> Unit)? = null,
    /** Opens the shopping list for what's planned; null hides the button. */
    onShoppingList: (() -> Unit)? = null,
    /** Opens the meal planner; null hides the button. */
    onOpenPlanner: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    val plannedKcal = planned.sumOf { it.calories.toDouble() }.toFloat()
    val total = loggedCalories + plannedKcal
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.EventNote, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("PLANNED", color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Text(
                    "${"%,d".format(plannedKcal.roundToInt())} kcal planned",
                    color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
                if (calorieGoal > 0f) {
                    val diff = (total - calorieGoal).roundToInt()
                    Text(
                        "The day would come to ${"%,d".format(total.roundToInt())} of ${"%,d".format(calorieGoal.roundToInt())}" +
                            when { diff > 0 -> " · ${"%,d".format(diff)} over"; diff < 0 -> " · ${"%,d".format(-diff)} left"; else -> " · right on" },
                        color = if (diff > calorieGoal * 0.1f) cs.error else cs.onSurfaceVariant, fontSize = 12.sp
                    )
                }
            }
            if (!ahead && planned.size > 1) {
                Box(
                    Modifier.pressBounce().clip(RoundedCornerShape(50)).background(cs.primary)
                        .clickable(onClickLabel = "Log everything planned") { onEat(planned) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) { Text("Log all", color = cs.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        }

        planned.groupBy { it.mealName }.forEach { (meal, items) ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(meal, color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                items.forEachIndexed { i, p ->
                    val first = i == 0; val last = i == items.lastIndex
                    val shape = RoundedCornerShape(
                        topStart = if (first) 16.dp else 6.dp, topEnd = if (first) 16.dp else 6.dp,
                        bottomStart = if (last) 16.dp else 6.dp, bottomEnd = if (last) 16.dp else 6.dp
                    )
                    Row(
                        Modifier.fillMaxWidth().clip(shape)
                            .border(1.dp, cs.outlineVariant.copy(alpha = 0.6f), shape)
                            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Faded: planned, not eaten yet
                        Column(Modifier.weight(1f).alpha(0.7f)) {
                            Text(p.foodName, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${Portion(p.quantity, p.unit).label} · ${p.calories.roundToInt()} kcal", color = cs.onSurfaceVariant, fontSize = 12.sp)
                        }
                        if (!ahead) {
                            Box(
                                Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(cs.primaryContainer)
                                    .clickable { onEat(listOf(p)) }
                                    .semantics { contentDescription = "Ate ${p.foodName}" },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.Check, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(20.dp)) }
                        }
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable { onRemove(p) }
                                .semantics { contentDescription = "Remove ${p.foodName} from the plan" },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Close, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
        if (onCopyToDays != null || onShoppingList != null || onOpenPlanner != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                onOpenPlanner?.let { PlanAction(Icons.Default.EditCalendar, "Planner", it, Modifier.weight(1f)) }
                onCopyToDays?.let { PlanAction(Icons.Default.ContentCopy, "Copy", it, Modifier.weight(1f)) }
                onShoppingList?.let { PlanAction(Icons.Default.ShoppingCart, "Shopping", it, Modifier.weight(1f)) }
            }
        }
        Text(
            if (ahead) "Planned food doesn't count yet. On the day, tick each one off as you eat it."
            else "Tick each one off as you eat it; it's logged then.",
            color = cs.onSurfaceVariant, fontSize = 11.5.sp
        )
    }
}

@Composable
private fun PlanAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.pressBounce().clip(RoundedCornerShape(50)).background(cs.secondaryContainer)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = cs.onSecondaryContainer, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
