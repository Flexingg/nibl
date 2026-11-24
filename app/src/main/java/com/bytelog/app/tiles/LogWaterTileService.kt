package com.bytelog.app.tiles

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bytelog.app.ByteLogApplication
import com.bytelog.app.R
import com.bytelog.app.widget.WidgetDataLoader
import com.bytelog.app.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Quick Settings tile that logs the user's default water container silently in the background
 * without opening the app, then shows a brief confirmation in the tile subtitle.
 */
class LogWaterTileService : TileService() {

    private var tileJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val app = applicationContext as? ByteLogApplication ?: return
        tileJob?.cancel()
        tileJob = app.applicationScope.launch(Dispatchers.IO) {
            val data = runCatching { WidgetDataLoader.load(app) }.getOrNull()
            withContext(Dispatchers.Main) {
                val tile = qsTile ?: return@withContext
                tile.icon = Icon.createWithResource(this@LogWaterTileService, R.drawable.ic_tile_water)
                if (data != null) {
                    val bottle = data.bottleOz.roundToInt()
                    val current = data.waterOz.roundToInt()
                    val goal = data.waterGoalOz.roundToInt()
                    tile.label = "+$bottle oz Water"
                    tile.state = if (goal > 0 && current >= goal) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "$current/$goal oz"
                    }
                } else {
                    tile.label = "Log Water"
                    tile.state = Tile.STATE_INACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "Tap to log"
                    }
                }
                tile.updateTile()
            }
        }
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext as? ByteLogApplication ?: return
        tileJob?.cancel()
        tileJob = app.applicationScope.launch(Dispatchers.IO) {
            val defaultOz = app.settingsRepository.defaultContainerOz.first()
            val containerName = app.settingsRepository.defaultContainerName.first()
            val before = runCatching { WidgetDataLoader.load(app) }.getOrNull()

            app.waterRepository.quickAddWaterOz(
                date = LocalDate.now().toString(),
                amountOz = defaultOz,
                containerName = containerName
            )

            // Optimistic confirmation immediately
            val addedOz = defaultOz.roundToInt()
            val optimisticTotal = ((before?.waterOz ?: 0f) + defaultOz).roundToInt()
            val goalOz = (before?.waterGoalOz ?: app.settingsRepository.targetWaterOz.first()).roundToInt()

            withContext(Dispatchers.Main) {
                qsTile?.apply {
                    icon = Icon.createWithResource(this@LogWaterTileService, R.drawable.ic_tile_water)
                    label = "Logged +$addedOz oz"
                    state = Tile.STATE_ACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = "+$addedOz oz ($optimisticTotal/$goalOz oz)"
                    }
                    updateTile()
                }
            }

            // Allow Room insert to settle, then sync widgets and restore normal tile label
            delay(400)
            runCatching { WidgetUpdater.updateAll(applicationContext) }
            val after = runCatching { WidgetDataLoader.load(app) }.getOrNull()

            delay(1800)
            withContext(Dispatchers.Main) {
                qsTile?.apply {
                    val finalTotal = after?.waterOz?.roundToInt() ?: optimisticTotal
                    val finalGoal = after?.waterGoalOz?.roundToInt() ?: goalOz
                    label = "+$addedOz oz Water"
                    state = if (finalGoal > 0 && finalTotal >= finalGoal) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = "$finalTotal/$finalGoal oz"
                    }
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        tileJob?.cancel()
    }
}
