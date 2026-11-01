package com.bytelog.app.health

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.WaterLogEntry
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs

/** A nutrition or hydration record already in Health Connect (from any app). */
data class ExistingRecord(
    val clientRecordId: String?,
    val packageName: String,
    val name: String? = null,
    val kcal: Double? = null,
    val ml: Double? = null,
    val start: Instant
)

/** What Nibl writes for one food entry. */
data class FoodRecord(val entry: FoodEntry, val start: Instant, val clientRecordId: String)

/** What Nibl writes for one drink. */
data class WaterRecord(val log: WaterLogEntry, val start: Instant, val clientRecordId: String)

/**
 * Deciding what Nibl writes to Health Connect so nothing is counted twice. The SparkyFitness phone app writes the
 * same Sparky diary (each entry as "sparky-nutrition-<entry id>-<version>", each drink as
 * "sparky-water-entry-<id>-<version>"); an entry it has written is left to it, and Nibl's own copy is removed.
 */
object HealthConnectPlan {
    const val NIBL_PREFIX = "nibl-"
    private const val SPARKY_NUTRITION = "sparky-nutrition-"
    private const val SPARKY_WATER_ENTRY = "sparky-water-entry-"

    /** The SparkyFitness phone app (release and development builds). */
    val SPARKY_PACKAGES = setOf("com.SparkyApps.SparkyFitnessMobile", "com.SparkyApps.SparkyFitnessMobile.dev")

    /** "sparky-nutrition-<entry id>-<version>" -> the Sparky entry id (ids contain dashes; the version is the last part). */
    fun sparkyEntryId(clientRecordId: String?): String? = idBetween(clientRecordId, SPARKY_NUTRITION)

    fun sparkyWaterId(clientRecordId: String?): String? = idBetween(clientRecordId, SPARKY_WATER_ENTRY)

    private fun idBetween(clientRecordId: String?, prefix: String): String? {
        if (clientRecordId == null || !clientRecordId.startsWith(prefix)) return null
        val rest = clientRecordId.removePrefix(prefix)
        val cut = rest.lastIndexOf('-')
        return if (cut > 0 && rest.substring(cut + 1).all { it.isDigit() }) rest.substring(0, cut) else rest.takeIf { it.isNotEmpty() }
    }

    private fun fromSparky(r: ExistingRecord) = r.packageName in SPARKY_PACKAGES || r.clientRecordId?.startsWith("sparky-") == true

    private fun sameName(a: String?, b: String?) =
        a != null && b != null && a.trim().lowercase().replace(Regex("\\s+"), " ") == b.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Usual times for a meal logged without one (the same as SparkyFitness uses, so records line up). */
    private fun mealTime(mealName: String): LocalTime = when (mealName.trim().lowercase()) {
        "breakfast" -> LocalTime.of(8, 0)
        "lunch" -> LocalTime.of(12, 30)
        "dinner" -> LocalTime.of(19, 0)
        else -> LocalTime.of(15, 0)
    }

    /** When the food was eaten: its logged time on its day, else the meal's usual time. Null while still ahead. */
    fun foodTime(e: FoodEntry, zone: ZoneId, now: Instant): Instant? {
        val day = runCatching { LocalDate.parse(e.entryDate.take(10)) }.getOrNull() ?: return null
        val time = e.entryTime?.let { t -> runCatching { LocalTime.parse(if (t.length == 5) "$t:00" else t.take(8)) }.getOrNull() }
            ?: mealTime(e.mealName)
        val at = day.atTime(time).atZone(zone).toInstant()
        return at.takeIf { !it.isAfter(now) }
    }

    fun waterTime(w: WaterLogEntry, zone: ZoneId, now: Instant): Instant? {
        val logged = w.loggedAt?.let { s ->
            runCatching { Instant.parse(s) }.getOrNull() ?: runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
        }
        val at = logged ?: runCatching { LocalDate.parse(w.entryDate.take(10)).atTime(12, 0).atZone(zone).toInstant() }.getOrNull() ?: return null
        return at.takeIf { !it.isAfter(now) }
    }

    /**
     * The day's food Nibl should write: logged by hand (not something Sparky imported, which already came from
     * Health Connect), not being deleted, eaten by now, and not already written by the SparkyFitness app, matched by
     * its Sparky entry id, or failing that by name and calories.
     */
    fun foodToWrite(entries: List<FoodEntry>, existing: List<ExistingRecord>, zone: ZoneId, now: Instant, version: Long): List<FoodRecord> {
        val sparky = existing.filter(::fromSparky)
        val coveredIds = sparky.mapNotNull { sparkyEntryId(it.clientRecordId) }.toSet()
        val unmatched = sparky.filter { sparkyEntryId(it.clientRecordId) == null }.toMutableList()
        return entries.mapNotNull { e ->
            if (e.syncStatus == FoodEntry.SYNC_STATUS_PENDING_DELETE || e.source != null) return@mapNotNull null
            if (e.id in coveredIds) return@mapNotNull null
            val twin = unmatched.firstOrNull { r -> sameName(r.name, e.foodName) && abs((r.kcal ?: -1.0) - e.calories) <= 1.0 }
            if (twin != null) { unmatched.remove(twin); return@mapNotNull null }
            val at = foodTime(e, zone, now) ?: return@mapNotNull null
            FoodRecord(e, at, "${NIBL_PREFIX}nutrition-${e.id}-$version")
        }
    }

    /** The day's drinks Nibl should write: logged by hand and not already written by the SparkyFitness app. */
    fun waterToWrite(logs: List<WaterLogEntry>, existing: List<ExistingRecord>, zone: ZoneId, now: Instant, version: Long): List<WaterRecord> {
        val sparky = existing.filter(::fromSparky)
        val coveredIds = sparky.mapNotNull { sparkyWaterId(it.clientRecordId) }.toSet()
        val unmatched = sparky.filter { sparkyWaterId(it.clientRecordId) == null && it.clientRecordId?.startsWith("sparky-water-2") != true }.toMutableList()
        return logs.mapNotNull { w ->
            if (w.syncStatus == WaterLogEntry.SYNC_STATUS_PENDING_DELETE || (w.source != null && !w.source.equals("manual", ignoreCase = true))) return@mapNotNull null
            if (w.id in coveredIds) return@mapNotNull null
            val at = waterTime(w, zone, now) ?: return@mapNotNull null
            val twin = unmatched.firstOrNull { r -> abs((r.ml ?: -1.0) - w.waterMl) <= 1.0 && abs(r.start.epochSecond - at.epochSecond) <= 120 }
            if (twin != null) { unmatched.remove(twin); return@mapNotNull null }
            WaterRecord(w, at, "${NIBL_PREFIX}water-${w.id}-$version")
        }
    }

    /** Content of what would be written, so an unchanged day isn't rewritten (Health Connect limits writes). */
    fun signature(food: List<FoodRecord>, water: List<WaterRecord>): String =
        (food.map { "f:${it.entry.id}:${it.entry.foodName}:${it.entry.calories}:${it.entry.protein}:${it.entry.carbs}:${it.entry.fat}:${it.start.epochSecond}" } +
            water.map { "w:${it.log.id}:${it.log.waterMl}:${it.start.epochSecond}" }).sorted().joinToString("|").hashCode().toString()
}
