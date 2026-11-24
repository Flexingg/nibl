package com.bytelog.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.bytelog.app.data.reports.ExerciseProgress
import com.bytelog.app.data.reports.ExerciseSessionLog
import com.bytelog.app.data.reports.ExerciseSetLog
import com.bytelog.app.data.reports.LoggedExercise
import com.bytelog.app.data.reports.ProgressGrouping
import com.bytelog.app.ui.reports.ExerciseProgressCard
import com.bytelog.app.ui.theme.ByteLogTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1400dp-xhdpi")
class ExerciseProgressRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String) {
        val dir = System.getProperty("bytelog.screenshots") ?: return
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun showsTheExercisesTrendsInTheUsersUnits() {
        val bench = LoggedExercise("ex-1", "Bench Press")
        val start = LocalDate.of(2026, 9, 7)
        val sessions = (0 until 10).map { i ->
            val top = 70.0 + i * 2.5
            ExerciseSessionLog("e$i", start.plusWeeks(i.toLong()), 50.0, 220.0, "weight_reps", null, listOf(
                ExerciseSetLog(1, "Warm-up", 10.0, 40.0, null, null),
                ExerciseSetLog(2, "Working Set", 5.0, top, 40.0, 8.0),
                ExerciseSetLog(3, "Working Set", 5.0, top, 40.0, 8.0),
                ExerciseSetLog(4, "Working Set", 8.0, top - 10, 45.0, 8.0)
            ))
        }
        val report = ExerciseProgress.report(bench, sessions, ProgressGrouping.WEEK)
        var grouping = ProgressGrouping.WEEK
        compose.setContent {
            ByteLogTheme(darkTheme = true, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                        ExerciseProgressCard(listOf(bench, LoggedExercise("ex-2", "Squat")), bench, ProgressGrouping.WEEK, report, false, null, "lbs",
                            onSelect = {}, onGrouping = { grouping = it })
                    }
                }
            }
        }
        compose.onNodeWithText("Bench Press").assertIsDisplayed()
        // 92.5 kg × 5 → est. 1RM 107.9 kg = 237.9 lbs
        compose.onNodeWithText("238 lbs").assertIsDisplayed()
        compose.onNodeWithText("+32%").assertIsDisplayed()
        shot("exercise_progress")
        compose.onNodeWithText("Month").performClick()
        assertEquals(ProgressGrouping.MONTH, grouping)
    }
}
