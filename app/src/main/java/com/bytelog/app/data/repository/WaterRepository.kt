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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

class WaterRepository(
    private val waterLogDao: WaterLogDao,
    private val sparkyApiClient: SparkyApiClient,
    private val settingsRepository: SettingsRepository,
    private val appScope: CoroutineScope
) {
    private val _containers = MutableStateFlow<List<WaterContainer>>(emptyList())
    val containers: StateFlow<List<WaterContainer>> = _containers.asStateFlow()

    val targetWater: Flow<Float> = settingsRepository.targetWater

    fun getLogsForDate(date: String): Flow<List<WaterLogEntry>> {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
        return waterLogDao.getLogsForDate(cleanDate)
    }

    fun getTotalWaterForDate(date: String): Flow<Float> {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
        return waterLogDao.getTotalWaterForDate(cleanDate)
    }

    /**
     * Instantly log water intake locally (0ms optimistic latency)
     * and asynchronously sync with Sparky Fitness Server.
     */
    fun quickAddWater(
        date: String,
        amountMl: Float,
        containerId: Int? = null,
        containerName: String? = null
    ) {
        val cleanDate = if (date.contains("T")) date.substringBefore("T") else date
        val nowIso = try {
            Instant.now().toString()
        } catch (_: Exception) {
            null
        }

        val entry = WaterLogEntry(
            id = UUID.randomUUID().toString(),
            entryDate = cleanDate,
            waterMl = amountMl,
            containerId = containerId,
            containerName = containerName ?: "${amountMl.toInt()} ml",
            loggedAt = nowIso,
            syncStatus = WaterLogEntry.SYNC_STATUS_PENDING_INSERT
        )

        appScope.launch(Dispatchers.IO) {
            waterLogDao.insertLog(entry)

            // Asynchronously sync with Sparky Fitness
            try {
                if (containerId != null) {
                    sparkyApiClient.upsertWaterIntake(cleanDate, 1, containerId)
                } else {
                    // Check standard 250ml increments
                    when (amountMl) {
                        250f -> sparkyApiClient.upsertWaterIntake(cleanDate, 1, null)
                        500f -> sparkyApiClient.upsertWaterIntake(cleanDate, 2, null)
                        750f -> sparkyApiClient.upsertWaterIntake(cleanDate, 3, null)
                        1000f -> sparkyApiClient.upsertWaterIntake(cleanDate, 4, null)
                        else -> {
                            // Check if matching container already exists
                            val existingContainer = _containers.value.find { it.volume == amountMl }
                            if (existingContainer != null) {
                                sparkyApiClient.upsertWaterIntake(cleanDate, 1, existingContainer.id)
                            } else {
                                // Create custom container in Sparky so exact amount is tracked
                                val createRes = sparkyApiClient.createWaterContainer(
                                    name = "${amountMl.toInt()} ml",
                                    volume = amountMl,
                                    unit = "ml"
                                )
                                if (createRes.isSuccess) {
                                    val newContainer = createRes.getOrNull()
                                    if (newContainer != null) {
                                        _containers.value = _containers.value + newContainer
                                        sparkyApiClient.upsertWaterIntake(cleanDate, 1, newContainer.id)
                                    } else {
                                        val changeDrinks = (amountMl / 250f).coerceAtLeast(1f).toInt()
                                        sparkyApiClient.upsertWaterIntake(cleanDate, changeDrinks, null)
                                    }
                                } else {
                                    val changeDrinks = (amountMl / 250f).coerceAtLeast(1f).toInt()
                                    sparkyApiClient.upsertWaterIntake(cleanDate, changeDrinks, null)
                                }
                            }
                        }
                    }
                }

                // Reconcile itemized logs with Sparky to replace local UUID with server UUID
                syncWaterLogs(cleanDate)
            } catch (_: Exception) {}
        }
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
                _containers.value = containersRes.getOrNull().orEmpty()
            }
        } catch (_: Exception) {}

        // 2. Fetch and reconcile itemized logs
        syncWaterLogs(cleanDate)

        // 3. Fetch authoritative total
        val intakeRes = sparkyApiClient.fetchWaterIntake(cleanDate)
        if (intakeRes.isSuccess) {
            val totalMl = intakeRes.getOrNull() ?: 0f
            Result.success(totalMl)
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
                    WaterLogEntry(
                        id = dto.id,
                        entryDate = dto.entryDate,
                        waterMl = dto.waterMl,
                        containerId = dto.containerId,
                        containerName = dto.containerName ?: "${dto.waterMl.toInt()} ml",
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
