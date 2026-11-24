package com.bytelog.app.tiles

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bytelog.app.ByteLogApplication
import com.bytelog.app.R
import com.bytelog.app.widget.WidgetDataLoader
import com.bytelog.app.widget.WidgetDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile displaying today's remaining calories right in the shade
 * and opening Nibl Diary on tap.
 */
class CaloriesLeftTileService : TileService() {

    private var tileJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val app = applicationContext as? ByteLogApplication ?: return
        tileJob?.cancel()
        tileJob = app.applicationScope.launch(Dispatchers.IO) {
            val data = runCatching { WidgetDataLoader.load(app) }.getOrNull()
            withContext(Dispatchers.Main) {
                val tile = qsTile ?: return@withContext
                tile.icon = Icon.createWithResource(this@CaloriesLeftTileService, R.drawable.ic_tile_calories)
                if (data != null) {
                    val over = data.eaten - data.goal
                    tile.label = if (over > 0 && data.goal > 0) {
                        "$over kcal over"
                    } else {
                        "${data.left} kcal left"
                    }
                    tile.state = if (data.eaten > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "${data.eaten} / ${data.goal} kcal"
                    }
                } else {
                    tile.label = "Calories"
                    tile.state = Tile.STATE_INACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "Open Nibl"
                    }
                }
                tile.updateTile()
            }
        }
    }

    override fun onClick() {
        super.onClick()
        launchWidgetDestination(WidgetDestination.Diary)
    }

    override fun onStopListening() {
        super.onStopListening()
        tileJob?.cancel()
    }
}
