package com.bytelog.app.data.repository

import com.bytelog.app.data.local.db.WeightDao
import com.bytelog.app.data.local.model.WeightEntry
import com.bytelog.app.data.remote.SparkyApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WeightRepository(
    private val weightDao: WeightDao,
    private val sparkyApiClient: SparkyApiClient,
    private val settingsRepository: SettingsRepository,
    private val appScope: CoroutineScope
) {
    val weightUnit: Flow<String> = settingsRepository.weightUnit

    fun getWeightForDate(date: String): Flow<WeightEntry?> {
        return weightDao.getWeightForDate(date)
    }

    fun getLatestWeightOnOrBefore(date: String): Flow<WeightEntry?> {
        return weightDao.getLatestWeightOnOrBefore(date)
    }

    fun getWeightsInRange(startDate: String, endDate: String): Flow<List<WeightEntry>> {
        return weightDao.getWeightsInRange(startDate, endDate)
    }

    fun getAllWeights(): Flow<List<WeightEntry>> {
        return weightDao.getAllWeights()
    }

    suspend fun saveWeightUnit(unit: String) {
        settingsRepository.saveWeightUnit(unit)
    }

    fun logWeight(date: String, weight: Float, unit: String? = null, bodyFatPercentage: Float? = null) {
        appScope.launch(Dispatchers.IO) {
            val resolvedUnit = unit ?: settingsRepository.weightUnit.first()
            val entry = WeightEntry(
                entryDate = date,
                weight = weight,
                unit = resolvedUnit,
                bodyFatPercentage = bodyFatPercentage,
                syncStatus = WeightEntry.SYNC_STATUS_PENDING,
                updatedAt = System.currentTimeMillis()
            )
            weightDao.insertOrUpdate(entry)

            // Sync with Sparky Fitness in background
            val remoteResult = sparkyApiClient.upsertWeight(date, weight, bodyFatPercentage)
            if (remoteResult.isSuccess) {
                weightDao.insertOrUpdate(entry.copy(syncStatus = WeightEntry.SYNC_STATUS_SYNCED))
            }
        }
    }

    suspend fun syncWeightForDate(date: String) = withContext(Dispatchers.IO) {
        val remoteWeightResult = sparkyApiClient.fetchWeightForDate(date)
        if (remoteWeightResult.isSuccess) {
            val remoteWeight = remoteWeightResult.getOrNull()
            if (remoteWeight != null && remoteWeight > 0f) {
                val currentUnit = settingsRepository.weightUnit.first()
                val entry = WeightEntry(
                    entryDate = date,
                    weight = remoteWeight,
                    unit = currentUnit,
                    syncStatus = WeightEntry.SYNC_STATUS_SYNCED
                )
                weightDao.insertOrUpdate(entry)
            }
        }
    }
}
