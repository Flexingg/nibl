package com.bytelog.app.ui.diary

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.repository.ShoppingList
import com.bytelog.app.data.repository.ShoppingListResult
import com.bytelog.app.ui.components.pressBounce
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** One day of the week ahead on the home screen. */
data class PlanDaySummary(val date: LocalDate, val calories: Float, val foods: Int)

@Composable
private fun CardHeader(icon: ImageVector, title: String, subtitle: String, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClickLabel = "Open $title", onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Text(subtitle, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant)
    }
}

/**
 * The week ahead on the home screen: each day with how much is planned, tapping through to the planner (a day
 * opens it there).
 */
@Composable
fun MealPlanHomeCard(days: List<PlanDaySummary>, calorieGoal: Float, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val planned = days.count { it.foods > 0 }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardHeader(
            Icons.Default.CalendarMonth, "Meal plan",
            if (planned == 0) "Nothing planned this week" else "$planned of 7 days planned", onOpen
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { d ->
                val frac = if (calorieGoal > 0f) (d.calories / calorieGoal).coerceIn(0f, 1f) else if (d.foods > 0) 1f else 0f
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(if (d.foods > 0) cs.primary.copy(alpha = 0.08f + 0.22f * frac) else cs.surfaceContainerHigh)
                        .clickable(role = Role.Button, onClick = onOpen)
                        .semantics { contentDescription = "${d.date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}: ${if (d.foods == 0) "nothing planned" else "${d.calories.roundToInt()} kcal planned"}" }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(d.date.format(DateTimeFormatter.ofPattern("EEEEE", Locale.getDefault())), color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text("${d.date.dayOfMonth}", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (d.foods == 0) "–" else if (d.calories >= 1000) "${(d.calories / 100).roundToInt() / 10.0}k" else "${d.calories.roundToInt()}",
                        color = if (d.foods == 0) cs.onSurfaceVariant else cs.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
                    )
                }
            }
        }
        Text(
            if (planned == 0) "Plan meals ahead, edit and copy days, and get a shopping list for them." else "Tap to see, edit or copy the plan.",
            color = cs.onSurfaceVariant, fontSize = 12.sp
        )
    }
}

/** What to buy for the week's plan on the home screen: how much is left to get, the cost and the next few items. */
@Composable
fun ShoppingHomeCard(list: ShoppingListResult, checked: Set<String>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val left = list.items.filter { it.key !in checked }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CardHeader(
            Icons.Default.ShoppingCart, "Shopping list",
            when {
                list.items.isEmpty() -> "Nothing to buy yet"
                left.isEmpty() -> "All ${list.items.size} got"
                else -> "${left.size} to get" + if (list.priced > 0) " · about ${ShoppingList.money(left.sumOf { it.cost ?: 0.0 })}" else ""
            },
            onOpen
        )
        if (list.items.isEmpty()) {
            Text("Plan food for the week and what to buy shows up here, with prices where you've saved them.", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            left.take(4).forEach { i ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(cs.primary))
                    Spacer(Modifier.width(10.dp))
                    Text(i.name, color = cs.onSurface, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(i.amountLabel, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
                }
            }
            if (left.size > 4) Text("and ${left.size - 4} more", color = cs.onSurfaceVariant, fontSize = 12.sp)
            Box(
                Modifier.pressBounce().clip(RoundedCornerShape(50)).background(cs.primary)
                    .clickable(role = Role.Button, onClick = onOpen).heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 11.dp)
            ) { Text("Open shopping list", color = cs.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
