package com.bytelog.app.data.fasting

import android.content.Context
import com.bytelog.app.data.remote.SparkyApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Serializable
private data class FastingCacheSnapshot(
    val activeFast: FastingLog? = null,
    val history: List<FastingLog> = emptyList(),
    val stats: FastingStats = FastingStats(),
    val preferences: FastingPreferences = FastingPreferences()
)

/**
 * Repository for active fasting, history, stats, and preferences.
 * Caches state locally in `context.filesDir/fasting_cache.json` for instant offline rendering and fallback.
 */
class FastingRepository(
    context: Context,
    sparkyApiClient: SparkyApiClient,
    private val appScope: CoroutineScope
) {
    private val api = FastingApi(sparkyApiClient)
    private val cacheFile = File(context.applicationContext.filesDir, "fasting_cache.json")
    private val cacheJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val _activeFast = MutableStateFlow<FastingLog?>(null)
    val activeFast: StateFlow<FastingLog?> = _activeFast.asStateFlow()

    private val _history = MutableStateFlow<List<FastingLog>>(emptyList())
    val history: StateFlow<List<FastingLog>> = _history.asStateFlow()

    private val _stats = MutableStateFlow(FastingStats())
    val stats: StateFlow<FastingStats> = _stats.asStateFlow()

    private val _preferences = MutableStateFlow(FastingPreferences())
    val preferences: StateFlow<FastingPreferences> = _preferences.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadCacheSync()
        appScope.launch(Dispatchers.IO) {
            refresh()
        }
    }

    suspend fun refresh(): Result<Unit> = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            coroutineScope {
                val currentDeferred = async { api.getCurrentFast() }
                val historyDeferred = async { api.getHistory(limit = 50) }
                val statsDeferred = async { api.getStats() }
                val prefsDeferred = async { api.getPreferences() }

                val currentRes = currentDeferred.await()
                val historyRes = historyDeferred.await()
                val statsRes = statsDeferred.await()
                val prefsRes = prefsDeferred.await()

                currentRes.onSuccess { current ->
                    _activeFast.value = current?.takeIf { it.isActive && !it.isEatingWindow }
                }
                historyRes.onSuccess { list ->
                    _history.value = list
                }
                statsRes.onSuccess { s ->
                    _stats.value = s
                }
                prefsRes.onSuccess { p ->
                    _preferences.value = p
                }

                persistCache()

                val firstFailure = listOf(currentRes, historyRes, statsRes, prefsRes)
                    .firstNotNullOfOrNull { it.exceptionOrNull() }
                if (firstFailure != null && currentRes.isFailure && historyRes.isFailure) {
                    Result.failure(firstFailure)
                } else {
                    Result.success(Unit)
                }
            }
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun startFast(
        startTimeIso: String = Instant.now().toString(),
        targetEndTimeIso: String? = null,
        fastingType: String = _preferences.value.defaultProtocol
    ): Result<FastingLog> = withContext(Dispatchers.IO) {
        val computedTargetEnd = targetEndTimeIso ?: computeTargetEndIso(startTimeIso, fastingType)
        val remote = api.startFast(startTimeIso, computedTargetEnd, fastingType)
        val log = remote.getOrElse {
            // Offline fallback so the user can still start fasting immediately
            FastingLog(
                id = "local_${UUID.randomUUID()}",
                startTime = startTimeIso,
                endTime = null,
                targetEndTime = computedTargetEnd,
                durationMinutes = null,
                fastingType = fastingType,
                status = "active"
            )
        }
        _activeFast.value = log
        persistCache()
        if (remote.isSuccess) Result.success(log) else Result.success(log)
    }

    suspend fun endFast(
        id: String,
        endTimeIso: String = Instant.now().toString(),
        startTimeIso: String? = null,
        targetEndTimeIso: String? = null,
        fastingType: String? = null
    ): Result<FastingLog> = withContext(Dispatchers.IO) {
        val current = _activeFast.value
        val effectiveStart = startTimeIso ?: current?.startTime
        val effectiveTarget = targetEndTimeIso ?: current?.targetEndTime
        val effectiveType = fastingType ?: current?.fastingType ?: _preferences.value.defaultProtocol

        val remote = if (id.startsWith("local_")) {
            Result.failure(IllegalStateException("Local offline fast"))
        } else {
            api.endFast(id, endTimeIso, effectiveStart, effectiveTarget, effectiveType)
        }

        val completed = remote.getOrElse {
            val startInst = parseFastingInstant(effectiveStart) ?: Instant.now()
            val endInst = parseFastingInstant(endTimeIso) ?: Instant.now()
            val mins = Duration.between(startInst, endInst).toMinutes().toInt().coerceAtLeast(0)
            FastingLog(
                id = id,
                startTime = effectiveStart ?: startInst.toString(),
                endTime = endTimeIso,
                targetEndTime = effectiveTarget,
                durationMinutes = mins,
                fastingType = effectiveType,
                status = "completed"
            )
        }

        _activeFast.value = null
        val updatedHistory = listOf(completed) + _history.value.filterNot { it.id == completed.id }
        _history.value = updatedHistory
        _stats.value = computeStatsFromHistory(updatedHistory)
        persistCache()

        if (remote.isSuccess) {
            // Refresh stats from server in background
            api.getStats().onSuccess {
                _stats.value = it
                persistCache()
            }
        }
        Result.success(completed)
    }

    suspend fun updateFast(
        id: String,
        startTimeIso: String? = null,
        endTimeIso: String? = null,
        targetEndTimeIso: String? = null,
        fastingType: String? = null
    ): Result<FastingLog> = withContext(Dispatchers.IO) {
        val remote = if (id.startsWith("local_")) {
            Result.failure(IllegalStateException("Local offline fast"))
        } else {
            api.updateFast(id, startTimeIso, endTimeIso, targetEndTimeIso, fastingType)
        }

        val existing = _activeFast.value?.takeIf { it.id == id }
            ?: _history.value.firstOrNull { it.id == id }

        val updated = remote.getOrElse {
            val base = existing ?: return@withContext Result.failure(IllegalArgumentException("Fast $id not found"))
            val newStart = startTimeIso ?: base.startTime
            val newEnd = endTimeIso ?: base.endTime
            val newTarget = targetEndTimeIso ?: base.targetEndTime
            val newType = fastingType ?: base.fastingType
            val newDuration = if (newEnd != null) {
                val s = parseFastingInstant(newStart)
                val e = parseFastingInstant(newEnd)
                if (s != null && e != null) Duration.between(s, e).toMinutes().toInt().coerceAtLeast(0) else base.durationMinutes
            } else {
                base.durationMinutes
            }
            base.copy(
                startTime = newStart,
                endTime = newEnd,
                targetEndTime = newTarget,
                fastingType = newType,
                durationMinutes = newDuration
            )
        }

        if (_activeFast.value?.id == id) {
            _activeFast.value = if (updated.isActive) updated else null
        }
        if (_history.value.any { it.id == id }) {
            val next = _history.value.map { if (it.id == id) updated else it }
            _history.value = next
            _stats.value = computeStatsFromHistory(next)
        }
        persistCache()
        // Sparky didn't take it: say so, since its copy wins at the next refresh
        remote.exceptionOrNull()?.takeIf { !id.startsWith("local_") }?.let { e ->
            return@withContext Result.failure(IllegalStateException("Sparky didn't save the change: ${e.message}"))
        }
        Result.success(updated)
    }

    suspend fun deleteFast(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val remote = if (id.startsWith("local_")) Result.success(Unit) else api.deleteFast(id)
        if (_activeFast.value?.id == id) {
            _activeFast.value = null
        }
        val nextHistory = _history.value.filterNot { it.id == id }
        _history.value = nextHistory
        _stats.value = computeStatsFromHistory(nextHistory)
        persistCache()

        if (remote.isSuccess) {
            api.getStats().onSuccess {
                _stats.value = it
                persistCache()
            }
        }
        remote
    }

    suspend fun updatePreferences(prefs: FastingPreferences): Result<FastingPreferences> = withContext(Dispatchers.IO) {
        _preferences.value = prefs
        persistCache()
        val remote = api.updatePreferences(prefs)
        remote.onSuccess { serverPrefs ->
            _preferences.value = serverPrefs
            persistCache()
        }
        remote
    }

    private fun computeTargetEndIso(startTimeIso: String, fastingType: String): String {
        val start = parseFastingInstant(startTimeIso) ?: Instant.now()
        val protocol = FastingProtocol.fromCode(fastingType)
        val hours = if (protocol == FastingProtocol.CUSTOM) {
            _preferences.value.targetFastingHours.coerceAtLeast(1f)
        } else {
            protocol.fastHours
        }
        return start.plusSeconds((hours * 3600f).toLong()).toString()
    }

    private fun computeStatsFromHistory(items: List<FastingLog>): FastingStats {
        val completed = items.filter { !it.isActive }
        if (completed.isEmpty()) return FastingStats()
        val totalMinutes = completed.sumOf { log ->
            log.durationMinutes ?: run {
                val s = log.startInstant()
                val e = log.endInstant()
                if (s != null && e != null) Duration.between(s, e).toMinutes().toInt().coerceAtLeast(0) else 0
            }
        }
        return FastingStats(
            totalCompletedFasts = completed.size,
            totalMinutesFasted = totalMinutes,
            averageDurationMinutes = totalMinutes / completed.size
        )
    }

    private fun loadCacheSync() {
        runCatching {
            if (!cacheFile.exists()) return
            val text = cacheFile.readText()
            if (text.isBlank()) return
            val snapshot = cacheJson.decodeFromString<FastingCacheSnapshot>(text)
            _activeFast.value = snapshot.activeFast
            _history.value = snapshot.history
            _stats.value = snapshot.stats
            _preferences.value = snapshot.preferences
        }
    }

    private fun persistCache() {
        runCatching {
            val snapshot = FastingCacheSnapshot(
                activeFast = _activeFast.value,
                history = _history.value,
                stats = _stats.value,
                preferences = _preferences.value
            )
            cacheFile.writeText(cacheJson.encodeToString(snapshot))
        }
    }
}
