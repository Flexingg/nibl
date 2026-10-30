package com.bytelog.app.ui

import android.graphics.Bitmap
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.bytelog.app.ui.navigation.NavMotion
import com.bytelog.app.ui.navigation.page
import com.bytelog.app.ui.theme.ByteLogTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The back gesture: the page follows the finger as a shrinking card, and letting go early stays put. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class PredictiveBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController

    private fun shot(name: String) {
        val dir = System.getProperty("bytelog.screenshots") ?: return
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun setUp() {
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                nav = rememberNavController()
                NavHost(
                    nav, startDestination = "diary",
                    enterTransition = { NavMotion.enter(this, false) },
                    exitTransition = { NavMotion.exit(this, false) },
                    popEnterTransition = { NavMotion.popEnter(this, false) },
                    popExitTransition = { NavMotion.popExit(this, false) }
                ) {
                    page("diary") { Box(Modifier.fillMaxSize().background(Color(0xFF203040)), Alignment.Center) { Text("Diary page") } }
                    page("settings") { Box(Modifier.fillMaxSize().background(Color(0xFF7A3A20)), Alignment.Center) { Text("Settings page") } }
                }
            }
        }
        compose.runOnIdle { nav.navigate("settings") }
        compose.waitForIdle()
        assertTrue(shown("Settings page"))
        assertTrue(!shown("Diary page"))
    }

    private fun back(progress: Float) = BackEventCompat(200f, 900f, progress, BackEventCompat.EDGE_LEFT)

    @Test
    fun theGesturePeeksAtThePageUnderneathAndCanBeCancelled() {
        setUp()
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(back(0f))
            dispatcher.dispatchOnBackProgressed(back(0.4f))
        }
        compose.waitForIdle()
        // Mid-gesture: both pages are on screen
        assertTrue(shown("Settings page"))
        assertTrue(shown("Diary page"))
        shot("predictive_back_mid_gesture")

        // Letting go early: back where we were
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals("settings", nav.currentDestination?.route)
        assertTrue(!shown("Diary page"))

        // Completing it goes back
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(back(0f))
            dispatcher.dispatchOnBackProgressed(back(0.8f))
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertEquals("diary", nav.currentDestination?.route)
        assertTrue(!shown("Settings page"))
    }
}
