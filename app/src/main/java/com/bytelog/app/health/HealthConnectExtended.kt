package com.bytelog.app.health

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.WeightRepository
import com.bytelog.app.data.repository.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/**
 * Logical permission groups for Nibl's extended Health Connect integration.
 */
enum class HealthMetricGroup(
    val title: String,
    val description: String,
    val permissions: Set<String>
) {
    NUTRITION_HYDRATION(
        title = "Nutrition & Hydration",
        description = "Read and write meals and water logs",
        permissions = setOf(
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getWritePermission(NutritionRecord::class),
            HealthPermission.getReadPermission(HydrationRecord::class),
            HealthPermission.getWritePermission(HydrationRecord::class)
        )
    ),
    WEIGHT_BODY_COMP(
        title = "Weight & Body Fat",
        description = "Bidirectional sync for weight and body fat percentage",
        permissions = setOf(
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getWritePermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getWritePermission(BodyFatRecord::class)
        )
    ),
    ACTIVITY_STEPS(
        title = "Steps & Activity",
        description = "Daily steps, active calories, total calories, and distance",
        permissions = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class)
        )
    ),
    SLEEP(
        title = "Sleep",
        description = "Sleep sessions, duration, and stages",
        permissions = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class)
        )
    ),
    HEART_RATE_VITALS(
        title = "Heart Rate & Vitals",
        description = "Heart rate, resting HR, HRV, SpO2, and blood glucose",
        permissions = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(BloodGlucoseRecord::class)
        )
    ),
    EXERCISE(
        title = "Workouts",
        description = "Exercise sessions and workout durations",
        permissions = setOf(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        )
    ),
    HISTORY(
        title = "Historical Data",
        description = "Read health records older than 30 days for backfill",
        permissions = setOf(
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
        )
    ),
    BACKGROUND(
        title = "Background Sync",
        description = "Read Health Connect data periodically in the background",
        permissions = setOf(
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        )
    );

    companion object {
        val ALL_PERMISSIONS: Set<String> = entries.flatMap { it.permissions }.toSet()

        fun permissionsFor(groups: Collection<HealthMetricGroup>): Set<String> =
            groups.flatMap { it.permissions }.toSet()
    }
}

/**
 * Aggregated daily health metrics cached locally so Nibl's Diary and Reports can render
 * Steps, Active Calories, Sleep, Resting HR, and Body Comp offline.
 */
@Serializable
data class DailyHealthSnapshot(
    val date: String,
    val steps: Long = 0L,
    val activeCaloriesKcal: Double = 0.0,
    val totalCaloriesKcal: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val sleepMinutes: Int = 0,
    val restingHrBpm: Int? = null,
    val avgHrBpm: Int? = null,
    val latestWeightKg: Double? = null,
    val latestBodyFatPct: Double? = null
)

/**
 * User preferences for which extended Health Connect metrics are enabled.
 */
@Serializable
data class HealthSyncConfig(
    val syncActivitySteps: Boolean = true,
    val syncWeightBodyComp: Boolean = true,
    val writeWeightToHealthConnect: Boolean = false,
    val syncSleep: Boolean = true,
    val syncHeartRateVitals: Boolean = true,
    val syncExercise: Boolean = true,
    val backgroundSyncEnabled: Boolean = false
) {
    fun enabledGroups(): Set<HealthMetricGroup> = buildSet {
        if (syncActivitySteps) add(HealthMetricGroup.ACTIVITY_STEPS)
        if (syncWeightBodyComp) add(HealthMetricGroup.WEIGHT_BODY_COMP)
        if (syncSleep) add(HealthMetricGroup.SLEEP)
        if (syncHeartRateVitals) add(HealthMetricGroup.HEART_RATE_VITALS)
        if (syncExercise) add(HealthMetricGroup.EXERCISE)
        if (backgroundSyncEnabled) add(HealthMetricGroup.BACKGROUND)
    }
}

@Serializable
data class ExtendedSyncStatus(
    val lastSyncEpochMs: Long = 0L,
    val lastRecordsUploaded: Int = 0,
    val lastWeightsImported: Int = 0,
    val lastDaysRead: Int = 0,
    val isSyncing: Boolean = false,
    val error: String? = null
)

data class RangeSyncResult(
    val daysRead: Int,
    val recordsUploaded: Int,
    val weightsImported: Int,
    val error: String? = null
)

/**
 * Extended Health Connect reader, local 90-day snapshot cache, bidirectional weight sync,
 * and SparkyFitness `/api/health-data` (v3) uploader.
 */
class HealthConnectExtendedSync(
    private val context: Context,
    private val sparkyApiClient: SparkyApiClient,
    private val weightRepository: WeightRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** The SparkyFitness app sends Health Connect data to Sparky and writes weight there itself: Nibl only reads. */
    private val leaveToSparkyApp: suspend () -> Boolean = { false }
) {
    companion object {
        private const val SNAPSHOT_FILE_NAME = "hc_snapshots.json"
        private const val MAX_CACHED_DAYS = 90
        private const val SPARKY_BATCH_SIZE = 500
        private const val NIBL_WEIGHT_ID_PREFIX = "nibl-weight-"
        private const val NIBL_BODY_FAT_ID_PREFIX = "nibl-bodyfat-"
        private val KEY_HC_EXTENDED_CONFIG = stringPreferencesKey("hc_extended_config")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val prefs = context.getSharedPreferences("health_connect_extended", Context.MODE_PRIVATE)
    private val snapshotFile = File(context.filesDir, SNAPSHOT_FILE_NAME)
    private val syncMutex = Mutex()
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    private val _snapshots = MutableStateFlow(loadCachedSnapshots())
    val snapshots: StateFlow<Map<String, DailyHealthSnapshot>> = _snapshots.asStateFlow()

    private val _config = MutableStateFlow(loadInitialConfig())
    val config: StateFlow<HealthSyncConfig> = _config.asStateFlow()

    private val _status = MutableStateFlow(loadPersistedStatus())
    val status: StateFlow<ExtendedSyncStatus> = _status.asStateFlow()

    fun isAvailable(): Boolean =
        runCatching { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }
            .getOrDefault(false)

    suspend fun getGrantedPermissions(): Set<String> {
        if (!isAvailable()) return emptySet()
        return runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
    }

    suspend fun loadConfig(): HealthSyncConfig {
        val raw = runCatching {
            context.dataStore.data.map { it[KEY_HC_EXTENDED_CONFIG] }.first()
        }.getOrNull()
        val parsed = raw?.let { runCatching { json.decodeFromString<HealthSyncConfig>(it) }.getOrNull() }
            ?: HealthSyncConfig()
        _config.value = parsed
        return parsed
    }

    suspend fun updateConfig(transform: (HealthSyncConfig) -> HealthSyncConfig): HealthSyncConfig {
        val current = _config.value
        val updated = transform(current)
        _config.value = updated
        val encoded = json.encodeToString(updated)
        prefs.edit().putString("config_cache", encoded).apply()
        runCatching {
            context.dataStore.edit { it[KEY_HC_EXTENDED_CONFIG] = encoded }
        }
        return updated
    }

    /**
     * Convenience wrapper to sync the most recent [days] (inclusive of today).
     */
    suspend fun syncRecent(days: Int = 7, uploadToSparky: Boolean = true): RangeSyncResult {
        val today = LocalDate.now(zone())
        val start = today.minusDays((days - 1).coerceAtLeast(0).toLong())
        return syncRange(start, today, uploadToSparky)
    }

    /**
     * Reads all enabled & permitted Health Connect metrics for [startDate]..[endDate] inclusive,
     * updates the local 90-day [DailyHealthSnapshot] cache, imports new external weight entries
     * into [WeightRepository], and uploads records to SparkyFitness `/api/health-data`.
     */
    suspend fun syncRange(
        startDate: LocalDate,
        endDate: LocalDate,
        uploadToSparky: Boolean = true
    ): RangeSyncResult = syncMutex.withLock {
        if (!isAvailable()) {
            val err = "Health Connect is not available on this device"
            _status.value = _status.value.copy(isSyncing = false, error = err)
            return@withLock RangeSyncResult(0, 0, 0, err)
        }

        _status.value = _status.value.copy(isSyncing = true, error = null)

        val result = withContext(Dispatchers.IO) {
            try {
                val cfg = loadConfig()
                val granted = getGrantedPermissions()
                val z = zone()
                val from = startDate.atStartOfDay(z).toInstant()
                val to = endDate.plusDays(1).atStartOfDay(z).toInstant()

                val allDates = mutableListOf<String>()
                var cursor = startDate
                while (!cursor.isAfter(endDate)) {
                    allDates += cursor.toString()
                    cursor = cursor.plusDays(1)
                }

                // Per-day accumulators
                val stepsByDay = mutableMapOf<String, Long>()
                val activeCalByDay = mutableMapOf<String, Double>()
                val totalCalByDay = mutableMapOf<String, Double>()
                val distanceByDay = mutableMapOf<String, Double>()
                val sleepMinByDay = mutableMapOf<String, Int>()
                val restingHrByDay = mutableMapOf<String, MutableList<Int>>()
                val hrSamplesByDay = mutableMapOf<String, MutableList<Long>>()
                val latestWeightByDay = mutableMapOf<String, Pair<Instant, Double>>()
                val latestBodyFatByDay = mutableMapOf<String, Pair<Instant, Double>>()
                val externalWeightsToImport = mutableMapOf<String, Pair<Instant, Double>>()
                val externalBodyFatToImport = mutableMapOf<String, Pair<Instant, Double>>()

                val sparkyPayloads = mutableListOf<JsonObject>()

                // 1. Steps & Activity
                if (cfg.syncActivitySteps) {
                    if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
                        val records = readAll(StepsRecord::class, from, to)
                        for (r in records) {
                            val d = r.startTime.atZone(z).toLocalDate().toString()
                            stepsByDay[d] = (stepsByDay[d] ?: 0L) + r.count
                            sparkyPayloads += buildNumericPayload(
                                type = "step",
                                value = r.count.toDouble(),
                                unit = "count",
                                timestamp = r.endTime,
                                startTime = r.startTime,
                                source = r.metadata.dataOrigin.packageName
                            )
                        }
                    }

                    if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in granted) {
                        val records = readAll(ActiveCaloriesBurnedRecord::class, from, to)
                        for (r in records) {
                            val kcal = r.energy.inKilocalories
                            val d = r.startTime.atZone(z).toLocalDate().toString()
                            activeCalByDay[d] = (activeCalByDay[d] ?: 0.0) + kcal
                            sparkyPayloads += buildNumericPayload(
                                type = "Active Calories",
                                value = kcal,
                                unit = "kcal",
                                timestamp = r.endTime,
                                startTime = r.startTime,
                                source = r.metadata.dataOrigin.packageName
                            )
                        }
                    }

                    if (HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) in granted) {
                        val records = readAll(TotalCaloriesBurnedRecord::class, from, to)
                        for (r in records) {
                            val kcal = r.energy.inKilocalories
                            val d = r.startTime.atZone(z).toLocalDate().toString()
                            totalCalByDay[d] = (totalCalByDay[d] ?: 0.0) + kcal
                            sparkyPayloads += buildNumericPayload(
                                type = "total_calories",
                                value = kcal,
                                unit = "kcal",
                                timestamp = r.endTime,
                                startTime = r.startTime,
                                source = r.metadata.dataOrigin.packageName
                            )
                        }
                    }

                    if (HealthPermission.getReadPermission(DistanceRecord::class) in granted) {
                        val records = readAll(DistanceRecord::class, from, to)
                        for (r in records) {
                            val meters = r.distance.inMeters
                            val d = r.startTime.atZone(z).toLocalDate().toString()
                            distanceByDay[d] = (distanceByDay[d] ?: 0.0) + meters
                            sparkyPayloads += buildNumericPayload(
                                type = "distance",
                                value = meters,
                                unit = "m",
                                timestamp = r.endTime,
                                startTime = r.startTime,
                                source = r.metadata.dataOrigin.packageName
                            )
                        }
                    }
                }

                // 2. Weight & Body Composition
                if (cfg.syncWeightBodyComp) {
                    if (HealthPermission.getReadPermission(WeightRecord::class) in granted) {
                        val records = readAll(WeightRecord::class, from, to)
                        for (r in records) {
                            val kg = r.weight.inKilograms
                            if (kg <= 0.0) continue
                            val d = r.time.atZone(z).toLocalDate().toString()
                            val prev = latestWeightByDay[d]
                            if (prev == null || r.time.isAfter(prev.first)) {
                                latestWeightByDay[d] = r.time to kg
                            }

                            val isOwnEcho = r.metadata.dataOrigin.packageName == context.packageName ||
                                r.metadata.clientRecordId?.startsWith(NIBL_WEIGHT_ID_PREFIX) == true
                            if (!isOwnEcho) {
                                val prevExt = externalWeightsToImport[d]
                                if (prevExt == null || r.time.isAfter(prevExt.first)) {
                                    externalWeightsToImport[d] = r.time to kg
                                }
                            }

                            sparkyPayloads += buildNumericPayload(
                                type = "weight",
                                value = kg,
                                unit = "kg",
                                timestamp = r.time,
                                source = r.metadata.dataOrigin.packageName
                            )
                        }
                    }

                    if (HealthPermission.getReadPermission(BodyFatRecord::class) in granted) {
                        val records = readAll(BodyFatRecord::class, from, to)
                        for (r in records) {
                            val pct = r.percentage.value
                            if (pct <= 0.0 || pct >= 100.0) continue
                            val d = r.time.atZone(z).toLocalDate().toString()
                            val prev = latestBodyFatByDay[d]
                            if (prev == null || r.time.isAfter(prev.first)) {
                                latestBodyFatByDay[d] = r.time to pct
                            }
                            val isOwnEcho = r.metadata.dataOrigin.packageName == context.packageName ||
                                r.metadata.clientRecordId?.startsWith(NIBL_BODY_FAT_ID_PREFIX) == true
                            if (!isOwnEcho) {
                                val prevExt = externalBodyFatToImport[d]
                                if (prevExt == null || r.time.isAfter(prevExt.first)) {
                                    externalBodyFatToImport[d] = r.time to pct
                                }
                            }
                            sparkyPayloads += buildNumericPayload(
                                type = "body_fat",
                                value = pct,
                                unit = "%",
                                timestamp = r.time,
                                source = r.metadata.dataOrigin.packageName
                            )
                        }
                    }
                }

                // 3. Sleep
                if (cfg.syncSleep && HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
                    val records = readAll(SleepSessionRecord::class, from, to)
                    for (r in records) {
                        val minutes = Duration.between(r.startTime, r.endTime).toMinutes().toInt().coerceAtLeast(0)
                        // Attribute sleep session to wake-up day
                        val d = r.endTime.atZone(z).toLocalDate().toString()
                        sleepMinByDay[d] = (sleepMinByDay[d] ?: 0) + minutes

                        sparkyPayloads += buildJsonObject {
                            put("type", "SleepSession")
                            put("startTime", r.startTime.toString())
                            put("endTime", r.endTime.toString())
                            put("timestamp", r.endTime.toString())
                            put("durationMinutes", minutes)
                            put("value", minutes)
                            put("unit", "min")
                            put("source", r.metadata.dataOrigin.packageName)
                            r.title?.let { put("title", it) }
                            if (r.stages.isNotEmpty()) {
                                put("stages", buildJsonArray {
                                    for (s in r.stages) {
                                        add(buildJsonObject {
                                            put("startTime", s.startTime.toString())
                                            put("endTime", s.endTime.toString())
                                            put("stage", sleepStageName(s.stage))
                                        })
                                    }
                                })
                            }
                        }
                    }
                }

                // 4. Heart Rate & Vitals
                if (cfg.syncHeartRateVitals) {
                    if (HealthPermission.getReadPermission(RestingHeartRateRecord::class) in granted) {
                        val records = readAll(RestingHeartRateRecord::class, from, to)
                        for (r in records) {
                            val bpm = r.beatsPerMinute.toInt()
                            val d = r.time.atZone(z).toLocalDate().toString()
                            restingHrByDay.getOrPut(d) { mutableListOf() }.add(bpm)
                            sparkyPayloads += buildNumericPayload(
                                type = "resting_heart_rate",
                                value = bpm.toDouble(),
                                unit = "bpm",
                                timestamp = r.time,
                                source = r.metadata.dataOrigin.packageName
                            )
                        }
                    }

                    if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted) {
                        val records = readAll(HeartRateRecord::class, from, to)
                        for (r in records) {
                            for (sample in r.samples) {
                                val d = sample.time.atZone(z).toLocalDate().toString()
                                hrSamplesByDay.getOrPut(d) { mutableListOf() }.add(sample.beatsPerMinute)
                            }
                        }
                        for ((dateStr, samples) in hrSamplesByDay) {
                            if (samples.isEmpty()) continue
                            val dayEnd = LocalDate.parse(dateStr).atTime(23, 59).atZone(z).toInstant()
                            val minBpm = samples.min()
                            val maxBpm = samples.max()
                            val avgBpm = samples.average()
                            sparkyPayloads += buildNumericPayload("heart_rate_min", minBpm.toDouble(), "bpm", dayEnd)
                            sparkyPayloads += buildNumericPayload("heart_rate_max", maxBpm.toDouble(), "bpm", dayEnd)
                            sparkyPayloads += buildNumericPayload("heart_rate_avg", avgBpm, "bpm", dayEnd)
                        }
                    }

                    if (HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class) in granted) {
                        val records = readAll(HeartRateVariabilityRmssdRecord::class, from, to)
                        for (r in records) {
                            sparkyPayloads += buildNumericPayload(
                                type = "hrv",
                                value = r.heartRateVariabilityMillis,
                                unit = "ms",
                                timestamp = r.time,
                                source = r.metadata.dataOrigin.packageName
                            )
                        }
                    }

                    if (HealthPermission.getReadPermission(OxygenSaturationRecord::class) in granted) {
                        val records = readAll(OxygenSaturationRecord::class, from, to)
                        for (r in records) {
                            sparkyPayloads += buildNumericPayload(
                                type = "oxygen_saturation",
                                value = r.percentage.value,
                                unit = "%",
                                timestamp = r.time,
                                source = r.metadata.dataOrigin.packageName
                            )
                        }
                    }

                    if (HealthPermission.getReadPermission(BloodGlucoseRecord::class) in granted) {
                        val records = readAll(BloodGlucoseRecord::class, from, to)
                        for (r in records) {
                            sparkyPayloads += buildNumericPayload(
                                type = "blood_glucose",
                                value = r.level.inMilligramsPerDeciliter,
                                unit = "mg/dL",
                                timestamp = r.time,
                                source = r.metadata.dataOrigin.packageName
                            )
                        }
                    }
                }

                // 5. Exercise / Workouts
                if (cfg.syncExercise && HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) {
                    val records = readAll(ExerciseSessionRecord::class, from, to)
                    for (r in records) {
                        val minutes = Duration.between(r.startTime, r.endTime).toMinutes().toInt().coerceAtLeast(1)
                        val exerciseName = exerciseTypeName(r.exerciseType)
                        sparkyPayloads += buildJsonObject {
                            put("type", "ExerciseSession")
                            put("startTime", r.startTime.toString())
                            put("endTime", r.endTime.toString())
                            put("timestamp", r.startTime.toString())
                            put("durationMinutes", minutes)
                            put("exerciseType", exerciseName)
                            put("title", r.title ?: exerciseName)
                            r.notes?.let { put("notes", it) }
                            put("source", r.metadata.dataOrigin.packageName)
                        }
                    }
                }

                // 6. Build DailyHealthSnapshot for every date in range and merge into 90-day local cache
                val updatedMap = _snapshots.value.toMutableMap()
                for (d in allDates) {
                    val existing = updatedMap[d]
                    val hrList = hrSamplesByDay[d]
                    val rhrList = restingHrByDay[d]
                    val snapshot = DailyHealthSnapshot(
                        date = d,
                        steps = stepsByDay[d] ?: existing?.steps ?: 0L,
                        activeCaloriesKcal = activeCalByDay[d] ?: existing?.activeCaloriesKcal ?: 0.0,
                        totalCaloriesKcal = totalCalByDay[d] ?: existing?.totalCaloriesKcal ?: 0.0,
                        distanceMeters = distanceByDay[d] ?: existing?.distanceMeters ?: 0.0,
                        sleepMinutes = sleepMinByDay[d] ?: existing?.sleepMinutes ?: 0,
                        restingHrBpm = rhrList?.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                            ?: existing?.restingHrBpm,
                        avgHrBpm = hrList?.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                            ?: existing?.avgHrBpm,
                        latestWeightKg = latestWeightByDay[d]?.second ?: existing?.latestWeightKg,
                        latestBodyFatPct = latestBodyFatByDay[d]?.second ?: existing?.latestBodyFatPct
                    )
                    val hasAnyData = snapshot.steps > 0L ||
                        snapshot.activeCaloriesKcal > 0.0 ||
                        snapshot.totalCaloriesKcal > 0.0 ||
                        snapshot.distanceMeters > 0.0 ||
                        snapshot.sleepMinutes > 0 ||
                        snapshot.restingHrBpm != null ||
                        snapshot.avgHrBpm != null ||
                        snapshot.latestWeightKg != null ||
                        snapshot.latestBodyFatPct != null
                    if (hasAnyData) {
                        updatedMap[d] = snapshot
                    }
                }

                // Trim to recent 90 days
                val cutoffDate = LocalDate.now(z).minusDays(MAX_CACHED_DAYS.toLong()).toString()
                val trimmed = updatedMap.filterKeys { it >= cutoffDate }.toSortedMap()
                _snapshots.value = trimmed
                saveCachedSnapshots(trimmed)

                // 7. Import new external weight readings into WeightRepository (avoiding echo loops)
                var weightsImported = 0
                if (cfg.syncWeightBodyComp && externalWeightsToImport.isNotEmpty()) {
                    val preferredUnit = weightRepository.weightUnit.first()
                    val isLbs = preferredUnit.equals("lbs", ignoreCase = true)
                    for ((dateStr, pair) in externalWeightsToImport) {
                        val kg = pair.second
                        val existingWeight = weightRepository.getWeightForDate(dateStr).first()
                        val displayWeight = if (isLbs) {
                            ((kg * WeightRepository.KG_TO_LBS) * 10.0).roundToInt() / 10f
                        } else {
                            (kg * 10.0).roundToInt() / 10f
                        }
                        val bfPct = externalBodyFatToImport[dateStr]?.second?.toFloat()
                        val alreadyMatches = existingWeight != null &&
                            kotlin.math.abs(existingWeight.weight - displayWeight) < 0.15f &&
                            (bfPct == null || existingWeight.bodyFatPercentage != null)
                        if (!alreadyMatches) {
                            weightRepository.logWeight(
                                date = dateStr,
                                weight = displayWeight,
                                unit = preferredUnit,
                                bodyFatPercentage = bfPct ?: existingWeight?.bodyFatPercentage,
                                fromHealthConnect = true
                            )
                            weightsImported++
                        }
                    }
                }

                // 8. Upload to SparkyFitness POST /api/health-data in chunks of <= 500
                var recordsUploaded = 0
                var uploadError: String? = null
                // With the SparkyFitness app sending the same records, a second upload would store sleep and workouts twice
                if (uploadToSparky && sparkyPayloads.isNotEmpty() && !runCatching { leaveToSparkyApp() }.getOrDefault(false)) {
                    val uploadResult = uploadToSparky(sparkyPayloads)
                    uploadResult.onSuccess { count -> recordsUploaded = count }
                        .onFailure { err -> uploadError = err.message ?: err.javaClass.simpleName }
                }

                RangeSyncResult(
                    daysRead = allDates.size,
                    recordsUploaded = recordsUploaded,
                    weightsImported = weightsImported,
                    error = uploadError
                )
            } catch (e: Exception) {
                RangeSyncResult(0, 0, 0, e.message ?: e.javaClass.simpleName)
            }
        }

        val nowMs = System.currentTimeMillis()
        val newStatus = ExtendedSyncStatus(
            lastSyncEpochMs = nowMs,
            lastRecordsUploaded = result.recordsUploaded,
            lastWeightsImported = result.weightsImported,
            lastDaysRead = result.daysRead,
            isSyncing = false,
            error = result.error
        )
        _status.value = newStatus
        persistStatus(newStatus)
        result
    }

    /**
     * Writes a Nibl weight (and optional body fat %) entry to Health Connect using a deterministic
     * clientRecordId so subsequent reads skip it and avoid echo loops.
     */
    suspend fun writeWeightToHealthConnect(
        date: String,
        weightKg: Double,
        bodyFatPct: Double? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable() || weightKg <= 0.0) return@withContext false
        if (!_config.value.writeWeightToHealthConnect) return@withContext false
        // The SparkyFitness app writes Sparky's weigh-ins (this one included, once it's there) to Health Connect
        if (runCatching { leaveToSparkyApp() }.getOrDefault(false)) return@withContext false
        val granted = getGrantedPermissions()
        val z = zone()
        val day = runCatching { LocalDate.parse(date) }.getOrElse { LocalDate.now(z) }
        val instant = day.atTime(8, 0).atZone(z).toInstant()
        val offset = z.rules.getOffset(instant)
        val version = System.currentTimeMillis()

        val recordsToInsert = mutableListOf<Record>()
        if (HealthPermission.getWritePermission(WeightRecord::class) in granted) {
            recordsToInsert += WeightRecord(
                time = instant,
                zoneOffset = offset,
                weight = Mass.kilograms(weightKg),
                metadata = Metadata.manualEntry(
                    clientRecordId = "$NIBL_WEIGHT_ID_PREFIX$date",
                    clientRecordVersion = version
                )
            )
        }
        if (bodyFatPct != null && bodyFatPct > 0.0 && bodyFatPct < 100.0 &&
            HealthPermission.getWritePermission(BodyFatRecord::class) in granted
        ) {
            recordsToInsert += BodyFatRecord(
                time = instant,
                zoneOffset = offset,
                percentage = Percentage(bodyFatPct),
                metadata = Metadata.manualEntry(
                    clientRecordId = "$NIBL_BODY_FAT_ID_PREFIX$date",
                    clientRecordVersion = version
                )
            )
        }
        if (recordsToInsert.isEmpty()) return@withContext false
        runCatching {
            client.insertRecords(recordsToInsert)
            true
        }.getOrDefault(false)
    }

    private suspend fun uploadToSparky(records: List<JsonObject>): Result<Int> = withContext(Dispatchers.IO) {
        val baseUrl = sparkyApiClient.currentBaseUrl().trimEnd('/')
        if (baseUrl.isBlank()) return@withContext Result.success(0)

        var uploaded = 0
        for (chunk in records.chunked(SPARKY_BATCH_SIZE)) {
            val payload = buildJsonObject {
                put("data", buildJsonArray { chunk.forEach { add(it) } })
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/health-data")
                .header("X-Workout-Model-Version", "3")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                sparkyApiClient.httpClient().newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string()?.take(200).orEmpty()
                        return@withContext Result.failure(
                            IllegalStateException("Sparky health-data upload failed (${resp.code}): $errBody")
                        )
                    }
                    uploaded += chunk.size
                }
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }
        Result.success(uploaded)
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val res = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    pageToken = token
                )
            )
            out += res.records
            token = res.pageToken
        } while (token != null)
        return out
    }

    private fun buildNumericPayload(
        type: String,
        value: Double,
        unit: String,
        timestamp: Instant,
        startTime: Instant? = null,
        source: String? = null
    ): JsonObject = buildJsonObject {
        put("type", type)
        put("value", value)
        put("unit", unit)
        put("timestamp", timestamp.toString())
        startTime?.let { put("startTime", it.toString()) }
        source?.let { put("source", it) }
    }

    private fun sleepStageName(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
        else -> "unknown"
    }

    private fun exerciseTypeName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "running"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running_treadmill"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "biking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "biking_stationary"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "strength_training"
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "weightlifting"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "hiit"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "pilates"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "swimming_pool"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swimming_open_water"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "rowing"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "rowing_machine"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "stair_climbing"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "stair_climbing_machine"
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "calisthenics"
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "stretching"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "dancing"
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "basketball"
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "soccer"
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "tennis"
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "boxing"
        else -> "other_workout"
    }

    private fun loadCachedSnapshots(): Map<String, DailyHealthSnapshot> {
        if (!snapshotFile.exists()) return emptyMap()
        return runCatching {
            val list = json.decodeFromString<List<DailyHealthSnapshot>>(snapshotFile.readText())
            list.associateBy { it.date }
        }.getOrDefault(emptyMap())
    }

    private fun saveCachedSnapshots(map: Map<String, DailyHealthSnapshot>) {
        runCatching {
            val tmp = File(context.filesDir, "$SNAPSHOT_FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(map.values.sortedBy { it.date }))
            tmp.renameTo(snapshotFile)
        }
    }

    private fun loadInitialConfig(): HealthSyncConfig {
        val raw = prefs.getString("config_cache", null) ?: return HealthSyncConfig()
        return runCatching { json.decodeFromString<HealthSyncConfig>(raw) }.getOrDefault(HealthSyncConfig())
    }

    private fun loadPersistedStatus(): ExtendedSyncStatus {
        return ExtendedSyncStatus(
            lastSyncEpochMs = prefs.getLong("last_sync_ms", 0L),
            lastRecordsUploaded = prefs.getInt("last_uploaded", 0),
            lastWeightsImported = prefs.getInt("last_weights_imported", 0),
            lastDaysRead = prefs.getInt("last_days_read", 0),
            isSyncing = false,
            error = prefs.getString("last_error", null)
        )
    }

    private fun persistStatus(s: ExtendedSyncStatus) {
        prefs.edit()
            .putLong("last_sync_ms", s.lastSyncEpochMs)
            .putInt("last_uploaded", s.lastRecordsUploaded)
            .putInt("last_weights_imported", s.lastWeightsImported)
            .putInt("last_days_read", s.lastDaysRead)
            .putString("last_error", s.error)
            .apply()
    }
}
