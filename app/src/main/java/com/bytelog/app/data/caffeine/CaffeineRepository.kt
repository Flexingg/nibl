package com.bytelog.app.data.caffeine

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Manages caffeine tracking state (`StateFlow<CaffeineStatus?>`), local offline persistence
 * (DataStore for settings + JSON cache for per-day doses), and synchronization with [CaffeineApi].
 */
class CaffeineRepository(
    private val context: Context,
    private val caffeineApi: CaffeineApi
) {
    constructor(context: Context, api: SparkyApiClient) : this(context, CaffeineApi(api))

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    private val cacheLock = Any()
    private val doseCacheSerializer = MapSerializer(String.serializer(), ListSerializer(CaffeineDose.serializer()))

    data class LastCaffeineItem(val name: String, val mg: Float)

    private val _status = MutableStateFlow<CaffeineStatus?>(null)
    val status: StateFlow<CaffeineStatus?> = _status.asStateFlow()

    private val _latestItem = MutableStateFlow<LastCaffeineItem?>(null)
    val latestItem: StateFlow<LastCaffeineItem?> = _latestItem.asStateFlow()

    private val _settings = MutableStateFlow(CaffeineSettings())
    val settings: StateFlow<CaffeineSettings> = _settings.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var lastLoadedDate: String = LocalDate.now().toString()

    /**
     * Loads local cached caffeine data immediately, then refreshes from Sparky.
     * Falls back seamlessly to local exponential decay math when offline.
     */
    suspend fun refresh(
        date: String = LocalDate.now().toString(),
        doseMg: Float = 200f
    ) {
        val cleanDate = date.substringBefore('T').trim().ifBlank { LocalDate.now().toString() }
        lastLoadedDate = cleanDate
        _isLoading.value = true
        _lastError.value = null

        val localSettings = readSettingsFromStore()
        _settings.value = localSettings
        if (_latestItem.value == null) {
            _latestItem.value = readLastItemFromStore()
        }
        val localDoses = readDosesForDate(cleanDate)
        updateLatestItemFromDoses(localDoses, cleanDate)

        // Emit immediate local estimate so UI renders instantly even before network returns
        if (_status.value == null || lastLoadedDate == cleanDate) {
            _status.value = CaffeineMath.buildLocalStatus(
                date = cleanDate,
                doses = localDoses,
                halfLifeHours = localSettings.halfLifeHours,
                targetBedtime = localSettings.targetBedtime,
                nextDoseMg = doseMg
            )
        }

        // Pull server settings (best effort)
        caffeineApi.getCaffeineSettings().onSuccess { (halfLife, bedtime) ->
            val updated = CaffeineSettings(halfLifeHours = halfLife, targetBedtime = bedtime)
            _settings.value = updated
            saveSettingsToStore(updated)
        }

        // Pull server active caffeine status
        caffeineApi.getActiveCaffeine(cleanDate, doseMg).fold(
            onSuccess = { remoteStatus ->
                val mergedSettings = CaffeineSettings(
                    halfLifeHours = remoteStatus.halfLifeHours.coerceIn(2f, 8f),
                    targetBedtime = remoteStatus.targetBedtime.take(5).ifBlank { _settings.value.targetBedtime }
                )
                _settings.value = mergedSettings
                saveSettingsToStore(mergedSettings)
                saveDosesForDate(cleanDate, remoteStatus.doses)
                updateLatestItemFromDoses(remoteStatus.doses, cleanDate)
                _status.value = remoteStatus
            },
            onFailure = { err ->
                // Offline fallback: recompute with latest known local settings & doses
                val s = _settings.value
                _status.value = CaffeineMath.buildLocalStatus(
                    date = cleanDate,
                    doses = readDosesForDate(cleanDate),
                    halfLifeHours = s.halfLifeHours,
                    targetBedtime = s.targetBedtime,
                    nextDoseMg = doseMg
                )
                _lastError.value = err.message
            }
        )

        _isLoading.value = false
    }

    /**
     * Logs a caffeine dose at [timeHHmm] on [date].
     * Always persists locally immediately for offline resilience, then posts to Sparky.
     */
    suspend fun logCaffeine(
        name: String,
        mg: Float,
        timeHHmm: String,
        date: String = LocalDate.now().toString(),
        mealTypeId: String = ""
    ): Result<Unit> {
        val cleanDate = date.substringBefore('T').trim().ifBlank { LocalDate.now().toString() }
        val cleanTime = timeHHmm.trim().take(5).ifBlank {
            LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        }
        val localDate = runCatching { LocalDate.parse(cleanDate) }.getOrDefault(LocalDate.now())
        val localTime = runCatching { LocalTime.parse(cleanTime, DateTimeFormatter.ofPattern("HH:mm")) }
            .getOrDefault(LocalTime.now())
        val isoTimestamp = LocalDateTime.of(localDate, localTime)
            .atZone(ZoneId.systemDefault())
            .toOffsetDateTime()
            .toString()

        val newDose = CaffeineDose(
            at = isoTimestamp,
            mg = mg,
            name = name.ifBlank { "Caffeine" },
            isEstimated = false
        )

        // Optimistic local persistence
        val updatedDoses = (readDosesForDate(cleanDate) + newDose).sortedBy {
            CaffeineMath.parseDoseEpochMs(it.at, localDate) ?: 0L
        }
        saveDosesForDate(cleanDate, updatedDoses)
        val loggedItem = LastCaffeineItem(newDose.name, newDose.mg)
        _latestItem.value = loggedItem
        saveLastItemToStore(loggedItem)
        val currentSettings = readSettingsFromStore()
        _settings.value = currentSettings
        _status.value = CaffeineMath.buildLocalStatus(
            date = cleanDate,
            doses = updatedDoses,
            halfLifeHours = currentSettings.halfLifeHours,
            targetBedtime = currentSettings.targetBedtime
        )

        // Sync to Sparky
        val remoteResult = caffeineApi.logCaffeineQuickEntry(
            date = cleanDate,
            timeHHmm = cleanTime,
            name = newDose.name,
            mg = mg,
            mealTypeId = mealTypeId
        )
        if (remoteResult.isSuccess) {
            refresh(cleanDate)
        }
        return remoteResult
    }

    /**
     * Updates half-life (2–8 hours) and target bedtime (`HH:mm`), persisting locally and to Sparky.
     */
    suspend fun updateSettings(
        halfLifeHours: Float,
        targetBedtime: String
    ): Result<Unit> {
        val clamped = halfLifeHours.coerceIn(2f, 8f)
        val cleanBedtime = targetBedtime.trim().take(5).ifBlank { "22:30" }
        val nextSettings = CaffeineSettings(halfLifeHours = clamped, targetBedtime = cleanBedtime)
        _settings.value = nextSettings
        saveSettingsToStore(nextSettings)

        // Recompute current status immediately
        val currentDoses = _status.value?.doses ?: readDosesForDate(lastLoadedDate)
        _status.value = CaffeineMath.buildLocalStatus(
            date = lastLoadedDate,
            doses = currentDoses,
            halfLifeHours = clamped,
            targetBedtime = cleanBedtime
        )

        val remoteResult = caffeineApi.updateCaffeineSettings(clamped, cleanBedtime)
        if (remoteResult.isSuccess) {
            refresh(lastLoadedDate)
        }
        return remoteResult
    }

    // ---- Local persistence helpers ----

    private suspend fun readSettingsFromStore(): CaffeineSettings {
        val prefs = context.dataStore.data.first()
        val halfLife = (prefs[KEY_CAFFEINE_HALF_LIFE] ?: 5f).coerceIn(2f, 8f)
        val bedtime = prefs[KEY_CAFFEINE_BEDTIME]?.take(5)?.ifBlank { "22:30" } ?: "22:30"
        return CaffeineSettings(halfLifeHours = halfLife, targetBedtime = bedtime)
    }

    private suspend fun saveSettingsToStore(settings: CaffeineSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CAFFEINE_HALF_LIFE] = settings.halfLifeHours.coerceIn(2f, 8f)
            prefs[KEY_CAFFEINE_BEDTIME] = settings.targetBedtime.take(5)
        }
    }

    private suspend fun readDosesForDate(date: String): List<CaffeineDose> = withContext(Dispatchers.IO) {
        synchronized(cacheLock) {
            readAllCachedDosesLocked()[date].orEmpty()
        }
    }

    private suspend fun saveDosesForDate(date: String, doses: List<CaffeineDose>) = withContext(Dispatchers.IO) {
        synchronized(cacheLock) {
            val all = readAllCachedDosesLocked().toMutableMap()
            all[date] = doses
            // Keep at most 14 most-recent days in local cache
            if (all.size > 14) {
                val keysToDrop = all.keys.sorted().take(all.size - 14)
                keysToDrop.forEach { all.remove(it) }
            }
            writeAllCachedDosesLocked(all)
        }
    }

    private fun readAllCachedDosesLocked(): Map<String, List<CaffeineDose>> {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        if (!file.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString(doseCacheSerializer, file.readText())
        }.getOrDefault(emptyMap())
    }

    private fun writeAllCachedDosesLocked(data: Map<String, List<CaffeineDose>>) {
        runCatching {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            val tmp = File(context.filesDir, "$CACHE_FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(doseCacheSerializer, data))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    private suspend fun updateLatestItemFromDoses(doses: List<CaffeineDose>, date: String) {
        val localDate = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
        val latest = doses
            .filter { it.mg > 0f && it.name.isNotBlank() }
            .maxByOrNull { CaffeineMath.parseDoseEpochMs(it.at, localDate) ?: 0L }
            ?: return
        val item = LastCaffeineItem(latest.name.trim(), latest.mg)
        _latestItem.value = item
        saveLastItemToStore(item)
    }

    private suspend fun readLastItemFromStore(): LastCaffeineItem? {
        val prefs = context.dataStore.data.first()
        val name = prefs[KEY_LAST_CAFFEINE_NAME]?.trim().orEmpty()
        val mg = prefs[KEY_LAST_CAFFEINE_MG] ?: 0f
        return if (name.isNotBlank() && mg > 0f) LastCaffeineItem(name, mg) else null
    }

    private suspend fun saveLastItemToStore(item: LastCaffeineItem) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_CAFFEINE_NAME] = item.name
            prefs[KEY_LAST_CAFFEINE_MG] = item.mg
        }
    }

    companion object {
        private val KEY_CAFFEINE_HALF_LIFE = floatPreferencesKey("caffeine_half_life_hours")
        private val KEY_CAFFEINE_BEDTIME = stringPreferencesKey("caffeine_target_bedtime")
        private val KEY_LAST_CAFFEINE_NAME = stringPreferencesKey("caffeine_last_item_name")
        private val KEY_LAST_CAFFEINE_MG = floatPreferencesKey("caffeine_last_item_mg")
        private const val CACHE_FILE_NAME = "caffeine_doses_cache.json"
    }
}
