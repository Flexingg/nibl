package com.bytelog.app.health

import android.content.Context
import android.content.SharedPreferences
import com.bytelog.app.data.local.db.FoodEntryDao
import com.bytelog.app.data.local.db.WaterLogDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Health Connect records Nibl has written, remembered so they aren't taken for new food when the SparkyFitness
 * phone app imports them into Sparky and Sparky sends them back ("Health Connect" entries whose id is one of these).
 */
object HealthConnectEcho {
    @Volatile private var prefs: SharedPreferences? = null
    private const val KEY = "written_uuids"

    fun init(context: Context) { prefs = context.getSharedPreferences("health_connect", Context.MODE_PRIVATE) }

    fun remember(uuids: Collection<String>) {
        val p = prefs ?: return
        if (uuids.isEmpty()) return
        // Newest last; kept to a few thousand (weeks of meals and drinks)
        val all = (p.getString(KEY, "").orEmpty().split(',').filter { it.isNotBlank() } + uuids).distinct().takeLast(4000)
        p.edit().putString(KEY, all.joinToString(",")).apply()
    }

    fun isEcho(source: String?, sourceId: String?): Boolean {
        if (source == null || sourceId == null || !source.contains("health", ignoreCase = true)) return false
        val p = prefs ?: return false
        return p.getString(KEY, "").orEmpty().split(',').contains(sourceId)
    }
}

/**
 * Writes the diary (food and drinks) to Health Connect, a day at a time, leaving anything the SparkyFitness app has
 * already written to it. Each day's records are replaced as a whole when the day changes: the last run's records
 * are deleted and the current ones written with fresh ids (Health Connect won't take an id back once deleted).
 * Runs only while the app is open: it has to read what's there first, and reading in the background needs another
 * permission. If a day can't be read, nothing is written for it.
 */
class HealthConnectSync(
    context: Context,
    private val store: HealthStore,
    private val foodEntryDao: FoodEntryDao,
    private val waterLogDao: WaterLogDao,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Instant = { Instant.now() }
) {
    private val prefs = context.getSharedPreferences("health_connect", Context.MODE_PRIVATE)
    private val lock = Mutex()

    data class Status(val lastRun: Long = 0L, val written: Int = 0, val leftToSparky: Int = 0, val error: String? = null)

    private val _status = MutableStateFlow(Status(lastRun = prefs.getLong("last_run", 0L)))
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Today and the days before it (edits are usually recent; older days were written when they were current). */
    fun recentDays(days: Int = 7): List<String> {
        val today = clock().atZone(zone()).toLocalDate()
        return (days - 1 downTo 0).map { today.minusDays(it.toLong()).toString() }
    }

    suspend fun syncDays(dates: List<String> = recentDays()): Status = lock.withLock {
        var written = 0; var left = 0; var error: String? = null
        for (date in dates) {
            runCatching { syncDay(date) }
                .onSuccess { (w, l) -> written += w; left += l }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
        }
        val s = Status(clock().toEpochMilli(), written, left, error)
        prefs.edit().putLong("last_run", s.lastRun).apply()
        _status.value = s
        s
    }

    /** One day: returns (records Nibl now has there, entries left to the SparkyFitness app). */
    private suspend fun syncDay(date: String): Pair<Int, Int> {
        val z = zone()
        val day = LocalDate.parse(date)
        val from = day.atStartOfDay(z).toInstant()
        val to = day.plusDays(1).atStartOfDay(z).toInstant()
        val now = clock()

        val entries = foodEntryDao.getEntriesForDate(date).first()
        val logs = waterLogDao.getLogsForDate(date).first()
        // Read first: without knowing what's there, writing could double up
        val nutrition = store.readNutrition(from, to)
        val hydration = store.readHydration(from, to)
        val version = now.toEpochMilli()
        val food = HealthConnectPlan.foodToWrite(entries, nutrition, z, now, version)
        val water = HealthConnectPlan.waterToWrite(logs, hydration, z, now, version)
        val left = entries.count { it.source == null } - food.size

        val sig = HealthConnectPlan.signature(food, water)
        if (prefs.getString("sig:$date", null) == sig) return food.size + water.size to left

        store.deleteFood(ids("food:$date"))
        store.deleteWater(ids("water:$date"))
        val foodUuids = store.writeFood(food, version, z)
        val waterUuids = store.writeWater(water, version, z)
        HealthConnectEcho.remember(foodUuids + waterUuids)
        prefs.edit()
            .putString("food:$date", food.joinToString(",") { it.clientRecordId })
            .putString("water:$date", water.joinToString(",") { it.clientRecordId })
            .putString("sig:$date", sig)
            .apply()
        return food.size + water.size to left
    }

    private fun ids(key: String) = prefs.getString(key, "").orEmpty().split(',').filter { it.isNotBlank() }

    /** Takes back everything Nibl wrote (when writing is turned off). */
    suspend fun removeAll() = lock.withLock {
        prefs.all.keys.filter { it.startsWith("food:") }.forEach { k -> runCatching { store.deleteFood(ids(k)) } }
        prefs.all.keys.filter { it.startsWith("water:") }.forEach { k -> runCatching { store.deleteWater(ids(k)) } }
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("food:") || it.startsWith("water:") || it.startsWith("sig:") }.forEach { e.remove(it) }
        e.apply()
    }
}
