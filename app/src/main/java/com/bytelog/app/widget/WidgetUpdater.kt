package com.bytelog.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.bytelog.app.ByteLogApplication
import com.bytelog.app.MainActivity
import com.bytelog.app.R
import com.bytelog.app.ui.theme.DarkExtraColors
import com.bytelog.app.ui.theme.LightExtraColors
import com.bytelog.app.ui.theme.byteColorSchemeFor
import kotlinx.coroutines.flow.first
import kotlin.math.min
import kotlin.math.roundToInt

fun WidgetKind.providerClass(): Class<*> = when (this) {
    WidgetKind.TODAY -> TodayWidgetProvider::class.java
    WidgetKind.CALORIES -> CaloriesWidgetProvider::class.java
    WidgetKind.WATER -> WaterWidgetProvider::class.java
    WidgetKind.QUICK_LOG -> QuickLogWidgetProvider::class.java
    WidgetKind.FAMILY -> FamilyWidgetProvider::class.java
}

private fun WidgetKind.layout(): Int = when (this) {
    WidgetKind.TODAY -> R.layout.widget_today
    WidgetKind.CALORIES -> R.layout.widget_calories
    WidgetKind.WATER -> R.layout.widget_water
    WidgetKind.QUICK_LOG -> R.layout.widget_quick_log
    WidgetKind.FAMILY -> R.layout.widget_family
}

/** Draws the widgets and hands them to the launcher, with taps wired to the app. */
object WidgetUpdater {
    /** Updates every widget on the home screen from the current data (skips kinds that aren't placed). */
    suspend fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val placed = WidgetKind.values().mapNotNull { kind ->
            val ids = mgr.getAppWidgetIds(ComponentName(context, kind.providerClass()))
            if (ids.isEmpty()) null else kind to ids
        }
        if (placed.isEmpty()) return
        val data = WidgetDataLoader.load(context.applicationContext as ByteLogApplication)
        placed.forEach { (kind, ids) -> update(context, kind, ids, data) }
    }

    suspend fun update(context: Context, kind: WidgetKind, ids: IntArray, data: WidgetData? = null) {
        val app = context.applicationContext as ByteLogApplication
        val d = data ?: WidgetDataLoader.load(app)
        val mgr = AppWidgetManager.getInstance(context)
        val dynamic = app.settingsRepository.useDeviceColors.first()
        val light = WidgetColors.from(byteColorSchemeFor(context, false, dynamic), LightExtraColors, dark = false)
        val dark = WidgetColors.from(byteColorSchemeFor(context, true, dynamic), DarkExtraColors, dark = true)

        ids.forEach { id ->
            val (w, h) = sizeFor(context, mgr, id, kind)
            val views = RemoteViews(context.packageName, kind.layout())
            // Both looks are sent; the layout for the launcher's current day/night mode shows the right one
            views.setImageViewBitmap(R.id.img_light, WidgetRenderer.render(kind, d, light, w, h))
            views.setImageViewBitmap(R.id.img_dark, WidgetRenderer.render(kind, d, dark, w, h))
            val description = describe(kind, d)
            views.setContentDescription(R.id.img_light, description)
            views.setContentDescription(R.id.img_dark, description)
            wireTaps(context, views, kind, d)
            mgr.updateAppWidget(id, views)
        }
    }

    private fun describe(kind: WidgetKind, d: WidgetData) = when (kind) {
        WidgetKind.TODAY -> "Today: ${d.eaten} of ${d.goal} calories, ${d.waterOz.roundToInt()} of ${d.waterGoalOz.roundToInt()} ounces of water"
        WidgetKind.CALORIES -> "Calories today: ${d.eaten} of ${d.goal}, ${d.left} left"
        WidgetKind.WATER -> "Water today: ${d.waterOz.roundToInt()} of ${d.waterGoalOz.roundToInt()} ounces. Tap to add ${d.bottleOz.roundToInt()} ounces"
        WidgetKind.QUICK_LOG -> "Quick log a meal"
        WidgetKind.FAMILY -> "Family today: " + d.family.joinToString(", ") { "${it.name} ${it.kcal} calories" }
    }

    /** Widget size in pixels from what the launcher reports, kept to a sane bitmap size. */
    private fun sizeFor(context: Context, mgr: AppWidgetManager, id: Int, kind: WidgetKind): Pair<Int, Int> {
        val o = mgr.getAppWidgetOptions(id)
        val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 }?.toFloat() ?: kind.baseW
        val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 }?.toFloat() ?: kind.baseH
        var density = min(context.resources.displayMetrics.density, 3f)
        while (wDp * density * hDp * density * 4f * 2f > 6_000_000f && density > 1f) density -= 0.25f
        return (wDp * density).roundToInt().coerceAtLeast(1) to (hDp * density).roundToInt().coerceAtLeast(1)
    }

    private fun open(context: Context, dest: WidgetDestination): PendingIntent {
        val code = WidgetLinks.encode(dest)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = WidgetLinks.ACTION_OPEN
            putExtra(WidgetLinks.EXTRA_DEST, code)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(context, code.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun addWater(context: Context): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java).setAction(WidgetLinks.ACTION_ADD_WATER)
        return PendingIntent.getBroadcast(context, 7001, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun wireTaps(context: Context, v: RemoteViews, kind: WidgetKind, d: WidgetData) {
        val meal = d.suggestedMeal
        when (kind) {
            WidgetKind.TODAY -> {
                v.setOnClickPendingIntent(R.id.tap_open, open(context, WidgetDestination.Diary))
                v.setOnClickPendingIntent(R.id.tap_water, addWater(context))
                v.setOnClickPendingIntent(R.id.tap_add, open(context, WidgetDestination.Search(meal)))
            }
            WidgetKind.CALORIES -> v.setOnClickPendingIntent(R.id.tap_open, open(context, WidgetDestination.Diary))
            WidgetKind.WATER -> v.setOnClickPendingIntent(R.id.tap_water, addWater(context))
            WidgetKind.QUICK_LOG -> {
                v.setOnClickPendingIntent(R.id.tap_breakfast, open(context, WidgetDestination.Search("Breakfast")))
                v.setOnClickPendingIntent(R.id.tap_lunch, open(context, WidgetDestination.Search("Lunch")))
                v.setOnClickPendingIntent(R.id.tap_dinner, open(context, WidgetDestination.Search("Dinner")))
                v.setOnClickPendingIntent(R.id.tap_snack, open(context, WidgetDestination.Search("Snack")))
                v.setOnClickPendingIntent(R.id.tap_scan, open(context, WidgetDestination.Scan(meal)))
            }
            WidgetKind.FAMILY -> {
                v.setOnClickPendingIntent(R.id.tap_open, open(context, WidgetDestination.Family))
                v.setOnClickPendingIntent(R.id.tap_share, open(context, WidgetDestination.Share(meal)))
            }
        }
    }
}
