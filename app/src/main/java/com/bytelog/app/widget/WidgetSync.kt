package com.bytelog.app.widget

import com.bytelog.app.ByteLogApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Keeps the widgets current: redraws them whenever today's food, water or the goals change (and at midnight). */
object WidgetSync {
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start(app: ByteLogApplication, scope: CoroutineScope) {
        scope.launch {
            val today = flow { while (true) { emit(LocalDate.now().toString()); delay(60_000) } }.distinctUntilChanged()
            today.flatMapLatest { date ->
                val s = app.settingsRepository
                val sources: List<Flow<Any?>> = listOf(
                    app.database.foodEntryDao().getEntriesForDate(date),
                    app.database.waterLogDao().getTotalWaterForDate(date),
                    s.targetCalories, s.targetProtein, s.targetCarbs, s.targetFat, s.targetWaterOz, s.defaultContainerOz, s.useDeviceColors,
                    // the fasting time on the Today widget moves on by itself
                    flow { while (true) { emit(System.currentTimeMillis() / 900_000L); delay(60_000) } }.distinctUntilChanged()
                )
                combine(sources) { it.size }
            }
                .debounce(600)
                .collect {
                    runCatching { WidgetUpdater.updateAll(app) }
                    runCatching { com.bytelog.app.tiles.QuickSettingsTileUpdater.requestTileUpdates(app) }
                }
        }
    }
}
