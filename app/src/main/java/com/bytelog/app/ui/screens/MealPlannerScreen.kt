package com.bytelog.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.PlannedEntry
import com.bytelog.app.data.repository.PlannerConfig
import com.bytelog.app.ui.components.PlanDaysDialog
import com.bytelog.app.ui.components.ScreenHeader
import com.bytelog.app.ui.components.rememberScrollFraction
import com.bytelog.app.ui.components.shortDay
import com.bytelog.app.ui.logging.Portion
import com.bytelog.app.ui.logging.Portions
import com.bytelog.app.ui.theme.fluidBackdrop
import com.bytelog.app.ui.viewmodel.MealPlannerViewModel
import com.bytelog.app.ui.viewmodel.PlannerDay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The meal planner: the days ahead, each with its planned food by meal and what it comes to against the goal. Tap
 * a food to change its amount, unit, meal or day; add food to a day, copy a day to others, or clear it. How many
 * days show, where the week starts and what's shown are set from the tune button.
 */
@Composable
fun MealPlannerScreen(
    viewModel: MealPlannerViewModel,
    onBack: () -> Unit,
    onAddFood: (date: String, mealTypeId: String, mealName: String) -> Unit,
    onShoppingList: () -> Unit
) {
    val s by viewModel.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var copyFrom by remember { mutableStateOf<PlannerDay?>(null) }
    val today = LocalDate.now()

    copyFrom?.let { day ->
        PlanDaysDialog(
            title = "Copy ${shortDay(day.date, today)} to…",
            note = "${day.entries.size} planned food${if (day.entries.size == 1) "" else "s"}, same meals and amounts, go on each day you pick.",
            initial = emptySet(), exclude = day.date.toString(),
            confirmLabel = { n -> "Copy to $n day${if (n == 1) "" else "s"}" },
            onConfirm = { days ->
                copyFrom = null
                if (days.isNotEmpty()) viewModel.copyDay(day, days) { n -> scope.launch { snackbar.showSnackbar("Copied $n food${if (n == 1) "" else "s"}") } }
            },
            onDismiss = { copyFrom = null }
        )
    }

    fun removeWithUndo(entries: List<PlannedEntry>, what: String) {
        viewModel.remove(entries)
        scope.launch {
            if (snackbar.showSnackbar(what, actionLabel = "Undo") == SnackbarResult.ActionPerformed) viewModel.restore(entries)
        }
    }

    Scaffold(
        modifier = Modifier.fluidBackdrop(),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ScreenHeader(
                title = "Meal plan", subtitle = rangeLabel(s.from, s.to), onBack = onBack, fraction = rememberScrollFraction(list),
                actions = {
                    IconButton(onClick = onShoppingList) { Icon(Icons.Default.ShoppingCart, contentDescription = "Shopping list", tint = cs.onSurface) }
                    IconButton(onClick = { showSettings = !showSettings }) { Icon(Icons.Default.Tune, contentDescription = "Planner settings", tint = if (showSettings) cs.primary else cs.onSurface) }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = list, modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "nav") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AnimatedVisibility(showSettings) { Settings(s.config, viewModel::setConfig) }
                    PageNav(s, onPrev = viewModel::previousPage, onNext = viewModel::nextPage, onToday = viewModel::thisPage)
                }
            }
            items(s.days, key = { it.date.toString() }) { day ->
                DayCard(
                    day = day, state = s, today = today, editing = editing,
                    onEdit = { editing = if (editing == it) null else it },
                    onAdd = { meal -> onAddFood(day.date.toString(), meal.id, meal.name) },
                    onCopy = { copyFrom = day },
                    onClear = { removeWithUndo(day.entries, "Cleared ${shortDay(day.date, today)}") },
                    viewModel = viewModel,
                    onRemove = { e -> editing = null; removeWithUndo(listOf(e), "Removed ${e.foodName}") }
                )
            }
        }
    }
}

private fun rangeLabel(from: LocalDate, to: LocalDate): String {
    val f = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    return "${from.format(f)} – ${to.format(f)}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Settings(c: PlannerConfig, onChange: (PlannerConfig) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("SHOW", color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlannerConfig.DAY_CHOICES.forEach { n -> Pill("$n days", c.days == n) { onChange(c.copy(days = n)) } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Start today", !c.startOnMonday) { onChange(c.copy(startOnMonday = false)) }
            Pill("Start Monday", c.startOnMonday) { onChange(c.copy(startOnMonday = true)) }
        }
        Toggle("Calories against your goal", c.showGoal) { onChange(c.copy(showGoal = it)) }
        Toggle("Protein, carbs and fat", c.showMacros) { onChange(c.copy(showMacros = it)) }
        Toggle("Costs, from your saved prices", c.showCosts) { onChange(c.copy(showCosts = it)) }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Switch) { onChange(!on) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) cs.onPrimary else cs.onSurface,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) cs.primary else cs.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

@Composable
private fun PageNav(s: MealPlannerViewModel.State, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.Default.ChevronLeft, contentDescription = "Earlier days", tint = cs.onSurface) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (s.page) { 0 -> if (s.config.startOnMonday) "This week" else "Coming up"; 1 -> "Next"; -1 -> "Before"; else -> if (s.page > 0) "${s.page} ahead" else "${-s.page} back" },
                color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
            )
            Text("${s.planned} planned · ${s.config.days} days", color = cs.onSurfaceVariant, fontSize = 12.sp)
        }
        if (s.page != 0) {
            Text(
                "Back to now", color = cs.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onToday).padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
        IconButton(onClick = onNext) { Icon(Icons.Default.ChevronRight, contentDescription = "Later days", tint = cs.onSurface) }
    }
}

@Composable
private fun DayCard(
    day: PlannerDay,
    state: MealPlannerViewModel.State,
    today: LocalDate,
    editing: String?,
    onEdit: (String) -> Unit,
    onAdd: (MealType) -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    viewModel: MealPlannerViewModel,
    onRemove: (PlannedEntry) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val past = day.date.isBefore(today)
    var addOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer.copy(alpha = if (past) 0.6f else 1f))
            .animateContentSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (day.date) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> day.date.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())) },
                    color = if (day.date == today) cs.primary else cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
                Text(day.date.format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault())), color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
            if (!day.isEmpty) {
                IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy ${day.date} to other days", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
                IconButton(onClick = onClear) { Icon(Icons.Default.DeleteOutline, contentDescription = "Clear ${day.date}", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
            }
        }
        if (!day.isEmpty) {
            val goal = state.goal
            if (state.config.showGoal && goal > 0f) {
                val frac = (day.calories / goal).coerceIn(0f, 1.2f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { frac.coerceAtMost(1f) }, modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(50)),
                        color = if (frac > 1.05f) cs.error else cs.primary, trackColor = cs.surfaceContainerHighest
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("${"%,d".format(day.calories.roundToInt())} / ${"%,d".format(goal.roundToInt())} kcal", color = cs.onSurfaceVariant, fontSize = 12.sp)
                }
            } else {
                Text("${"%,d".format(day.calories.roundToInt())} kcal planned", color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
            if (state.config.showMacros) {
                Text("P ${day.protein.roundToInt()} g · C ${day.carbs.roundToInt()} g · F ${day.fat.roundToInt()} g", color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
            if (state.config.showCosts && day.priced > 0) {
                // The day's cost, and protein per dollar over the foods with prices
                val pricedProtein = day.entries.filter { it.id in state.costs }.sumOf { it.protein.toDouble() }
                val missing = day.entries.size - day.priced
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Savings, contentDescription = null, tint = cs.tertiary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "About ${com.bytelog.app.data.repository.ShoppingList.money(day.cost)}" +
                            (if (day.cost > 0) " · ${(pricedProtein / day.cost).roundToInt()} g protein per $" else "") +
                            (if (missing > 0) " · $missing without a price" else ""),
                        color = cs.tertiary, fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        day.meals.forEach { (meal, items) ->
            Text(meal, color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp, start = 2.dp))
            items.forEach { e ->
                PlannedRow(
                    e, open = editing == e.id, onToggle = { onEdit(e.id) }, viewModel = viewModel, state = state, today = today,
                    onRemove = { onRemove(e) }
                )
            }
        }
        if (day.isEmpty) Text("Nothing planned yet.", color = cs.onSurfaceVariant, fontSize = 13.sp)
        // Add food to one of the day's meals
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClickLabel = "Add food to ${day.date}") { addOpen = !addOpen }
                .heightIn(min = 40.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add food", color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        AnimatedVisibility(addOpen) {
            ChipRow(state.mealTypes.map { it.name }, selected = null) { name ->
                addOpen = false
                state.mealTypes.firstOrNull { it.name == name }?.let(onAdd)
            }
        }
    }
}

@Composable
private fun PlannedRow(
    e: PlannedEntry,
    open: Boolean,
    onToggle: () -> Unit,
    viewModel: MealPlannerViewModel,
    state: MealPlannerViewModel.State,
    today: LocalDate,
    onRemove: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (open) cs.secondaryContainer.copy(alpha = 0.5f) else cs.surfaceContainerHigh)
            .animateContentSize()
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Done editing" else "Edit ${e.foodName}", onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(e.foodName + (e.brandName?.let { " · $it" } ?: ""), color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Portion(e.quantity, e.unit).label, color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${e.calories.roundToInt()} kcal", color = cs.onSurfaceVariant, fontSize = 13.sp)
                if (state.config.showCosts) state.costs[e.id]?.let { Text(com.bytelog.app.data.repository.ShoppingList.money(it), color = cs.tertiary, fontSize = 12.sp) }
            }
        }
        if (open) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Amount
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val step = Portions.step(e.unit, e.quantity)
                    RoundButton(Icons.Default.Remove, "Less ${e.foodName}") {
                        viewModel.save(viewModel.withAmount(e, (e.quantity - step).coerceAtLeast(step), e.unit))
                    }
                    Text(
                        Portion(e.quantity, e.unit).label, color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    RoundButton(Icons.Default.Add, "More ${e.foodName}") { viewModel.save(viewModel.withAmount(e, e.quantity + step, e.unit)) }
                }
                val units = viewModel.unitsFor(e)
                if (units.size > 1) {
                    Label("Unit")
                    ChipRow(units, selected = e.unit) { u -> viewModel.save(viewModel.inUnit(e, u)) }
                }
                Label("Meal")
                ChipRow(state.mealTypes.map { it.name }, selected = e.mealName) { name ->
                    state.mealTypes.firstOrNull { it.name == name }?.let { viewModel.moveToMeal(e, it) }
                }
                Label("Day")
                val days = state.days.map { it.date }
                ChipRow(days.map { shortDay(it, today) }, selected = shortDay(LocalDate.parse(e.entryDate), today)) { label ->
                    days.firstOrNull { shortDay(it, today) == label }?.let { viewModel.moveTo(e, it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!LocalDate.parse(e.entryDate).isAfter(today)) {
                        Pill("Ate it", false) { viewModel.eat(listOf(e)) }
                    }
                    Pill("Remove", false, onClick = onRemove)
                    Spacer(Modifier.weight(1f))
                    Pill("Done", true, onClick = onToggle)
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(options: List<String>, selected: String?, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> Pill(o, selected != null && o.equals(selected, ignoreCase = true)) { onPick(o) } }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(cs.primaryContainer).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(20.dp)) }
}
