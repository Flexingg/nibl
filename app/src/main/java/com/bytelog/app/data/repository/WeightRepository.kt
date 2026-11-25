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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.bytelog.app.data.remote.fetchWeightForDate
import com.bytelog.app.data.remote.upsertWeight
import com.bytelog.app.data.remote.fetchWeightRange

class WeightRepository(
    private val weightDao: WeightDao,
    private val sparkyApiClient: SparkyApiClient,
    private val settingsRepository: SettingsRepository,
    private val appScope: CoroutineScope
) {
    companion object {
        const val LBS_TO_KG = 0.45359237f
        const val KG_TO_LBS = 2.20462262f
    }

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

    suspend fun saveWeightUnit(newUnit: String) = withContext(Dispatchers.IO) {
        val oldUnit = settingsRepository.weightUnit.first()
        if (!oldUnit.equals(newUnit, ignoreCase = true)) {
            val allWeights = weightDao.getAllWeightsList()
            if (allWeights.isNotEmpty()) {
                val converted = allWeights.map { entry ->
                    val newWeight = if (newUnit.equals("lbs", ignoreCase = true)) {
                        entry.weight * KG_TO_LBS
                    } else {
                        entry.weight * LBS_TO_KG
                    }
                    entry.copy(
                        weight = (newWeight * 10).toInt() / 10f,
                        unit = newUnit,
                        updatedAt = System.currentTimeMillis()
                    )
                }
                weightDao.insertOrUpdateAll(converted)
            }
            settingsRepository.saveWeightUnit(newUnit)
        }
    }

    /** Called with each weigh-in logged here (date, kg, body fat %): writes it to Health Connect when that's on. */
    @Volatile var afterLog: (suspend (date: String, kg: Double, bodyFat: Double?) -> Unit)? = null

    fun logWeight(date: String, weight: Float, unit: String? = null, bodyFatPercentage: Float? = null, fromHealthConnect: Boolean = false) {
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

            // Sparky Fitness check-in API expects weight in KG!
            val weightInKg = if (resolvedUnit.equals("lbs", ignoreCase = true)) {
                weight * LBS_TO_KG
            } else {
                weight
            }

            // Not one read from Health Connect: it's there already
            if (!fromHealthConnect) runCatching { afterLog?.invoke(date, weightInKg.toDouble(), bodyFatPercentage?.toDouble()) }

            val remoteResult = sparkyApiClient.upsertWeight(date, weightInKg, bodyFatPercentage)
            if (remoteResult.isSuccess) {
                weightDao.insertOrUpdate(entry.copy(syncStatus = WeightEntry.SYNC_STATUS_SYNCED))
            }
        }
    }

    /**
     * Sends weigh-ins made on the phone that Sparky hasn't accepted yet. Returns how many are still waiting.
     */
    suspend fun pushPendingWeights(): Int = withContext(Dispatchers.IO) {
        var failed = 0
        for (entry in weightDao.getPendingWeights()) {
            val kg = if (entry.unit.equals("lbs", ignoreCase = true)) entry.weight * LBS_TO_KG else entry.weight
            val ok = try { sparkyApiClient.upsertWeight(entry.entryDate, kg, entry.bodyFatPercentage).isSuccess } catch (_: Exception) { false }
            // Only mark it synced if it wasn't changed again while sending
            if (ok) {
                val now = weightDao.getAllWeightsList().firstOrNull { it.entryDate == entry.entryDate }
                if (now != null && now.updatedAt == entry.updatedAt) weightDao.insertOrUpdate(now.copy(syncStatus = WeightEntry.SYNC_STATUS_SYNCED))
            } else failed++
        }
        failed
    }

    suspend fun countPending(): Int = withContext(Dispatchers.IO) { weightDao.getPendingWeights().size }

    /** Dates whose weigh-in on the phone hasn't reached Sparky yet; a download must not overwrite these. */
    private suspend fun pendingDates(): Set<String> = weightDao.getPendingWeights().map { it.entryDate }.toSet()

    suspend fun syncWeightForDate(date: String) = withContext(Dispatchers.IO) {
        pushPendingWeights()
        if (date in pendingDates()) return@withContext
        val remoteWeightResult = sparkyApiClient.fetchWeightForDate(date)
        if (remoteWeightResult.isSuccess) {
            val remoteWeightKg = remoteWeightResult.getOrNull()
            if (remoteWeightKg != null && remoteWeightKg > 0f) {
                val currentUnit = settingsRepository.weightUnit.first()
                val displayWeight = if (currentUnit.equals("lbs", ignoreCase = true)) {
                    (remoteWeightKg * KG_TO_LBS * 10).toInt() / 10f
                } else {
                    remoteWeightKg
                }
                val entry = WeightEntry(
                    entryDate = date,
                    weight = displayWeight,
                    unit = currentUnit,
                    syncStatus = WeightEntry.SYNC_STATUS_SYNCED,
                    updatedAt = System.currentTimeMillis()
                )
                weightDao.insertOrUpdate(entry)
            }
        }
    }

    suspend fun syncWeightHistory(days: Int = 90): Result<Int> = withContext(Dispatchers.IO) {
        try {
            pushPendingWeights()
            val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val today = LocalDate.now()
            val startDate = today.minusDays(days.toLong()).format(dateFormatter)
            val endDate = today.format(dateFormatter)

            val remoteResult = sparkyApiClient.fetchWeightRange(startDate, endDate)
            if (remoteResult.isSuccess) {
                val list = remoteResult.getOrNull().orEmpty()
                if (list.isNotEmpty()) {
                    val currentUnit = settingsRepository.weightUnit.first()
                    val isLbs = currentUnit.equals("lbs", ignoreCase = true)
                    val keep = pendingDates()
                    val entries = list.filter { (date, _) -> date !in keep }.map { (date, kg) ->
                        val displayW = if (isLbs) (kg * KG_TO_LBS * 10).toInt() / 10f else kg
                        WeightEntry(
                            entryDate = date,
                            weight = displayW,
                            unit = currentUnit,
                            syncStatus = WeightEntry.SYNC_STATUS_SYNCED,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    weightDao.insertOrUpdateAll(entries)
                }
                Result.success(list.size)
            } else {
                Result.failure(remoteResult.exceptionOrNull() ?: Exception("Failed to fetch weights from Sparky"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun repairKgStoredAsLbs() = withContext(Dispatchers.IO) {
        val currentUnit = settingsRepository.weightUnit.first()
        if (currentUnit.equals("lbs", ignoreCase = true)) {
            val allWeights = weightDao.getAllWeightsList()
            val needFix = allWeights.filter { it.unit.equals("lbs", ignoreCase = true) && it.weight in 35f..120f }
            if (needFix.isNotEmpty()) {
                val fixed = needFix.map { entry ->
                    entry.copy(
                        weight = ((entry.weight * KG_TO_LBS) * 10).toInt() / 10f,
                        updatedAt = System.currentTimeMillis()
                    )
                }
                weightDao.insertOrUpdateAll(fixed)
            }
        }
    }
}
