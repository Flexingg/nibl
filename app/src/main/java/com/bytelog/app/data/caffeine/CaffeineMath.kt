package com.bytelog.app.data.caffeine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.log2
import kotlin.math.pow

/**
 * Pure Kotlin exponential caffeine pharmacokinetics:
 *   active(t) = Σ mg_i * 2^(-Δt_hours / halfLifeHours)  for all doses with Δt >= 0.
 */
object CaffeineMath {

    private const val MS_PER_HOUR = 3_600_000.0
    private val HHMM_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Parses a dose timestamp in ISO-8601 (Instant, OffsetDateTime, ZonedDateTime, LocalDateTime)
     * or `HH:mm` / `HH:mm:ss` format into epoch milliseconds.
     */
    fun parseDoseEpochMs(
        at: String,
        fallbackDate: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long? {
        val raw = at.trim()
        if (raw.isEmpty()) return null

        runCatching { return Instant.parse(raw).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(raw).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(raw).atZone(zoneId).toInstant().toEpochMilli() }
        runCatching {
            val normalized = if (raw.length == 5) "$raw:00" else raw
            val time = LocalTime.parse(normalized)
            return LocalDateTime.of(fallbackDate, time).atZone(zoneId).toInstant().toEpochMilli()
        }
        return null
    }

    /** Formats a dose's `at` field to local `HH:mm` for UI display. */
    fun formatDoseTime(
        at: String,
        fallbackDate: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val ms = parseDoseEpochMs(at, fallbackDate, zoneId) ?: return at.take(5)
        return Instant.ofEpochMilli(ms).atZone(zoneId).toLocalTime().format(HHMM_FORMATTER)
    }

    /**
     * Computes active caffeine (mg) at [targetEpochMs] across [doses] using exponential decay.
     */
    fun activeAt(
        doses: List<CaffeineDose>,
        targetEpochMs: Long,
        halfLifeHours: Float,
        fallbackDate: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Float {
        val parsed = doses.mapNotNull { dose ->
            val ms = parseDoseEpochMs(dose.at, fallbackDate, zoneId) ?: return@mapNotNull null
            ms to dose.mg
        }
        return activeAtParsed(parsed, targetEpochMs, halfLifeHours)
    }

    /** Fast overload for pre-parsed `(epochMs, mg)` pairs. */
    fun activeAtParsed(
        dosesWithMs: List<Pair<Long, Float>>,
        targetEpochMs: Long,
        halfLifeHours: Float
    ): Float {
        val safeHalfLife = halfLifeHours.coerceIn(1f, 24f).toDouble()
        var total = 0.0
        for ((doseMs, mg) in dosesWithMs) {
            if (mg <= 0f) continue
            val deltaMs = targetEpochMs - doseMs
            if (deltaMs < 0L) continue
            val deltaHours = deltaMs.toDouble() / MS_PER_HOUR
            total += mg.toDouble() * 2.0.pow(-deltaHours / safeHalfLife)
        }
        return total.toFloat()
    }

    /**
     * Samples the exponential decay curve across `[startEpochMs, endEpochMs]` in [steps] intervals
     * (returning `steps + 1` `(epochMs, activeMg)` points).
     */
    fun buildDecayCurve(
        doses: List<CaffeineDose>,
        startEpochMs: Long,
        endEpochMs: Long,
        halfLifeHours: Float,
        steps: Int = 48,
        fallbackDate: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<Pair<Long, Float>> {
        val safeSteps = steps.coerceAtLeast(2)
        val span = (endEpochMs - startEpochMs).coerceAtLeast(1L)
        val parsed = doses.mapNotNull { dose ->
            val ms = parseDoseEpochMs(dose.at, fallbackDate, zoneId) ?: return@mapNotNull null
            ms to dose.mg
        }
        return (0..safeSteps).map { i ->
            val t = startEpochMs + (span * i) / safeSteps
            t to activeAtParsed(parsed, t, halfLifeHours)
        }
    }

    /**
     * Resolves [targetBedtime] (`HH:mm`) on [date] into epoch milliseconds.
     * Bedtimes earlier than 06:00 (e.g. `00:30`) are treated as occurring after midnight on `date + 1`.
     */
    fun computeBedtimeEpochMs(
        date: LocalDate,
        targetBedtime: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        val time = runCatching {
            val cleaned = targetBedtime.trim().take(5)
            LocalTime.parse(cleaned, HHMM_FORMATTER)
        }.getOrDefault(LocalTime.of(22, 30))
        val targetDate = if (time.hour < 6) date.plusDays(1) else date
        return LocalDateTime.of(targetDate, time).atZone(zoneId).toInstant().toEpochMilli()
    }

    /**
     * Estimates the latest `HH:mm` time a user could take [nextDoseMg] and still remain at or below
     * [thresholdMg] by [bedtimeEpochMs]. Returns `null` if already at or over [thresholdMg] at bedtime.
     */
    fun estimateCutoffTime(
        doses: List<CaffeineDose>,
        bedtimeEpochMs: Long,
        halfLifeHours: Float,
        nextDoseMg: Float = 200f,
        thresholdMg: Float = 100f,
        fallbackDate: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String? {
        val existingAtBedtime = activeAt(doses, bedtimeEpochMs, halfLifeHours, fallbackDate, zoneId)
        val headroom = thresholdMg - existingAtBedtime
        if (headroom <= 0f) return null
        if (nextDoseMg <= headroom) {
            return Instant.ofEpochMilli(bedtimeEpochMs).atZone(zoneId).toLocalTime().format(HHMM_FORMATTER)
        }
        val safeHalfLife = halfLifeHours.coerceIn(1f, 24f).toDouble()
        val hoursBeforeBed = safeHalfLife * log2(nextDoseMg.toDouble() / headroom.toDouble())
        val cutoffMs = bedtimeEpochMs - (hoursBeforeBed * MS_PER_HOUR).toLong()
        return Instant.ofEpochMilli(cutoffMs).atZone(zoneId).toLocalTime().format(HHMM_FORMATTER)
    }

    /**
     * Builds a complete [CaffeineStatus] locally from cached doses & settings (used offline or for
     * immediate optimistic updates right after logging).
     */
    fun buildLocalStatus(
        date: String,
        doses: List<CaffeineDose>,
        halfLifeHours: Float,
        targetBedtime: String,
        nextDoseMg: Float = 200f,
        thresholdMg: Float = 100f,
        nowEpochMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): CaffeineStatus {
        val localDate = runCatching { LocalDate.parse(date.take(10)) }.getOrDefault(LocalDate.now())
        val bedtimeMs = computeBedtimeEpochMs(localDate, targetBedtime, zoneId)
        val bedtimeIso = Instant.ofEpochMilli(bedtimeMs).atZone(zoneId).toOffsetDateTime().toString()
        val activeNow = activeAt(doses, nowEpochMs, halfLifeHours, localDate, zoneId)
        val atBedtime = activeAt(doses, bedtimeMs, halfLifeHours, localDate, zoneId)
        val headroom = (thresholdMg - atBedtime).coerceAtLeast(0f)
        val cutoffTime = estimateCutoffTime(
            doses = doses,
            bedtimeEpochMs = bedtimeMs,
            halfLifeHours = halfLifeHours,
            nextDoseMg = nextDoseMg,
            thresholdMg = thresholdMg,
            fallbackDate = localDate,
            zoneId = zoneId
        )
        val totalToday = doses.sumOf { it.mg.toDouble() }.toFloat()

        val cutoffEpochMs = cutoffTime?.let { parseDoseEpochMs(it, localDate, zoneId) }
        val cutoffState = when {
            atBedtime >= thresholdMg -> "over_threshold"
            cutoffEpochMs != null && nowEpochMs > cutoffEpochMs -> "past_cutoff"
            atBedtime >= thresholdMg * 0.7f -> "caution"
            else -> "safe"
        }

        return CaffeineStatus(
            halfLifeHours = halfLifeHours.coerceIn(2f, 8f),
            targetBedtime = targetBedtime,
            bedtimeAt = bedtimeIso,
            doses = doses,
            activeMgNow = activeNow,
            atBedtimeMg = atBedtime,
            latestSafeDoseTime = cutoffTime,
            cutoffState = cutoffState,
            bedtimeHeadroomMg = headroom,
            thresholdMg = thresholdMg,
            totalTodayMg = totalToday
        )
    }
}
