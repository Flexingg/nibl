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

            // Asynchronously sync with Sparky Fitness
            try {
                val pushed = pushDrink(cleanDate, amountOz, containerId, displayName)
                // Once Sparky has the drink, the local copy must stop counting as a separate pending
                // one. Reconciling below swaps it for the server's own entry; leaving it pending made
                // every drink count twice.
                if (pushed) waterLogDao.updateSyncStatus(entry.id, WaterLogEntry.SYNC_STATUS_SYNCED)

                // Reconcile itemized logs with Sparky
                syncWaterLogs(cleanDate)
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
            val latest = waterLogDao.getLatestLogForDate(cleanDate)
            if (latest != null) {
                waterLogDao.deleteLogById(latest.id)
                try {
                    // Try direct delete by ID first
                    val delRes = sparkyApiClient.deleteWaterLog(latest.id)
                    if (!delRes.isSuccess) {
                        // Fallback to decrementing drinks count
                        sparkyApiClient.upsertWaterIntake(cleanDate, -1, latest.containerId)
                    }
                    syncWaterLogs(cleanDate)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Delete a specific water entry.
     */
    fun deleteWaterLog(entry: WaterLogEntry) {
        appScope.launch(Dispatchers.IO) {
            waterLogDao.deleteLogById(entry.id)
            try {
                sparkyApiClient.deleteWaterLog(entry.id)
                syncWaterLogs(entry.entryDate)
            } catch (_: Exception) {}
        }
    }

    /**
     * Fully sync water logs and intake totals for a date from Sparky Fitness Server.
     */
    suspend fun syncWaterForDate(date: String): Result<Float> = withContext(Dispatchers.IO) {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date

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

                // Delete only synced entries for this date, preserve any pending ones
                waterLogDao.deleteSyncedLogsForDate(cleanDate)
                if (serverEntries.isNotEmpty()) {
                    waterLogDao.insertLogs(serverEntries)
                }
            }
        } catch (_: Exception) {}
    }
}
