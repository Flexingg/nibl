package com.bytelog.app.data

import com.bytelog.app.data.reports.ExerciseProgress
import com.bytelog.app.data.reports.ExerciseSessionLog
import com.bytelog.app.data.reports.ExerciseSetLog
import com.bytelog.app.data.reports.LoggedExercise
import com.bytelog.app.data.reports.ProgressGrouping
import com.bytelog.app.data.reports.ReportCustomization
import com.bytelog.app.data.reports.ReportSectionId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExerciseProgressTest {
    private val bench = LoggedExercise("ex-1", "Bench Press")
    private fun set(n: Int, kg: Double?, reps: Double?, type: String? = "Working Set", dur: Double? = null) = ExerciseSetLog(n, type, reps, kg, dur, null)
    private fun session(day: String, vararg sets: ExerciseSetLog, modality: String? = "weight_reps", bw: Double? = null) =
        ExerciseSessionLog("e-$day", LocalDate.parse(day), 45.0, 200.0, modality, bw, sets.toList())

    @Test fun epleyAndWorkingSetsLikeTheWebApp() {
        assertEquals(100.0 * (1 + 5 / 30.0), ExerciseProgress.epley1Rm(100.0, 5.0), 1e-9)
        assertEquals(0.0, ExerciseProgress.epley1Rm(0.0, 5.0), 0.0)
        val s = session("2026-11-02")
        assertTrue(ExerciseProgress.counts(set(1, 60.0, 8.0), s))
        assertTrue("warm-ups don't count", !ExerciseProgress.counts(set(1, 40.0, 10.0, type = "Warm-up"), s))
        assertTrue("no reps, no count", !ExerciseProgress.counts(set(1, 60.0, 0.0), s))
        assertTrue("no weight, no count", !ExerciseProgress.counts(set(1, null, 10.0), s))
    }

    @Test fun bodyweightExercisesLoadBodyWeightPlusWhatsAdded() {
        val pullups = session("2026-11-02", set(1, 10.0, 8.0), set(2, null, 10.0), modality = "bodyweight_reps", bw = 80.0)
        assertEquals(90.0, ExerciseProgress.loadKg(pullups.sets[0], pullups), 0.0)
        assertEquals(80.0, ExerciseProgress.loadKg(pullups.sets[1], pullups), 0.0)
        assertTrue(ExerciseProgress.counts(pullups.sets[1], pullups))
    }

    @Test fun aReportGroupsByWeekAndFindsTheBests() {
        val sessions = listOf(
            session("2026-11-02", set(1, 40.0, 10.0, type = "warmup"), set(2, 80.0, 5.0), set(3, 80.0, 5.0)),   // Monday
            session("2026-11-05", set(1, 85.0, 3.0, dur = 30.0)),                                                 // same week
            session("2026-11-10", set(1, 90.0, 3.0), set(2, 70.0, 12.0))                                          // next week
        )
        val r = ExerciseProgress.report(bench, sessions, ProgressGrouping.WEEK)
        assertEquals(3, r.sessions)
        assertEquals(listOf(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-09")), r.points.map { it.start })
        assertEquals(80.0 * 5 * 2 + 85.0 * 3, r.points[0].volumeKg, 1e-9)
        assertEquals(85.0, r.points[0].maxWeightKg, 0.0)
        assertEquals(0.5, r.points[0].timeUnderTensionMin, 1e-9)
        assertEquals(90.0, r.heaviest!!.weightKg, 0.0)
        assertEquals(12, r.mostReps!!.reps)
        assertEquals("90 × 3 (99) just beats 70 × 12 (98)", 90.0 * (1 + 3 / 30.0), r.bestSet!!.estimated1RmKg, 1e-9)
        assertEquals(5, r.totalSets)
        assertTrue(r.estimated1RmChange!! > 0)
        assertEquals(listOf(3 to 87.5, 5 to 80.0, 12 to 70.0), r.repsVsWeight)
        assertEquals("newest first", LocalDate.parse("2026-11-10"), r.recent.first().first.date)
    }

    @Test fun byDayAndByMonth() {
        val sessions = listOf(session("2026-10-30", set(1, 50.0, 5.0)), session("2026-11-02", set(1, 60.0, 5.0)))
        assertEquals(2, ExerciseProgress.report(bench, sessions, ProgressGrouping.DAY).points.size)
        assertEquals(listOf("2026-10-01", "2026-11-01"), ExerciseProgress.report(bench, sessions, ProgressGrouping.MONTH).points.map { it.start.toString() })
        assertNull(ExerciseProgress.report(bench, sessions.take(1), ProgressGrouping.DAY).estimated1RmChange)
    }

    @Test fun readsSparkysAnswers() {
        val names = ExerciseProgress.parseExercises(Json.parseToJsonElement(
            """[{"id":"b","name":"Squat"},{"id":null,"name":"Deleted"},{"id":"a","name":"Bench Press"},{"id":"c","name":"Active Calories"}]"""))
        assertEquals(listOf("Bench Press", "Squat"), names.map { it.name })

        val sessions = ExerciseProgress.parseSessions(Json.parseToJsonElement("""[
          {"exercise_entry_id":"e1","entry_date":"2026-11-02T00:00:00.000Z","duration_minutes":40,"calories_burned":"210.5","modality":"weight_reps","body_weight_kg":null,
           "sets":[{"id":2,"set_number":2,"set_type":"Working Set","reps":5,"weight":"80","duration":null,"rpe":8,"is_pr":true},
                   {"id":1,"set_number":1,"set_type":"Warm-up","reps":10,"weight":40,"duration":null,"rpe":null,"is_pr":false}]},
          {"entry_date":"bad"}]"""))
        val s = sessions.single()
        assertEquals(LocalDate.parse("2026-11-02"), s.date)
        assertEquals(210.5, s.caloriesBurned, 0.0)
        assertEquals("sorted by set number", listOf(1, 2), s.sets.map { it.setNumber })
        assertEquals(80.0, s.sets[1].weightKg!!, 0.0)
        assertTrue(s.sets[1].isPr)
    }

    @Test fun theNewSectionAppearsForPeopleWithASavedLayout() {
        val saved = ReportCustomization(order = ReportSectionId.entries.filter { it != ReportSectionId.EXERCISE_PROGRESS })
        val loaded = ReportCustomization.fromJson(saved.toJson())
        assertEquals("right after Lifting & Workouts", ReportSectionId.WORKOUTS, loaded.order[loaded.order.indexOf(ReportSectionId.EXERCISE_PROGRESS) - 1])
    }
}
