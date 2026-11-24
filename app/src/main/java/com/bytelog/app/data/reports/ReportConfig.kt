package com.bytelog.app.data.reports

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bytelog.app.data.repository.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ReportSectionId(val title: String, val description: String) {
    NUTRITION_MACROS("Nutrition & Macros", "Daily calories vs goal, P/C/F split, and goal adherence"),
    MICRONUTRIENTS("Micronutrients", "Pinned daily averages and sparklines for key micronutrients"),
    HYDRATION("Hydration", "Daily water intake bars vs target and streak"),
    WEIGHT_TDEE("Weight & TDEE", "Smoothed weight trend, weekly rate, body fat, and adaptive TDEE"),
    CAFFEINE("Caffeine", "Daily caffeine intake, average, and peak day"),
    FASTING("Fasting", "Completed fasts, average duration, and daily fasting hours"),
    ACTIVITY_SLEEP("Activity & Sleep", "Health Connect steps, active calories, sleep duration, and resting HR"),
    COMPARE("Metric Compare", "Overlay any two metrics and compute their correlation")
}

enum class ReportRange(val days: Int, val label: String) {
    D7(7, "7D"),
    D14(14, "14D"),
    D30(30, "30D"),
    D90(90, "90D")
}

data class MicronutrientDef(
    val key: String,
    val label: String,
    val unit: String,
    val dailyTarget: Double
)

object MicronutrientCatalog {
    val ALL: List<MicronutrientDef> = listOf(
        MicronutrientDef("fiber", "Fiber", "g", 28.0),
        MicronutrientDef("sodium", "Sodium", "mg", 2300.0),
        MicronutrientDef("sugars", "Sugars", "g", 50.0),
        MicronutrientDef("saturated_fat", "Sat. Fat", "g", 20.0),
        MicronutrientDef("potassium", "Potassium", "mg", 3500.0),
        MicronutrientDef("cholesterol", "Cholesterol", "mg", 300.0),
        MicronutrientDef("calcium", "Calcium", "mg", 1000.0),
        MicronutrientDef("iron", "Iron", "mg", 18.0),
        MicronutrientDef("vitamin_a", "Vitamin A", "mcg", 900.0),
        MicronutrientDef("vitamin_c", "Vitamin C", "mg", 90.0)
    )

    val DEFAULT_PINNED: Set<String> = setOf("fiber", "sodium", "sugars", "saturated_fat", "potassium")

    fun byKey(key: String): MicronutrientDef? = ALL.firstOrNull { it.key == key }
}

@Serializable
data class ReportCustomization(
    val order: List<ReportSectionId> = ReportSectionId.entries,
    val hidden: Set<ReportSectionId> = emptySet(),
    val defaultRange: ReportRange = ReportRange.D14,
    val pinnedMicronutrients: Set<String> = MicronutrientCatalog.DEFAULT_PINNED
) {
    val visible: List<ReportSectionId>
        get() = order.filter { it !in hidden }

    val isDefault: Boolean
        get() = this == DEFAULT

    fun move(from: Int, to: Int): ReportCustomization {
        if (from !in order.indices || to !in order.indices || from == to) return this
        val next = order.toMutableList()
        next.add(to, next.removeAt(from))
        return copy(order = next)
    }

    fun toggleSection(section: ReportSectionId, visible: Boolean): ReportCustomization =
        if (visible) copy(hidden = hidden - section) else copy(hidden = hidden + section)

    fun toggleMicronutrient(key: String): ReportCustomization {
        val next = if (key in pinnedMicronutrients) {
            (pinnedMicronutrients - key).ifEmpty { setOf(key) }
        } else {
            pinnedMicronutrients + key
        }
        return copy(pinnedMicronutrients = next)
    }

    fun toJson(): String = codec.encodeToString(this)

    companion object {
        val DEFAULT = ReportCustomization()

        private val codec = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        fun fromJson(raw: String?): ReportCustomization {
            if (raw.isNullOrBlank()) return DEFAULT
            val parsed = runCatching { codec.decodeFromString<ReportCustomization>(raw) }.getOrNull()
                ?: return DEFAULT
            // Ensure any newly added ReportSectionId values are appended in their default order
            val normalizedOrder = parsed.order.distinct().toMutableList()
            ReportSectionId.entries.forEach { id ->
                if (id !in normalizedOrder) normalizedOrder.add(id)
            }
            val validPinned = parsed.pinnedMicronutrients.filter { MicronutrientCatalog.byKey(it) != null }.toSet()
                .ifEmpty { MicronutrientCatalog.DEFAULT_PINNED }
            return parsed.copy(
                order = normalizedOrder,
                pinnedMicronutrients = validPinned
            )
        }
    }
}

/**
 * DataStore persistence for [ReportCustomization] under `KEY_REPORT_CONFIG`.
 */
class ReportConfigStore(private val context: Context) {
    companion object {
        val KEY_REPORT_CONFIG = stringPreferencesKey("report_config")
    }

    val customization: Flow<ReportCustomization> = context.dataStore.data.map { prefs ->
        ReportCustomization.fromJson(prefs[KEY_REPORT_CONFIG])
    }

    suspend fun save(config: ReportCustomization) {
        context.dataStore.edit { prefs ->
            prefs[KEY_REPORT_CONFIG] = config.toJson()
        }
    }
}
