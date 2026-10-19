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

/** The Nibl splash plays through (cookie, nibbles, wordmark, the crumb dot, tagline) and hands over to the app. */
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
        for ((at, name) in listOf(250L to "splash_1_pop", 620L to "splash_2_nibble", 1150L to "splash_3_word", 1650L to "splash_4_dot", 2350L to "splash_5_final")) {
            compose.mainClock.advanceTimeBy(at - t); t = at
            compose.waitForIdle()
            shot(name)
        }
        assertFalse("still showing before the fade", finished)
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        assertTrue("hands over to the app", finished)
    }
}
