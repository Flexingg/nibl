package com.bytelog.app.data.reports

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.max

/** An exercise the user has logged, for the Exercise Progress picker. */
data class LoggedExercise(val id: String, val name: String)

/** One set as Sparky stores it. Weight is in kg, duration in seconds. */
data class ExerciseSetLog(
    val setNumber: Int,
    val type: String?,
    val reps: Double?,
    val weightKg: Double?,
    val durationSec: Double?,
    val rpe: Double?,
    val isPr: Boolean = false
)

/** One logged session of an exercise (an exercise entry) with its sets. */
data class ExerciseSessionLog(
    val id: String,
    val date: LocalDate,
    val durationMinutes: Double,
    val caloriesBurned: Double,
    val modality: String?,
    val bodyWeightKg: Double?,
    val sets: List<ExerciseSetLog>
)

enum class ProgressGrouping(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month") }

/** A day, week or month of an exercise: the numbers the progress charts plot. Weights in kg. */
data class ProgressPoint(
    val start: LocalDate,
    val volumeKg: Double,
    val maxWeightKg: Double,
    val estimated1RmKg: Double,
    val timeUnderTensionMin: Double,
    val sets: Int,
    val reps: Int
)

/** The single best set by estimated 1RM. */
data class BestSet(val date: LocalDate, val weightKg: Double, val reps: Int, val estimated1RmKg: Double)

data class ExerciseProgressReport(
    val exercise: LoggedExercise,
    val sessions: Int,
    val points: List<ProgressPoint>,
    /** Average weight lifted for each rep count (kg), fewest reps first. */
    val repsVsWeight: List<Pair<Int, Double>>,
    val bestSet: BestSet?,
    val heaviest: BestSet?,
    val mostReps: BestSet?,
    val bestSessionVolumeKg: Double,
    val totalVolumeKg: Double,
    val totalSets: Int,
    val totalReps: Int,
    /** Change in estimated 1RM from the first to the last point, as a fraction (0.05 = +5%). Null with < 2 points. */
    val estimated1RmChange: Double?,
    /** Recent sessions, newest first, each with its best set. */
    val recent: List<Pair<ExerciseSessionLog, BestSet?>>
)

/**
 * Exercise progress the way SparkyFitness's web app works it out:
 *  - only working sets count: not warm-ups, with reps, and with a weight (unless it's a bodyweight exercise);
 *  - the load is the weight, or body weight plus any added weight for bodyweight exercises;
 *  - volume = load × reps; estimated 1RM (Epley) = load × (1 + reps / 30);
 *  - time under tension adds up every set's duration.
 */
object ExerciseProgress {

    fun isWarmup(type: String?): Boolean = type?.lowercase()?.replace(Regex("[^a-z]"), "")?.contains("warm") == true

    private fun isBodyweight(modality: String?) = modality.equals("bodyweight_reps", ignoreCase = true)

    fun loadKg(set: ExerciseSetLog, session: ExerciseSessionLog): Double {
        val w = set.weightKg ?: 0.0
        return if (isBodyweight(session.modality)) {
            val bw = session.bodyWeightKg ?: 0.0
            if (bw > 0.0) max(0.0, bw + w) else max(0.0, w)
        } else w
    }

    fun counts(set: ExerciseSetLog, session: ExerciseSessionLog): Boolean =
        !isWarmup(set.type) && (set.reps ?: 0.0) > 0.0 && (isBodyweight(session.modality) || (set.weightKg ?: 0.0) > 0.0)

    fun epley1Rm(loadKg: Double, reps: Double): Double = if (loadKg <= 0.0 || reps <= 0.0) 0.0 else loadKg * (1.0 + reps / 30.0)

    fun bucketStart(date: LocalDate, grouping: ProgressGrouping): LocalDate = when (grouping) {
        ProgressGrouping.DAY -> date
        ProgressGrouping.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        ProgressGrouping.MONTH -> date.withDayOfMonth(1)
    }

    private fun bestOf(session: ExerciseSessionLog): BestSet? = session.sets.filter { counts(it, session) }.map { s ->
        val load = loadKg(s, session); val reps = s.reps ?: 0.0
        BestSet(session.date, load, reps.toInt(), epley1Rm(load, reps))
    }.maxByOrNull { it.estimated1RmKg }

    fun report(exercise: LoggedExercise, sessions: List<ExerciseSessionLog>, grouping: ProgressGrouping): ExerciseProgressReport {
        val sorted = sessions.sortedBy { it.date }
        val counted = sorted.flatMap { s -> s.sets.filter { counts(it, s) }.map { s to it } }

        val points = sorted.groupBy { bucketStart(it.date, grouping) }.toSortedMap().map { (start, group) ->
            val sets = group.flatMap { s -> s.sets.filter { counts(it, s) }.map { s to it } }
            ProgressPoint(
                start = start,
                volumeKg = sets.sumOf { (s, set) -> loadKg(set, s) * (set.reps ?: 0.0) },
                maxWeightKg = sets.maxOfOrNull { (s, set) -> loadKg(set, s) } ?: 0.0,
                estimated1RmKg = sets.maxOfOrNull { (s, set) -> epley1Rm(loadKg(set, s), set.reps ?: 0.0) } ?: 0.0,
                timeUnderTensionMin = group.sumOf { s -> s.sets.sumOf { it.durationSec ?: 0.0 } } / 60.0,
                sets = sets.size,
                reps = sets.sumOf { (_, set) -> (set.reps ?: 0.0).toInt() }
            )
        }

        val repsVsWeight = counted.groupBy { (_, set) -> (set.reps ?: 0.0).toInt() }.toSortedMap()
            .map { (reps, list) -> reps to list.map { (s, set) -> loadKg(set, s) }.average() }

        val allSets = counted.map { (s, set) ->
            val load = loadKg(set, s); val reps = set.reps ?: 0.0
            BestSet(s.date, load, reps.toInt(), epley1Rm(load, reps))
        }
        val trend = points.filter { it.estimated1RmKg > 0.0 }
        val change = if (trend.size >= 2) (trend.last().estimated1RmKg - trend.first().estimated1RmKg) / trend.first().estimated1RmKg else null

        return ExerciseProgressReport(
            exercise = exercise,
            sessions = sorted.size,
            points = points,
            repsVsWeight = repsVsWeight,
            bestSet = allSets.maxByOrNull { it.estimated1RmKg },
            heaviest = allSets.maxWithOrNull(compareBy<BestSet> { it.weightKg }.thenBy { it.reps }),
            mostReps = allSets.maxWithOrNull(compareBy<BestSet> { it.reps }.thenBy { it.weightKg }),
            bestSessionVolumeKg = sorted.maxOfOrNull { s -> s.sets.filter { counts(it, s) }.sumOf { loadKg(it, s) * (it.reps ?: 0.0) } } ?: 0.0,
            totalVolumeKg = points.sumOf { it.volumeKg },
            totalSets = counted.size,
            totalReps = counted.sumOf { (_, set) -> (set.reps ?: 0.0).toInt() },
            estimated1RmChange = change,
            recent = sorted.reversed().take(6).map { it to bestOf(it) }
        )
    }

    // ---- Reading Sparky's answers ----

    /** `GET /api/exercises/names`: the exercises the user has logged (ones deleted from the library have no id). */
    fun parseExercises(root: JsonElement?): List<LoggedExercise> = (root as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o.text("id") ?: return@mapNotNull null
        val name = o.text("name") ?: return@mapNotNull null
        if (name.equals("Active Calories", ignoreCase = true)) null else LoggedExercise(id, name)
    }.distinctBy { it.id }.sortedBy { it.name.lowercase() }

    /** `GET /api/exercise-entries/progress/{id}`: one item per logged session, with its sets. */
    fun parseSessions(root: JsonElement?): List<ExerciseSessionLog> = (root as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val date = o.text("entry_date")?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
        val sets = (o["sets"] as? JsonArray).orEmpty().mapIndexedNotNull { i, s ->
            val so = s as? JsonObject ?: return@mapIndexedNotNull null
            ExerciseSetLog(
                setNumber = so.num("set_number")?.toInt() ?: (i + 1),
                type = so.text("set_type"),
                reps = so.num("reps"),
                weightKg = so.num("weight"),
                durationSec = so.num("duration"),
                rpe = so.num("rpe"),
                isPr = (so["is_pr"] as? JsonPrimitive)?.booleanOrNull == true
            )
        }.sortedBy { it.setNumber }
        ExerciseSessionLog(
            id = o.text("exercise_entry_id") ?: o.text("id") ?: date.toString(),
            date = date,
            durationMinutes = o.num("duration_minutes") ?: 0.0,
            caloriesBurned = o.num("calories_burned") ?: 0.0,
            modality = o.text("modality"),
            bodyWeightKg = o.num("body_weight_kg"),
            sets = sets
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
}
