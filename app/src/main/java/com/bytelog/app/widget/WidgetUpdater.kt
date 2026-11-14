package com.bytelog.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews
import com.bytelog.app.ByteLogApplication
import com.bytelog.app.MainActivity
import com.bytelog.app.R
import com.bytelog.app.ui.theme.DarkExtraColors
import com.bytelog.app.ui.theme.LightExtraColors
import com.bytelog.app.ui.theme.byteColorSchemeFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.math.roundToInt

fun WidgetKind.providerClass(): Class<out android.appwidget.AppWidgetProvider> = when (this) {
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
    /** One update at a time, so two can't interleave and the older data can't land last. */
    private val lock = Mutex()

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

        val frames = if (kind == WidgetKind.WATER) waterFrames(context) else 1
        lock.withLock { ids.forEach { id ->
            val (w, h) = sizeFor(context, mgr, id, kind, frames)
            val views = RemoteViews(context.packageName, kind.layout())
            val description = describe(kind, d)
            // Both looks are sent; the layout for the launcher's current day/night mode shows the right one
            if (kind == WidgetKind.WATER) {
                // The wave moves: each look is a loop of frames, flipped through by the launcher
                for ((flip, colors) in listOf(R.id.flip_light to light, R.id.flip_dark to dark)) {
                    views.removeAllViews(flip)
                    repeat(frames) { f ->
                        val phase = (2 * Math.PI * f / frames).toFloat()
                        val frame = RemoteViews(context.packageName, R.layout.widget_frame)
                        frame.setImageViewBitmap(R.id.frame, WidgetRenderer.render(kind, d, colors, w, h, phase))
                        views.addView(flip, frame)
                    }
                    views.setContentDescription(flip, description)
                }
            } else {
                views.setImageViewBitmap(R.id.img_light, WidgetRenderer.render(kind, d, light, w, h))
                views.setImageViewBitmap(R.id.img_dark, WidgetRenderer.render(kind, d, dark, w, h))
                views.setContentDescription(R.id.img_light, description)
                views.setContentDescription(R.id.img_dark, description)
            }
            wireTaps(context, views, kind, d)
            mgr.updateAppWidget(id, views)
        } }
    }

    private fun describe(kind: WidgetKind, d: WidgetData) = when (kind) {
        WidgetKind.TODAY -> "Today: ${d.eaten} of ${d.goal} calories, ${d.waterOz.roundToInt()} of ${d.waterGoalOz.roundToInt()} ounces of water"
        WidgetKind.CALORIES -> "Calories today: ${d.eaten} of ${d.goal}, ${d.left} left"
        WidgetKind.WATER -> "Water today: ${d.waterOz.roundToInt()} of ${d.waterGoalOz.roundToInt()} ounces. Tap to add ${d.bottleOz.roundToInt()} ounces"
        WidgetKind.QUICK_LOG -> "Quick log a meal"
        WidgetKind.FAMILY -> "Family today: " + d.family.joinToString(", ") { "${it.name} ${it.kcal} calories" }
    }

    /**
     * Frames in the water widget's wave loop: none moving (one frame) when the phone's animations are switched off,
     * which is also what people who find motion uncomfortable choose.
     */
    private fun waterFrames(context: Context): Int {
        val scale = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
        return if (scale == 0f) 1 else WidgetRenderer.WAVE_FRAMES
    }

    /**
     * Widget size in pixels from what the launcher reports, kept to a sane bitmap size. All the bitmaps in one update
     * (two looks of [frames] each) have to fit the launcher's limit, about 1.5 screens' worth, so animated widgets
     * are drawn a little less sharp.
     */
    private fun sizeFor(context: Context, mgr: AppWidgetManager, id: Int, kind: WidgetKind, frames: Int = 1): Pair<Int, Int> {
        val o = mgr.getAppWidgetOptions(id)
        val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 }?.toFloat() ?: kind.baseW
        val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 }?.toFloat() ?: kind.baseH
        val dm = context.resources.displayMetrics
        val budget = min(if (frames > 1) 9_000_000f else 6_000_000f, 0.6f * dm.widthPixels * dm.heightPixels * 4f * 1.5f).coerceAtLeast(1_000_000f)
        var density = min(dm.density, 3f)
        while (wDp * density * hDp * density * 4f * 2f * frames > budget && density > 1f) density -= 0.25f
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
