package com.bytelog.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.repository.MealPlanDraft
import com.bytelog.app.data.repository.PlanItem
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A meal plan from the assistant: days at a glance, each opening to its meals, and a button that puts it all on the
 * Planned list (where each food is ticked off as it's eaten).
 */
@Composable
fun MealPlanCard(plan: MealPlanDraft, added: Boolean, onAdd: () -> Unit, modifier: Modifier = Modifier, onShoppingList: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(16.dp)
    var adding by remember(added) { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ByteTomato.copy(alpha = 0.07f))
            .border(1.dp, ByteTomato.copy(alpha = 0.35f), shape)
            .padding(12.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = ByteTomato, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Meal plan", color = ByteCream, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                val n = plan.days.size
                Text("$n day${if (n == 1) "" else "s"} · ${plan.items.size} foods · ~${plan.caloriesPerDay} kcal a day", color = ByteCreamMuted, fontSize = 11.sp)
            }
        }
        plan.byDay.forEach { (date, items) -> PlanDay(date, items) }
        Spacer(Modifier.size(2.dp))
        val enabled = !added && !adding
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(50))
                .background(if (added) MacroCarbs.copy(alpha = 0.15f) else ByteTomato)
                .clickable(enabled = enabled, role = Role.Button) { adding = true; onAdd() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (added) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MacroCarbs, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Added to Planned", color = MacroCarbs, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Text(if (adding) "Adding…" else "Add to Planned", color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (added && onShoppingList != null) {
            Text(
                "Open the shopping list", color = ByteTomato, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onShoppingList).padding(vertical = 6.dp)
            )
        }
        Text(
            if (added) "Find each day under Planned on the home screen and tick food off as you eat it."
            else "Nothing is logged yet: each day's food goes on its Planned list, ready to tick off as you eat it.",
            color = ByteCreamMuted, fontSize = 11.sp
        )
    }
}

@Composable
private fun PlanDay(date: String, items: List<PlanItem>) {
    var open by remember { mutableStateOf(false) }
    val kcal = items.sumOf { it.calories.toDouble() }.roundToInt()
    val protein = items.sumOf { it.protein.toDouble() }.roundToInt()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ByteSurfaceVariantDark.copy(alpha = 0.35f))
            .clickable(role = Role.Button, onClickLabel = if (open) "Hide meals" else "Show meals") { open = !open }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dayLabel(date), color = ByteCream, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("$kcal kcal · ${protein}g protein", color = ByteCreamMuted, fontSize = 11.sp)
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = ByteCreamMuted, modifier = Modifier.size(18.dp))
        }
        if (open) {
            items.groupBy { it.meal }.forEach { (meal, foods) ->
                Text(meal, color = ByteTomato, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                foods.forEach { f ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(
                            "${amount(f)} ${f.food}${f.brand?.let { " ($it)" } ?: ""}",
                            color = ByteCream, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        Text("${f.calories.roundToInt()} kcal", color = ByteCreamMuted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

private fun amount(f: PlanItem): String {
    val q = if (f.quantity % 1f == 0f) f.quantity.toInt().toString() else "%.1f".format(Locale.getDefault(), f.quantity)
    return "$q ${f.unit}"
}

private fun dayLabel(date: String): String = runCatching {
    val d = LocalDate.parse(date)
    val today = LocalDate.now()
    when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }
}.getOrDefault(date)
