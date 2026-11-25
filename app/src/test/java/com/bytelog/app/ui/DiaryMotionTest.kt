package com.bytelog.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.bytelog.app.data.local.model.DailySummary
import com.bytelog.app.data.local.model.WeightEntry
import com.bytelog.app.ui.components.BodyweightCard
import com.bytelog.app.ui.components.DailyMacroHeader
import com.bytelog.app.ui.components.HeaderCollapseState
import com.bytelog.app.ui.components.HydrationCard
import com.bytelog.app.ui.components.MacroStrip
import com.bytelog.app.ui.components.WaterStrip
import com.bytelog.app.ui.components.WeightStrip
import com.bytelog.app.ui.components.displayWeight
import com.bytelog.app.ui.components.weightChange
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteLogTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The Diary header folds into narrow strips on scroll and springs back at the top. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class DiaryMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val user = NestedScrollSource.UserInput

    // ----- scroll logic -----

    @Test fun scrollingDownFoldsTheHeaderBeforeTheListMoves() {
        val s = HeaderCollapseState(200f)
        // Finger up 120 px: the header takes all of it
        val used = s.connection.onPreScroll(Offset(0f, -120f), user)
        assertEquals(-120f, used.y, 0.01f)
        assertEquals(0.6f, s.target, 0.001f)
        // 120 more: only 80 px of fold is left, so the list gets the remaining 40
        val used2 = s.connection.onPreScroll(Offset(0f, -120f), user)
        assertEquals(-80f, used2.y, 0.01f)
        assertEquals(1f, s.target, 0.001f)
        // Fully folded: the list takes everything
        assertEquals(0f, s.connection.onPreScroll(Offset(0f, -50f), user).y, 0.01f)
    }

    @Test fun scrollingUpOnlyUnfoldsOnceTheListIsBackAtTheTop() {
        val s = HeaderCollapseState(200f)
        s.connection.onPreScroll(Offset(0f, -300f), user)
        assertEquals(1f, s.target, 0.001f)
        // Dragging down while the list can still scroll up: the pre-scroll leaves it alone
        assertEquals(0f, s.connection.onPreScroll(Offset(0f, 80f), user).y, 0.01f)
        assertEquals(1f, s.target, 0.001f)
        // The list used all of it, nothing left over: still folded
        s.connection.onPostScroll(Offset(0f, 80f), Offset.Zero, user)
        assertEquals(1f, s.target, 0.001f)
        // At the top the list has nothing to scroll, so the leftover unfolds the header
        val used = s.connection.onPostScroll(Offset.Zero, Offset(0f, 50f), user)
        assertEquals(50f, used.y, 0.01f)
        assertEquals(0.75f, s.target, 0.001f)
    }

    @Test fun letGoHalfwaySettlesOnTheNearerState() = runBlocking {
        val a = HeaderCollapseState(200f)
        a.connection.onPreScroll(Offset(0f, -60f), user)           // 30%
        a.connection.onPostFling(Velocity.Zero, Velocity.Zero)
        assertEquals(0f, a.target, 0f)

        val b = HeaderCollapseState(200f)
        b.connection.onPreScroll(Offset(0f, -130f), user)          // 65%
        b.connection.onPostFling(Velocity.Zero, Velocity.Zero)
        assertEquals(1f, b.target, 0f)
    }

    // ----- weight helpers -----

    @Test fun weightHelpersConvertUnitsAndFindTheChange() {
        val today = WeightEntry("2026-10-06", 182.4f, "lbs")
        val before = WeightEntry("2026-10-05", 183.0f, "lbs")
        assertEquals(-0.6f, weightChange(today, before, "lbs")!!, 0.01f)
        assertEquals(null, weightChange(today, today, "lbs"))                // same day
        assertEquals(null, weightChange(null, before, "lbs"))
        assertEquals(82.7f, displayWeight(today, "kg"), 0.05f)
        assertEquals(182.4f, displayWeight(today, "lbs"), 0.001f)
    }

    // ----- rendering -----

    private var fold by mutableFloatStateOf(0f)

    private fun setHeader() {
        val today = WeightEntry("2026-10-06", 182.4f, "lbs")
        val before = WeightEntry("2026-10-05", 183.0f, "lbs")
        val summary = DailySummary("2026-10-06", totalCalories = 1312f, targetCalories = 2150f, totalProtein = 112f, targetProtein = 160f,
            totalCarbs = 128f, targetCarbs = 220f, totalFat = 41f, targetFat = 70f)
        compose.setContent {
            ByteLogTheme {
                Column(Modifier.fillMaxSize().background(ByteInkDark).padding(16.dp)) {
                    com.bytelog.app.ui.dashboard.DashboardHeader(
                        fraction = { fold },
                        modifier = Modifier.testTag("header"),
                        tiles = listOf(
                            com.bytelog.app.ui.dashboard.FoldTileSpec("calories", 4, ownCard = false, expanded = { DailyMacroHeader(summary) }, compact = { MacroStrip(summary) }),
                            com.bytelog.app.ui.dashboard.FoldTileSpec("water", 4, ownCard = false,
                                expanded = { HydrationCard(48f, 96f, 20f, "Bottle", onLogDefault = {}, onOpenCustom = {}, onUndoLast = {}) },
                                compact = { WaterStrip(48f, 96f, 20f, onLogDefault = {}) }),
                            com.bytelog.app.ui.dashboard.FoldTileSpec("weight", 4, ownCard = false,
                                expanded = { BodyweightCard(today, before, "lbs", onOpenLogDialog = {}) },
                                compact = { WeightStrip(displayWeight(today, "lbs"), "lbs", weightChange(today, before, "lbs")) })
                        )
                    )
                }
            }
        }
    }

    private fun foldTo(f: Float) { compose.runOnIdle { fold = f }; compose.waitForIdle() }

    private fun shot(name: String) {
        val dir = System.getProperty("bytelog.screenshots") ?: return
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun theHeaderFoldsFromThreeCardsIntoTwoNarrowStrips() {
        setHeader()
        foldTo(0f)
        val full = compose.onNodeWithTag("header").getUnclippedBoundsInRoot()
        shot("12-diary-header-full")
        foldTo(0.5f)
        shot("13-diary-header-halfway")
        foldTo(1f)
        val folded = compose.onNodeWithTag("header").getUnclippedBoundsInRoot()
        shot("14-diary-header-narrow")

        val fullH = full.bottom - full.top
        val foldedH = folded.bottom - folded.top
        // A full-width strip, an 8 dp gap and the water/weight row, 54 dp each
        assertEquals(116f, foldedH.value, 0.6f)
        assertTrue("the full header is much taller ($fullH vs $foldedH)", fullH.value > foldedH.value * 2.5f)
        assertEquals("full width in both states", full.right - full.left, folded.right - folded.left)

        foldTo(0f)
        assertEquals("unfolds back to the same height", fullH.value, (compose.onNodeWithTag("header").getUnclippedBoundsInRoot().let { it.bottom - it.top }).value, 0.6f)
    }
}
