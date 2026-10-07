package com.bytelog.app.data.repository

import com.bytelog.app.data.local.db.WaterLogDao
import com.bytelog.app.data.local.model.WaterLogEntry
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.remote.WaterContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

const val ML_PER_FL_OZ = 29.5735f

fun Float.toOz(): Float = this / ML_PER_FL_OZ
fun Float.toMl(): Float = this * ML_PER_FL_OZ
fun WaterLogEntry.waterOz(): Float = this.waterMl / ML_PER_FL_OZ

class WaterRepository(
    private val waterLogDao: WaterLogDao,
    private val sparkyApiClient: SparkyApiClient,
    private val settingsRepository: SettingsRepository,
    private val appScope: CoroutineScope
) {
    private val _containers = MutableStateFlow<List<WaterContainer>>(emptyList())
    val containers: StateFlow<List<WaterContainer>> = _containers.asStateFlow()

    val targetWaterOz: Flow<Float> = settingsRepository.targetWaterOz
    val defaultContainerOz: Flow<Float> = settingsRepository.defaultContainerOz
    val defaultContainerName: Flow<String> = settingsRepository.defaultContainerName

    fun getLogsForDate(date: String): Flow<List<WaterLogEntry>> {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
        return waterLogDao.getLogsForDate(cleanDate)
    }

    fun getTotalWaterOzForDate(date: String): Flow<Float> {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
        return waterLogDao.getTotalWaterForDate(cleanDate).map { ml -> ml / ML_PER_FL_OZ }
    }

    suspend fun setDefaultContainer(oz: Float, name: String) {
        settingsRepository.saveDefaultContainer(oz, name)
    }

    suspend fun setTargetWaterOz(targetOz: Float) {
        settingsRepository.saveWaterTargetOz(targetOz)
    }

    /**
     * Instantly log water intake in oz locally (0ms optimistic latency)
     * and asynchronously sync with Sparky Fitness Server.
     */
    fun quickAddWaterOz(
        date: String,
        amountOz: Float,
        containerId: Int? = null,
        containerName: String? = null
    ) {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
        val amountMl = amountOz * ML_PER_FL_OZ
        val nowIso = try {
            Instant.now().toString()
        } catch (_: Exception) {
            null
        }

        val rawDisplayName = containerName ?: "${amountOz.roundToInt()} oz"
        val displayName = rawDisplayName
            .replace("591 ml", "20 oz", ignoreCase = true)
            .replace("591ml", "20 oz", ignoreCase = true)

        val entry = WaterLogEntry(
            id = UUID.randomUUID().toString(),
            entryDate = cleanDate,
            waterMl = amountMl,
            containerId = containerId,
            containerName = displayName,
            loggedAt = nowIso,
            syncStatus = WaterLogEntry.SYNC_STATUS_PENDING_INSERT
        )

        appScope.launch(Dispatchers.IO) {
            waterLogDao.insertLog(entry)
            // Send it (with any other drinks Sparky hasn't received yet), then reconcile the day
            try {
                pushPendingWater()
                syncWaterLogs(cleanDate)
            } catch (_: Exception) {}
        }
    }

    private val pushLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Sends drinks logged and removed on the phone that Sparky hasn't accepted yet, for any date. A drink only
     * stops being pending once Sparky has it (so it is never counted twice), and anything refused is kept and
     * tried again on the next sync. Returns how many changes are still waiting.
     */
    suspend fun pushPendingWater(): Int = withContext(Dispatchers.IO) {
        pushLock.withLock {
            var failed = 0
            for (log in waterLogDao.getPendingInsertLogs()) {
                val oz = log.waterMl / ML_PER_FL_OZ
                val ok = try { pushDrink(log.entryDate, oz, log.containerId, log.containerName ?: "${oz.roundToInt()} oz") } catch (_: Exception) { false }
                if (ok) waterLogDao.updateSyncStatus(log.id, WaterLogEntry.SYNC_STATUS_SYNCED) else failed++
            }
            for (log in waterLogDao.getPendingDeleteLogs()) {
                val ok = try {
                    sparkyApiClient.deleteWaterLog(log.id).isSuccess ||
                        sparkyApiClient.upsertWaterIntake(log.entryDate, -1, log.containerId).isSuccess
                } catch (_: Exception) { false }
                if (ok) waterLogDao.deleteLogById(log.id) else failed++
            }
            failed
        }
    }

    suspend fun countPending(): Int = withContext(Dispatchers.IO) { waterLogDao.countPendingSync() }

    /** Removes a drink: straight away if Sparky never got it, otherwise once Sparky confirms. */
    private suspend fun removeLog(log: WaterLogEntry) {
        if (log.syncStatus == WaterLogEntry.SYNC_STATUS_PENDING_INSERT) {
            waterLogDao.deleteLogById(log.id)
        } else {
            waterLogDao.updateSyncStatus(log.id, WaterLogEntry.SYNC_STATUS_PENDING_DELETE)
            try {
                pushPendingWater()
                syncWaterLogs(log.entryDate)
            } catch (_: Exception) {}
        }
    }

    /** Sends one drink to Sparky, creating a matching container if needed. True when Sparky accepted it. */
    private suspend fun pushDrink(cleanDate: String, amountOz: Float, containerId: Int?, displayName: String): Boolean {
        if (containerId != null) {
            return sparkyApiClient.upsertWaterIntake(cleanDate, 1, containerId).isSuccess
        }
        val amountMl = amountOz * ML_PER_FL_OZ
        // Check if matching container already exists on Sparky.
        // Container volumes are always in ml on Sparky, whatever their unit label says.
        val existingContainer = _containers.value.find { container -> abs(container.volumeMl - amountMl) < 5f }
        if (existingContainer != null) {
            return sparkyApiClient.upsertWaterIntake(cleanDate, 1, existingContainer.id).isSuccess
        }
        // Create a container for this amount (volume in ml, as Sparky stores it)
        val created = sparkyApiClient.createWaterContainer(name = displayName, volume = amountMl, unit = "ml").getOrNull()
        if (created != null) {
            _containers.value = _containers.value + created
            return sparkyApiClient.upsertWaterIntake(cleanDate, 1, created.id).isSuccess
        }
        val changeDrinks = (amountMl / 250f).coerceAtLeast(1f).roundToInt()
        return sparkyApiClient.upsertWaterIntake(cleanDate, changeDrinks, null).isSuccess
    }

    /**
     * Remove the most recent water log entry (undo function).
     */
    fun undoLastDrink(date: String) {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
        appScope.launch(Dispatchers.IO) {
            waterLogDao.getLatestLogForDate(cleanDate)?.let { removeLog(it) }
        }
    }

    /**
     * Delete a specific water entry.
     */
    fun deleteWaterLog(entry: WaterLogEntry) {
        appScope.launch(Dispatchers.IO) { removeLog(entry) }
    }

    /**
     * Fully sync water logs and intake totals for a date from Sparky Fitness Server.
     */
    suspend fun syncWaterForDate(date: String): Result<Float> = withContext(Dispatchers.IO) {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date

        // 0. Send the phone's unsent drinks and removals first, so the download below can't drop them
        try { pushPendingWater() } catch (_: Exception) {}

        // 1. Fetch user water containers if empty
        try {
            val containersRes = sparkyApiClient.fetchWaterContainers()
            if (containersRes.isSuccess) {
                val list = containersRes.getOrNull().orEmpty()
                _containers.value = list

                // If primary container exists, adopt its name and volume if user hasn't set custom
                val primary = list.find { it.isPrimary }
                if (primary != null) {
                    val name = primary.name
                        .replace("591 ml", "20 oz", ignoreCase = true)
                        .replace("591ml", "20 oz", ignoreCase = true)
                    settingsRepository.saveDefaultContainer(primary.volumeOz, name)
                }
            }
        } catch (_: Exception) {}

        // 2. Fetch and reconcile itemized logs
        syncWaterLogs(cleanDate)

        // 3. Fetch authoritative total
        val intakeRes = sparkyApiClient.fetchWaterIntake(cleanDate)
        if (intakeRes.isSuccess) {
            val totalMl = intakeRes.getOrNull() ?: 0f
            Result.success(totalMl / ML_PER_FL_OZ)
        } else {
            Result.failure(intakeRes.exceptionOrNull() ?: Exception("Failed to sync water intake"))
        }
    }

    private suspend fun syncWaterLogs(cleanDate: String) {
        try {
            val logsRes = sparkyApiClient.fetchWaterLog(cleanDate)
            if (logsRes.isSuccess) {
                val serverDtos = logsRes.getOrNull().orEmpty()
                val serverEntries = serverDtos.map { dto ->
                    val oz = if (abs(dto.waterMl - 591f) < 5f) 20f else dto.waterMl / ML_PER_FL_OZ
                    val rawName = dto.containerName ?: "${oz.roundToInt()} oz"
                    val name = rawName
                        .replace("591 ml", "20 oz", ignoreCase = true)
                        .replace("591ml", "20 oz", ignoreCase = true)
                    WaterLogEntry(
                        id = dto.id,
                        entryDate = dto.entryDate,
                        waterMl = dto.waterMl,
                        containerId = dto.containerId,
                        containerName = name,
                        loggedAt = dto.createdAt,
                        syncStatus = WaterLogEntry.SYNC_STATUS_SYNCED
                    )
                }

                // Delete only synced entries for this date, preserve any pending ones, and don't bring back
                // drinks removed on the phone whose removal Sparky hasn't confirmed yet
                val removing = waterLogDao.getPendingDeleteLogs().map { it.id }.toSet()
                waterLogDao.deleteSyncedLogsForDate(cleanDate)
                val incoming = serverEntries.filter { it.id !in removing }
                if (incoming.isNotEmpty()) {
                    waterLogDao.insertLogs(incoming)
                }
            }
        } catch (_: Exception) {}
    }
}
