package com.bytelog.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.bytelog.app.ByteLogApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/** One home screen widget: redraws itself when the launcher asks (periodically, on resize, after a restart). */
abstract class BaseWidgetProvider(private val kind: WidgetKind) : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refresh(context, appWidgetIds)

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) =
        refresh(context, intArrayOf(appWidgetId))

    private fun refresh(context: Context, ids: IntArray) {
        val app = context.applicationContext as ByteLogApplication
        val pending = goAsync()
        app.applicationScope.launch(Dispatchers.Default) {
            try {
                if (kind == WidgetKind.FAMILY) runCatching { WidgetDataLoader.refreshFamily(app) }
                WidgetUpdater.update(context, kind, ids)
            } finally {
                pending?.finish()
            }
        }
    }
}

class TodayWidgetProvider : BaseWidgetProvider(WidgetKind.TODAY)
class CaloriesWidgetProvider : BaseWidgetProvider(WidgetKind.CALORIES)
class WaterWidgetProvider : BaseWidgetProvider(WidgetKind.WATER)
class QuickLogWidgetProvider : BaseWidgetProvider(WidgetKind.QUICK_LOG)
class FamilyWidgetProvider : BaseWidgetProvider(WidgetKind.FAMILY)

/** Taps that do something without opening the app (adding a bottle of water). */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetLinks.ACTION_ADD_WATER) return
        val app = context.applicationContext as ByteLogApplication
        val pending = goAsync()
        app.applicationScope.launch(Dispatchers.IO) {
            try {
                val oz = app.settingsRepository.defaultContainerOz.first()
                val name = app.settingsRepository.defaultContainerName.first()
                app.waterRepository.quickAddWaterOz(LocalDate.now().toString(), oz, containerName = name)
                delay(400)   // the drink is saved on its own thread; the widget then shows it
                WidgetUpdater.updateAll(context)
            } finally {
                pending?.finish()
            }
        }
    }
}
