package com.bytelog.app.health

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bytelog.app.ByteLogApplication
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job (every 2 hours, network required) that syncs both Nibl's diary-to-Health-Connect
 * writer ([HealthConnectSync]) and the extended Health Connect reader/uploader ([HealthConnectExtendedSync])
 * over the last 3 days when background sync is enabled.
 */
class HealthConnectWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val WORK_NAME = "nibl_health_connect_sync"
        private const val SYNC_INTERVAL_HOURS = 2L
        private const val RECENT_SYNC_DAYS = 3

        /**
         * Optional provider hook so Application wiring can supply its singleton [HealthConnectExtendedSync]
         * instance (sharing the in-memory snapshot StateFlow). Falls back to constructing one from
         * [ByteLogApplication.sparkyApiClient] and [ByteLogApplication.weightRepository].
         */
        @Volatile
        var extendedSyncProvider: ((Context) -> HealthConnectExtendedSync)? = null

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<HealthConnectWorker>(
                SYNC_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as? ByteLogApplication ?: return Result.success()
        if (!HealthConnectStore.isAvailable(applicationContext)) return Result.success()

        val extendedSync = extendedSyncProvider?.invoke(applicationContext)
            ?: HealthConnectExtendedSync(applicationContext, app.sparkyApiClient, app.weightRepository)

        val config = extendedSync.loadConfig()
        if (!config.backgroundSyncEnabled) {
            cancel(applicationContext)
            return Result.success()
        }

        val granted = extendedSync.getGrantedPermissions()
        val hasBackground = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted
        if (!hasBackground) {
            // Cannot read Health Connect in the background without READ_HEALTH_DATA_IN_BACKGROUND
            return Result.success()
        }

        var hadTransientError = false

        // 1. Diary -> Health Connect (food & water, last 3 days) if enabled and permitted
        runCatching {
            val writeEnabled = app.settingsRepository.healthConnectWrite.first()
            if (writeEnabled && granted.containsAll(HealthConnectStore.PERMISSIONS)) {
                val dates = app.healthConnectSync.recentDays(RECENT_SYNC_DAYS)
                app.healthConnectSync.syncDays(dates)
            }
        }

        // 2. Extended Health Connect -> local snapshot cache + WeightRepository + SparkyFitness (last 3 days)
        val extResult = runCatching {
            extendedSync.syncRecent(days = RECENT_SYNC_DAYS, uploadToSparky = true)
        }.getOrNull()

        if (extResult == null || extResult.error != null) {
            hadTransientError = true
        }

        return if (hadTransientError && runAttemptCount < 2) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
