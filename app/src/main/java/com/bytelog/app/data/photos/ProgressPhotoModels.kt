package com.bytelog.app.data.photos

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs

@Serializable
enum class PhotoPose(val apiValue: String, val label: String) {
    FRONT("front", "Front"),
    SIDE("side", "Side"),
    BACK("back", "Back");

    companion object {
        fun fromApiValue(raw: String?): PhotoPose? {
            if (raw.isNullOrBlank()) return null
            val normalized = raw.trim().lowercase(Locale.US)
            return entries.firstOrNull {
                it.apiValue == normalized || it.name.lowercase(Locale.US) == normalized
            }
        }
    }
}

@Serializable
data class ProgressPhotoItem(
    val id: String,
    val date: String,
    val pose: PhotoPose,
    val weightKg: Float? = null,
    val localPath: String? = null,
    val isSynced: Boolean = true
)

object ProgressPhotoFormatters {
    private const val KG_TO_LBS = 2.2046226f

    fun convertWeight(weightKg: Float, unit: String): Float {
        return if (unit.equals("kg", ignoreCase = true)) weightKg else weightKg * KG_TO_LBS
    }

    fun formatWeight(weightKg: Float?, unit: String): String? {
        if (weightKg == null) return null
        val normalizedUnit = if (unit.equals("kg", ignoreCase = true)) "kg" else "lbs"
        val value = convertWeight(weightKg, normalizedUnit)
        return String.format(Locale.US, "%.1f %s", value, normalizedUnit)
    }

    fun formatWeightDelta(deltaKg: Float?, unit: String): String? {
        if (deltaKg == null) return null
        val normalizedUnit = if (unit.equals("kg", ignoreCase = true)) "kg" else "lbs"
        val value = convertWeight(deltaKg, normalizedUnit)
        val sign = when {
            value > 0.05f -> "+"
            value < -0.05f -> "-"
            else -> ""
        }
        return String.format(Locale.US, "%s%.1f %s", sign, abs(value), normalizedUnit)
    }
}
