package com.bytelog.app.tiles

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.bytelog.app.MainActivity
import com.bytelog.app.widget.WidgetDestination
import com.bytelog.app.widget.WidgetLinks

/**
 * Helper to request Android Quick Settings tile refreshes whenever diary, water, or goals change.
 * Wave 2 can call [requestTileUpdates] from `WidgetSync.kt`.
 */
object QuickSettingsTileUpdater {

    fun requestTileUpdates(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            TileService.requestListeningState(
                appContext,
                ComponentName(appContext, CaloriesLeftTileService::class.java)
            )
        }
        runCatching {
            TileService.requestListeningState(
                appContext,
                ComponentName(appContext, LogWaterTileService::class.java)
            )
        }
    }
}

/**
 * Opens Nibl directly to a [WidgetDestination] from a Quick Settings tile,
 * cleanly handling Android 14+ (API 34) `PendingIntent` vs legacy `Intent`.
 */
internal fun TileService.launchWidgetDestination(dest: WidgetDestination) {
    val code = WidgetLinks.encode(dest)
    val intent = Intent(this, MainActivity::class.java).apply {
        action = WidgetLinks.ACTION_OPEN
        putExtra(WidgetLinks.EXTRA_DEST, code)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            code.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        startActivityAndCollapse(pendingIntent)
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}
