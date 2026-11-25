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
    val weight: Float?,
    val weightUnit: String,
    val weightDelta: Float?,
    val weeklyReview: WeeklyReview?,
    val activeFast: FastingLog?,
    /** Since food was last eaten, when there's no fast running. */
    val sinceLastMealMs: Long?,
    val nowMs: Long,
    val caffeine: CaffeineStatus?,
    val batches: List<BatchStatus>,
    val familyCount: Int,
    val plannedCount: Int,
    val plannedCalories: Float,
    val daysPlanned: Int,
    val shopping: ShoppingListResult,
    val shoppingChecked: Set<String>,
    val fitCount: Int,
    val tdee: Float?,
    val mealsLogged: Int,
    val mealsCalories: Float
)

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

/** Each tile's face, in the app's colours. */
@Composable
fun rememberTileFaces(data: DashboardData, actions: DashboardActions): (HomeTile) -> TileFace {
    val cs = MaterialTheme.colorScheme
    val x = LocalByteExtraColors.current
    val primary = cs.primary to cs.primaryContainer
    val secondary = cs.secondary to cs.secondaryContainer
    val tertiary = cs.tertiary to cs.tertiaryContainer
    val water = x.water to x.waterContainer
    val muted = cs.onSurfaceVariant to cs.surfaceContainerHighest
    return { tile -> tileFace(tile, data, actions, primary, secondary, tertiary, water, muted, x.success, cs.error) }
}

internal fun tileFace(
    tile: HomeTile, d: DashboardData, a: DashboardActions,
    primary: Pair<Color, Color>, secondary: Pair<Color, Color>, tertiary: Pair<Color, Color>, water: Pair<Color, Color>,
    muted: Pair<Color, Color>, success: Color, warn: Color
): TileFace {
    fun face(colors: Pair<Color, Color>, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String?, detail: String?, onClick: () -> Unit, progress: Float? = null, action: TileAction? = null) =
        TileFace(tile.title, icon, colors.first, colors.second, value, detail, progress, action, onClick)
    val s = d.summary
    return when (tile) {
        HomeTile.CALORIES -> {
            val left = s.targetCalories - s.totalCalories
            face(
                primary, Icons.Default.LocalFireDepartment, int(s.totalCalories),
                if (left >= 0) "${int(left)} left" else "${int(-left)} over", a.calories, progress = s.calorieProgress
            )
        }
        HomeTile.WATER -> face(
            water, Icons.Default.WaterDrop, "${d.waterOz.roundToInt()} oz", "of ${d.waterGoalOz.roundToInt()}", a.water,
            progress = if (d.waterGoalOz > 0f) d.waterOz / d.waterGoalOz else 0f,
            action = TileAction("+${d.drinkOz.roundToInt()}", "Log ${d.drinkOz.roundToInt()} oz", a.logDrink)
        )
        HomeTile.WEIGHT -> face(
            tertiary, Icons.Default.MonitorWeight,
            d.weight?.let { String.format(Locale.US, "%.1f %s", it, d.weightUnit) },
            d.weightDelta?.let { (if (it < 0) "↓" else "↑") + String.format(Locale.US, "%.1f", abs(it)) } ?: if (d.weight == null) "Tap to log" else null,
            a.weight
        )
        HomeTile.WEEKLY_REVIEW -> face(
            secondary, Icons.Default.Insights,
            d.weeklyReview?.let { "${int(it.avgCalories.toFloat())} avg" },
            d.weeklyReview?.let { "${it.daysOnTarget}/7 on target" } ?: "Mondays",
            a.week
        )
        HomeTile.FASTING -> {
            val fast = d.activeFast?.takeIf { it.isActive }
            if (fast != null) {
                val target = fast.targetDurationMillis().coerceAtLeast(1L)
                val elapsed = fast.elapsedMillis(java.time.Instant.ofEpochMilli(d.nowMs))
                face(success to secondary.second, Icons.Default.Timer, hoursMinutes(elapsed), "of ${hoursMinutes(target)}", a.fasting, progress = elapsed.toFloat() / target)
            } else {
                face(muted, Icons.Default.Timer, d.sinceLastMealMs?.let { hoursMinutes(it) }, if (d.sinceLastMealMs != null) "since food" else "Not fasting", a.fasting)
            }
        }
        HomeTile.CAFFEINE -> {
            val mg = d.caffeine?.activeMgNow ?: 0f
            val late = d.caffeine?.cutoffState?.let { it != "safe" } == true
            face(if (late) warn to muted.second else secondary, Icons.Default.Coffee, "${mg.roundToInt()} mg", if (late) "past cutoff" else "active", a.caffeine)
        }
        HomeTile.REPORTS -> face(primary, Icons.Default.Assessment, null, "Trends and progress", a.reports)
        HomeTile.PROGRESS_PHOTOS -> face(tertiary, Icons.Default.PhotoCamera, null, "Progress photos", a.photos)
        HomeTile.MEALIE -> face(secondary, Icons.Default.MenuBook, null, "Recipes", a.mealie)
        HomeTile.HEALTH_CONNECT -> face(warn to muted.second, Icons.Default.Favorite, null, "Health Connect", a.health)
        HomeTile.LEFTOVERS -> face(
            secondary, Icons.Default.Kitchen,
            if (d.batches.isEmpty()) null else "${d.batches.size} in fridge",
            d.batches.firstOrNull()?.batch?.name ?: "Nothing cooked", a.leftovers
        )
        HomeTile.FAMILY -> face(primary, Icons.Default.People, if (d.familyCount > 0) "${d.familyCount}" else null, if (d.familyCount > 0) "sharing" else "No one yet", a.family)
        HomeTile.PLANNED -> face(
            secondary, Icons.Default.EventNote,
            if (d.plannedCount > 0) "${d.plannedCount} planned" else null,
            if (d.plannedCount > 0) "${int(d.plannedCalories)} kcal" else "Nothing today", a.planner
        )
        HomeTile.MEALS -> face(
            primary, Icons.Default.Restaurant,
            if (d.mealsLogged > 0) "${d.mealsLogged} meal${if (d.mealsLogged == 1) "" else "s"}" else null,
            if (d.mealsLogged > 0) "${int(d.mealsCalories)} kcal" else "Always shown", a.meals
        )
        HomeTile.MEAL_PLAN -> face(tertiary, Icons.Default.CalendarMonth, "${d.daysPlanned}/7", "days planned", a.planner)
        HomeTile.SHOPPING -> {
            val left = d.shopping.items.count { it.key !in d.shoppingChecked }
            face(primary, Icons.Default.ShoppingCart, if (d.shopping.items.isEmpty()) null else "$left to get", if (d.shopping.items.isEmpty()) "Nothing to buy" else "${d.shopping.items.size} on list", a.shopping)
        }
        HomeTile.WHAT_FITS -> {
            val left = s.targetCalories - s.totalCalories
            face(tertiary, Icons.Default.TipsAndUpdates, "${int(left.coerceAtLeast(0f))} left", if (d.fitCount > 0) "${d.fitCount} ideas" else "Ideas", a.whatFits)
        }
        HomeTile.QUICK_LOG -> face(primary, Icons.Default.RestaurantMenu, null, "Log a meal", a.quickLog)
        HomeTile.TRENDS -> face(tertiary, Icons.Default.Insights, d.tdee?.let { int(it) }, "TDEE", a.trends)
    }
}
