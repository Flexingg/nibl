package com.bytelog.app.ui.family

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.family.MemberDay
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.remote.FamilyMember
import com.bytelog.app.ui.components.ScreenHeader
import com.bytelog.app.ui.components.popIn
import com.bytelog.app.ui.components.pressBounce
import com.bytelog.app.ui.components.rememberScrollFraction
import com.bytelog.app.ui.diary.LoadingSpinner
import com.bytelog.app.ui.diary.MorphButton
import com.bytelog.app.ui.diary.WavyLinearProgress
import com.bytelog.app.ui.theme.LocalByteExtraColors
import com.bytelog.app.ui.viewmodel.FamilyViewModel
import com.bytelog.app.ui.viewmodel.MemberToday
import com.bytelog.app.ui.viewmodel.MemberViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** A round badge with a person's initial. */
@Composable
fun Avatar(member: FamilyMember, size: Int = 44) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(size.dp).clip(CircleShape).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) {
        Text(member.initial, color = cs.onTertiaryContainer, fontSize = (size * 0.42f).sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Everyone sharing their diary with the user, with today's calories for each. */
@Composable
fun FamilyScreen(viewModel: FamilyViewModel, onBack: () -> Unit, onOpenMember: (String) -> Unit) {
    val s by viewModel.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    val popped = remember { mutableSetOf<Any>() }

    Scaffold(
        containerColor = cs.surface,
        topBar = {
            ScreenHeader(
                title = "Family",
                subtitle = if (s.members.isEmpty()) "Share diaries through Sparky" else "${s.members.size} ${if (s.members.size == 1) "person shares" else "people share"} their diary with you",
                onBack = onBack,
                fraction = rememberScrollFraction(list),
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = cs.onSurfaceVariant) }
                }
            )
        }
    ) { padding ->
        when {
            s.loading && s.members.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingSpinner(Modifier.size(36.dp), color = cs.primary)
            }
            s.error != null && s.members.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Text("Couldn't reach Sparky", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(s.error.orEmpty(), color = cs.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center)
                TextButton(onClick = viewModel::refresh) { Text("Try again") }
            }
            s.members.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Text("No one shares with you yet", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Family members appear here once they give you access in Sparky (Settings › Family Access). " +
                        "Then you can see their day, copy their meals, and split meals with them.",
                    color = cs.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center
                )
            }
            else -> LazyColumn(
                state = list, contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                modifier = Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(s.members, key = { _, m -> m.userId }) { i, m ->
                    MemberCard(m, s.today[m.userId], Modifier.popIn(m.userId, popped, (i.coerceAtMost(6) * 40).toLong())) { onOpenMember(m.userId) }
                }
            }
        }
    }
}

@Composable
private fun MemberCard(member: FamilyMember, today: MemberToday?, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val x = LocalByteExtraColors.current
    Column(
        modifier.fillMaxWidth().pressBounce(0.98f).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer)
            .clickable(enabled = true, onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(member)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(member.displayName, color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        member.canWriteDiary -> "You can log meals for them"
                        member.canViewDiary -> "View only"
                        else -> "No diary access"
                    },
                    color = cs.onSurfaceVariant, fontSize = 12.5.sp
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = cs.onSurfaceVariant)
        }
        if (today != null) {
            val goal = today.goal ?: 0f
            Row(verticalAlignment = Alignment.Bottom) {
                Text(String.format(Locale.getDefault(), "%,d", today.calories.roundToInt()), color = cs.onSurface, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                if (goal > 0f) Text(String.format(Locale.getDefault(), " / %,d kcal today", goal.roundToInt()), color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
                else Text(" kcal today", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
            if (goal > 0f) WavyLinearProgress((today.calories / goal).coerceIn(0f, 1f), cs.primary, Modifier.fillMaxWidth(), amplitude = 2.2.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MacroDot("P", today.protein, x.protein); MacroDot("C", today.carbs, x.carbs); MacroDot("F", today.fat, x.fat)
            }
        } else if (!member.canViewDiary) {
            Text("They haven't shared their diary with you.", color = cs.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun MacroDot(letter: String, grams: Float, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text("$letter ${grams.roundToInt()} g", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** One family member's diary: a day at a time, their goals, the week, and a button to copy a meal to your own diary. */
@Composable
fun MemberDiaryScreen(viewModel: MemberViewModel, onBack: () -> Unit) {
    val s by viewModel.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    val x = LocalByteExtraColors.current
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val member = s.member
    val day = s.day
    val isToday = s.date == LocalDate.now().toString()

    Scaffold(
        containerColor = cs.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ScreenHeader(
                title = member?.displayName ?: "Family",
                subtitle = if (member?.canWriteDiary == true) "You can log meals for them" else "Their diary",
                onBack = onBack, fraction = rememberScrollFraction(list),
                leading = member?.let { { Avatar(it, 40) } }
            )
        }
    ) { padding ->
        LazyColumn(
            state = list, contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 40.dp),
            modifier = Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "daynav") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.shiftDay(-1) }) { Icon(Icons.Default.ChevronLeft, "Previous day", tint = cs.onSurface) }
                    Text(
                        describe(s.date), color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.shiftDay(1) }, enabled = !isToday) {
                        Icon(Icons.Default.ChevronRight, "Next day", tint = if (isToday) cs.onSurface.copy(alpha = 0.3f) else cs.onSurface)
                    }
                }
            }
            when {
                s.loading && day == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { LoadingSpinner(Modifier.size(32.dp)) }
                }
                s.error != null && day == null -> item(key = "error") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.error.orEmpty(), color = cs.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
                        TextButton(onClick = viewModel::load) { Text("Try again") }
                    }
                }
                day != null -> {
                    item(key = "totals") { DayTotals(day, x.protein, x.carbs, x.fat) }
                    if (s.week.isNotEmpty()) item(key = "week") { WeekBars(s.week, day.goals?.calories) }
                    val groups = day.entries.groupBy { it.mealName }.toList()
                    if (groups.isEmpty()) item(key = "empty") {
                        Text("Nothing logged on ${describe(s.date).lowercase()}.", color = cs.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                    }
                    items(groups.size, key = { "meal_${groups[it].first}" }) { i ->
                        val (mealName, entries) = groups[i]
                        MealBlock(mealName, entries) {
                            viewModel.copyMealToMe(mealName, entries) { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayTotals(day: MemberDay, p: Color, c: Color, f: Color) {
    val cs = MaterialTheme.colorScheme
    val goals = day.goals
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(String.format(Locale.getDefault(), "%,d", day.calories.roundToInt()), color = cs.onSurface, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (goals != null && goals.calories > 0f) String.format(Locale.getDefault(), " / %,d kcal", goals.calories.roundToInt()) else " kcal",
                color = cs.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        if (goals != null && goals.calories > 0f) WavyLinearProgress((day.calories / goals.calories).coerceIn(0f, 1f), cs.primary, Modifier.fillMaxWidth())
        MacroLine("Protein", day.protein, goals?.protein, p)
        MacroLine("Carbs", day.carbs, goals?.carbs, c)
        MacroLine("Fat", day.fat, goals?.fat, f)
    }
}

@Composable
private fun MacroLine(label: String, value: Float, goal: Float?, color: Color) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row {
            Text(label.uppercase(), color = cs.onSurfaceVariant, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
            Text("${value.roundToInt()} g" + (goal?.takeIf { it > 0f }?.let { " / ${it.roundToInt()}" } ?: ""), color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        WavyLinearProgress(if (goal != null && goal > 0f) (value / goal).coerceIn(0f, 1f) else 0f, color, Modifier.fillMaxWidth(), amplitude = 2.dp, wavelength = 16.dp)
    }
}

/** Calories for the last days as small bars, today (the viewed day) on the right. */
@Composable
private fun WeekBars(week: List<Pair<String, Float?>>, goal: Float?) {
    val cs = MaterialTheme.colorScheme
    val top = maxOf(goal ?: 0f, week.maxOf { it.second ?: 0f }, 1f)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(16.dp)) {
        Text("LAST 7 DAYS", color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            week.forEachIndexed { i, (date, kcal) ->
                val last = i == week.lastIndex
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(
                        Modifier.fillMaxWidth().height(((kcal ?: 0f) / top * 64f).coerceAtLeast(3f).dp)
                            .clip(RoundedCornerShape(8.dp)).background(if (kcal == null) cs.surfaceContainerHighest else if (last) cs.primary else cs.primaryContainer)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        runCatching { LocalDate.parse(date).dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()) }.getOrDefault(""),
                        color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun MealBlock(mealName: String, entries: List<FoodEntry>, onCopyToMe: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(mealName, color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("${entries.sumOf { it.calories.toDouble() }.roundToInt()} kcal", color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
            MorphButton(onCopyToMe, Modifier.height(40.dp), cs.primaryContainer, description = "Copy $mealName to my diary") {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ContentCopy, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy to mine", color = cs.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        entries.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.foodName, color = cs.onSurface, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${com.bytelog.app.ui.logging.Portion(e.quantity, e.unit).label} • P ${e.protein.roundToInt()} C ${e.carbs.roundToInt()} F ${e.fat.roundToInt()}", color = cs.onSurfaceVariant, fontSize = 12.sp)
                }
                Text("${e.calories.roundToInt()} kcal", color = cs.primary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun describe(date: String): String {
    val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
    }
}
