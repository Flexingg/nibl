package com.bytelog.app.ui.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.bytelog.app.data.caffeine.CaffeineStatus
import com.bytelog.app.data.fasting.FastingLog
import com.bytelog.app.data.local.model.DailySummary
import com.bytelog.app.data.repository.BatchStatus
import com.bytelog.app.data.repository.HomeTile
import com.bytelog.app.data.repository.ShoppingListResult
import com.bytelog.app.data.analytics.AdaptiveTdeeCalculation
import com.bytelog.app.data.analytics.DayChartPoint
import com.bytelog.app.data.local.model.PlannedEntry
import com.bytelog.app.data.repository.FitOption
import com.bytelog.app.ui.diary.PlanDaySummary
import com.bytelog.app.ui.theme.ByteExtraColors
import androidx.compose.ui.graphics.ImageBitmap
import java.time.LocalDate
import java.time.format.TextStyle
import com.bytelog.app.data.repository.WeeklyReview
import com.bytelog.app.ui.theme.LocalByteExtraColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the home tiles show. */
class DashboardData(
    val summary: DailySummary,
    val waterOz: Float,
    val waterGoalOz: Float,
    val drinkOz: Float,
    /** Drinks logged today. */
    val drinks: Int,
    /** The last seven days of water, oldest first: "yyyy-MM-dd" and ounces. */
    val waterWeek: List<Pair<String, Float>> = emptyList(),
    val weight: Float?,
    val weightUnit: String,
    val weightDelta: Float?,
    /** Recent weigh-ins, oldest first, in [weightUnit]. */
    val recentWeights: List<Float>,
    val weeklyReview: WeeklyReview?,
    val activeFast: FastingLog?,
    /** Since food was last eaten, when there's no fast running. */
    val sinceLastMealMs: Long?,
    val nowMs: Long,
    val caffeine: CaffeineStatus?,
    val batches: List<BatchStatus>,
    /** Family members' names with what they've eaten today and their goal. */
    val family: List<Triple<String, Float, Float?>>,
    val planned: List<PlannedEntry>,
    val weekPlan: List<PlanDaySummary>,
    val shopping: ShoppingListResult,
    val shoppingChecked: Set<String>,
    val fits: List<FitOption>,
    /** The days of the calorie and weight chart, oldest first. */
    val days: List<DayChartPoint>,
    val tdee: AdaptiveTdeeCalculation?,
    /** Logged meals: name and calories. */
    val meals: List<Pair<String, Float>>,
    /** The meal it's time for ("Lunch"). */
    val mealNow: String,
    /** Usual meals that one tap logs: meal and what. */
    val mealShortcuts: List<Pair<String, String>>,
    /** Progress photos, newest first, with a loader for each picture. */
    val photos: List<Pair<String, suspend () -> ImageBitmap?>> = emptyList(),
    val recipes: List<String> = emptyList(),
    /** The Mealie server, when one is set up. */
    val mealieHost: String? = null,
    /** Health Connect: on, when it last wrote, how many records, the last error. */
    val health: HealthSummary? = null
)

data class HealthSummary(val on: Boolean, val lastRun: Long, val written: Int, val fromSparky: Int, val error: String?)

/** Where each tile goes when tapped. */
class DashboardActions(
    val calories: () -> Unit,
    val water: () -> Unit,
    val logDrink: () -> Unit,
    val weight: () -> Unit,
    val week: () -> Unit,
    val fasting: () -> Unit,
    val caffeine: () -> Unit,
    val reports: () -> Unit,
    val photos: () -> Unit,
    val mealie: () -> Unit,
    val health: () -> Unit,
    val leftovers: () -> Unit,
    val family: () -> Unit,
    val planner: () -> Unit,
    val shopping: () -> Unit,
    val whatFits: () -> Unit,
    val quickLog: () -> Unit,
    val trends: () -> Unit,
    val meals: () -> Unit
)

private fun int(n: Float) = String.format(Locale.getDefault(), "%,d", n.roundToInt())

/** Hours and minutes, "14h 05m", or minutes under an hour. */
internal fun hoursMinutes(ms: Long): String {
    val m = (ms / 60_000L).coerceAtLeast(0L)
    return if (m < 60) "${m}m" else String.format(Locale.US, "%dh %02dm", m / 60, m % 60)
}

/** "M", "T", … for a "yyyy-MM-dd" day. */
private fun dayLetter(date: String): String =
    runCatching { LocalDate.parse(date).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }.getOrDefault("")

/** "8:15 PM" (or "20:15"). */
private fun clock(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))

/** "just now", "5 min ago", "3 h ago", "2 d ago". */
private fun ago(then: Long, now: Long): String {
    val m = ((now - then) / 60_000L).coerceAtLeast(0L)
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / 1440} d ago"
    }
}

/** Each tile's face, in the app's colours. */
@Composable
fun rememberTileFaces(data: DashboardData, actions: DashboardActions): (HomeTile) -> TileFace {
    val cs = MaterialTheme.colorScheme
    val x = LocalByteExtraColors.current
    val colors = FaceColors(
        primary = cs.primary to cs.primaryContainer,
        secondary = cs.secondary to cs.secondaryContainer,
        tertiary = cs.tertiary to cs.tertiaryContainer,
        water = x.water to x.waterContainer,
        muted = cs.onSurfaceVariant to cs.surfaceContainerHighest,
        success = x.success,
        warn = cs.error,
        extra = x,
        amber = x.carbs to x.carbs.copy(alpha = 0.2f),
        pink = x.fat to x.fat.copy(alpha = 0.2f),
        green = x.success to x.success.copy(alpha = 0.2f),
        sky = x.protein to x.protein.copy(alpha = 0.2f)
    )
    return { tile -> tileFace(tile, data, actions, colors) }
}

internal class FaceColors(
    val primary: Pair<Color, Color>, val secondary: Pair<Color, Color>, val tertiary: Pair<Color, Color>, val water: Pair<Color, Color>,
    val muted: Pair<Color, Color>, val success: Color, val warn: Color, val extra: ByteExtraColors,
    val amber: Pair<Color, Color>, val pink: Pair<Color, Color>, val green: Pair<Color, Color>, val sky: Pair<Color, Color>
)

internal fun tileFace(tile: HomeTile, d: DashboardData, a: DashboardActions, c: FaceColors): TileFace {
    fun face(
        colors: Pair<Color, Color>, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String?, detail: String?, onClick: () -> Unit,
        progress: Float? = null, action: TileAction? = null, chart: TileChart? = null, lines: List<Pair<String, String>> = emptyList(),
        fill: Float? = null
    ) = TileFace(tile.title, icon, colors.first, colors.second, value, detail, progress, action, chart, fill, lines, onClick)
    val s = d.summary
    val week = d.days.takeLast(7)
    return when (tile) {
        HomeTile.CALORIES -> {
            val left = s.targetCalories - s.totalCalories
            fun part(l: String, v: Float, t: Float, col: Color) = MacroPart(l, if (t > 0f) v / t else 0f, v.roundToInt(), col)
            face(
                c.primary, Icons.Default.LocalFireDepartment, int(s.totalCalories),
                if (left >= 0) "${int(left)} left" else "${int(-left)} over", a.calories, progress = s.calorieProgress,
                chart = TileChart.Macros(listOf(
                    part("P", s.totalProtein, s.targetProtein, c.extra.protein),
                    part("C", s.totalCarbs, s.targetCarbs, c.extra.carbs),
                    part("F", s.totalFat, s.targetFat, c.extra.fat)
                )),
                lines = d.meals.map { (name, kcal) -> name to "${int(kcal)} kcal" }
            )
        }
        HomeTile.WATER -> {
            val drunk = d.waterWeek.filter { it.second > 0f }
            val left = (d.waterGoalOz - d.waterOz).coerceAtLeast(0f)
            face(
                c.water, Icons.Default.WaterDrop, "${d.waterOz.roundToInt()} oz", "of ${d.waterGoalOz.roundToInt()}", a.water,
                action = TileAction("+${d.drinkOz.roundToInt()}", "Log ${d.drinkOz.roundToInt()} oz", a.logDrink),
                fill = if (d.waterGoalOz > 0f) d.waterOz / d.waterGoalOz else 0f,
                chart = d.waterWeek.takeIf { it.isNotEmpty() }?.let { w -> TileChart.Bars(w.map { it.second }, d.waterGoalOz, w.map { dayLetter(it.first) }) },
                lines = listOfNotNull(
                    "${d.drinks} drink${if (d.drinks == 1) "" else "s"} today" to (if (left > 0f) "${left.roundToInt()} oz to go" else "goal reached"),
                    drunk.takeIf { it.size >= 2 }?.let { "Average this week" to "${(it.sumOf { p -> p.second.toDouble() } / it.size).roundToInt()} oz" },
                    d.waterWeek.takeIf { it.isNotEmpty() }?.let { w -> "Days at goal" to "${w.count { it.second >= d.waterGoalOz && d.waterGoalOz > 0f }}/${w.size}" }
                )
            )
        }
        HomeTile.WEIGHT -> {
            val first = d.recentWeights.firstOrNull(); val last = d.recentWeights.lastOrNull()
            face(
                c.tertiary, Icons.Default.MonitorWeight,
                d.weight?.let { String.format(Locale.US, "%.1f %s", it, d.weightUnit) },
                d.weightDelta?.let { (if (it < 0) "↓" else "↑") + String.format(Locale.US, "%.1f", abs(it)) } ?: if (d.weight == null) "Tap to log" else null,
                a.weight,
                chart = d.recentWeights.takeIf { it.size >= 2 }?.let { TileChart.Spark(it) },
                lines = listOfNotNull(
                    if (first != null && last != null && d.recentWeights.size >= 2)
                        "Last ${d.recentWeights.size} weigh-ins" to String.format(Locale.US, "%+.1f %s", last - first, d.weightUnit) else null,
                    d.tdee?.weightChangeRatePerWeek?.let { "Trend per week" to String.format(Locale.US, "%+.2f %s", it, d.weightUnit) }
                ).ifEmpty { listOf("Weigh in a few days" to "", "to see your trend" to "") }
            )
        }
        HomeTile.WEEKLY_REVIEW -> {
            val r = d.weeklyReview
            face(
                c.green, Icons.Default.Insights,
                r?.let { int(it.avgCalories.toFloat()) },
                r?.let { "avg · ${it.daysOnTarget}/7 on target" } ?: "Mondays",
                a.week,
                chart = r?.let { rv ->
                    TileChart.Bars(rv.days.map { it.calories }, rv.calorieGoal,
                        rv.days.map { it.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) })
                },
                lines = listOfNotNull(
                    r?.let { "Protein" to "${it.avgProtein} g avg" },
                    r?.let { "Days logged" to "${it.daysLogged}/7" },
                    r?.let { "Streak" to "${it.streak} days" }
                )
            )
        }
        HomeTile.FASTING -> {
            val fast = d.activeFast?.takeIf { it.isActive }
            if (fast != null) {
                val target = fast.targetDurationMillis().coerceAtLeast(1L)
                val now = java.time.Instant.ofEpochMilli(d.nowMs)
                val elapsed = fast.elapsedMillis(now)
                val ends = fast.startInstant()?.toEpochMilli()?.plus(target)
                face(
                    c.success to c.secondary.second, Icons.Default.Timer, hoursMinutes(elapsed), "of ${hoursMinutes(target)}", a.fasting,
                    progress = elapsed.toFloat() / target,
                    chart = TileChart.Meter(elapsed.toFloat() / target,
                        if (elapsed >= target) "Goal reached" else "${hoursMinutes(target - elapsed)} to go"),
                    lines = listOfNotNull(
                        "Plan" to fast.fastingType,
                        fast.startInstant()?.let { "Started" to clock(it.toEpochMilli()) },
                        ends?.let { "Ends" to clock(it) }
                    )
                )
            } else {
                face(
                    c.muted, Icons.Default.Timer, d.sinceLastMealMs?.let { hoursMinutes(it) }, if (d.sinceLastMealMs != null) "since food" else "Not fasting", a.fasting,
                    lines = listOf("Tap to start a fast" to "")
                )
            }
        }
        HomeTile.CAFFEINE -> {
            val cs = d.caffeine
            val mg = cs?.activeMgNow ?: 0f
            val late = cs?.cutoffState?.let { it != "safe" } == true
            face(
                if (late) c.warn to c.muted.second else c.amber, Icons.Default.Coffee, "${mg.roundToInt()} mg", if (late) "past cutoff" else "active", a.caffeine,
                chart = TileChart.Meter(mg / 400f, cs?.let { "${it.atBedtimeMg.roundToInt()} mg at bedtime" }),
                lines = listOfNotNull(
                    cs?.let { "Today" to "${it.doses.size} dose${if (it.doses.size == 1) "" else "s"}" },
                    cs?.latestSafeDoseTime?.let { "Last safe dose" to it.take(5) },
                    cs?.let { "Bedtime" to it.targetBedtime.take(5) }
                )
            )
        }
        HomeTile.REPORTS -> {
            val logged = week.filter { it.calories > 0f }
            val avg = if (logged.isEmpty()) null else logged.sumOf { it.calories.toDouble() }.toFloat() / logged.size
            face(
                c.primary, Icons.Default.Assessment, avg?.let { int(it) }, if (avg != null) "kcal a day" else "Trends and progress", a.reports,
                chart = week.takeIf { it.isNotEmpty() }?.let { w -> TileChart.Bars(w.map { it.calories }, w.last().targetCalories, w.map { dayLetter(it.date) }) },
                lines = listOfNotNull(
                    "Days logged this week" to "${logged.size}/7",
                    d.tdee?.let { "Maintenance (TDEE)" to int(it.tdee) },
                    d.tdee?.weightChangeRatePerWeek?.let { "Weight trend" to String.format(Locale.US, "%+.2f %s/wk", it, d.weightUnit) }
                )
            )
        }
        HomeTile.PROGRESS_PHOTOS -> {
            val latest = d.photos.firstOrNull()?.first
            face(
                c.tertiary, Icons.Default.PhotoCamera,
                if (d.photos.isEmpty()) null else "${d.photos.size}",
                latest?.let { "last ${shortDate(it)}" } ?: "Take your first", a.photos,
                chart = d.photos.takeIf { it.isNotEmpty() }?.let { p -> TileChart.Photos(p.size, p.map { it.second }) },
                lines = if (d.photos.isEmpty()) listOf("Front, side and back" to "", "Compare over time" to "") else emptyList()
            )
        }
        HomeTile.MEALIE -> face(
            c.green, Icons.Default.MenuBook,
            if (d.recipes.isEmpty()) null else "${d.recipes.size}",
            if (d.recipes.isEmpty()) (if (d.mealieHost != null) "Browse recipes" else "Recipes") else "recipes",
            a.mealie,
            lines = d.recipes.map { it to "" }.ifEmpty { listOfNotNull(d.mealieHost?.let { "Connected" to it }, "Import from Mealie" to "") }
        )
        HomeTile.HEALTH_CONNECT -> {
            val h = d.health
            face(
                if (h?.on == true) c.success to c.muted.second else c.warn to c.muted.second, Icons.Default.Favorite,
                when { h == null || !h.on -> "Off"; h.lastRun > 0 -> "On"; else -> "On" },
                when { h == null || !h.on -> "Tap to set up"; h.error != null -> "Couldn't write"; h.lastRun > 0 -> "synced ${ago(h.lastRun, d.nowMs)}"; else -> "Not synced yet" },
                a.health,
                lines = listOfNotNull(
                    h?.takeIf { it.on }?.let { "Written by Nibl" to "${it.written}" },
                    h?.takeIf { it.on && it.fromSparky > 0 }?.let { "Already from SparkyFitness" to "${it.fromSparky}" },
                    h?.error?.let { it to "" }
                ).ifEmpty { listOf("Share food and water" to "") }
            )
        }
        HomeTile.LEFTOVERS -> face(
            c.pink, Icons.Default.Kitchen,
            if (d.batches.isEmpty()) null else "${d.batches.size}",
            if (d.batches.isEmpty()) "Nothing cooked" else "in the fridge", a.leftovers,
            lines = d.batches.map { b ->
                b.batch.name to (b.remainingPortions?.let { String.format(Locale.US, "%.1f left", it).replace(".0 ", " ") } ?: "${(b.remainingFraction * 100).roundToInt()}%")
            }
        )
        HomeTile.FAMILY -> face(
            c.primary, Icons.Default.People,
            if (d.family.isNotEmpty()) "${d.family.size}" else null, if (d.family.isNotEmpty()) "sharing" else "No one yet", a.family,
            chart = d.family.takeIf { f -> f.size >= 2 && f.any { it.third != null } }?.let { f ->
                TileChart.Bars(f.map { m -> m.third?.takeIf { it > 0f }?.let { m.second / it } ?: 0f }, 1f, f.map { it.first.take(1) }, highlight = -2)
            },
            lines = d.family.map { (name, kcal, goal) -> name to (int(kcal) + (goal?.let { " / ${int(it)}" } ?: "")) }
        )
        HomeTile.PLANNED -> face(
            c.sky, Icons.Default.EventNote,
            if (d.planned.isNotEmpty()) "${d.planned.size}" else null,
            if (d.planned.isNotEmpty()) "${int(d.planned.sumOf { it.calories.toDouble() }.toFloat())} kcal planned" else "Nothing today", a.planner,
            lines = d.planned.map { it.foodName to "${int(it.calories)} kcal" }
        )
        HomeTile.MEALS -> face(
            c.primary, Icons.Default.Restaurant,
            if (d.meals.isNotEmpty()) "${d.meals.size} meal${if (d.meals.size == 1) "" else "s"}" else null,
            if (d.meals.isNotEmpty()) "${int(d.meals.sumOf { it.second.toDouble() }.toFloat())} kcal" else "Always shown", a.meals,
            lines = d.meals.map { (n, k) -> n to "${int(k)} kcal" }
        )
        HomeTile.MEAL_PLAN -> {
            val planned = d.weekPlan.count { it.foods > 0 }
            face(
                c.tertiary, Icons.Default.CalendarMonth, "$planned/${d.weekPlan.size.coerceAtLeast(7)}", "days planned", a.planner,
                chart = d.weekPlan.takeIf { it.isNotEmpty() }?.let { w ->
                    TileChart.Bars(w.map { it.calories }, s.targetCalories.takeIf { it > 0f }, w.map { it.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }, highlight = 0)
                },
                lines = d.weekPlan.filter { it.foods > 0 }.map {
                    it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) to "${it.foods} foods · ${int(it.calories)} kcal"
                }
            )
        }
        HomeTile.SHOPPING -> {
            val left = d.shopping.items.filter { it.key !in d.shoppingChecked }
            val cost = left.sumOf { it.cost ?: 0.0 }
            face(
                c.primary, Icons.Default.ShoppingCart,
                if (d.shopping.items.isEmpty()) null else "${left.size}",
                when {
                    d.shopping.items.isEmpty() -> "Nothing to buy"
                    cost > 0.0 -> "to get · ${com.bytelog.app.data.repository.ShoppingList.money(cost)}"
                    else -> "to get"
                },
                a.shopping,
                chart = if (d.shopping.items.isNotEmpty()) TileChart.Meter(1f - left.size.toFloat() / d.shopping.items.size, "${d.shopping.items.size - left.size} of ${d.shopping.items.size} got") else null,
                lines = left.map { it.name to (it.cost?.let { c -> com.bytelog.app.data.repository.ShoppingList.money(c) } ?: "") }
                    .ifEmpty { if (d.shopping.items.isEmpty()) listOf("Plan meals for the week" to "", "and the list fills itself" to "") else emptyList() }
            )
        }
        HomeTile.WHAT_FITS -> {
            val left = s.targetCalories - s.totalCalories
            face(
                c.tertiary, Icons.Default.TipsAndUpdates, int(left.coerceAtLeast(0f)), "kcal left", a.whatFits,
                lines = d.fits.map { it.label to "${int(it.calories)} kcal" }.ifEmpty { listOf("Log a little more to get ideas" to "") }
            )
        }
        HomeTile.QUICK_LOG -> face(
            c.primary, Icons.Default.RestaurantMenu, d.mealNow, "log it", a.quickLog,
            lines = d.mealShortcuts.map { (meal, foods) -> foods to "yesterday's ${meal.lowercase()}" }.ifEmpty { listOf("Search, scan or snap a photo" to "") }
        )
        HomeTile.TRENDS -> {
            val weights = d.days.mapNotNull { it.smoothedWeight ?: it.actualWeight }
            face(
                c.tertiary, Icons.Default.Insights, d.tdee?.let { int(it.tdee) }, "kcal TDEE", a.trends,
                chart = weights.takeIf { it.size >= 2 }?.let { TileChart.Spark(it) }
                    ?: d.days.takeLast(14).takeIf { it.isNotEmpty() }?.let { w -> TileChart.Bars(w.map { it.calories }, w.last().targetCalories) },
                lines = listOfNotNull(
                    d.tdee?.let { "Eat for your goal" to "${int(it.recommendedCalories)} kcal" },
                    d.tdee?.avgDailyIntake?.let { "Average intake" to "${int(it)} kcal" },
                    d.tdee?.let { "Confidence" to it.confidence.lowercase().replaceFirstChar { ch -> ch.uppercase() } }
                )
            )
        }
    }
}

/** "Nov 20" for a "yyyy-MM-dd" date. */
private fun shortDate(date: String): String =
    runCatching { LocalDate.parse(date.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())) }.getOrDefault(date)
