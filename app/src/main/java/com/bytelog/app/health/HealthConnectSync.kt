package com.bytelog.app.health

import android.content.Context
import android.content.SharedPreferences
import com.bytelog.app.data.local.db.FoodEntryDao
import com.bytelog.app.data.local.db.WaterLogDao
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.WaterLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.bytelog.app.data.remote.deleteFood

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
        if (source == null || sourceId == null || !imported(source)) return false
        val p = prefs ?: return false
        return p.getString(KEY, "").orEmpty().split(',').contains(sourceId)
    }

    /** Came into Sparky from Health Connect (its phone app's import). */
    fun imported(source: String?): Boolean = source?.contains("health", ignoreCase = true) == true

    private fun norm(s: String?) = s.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")

    /**
     * Sparky's diary for a day without the SparkyFitness app's imports of food that Nibl wrote to Health Connect.
     *
     * Nibl writes each food to Health Connect; the SparkyFitness phone app reads Health Connect and imports what other
     * apps wrote, so each food comes back into Sparky as a second entry (source "Health Connect"). Sparky's diary
     * list doesn't say which Health Connect record an entry came from, so a copy is recognised by what it is: an
     * imported entry with the same day and name as one logged in Sparky directly ([server]) or on this phone
     * ([local]). Imported food with no such twin (from another app) stays.
     */
    fun dropFoodEchoes(server: List<FoodEntry>, local: List<FoodEntry> = emptyList()): List<FoodEntry> {
        val originals = (server + local).filter { !imported(it.source) }
            .map { it.entryDate.take(10) to norm(it.foodName) }.toSet()
        return server.filterNot { e ->
            imported(e.source) && (isEcho(e.source, e.sourceId) || (e.entryDate.take(10) to norm(e.foodName)) in originals)
        }
    }

    /** The same for drinks: an imported drink of the same amount (within 1 ml) on the same day as one logged directly. */
    fun dropWaterEchoes(server: List<WaterLogEntry>, local: List<WaterLogEntry> = emptyList()): List<WaterLogEntry> {
        val originals = (server + local).filter { !imported(it.source) }
        return server.filterNot { w ->
            imported(w.source) && (isEcho(w.source, w.sourceId) ||
                originals.any { o -> o.entryDate.take(10) == w.entryDate.take(10) && kotlin.math.abs(o.waterMl - w.waterMl) <= 1f })
        }
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

        // Only records whose food or drink is gone are deleted; the rest are updated in place (same id, newer version)
        store.deleteFood(ids("food:$date") - food.map { it.clientRecordId }.toSet())
        store.deleteWater(ids("water:$date") - water.map { it.clientRecordId }.toSet())
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
