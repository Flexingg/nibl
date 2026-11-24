package com.bytelog.app.health

import androidx.health.connect.client.permission.HealthPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Standard lookback presets for Health Connect historical backfill.
 */
enum class BackfillPreset(val days: Int?, val label: String) {
    D30(30, "30d"),
    D90(90, "90d"),
    D180(180, "6m"),
    D365(365, "1y"),
    ALL(null, "All")
}

data class BackfillProgress(
    val isRunning: Boolean = false,
    val currentWindowLabel: String = "",
    val completedWindows: Int = 0,
    val totalWindows: Int = 0,
    val recordsUploaded: Int = 0,
    val error: String? = null
) {
    val fraction: Float
        get() = if (totalWindows > 0) (completedWindows.toFloat() / totalWindows).coerceIn(0f, 1f) else 0f
}

/**
 * Walks backwards in 30-day windows from today down to [lookbackDays] (or all available history up to 5 years
 * when `READ_HEALTH_DATA_HISTORY` is granted), syncing each window via [HealthConnectExtendedSync].
 * Supports live progress reporting, cancellation, and retry.
 */
class HealthConnectBackfill(
    private val extendedSync: HealthConnectExtendedSync,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {
    companion object {
        private const val CHUNK_DAYS = 30
        private const val ALL_HISTORY_MAX_DAYS = 365 * 5
        private const val MAX_CONSECUTIVE_EMPTY_AFTER_YEAR = 3
        private val LABEL_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy")
    }

    private val _progress = MutableStateFlow(BackfillProgress())
    val progress: StateFlow<BackfillProgress> = _progress.asStateFlow()

    private var activeJob: Job? = null
    private var lastRequestedDays: Int? = 90
    private var resumeStartWindowIndex: Int = 0

    /**
     * Starts a new backfill over [lookbackDays] (or `null` for all available history).
     * If a backfill is already running, it is cancelled before starting the new one.
     */
    fun start(scope: CoroutineScope, lookbackDays: Int? = 90) {
        activeJob?.cancel()
        lastRequestedDays = lookbackDays
        resumeStartWindowIndex = 0
        launchRun(scope, lookbackDays, startWindowIndex = 0, initialUploaded = 0)
    }

    /**
     * Retries from the window where the previous run failed or was cancelled.
     */
    fun retry(scope: CoroutineScope) {
        activeJob?.cancel()
        val prevUploaded = _progress.value.recordsUploaded
        launchRun(scope, lastRequestedDays, startWindowIndex = resumeStartWindowIndex, initialUploaded = prevUploaded)
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        val cur = _progress.value
        if (cur.isRunning) {
            _progress.value = cur.copy(
                isRunning = false,
                currentWindowLabel = "Cancelled",
                error = null
            )
        }
    }

    private fun launchRun(
        scope: CoroutineScope,
        lookbackDays: Int?,
        startWindowIndex: Int,
        initialUploaded: Int
    ) {
        activeJob = scope.launch(Dispatchers.IO) {
            if (!extendedSync.isAvailable()) {
                _progress.value = BackfillProgress(
                    isRunning = false,
                    error = "Health Connect is not available on this device"
                )
                return@launch
            }

            val granted = extendedSync.getGrantedPermissions()
            val hasHistory = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted
            val requestedDays = lookbackDays ?: ALL_HISTORY_MAX_DAYS
            val effectiveDays = if (!hasHistory && requestedDays > CHUNK_DAYS) {
                CHUNK_DAYS
            } else {
                requestedDays.coerceAtLeast(1)
            }

            val today = LocalDate.now(zone())
            val windows = buildWindows(today, effectiveDays)
            val total = windows.size
            var uploaded = initialUploaded
            var consecutiveEmpty = 0

            _progress.value = BackfillProgress(
                isRunning = true,
                currentWindowLabel = if (!hasHistory && requestedDays > CHUNK_DAYS) {
                    "Limited to 30d (History permission not granted)"
                } else {
                    "Preparing $total window${if (total == 1) "" else "s"}..."
                },
                completedWindows = startWindowIndex.coerceIn(0, total),
                totalWindows = total,
                recordsUploaded = uploaded,
                error = null
            )

            try {
                for (index in startWindowIndex until total) {
                    if (!isActive) throw CancellationException("Backfill cancelled")
                    resumeStartWindowIndex = index
                    val (winStart, winEnd) = windows[index]
                    val label = "${winStart.format(LABEL_FORMATTER)} – ${winEnd.format(LABEL_FORMATTER)}"

                    _progress.value = _progress.value.copy(
                        isRunning = true,
                        currentWindowLabel = label,
                        completedWindows = index,
                        totalWindows = total,
                        recordsUploaded = uploaded,
                        error = null
                    )

                    val res = extendedSync.syncRange(winStart, winEnd, uploadToSparky = true)
                    if (res.error != null) {
                        _progress.value = _progress.value.copy(
                            isRunning = false,
                            currentWindowLabel = label,
                            completedWindows = index,
                            totalWindows = total,
                            recordsUploaded = uploaded,
                            error = res.error
                        )
                        return@launch
                    }

                    uploaded += res.recordsUploaded
                    if (res.recordsUploaded == 0 && res.weightsImported == 0) {
                        consecutiveEmpty++
                    } else {
                        consecutiveEmpty = 0
                    }

                    _progress.value = _progress.value.copy(
                        isRunning = true,
                        currentWindowLabel = label,
                        completedWindows = index + 1,
                        totalWindows = total,
                        recordsUploaded = uploaded,
                        error = null
                    )

                    // For "All Available History", stop once we've scanned > 1 year and hit 3 empty 30-day chunks
                    val daysScanned = (index + 1) * CHUNK_DAYS
                    if (lookbackDays == null && daysScanned >= 365 && consecutiveEmpty >= MAX_CONSECUTIVE_EMPTY_AFTER_YEAR) {
                        break
                    }
                }

                resumeStartWindowIndex = 0
                _progress.value = _progress.value.copy(
                    isRunning = false,
                    currentWindowLabel = "Completed",
                    completedWindows = total,
                    totalWindows = total,
                    recordsUploaded = uploaded,
                    error = null
                )
            } catch (_: CancellationException) {
                _progress.value = _progress.value.copy(
                    isRunning = false,
                    currentWindowLabel = "Cancelled",
                    error = null
                )
            } catch (e: Exception) {
                _progress.value = _progress.value.copy(
                    isRunning = false,
                    error = e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    private fun buildWindows(today: LocalDate, totalDays: Int): List<Pair<LocalDate, LocalDate>> {
        val windows = mutableListOf<Pair<LocalDate, LocalDate>>()
        val earliest = today.minusDays((totalDays - 1).coerceAtLeast(0).toLong())
        var cursorEnd = today
        while (!cursorEnd.isBefore(earliest)) {
            val rawStart = cursorEnd.minusDays((CHUNK_DAYS - 1).toLong())
            val cursorStart = if (rawStart.isBefore(earliest)) earliest else rawStart
            windows += cursorStart to cursorEnd
            cursorEnd = cursorStart.minusDays(1)
        }
        return windows
    }
}
