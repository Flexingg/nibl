package com.bytelog.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.ui.components.RecentMealItem
import com.bytelog.app.ui.components.ScreenHeader
import com.bytelog.app.ui.components.popIn
import com.bytelog.app.ui.components.pressBounce
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.LocalReduceMotion
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Full page for copying meals from recent days into the day open in the Diary. */
@Composable
fun RecentMealsScreen(
    targetDate: String,
    recentMeals: List<RecentMealItem>,
    onBack: () -> Unit,
    onCopyMeals: (selected: List<RecentMealItem>, targetDate: String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val selected = remember(recentMeals) { mutableStateListOf<Int>().apply { if (recentMeals.isNotEmpty()) add(0) } }
    val popped = remember { mutableSetOf<Any>() }
    val target = remember(targetDate) { describeDay(targetDate) }

    // Group consecutive meals under their day so the list reads like the diary.
    val rows = remember(recentMeals) {
        buildList {
            var lastDay: String? = null
            recentMeals.forEachIndexed { i, meal ->
                if (meal.displayDate != lastDay) { add(Row.Day(meal.displayDate)); lastDay = meal.displayDate }
                add(Row.Meal(i, meal))
            }
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val headerFraction = com.bytelog.app.ui.components.rememberScrollFraction(listState)
    Scaffold(
        containerColor = ByteInkDark,
        topBar = {
            ScreenHeader(
                title = "Recent meals",
                subtitle = "Copy into $target",
                onBack = onBack,
                fraction = headerFraction
            )
        },
        bottomBar = {
            if (recentMeals.isNotEmpty()) {
                CopyBar(
                    count = selected.size,
                    target = target,
                    onCopy = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCopyMeals(selected.sorted().map { recentMeals[it] }, targetDate)
                    }
                )
            }
        }
    ) { padding ->
        if (recentMeals.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.History, contentDescription = null, tint = ByteCreamMuted, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text("No recent meals yet", color = ByteCream, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Meals you log over the past few days show up here, ready to copy.",
                    color = ByteCreamMuted, fontSize = 13.sp, textAlign = TextAlign.Center
                )
            }
            return@Scaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "controls") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${selected.size} of ${recentMeals.size} selected",
                        color = ByteCreamMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selected.clear(); selected.addAll(recentMeals.indices)
                    }) { Text("Select all", color = MacroProtein, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selected.clear()
                    }) { Text("Clear", color = ByteCreamMuted, fontSize = 13.sp) }
                }
            }
            itemsIndexed(rows, key = { _, row -> row.key }) { order, row ->
                val stagger = order.coerceAtMost(10) * 30L
                when (row) {
                    is Row.Day -> Text(
                        row.label.uppercase(Locale.getDefault()),
                        color = ByteTomato, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp).popIn(row.key, popped, stagger)
                    )
                    is Row.Meal -> MealRow(
                        meal = row.meal,
                        isSelected = row.index in selected,
                        modifier = Modifier.popIn(row.key, popped, stagger),
                        onToggle = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (row.index in selected) selected.remove(row.index) else selected.add(row.index)
                        }
                    )
                }
            }
        }
    }
}

private sealed interface Row {
    val key: String
    data class Day(val label: String) : Row { override val key get() = "day:$label" }
    data class Meal(val index: Int, val meal: RecentMealItem) : Row { override val key get() = "meal:$index:${meal.date}:${meal.mealName}" }
}

@Composable
private fun MealRow(meal: RecentMealItem, isSelected: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val bg by animateColorAsState(if (isSelected) ByteSurfaceVariantDark else ByteSurfaceDark, label = "meal_bg")
    val edge by animateColorAsState(if (isSelected) MacroProtein else Color.Transparent, label = "meal_edge")
    val check by animateFloatAsState(
        if (isSelected) 1f else 0f,
        if (reduce) spring(stiffness = 10_000f) else spring(dampingRatio = 0.45f, stiffness = 500f),
        label = "meal_check"
    )
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .pressBounce()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.5.dp, edge, RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(ByteSurfaceVariantDark).border(1.5.dp, ByteCreamMuted.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(26.dp).graphicsLayer { scaleX = check; scaleY = check; alpha = check.coerceIn(0f, 1f) }
                    .clip(CircleShape).background(MacroProtein),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = ByteInkDark, modifier = Modifier.size(16.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Text(meal.mealName, color = ByteCream, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${meal.calories.roundToInt()} kcal", color = ByteCream, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(meal.foodSummary, color = ByteCreamMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("P ${meal.protein.roundToInt()}g", color = MacroProtein, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text("C ${meal.carbs.roundToInt()}g", color = MacroCarbs, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text("F ${meal.fat.roundToInt()}g", color = MacroFat, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CopyBar(count: Int, target: String, onCopy: () -> Unit) {
    val enabled = count > 0
    val bg by animateColorAsState(if (enabled) ByteTomato else ByteSurfaceVariantDark, label = "copy_bg")
    Box(
        Modifier.fillMaxWidth().background(ByteInkDark).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .pressBounce(0.95f)
                .clip(RoundedCornerShape(16.dp))
                .background(bg)
                .clickable(enabled = enabled, onClick = onCopy),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = if (enabled) ByteCream else ByteCreamMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when (count) {
                    0 -> "Pick a meal to copy"
                    1 -> "Copy 1 meal to $target"
                    else -> "Copy $count meals to $target"
                },
                color = if (enabled) ByteCream else ByteCreamMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

/** "Today", "Yesterday", "Tomorrow" or e.g. "Sat, Oct 4". */
internal fun describeDay(date: String, today: LocalDate = LocalDate.now()): String {
    val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        today.plusDays(1) -> "Tomorrow"
        else -> d.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
    }
}
