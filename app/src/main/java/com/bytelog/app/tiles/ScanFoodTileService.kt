package com.bytelog.app.tiles

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bytelog.app.R
import com.bytelog.app.widget.WidgetDestination
import com.bytelog.app.widget.suggestedMealAt
import java.time.LocalTime

/**
 * Quick Settings tile that opens Nibl directly to the barcode scanner
 * for the meal matching the current time of day.
 */
class ScanFoodTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "Scan Food"
            icon = Icon.createWithResource(this@ScanFoodTileService, R.drawable.ic_tile_scan)
            state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = suggestedMealAt(LocalTime.now())
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val meal = suggestedMealAt(LocalTime.now())
        launchWidgetDestination(WidgetDestination.Scan(meal))
    }
}
