package com.bytelog.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.bytelog.app.ui.splash.AnimatedSplashScreen
import com.bytelog.app.ui.theme.ByteLogTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The Nibl splash plays through (glass bloom, nibbles, the pill, the lit wordmark, opening) and hands over to the app. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class SplashScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String) {
        val dir = System.getProperty("bytelog.screenshots") ?: return
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun playsThroughAndFinishes() {
        var finished = false
        compose.mainClock.autoAdvance = false
        compose.setContent { ByteLogTheme(darkTheme = false, dynamicColor = false) { AnimatedSplashScreen(onSplashFinished = { finished = true }) } }
        compose.onNodeWithContentDescription("Nibl").assertExists()
        var t = 0L
        for ((at, name) in listOf(400L to "splash_1_bloom", 1000L to "splash_2_nibble", 1850L to "splash_3_flow", 2450L to "splash_4_lit", 3000L to "splash_5_pill", 3800L to "splash_6_open")) {
            compose.mainClock.advanceTimeBy(at - t); t = at
            compose.waitForIdle()
            shot(name)
        }
        assertFalse("still showing before the fade", finished)
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertTrue("hands over to the app", finished)
    }
}
