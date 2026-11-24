package com.bytelog.app.data.fasting

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@Serializable
data class FastingLog(
    val id: String,
    val startTime: String,
    val endTime: String? = null,
    val targetEndTime: String? = null,
    val durationMinutes: Int? = null,
    val fastingType: String = "16-8",
    val status: String = "active",
    val isAutoCalculated: Boolean = false,
    val isEatingWindow: Boolean = false,
    val eatingWindowRemainingMinutes: Int? = null
) {
    val isActive: Boolean
        get() = status.equals("active", ignoreCase = true) ||
            (endTime.isNullOrBlank() && !status.equals("completed", ignoreCase = true) && !status.equals("cancelled", ignoreCase = true))

    fun startInstant(): Instant? = parseFastingInstant(startTime)
    fun endInstant(): Instant? = parseFastingInstant(endTime)
    fun targetEndInstant(): Instant? = parseFastingInstant(targetEndTime)

    fun elapsedMillis(now: Instant = Instant.now()): Long {
        val start = startInstant() ?: return 0L
        val end = endInstant() ?: now
        return Duration.between(start, end).toMillis().coerceAtLeast(0L)
    }

    fun targetDurationMillis(): Long {
        val start = startInstant()
        val target = targetEndInstant()
        if (start != null && target != null) {
            val diff = Duration.between(start, target).toMillis()
            if (diff > 0L) return diff
        }
        val protocol = FastingProtocol.fromCode(fastingType)
        return (protocol.fastHours * 3_600_000L).toLong().coerceAtLeast(3_600_000L)
    }
}

@Serializable
data class FastingPreferences(
    val autoCalculate: Boolean = false,
    val defaultProtocol: String = "16-8",
    val targetFastingHours: Float = 16f,
    val targetEatingHours: Float = 8f,
    val calorieThreshold: Int = 10,
    val preEndAlertMinutes: Int = 30,
    val eatingWindowAlert: Boolean = false
)

@Serializable
data class FastingStats(
    val totalCompletedFasts: Int = 0,
    val totalMinutesFasted: Int = 0,
    val averageDurationMinutes: Int = 0
)

enum class FastingProtocol(
    val code: String,
    val label: String,
    val fastHours: Float,
    val eatHours: Float
) {
    P14_10("14-10", "14:10", 14f, 10f),
    P16_8("16-8", "16:8", 16f, 8f),
    P18_6("18-6", "18:6", 18f, 6f),
    P20_4("20-4", "20:4", 20f, 4f),
    OMAD("23-1", "OMAD", 23f, 1f),
    CUSTOM("custom", "Custom", 16f, 8f);

    companion object {
        fun fromCode(code: String?): FastingProtocol {
            val normalized = code?.trim()?.lowercase()?.replace(':', '-') ?: return P16_8
            return entries.firstOrNull {
                it.code.equals(normalized, ignoreCase = true) ||
                    it.label.equals(code.trim(), ignoreCase = true) ||
                    it.name.equals(code.trim(), ignoreCase = true)
            } ?: CUSTOM
        }
    }
}

enum class MetabolicZone(
    val label: String,
    val minHours: Float,
    val description: String
) {
    BLOOD_SUGAR(
        label = "Blood Sugar",
        minHours = 0f,
        description = "Blood sugar rises and falls as your body digests your last meal (0–4h)"
    ),
    POST_ABSORPTIVE(
        label = "Post-Absorptive",
        minHours = 4f,
        description = "Insulin drops and liver glycogen starts supplying steady energy (4–12h)"
    ),
    FAT_BURNING(
        label = "Fat Burning",
        minHours = 12f,
        description = "Glycogen depletes and your body ramps up fat mobilization (12–16h)"
    ),
    KETOSIS(
        label = "Ketosis",
        minHours = 16f,
        description = "Ketone production increases to power your brain and muscles (16–24h)"
    ),
    DEEP_AUTOPHAGY(
        label = "Deep Autophagy",
        minHours = 24f,
        description = "Cellular cleanup and renewal pathways become more active (24h+)"
    );

    companion object {
        fun forElapsedHours(hours: Float): MetabolicZone {
            val h = hours.coerceAtLeast(0f)
            return entries.lastOrNull { h >= it.minHours } ?: BLOOD_SUGAR
        }

        fun forElapsedMillis(millis: Long): MetabolicZone =
            forElapsedHours(millis / 3_600_000f)
    }
}

/** Parses ISO-8601 timestamps from Sparky (UTC `Z`, offset, or bare local datetime). */
fun parseFastingInstant(iso: String?): Instant? {
    if (iso.isNullOrBlank()) return null
    val trimmed = iso.trim()
    runCatching { return Instant.parse(trimmed) }
    runCatching { return OffsetDateTime.parse(trimmed).toInstant() }
    runCatching {
        val normalized = trimmed.replace(' ', 'T')
        return LocalDateTime.parse(normalized).atZone(ZoneId.systemDefault()).toInstant()
    }
    runCatching {
        val normalized = trimmed.replace(' ', 'T')
        return LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC)
    }
    return null
}

/** Formats milliseconds into `HH:MM:SS` for the live fasting timer. */
fun formatFastingTimer(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

/** Formats minutes into a compact human string like `16h 30m` or `45m`. */
fun formatFastingMinutes(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    val h = safe / 60
    val m = safe % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}
