package com.bytelog.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.ui.theme.DarkExtraColors
import com.bytelog.app.ui.theme.LightExtraColors
import com.bytelog.app.ui.theme.byteColorSchemeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WidgetRendererTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun colors(dark: Boolean) = WidgetColors.from(byteColorSchemeFor(context, dark, dynamicColor = false), if (dark) DarkExtraColors else LightExtraColors, dark)
    private fun px(kind: WidgetKind, scale: Float = 3f) = (kind.baseW * scale).toInt() to (kind.baseH * scale).toInt()

    @Test fun everyWidgetDrawsInBothLooksWithRoundedCorners() {
        for (kind in WidgetKind.values()) for (dark in listOf(false, true)) {
            val (w, h) = px(kind)
            val bmp = WidgetRenderer.render(kind, WidgetData.Sample, colors(dark), w, h)
            assertEquals(w, bmp.width); assertEquals(h, bmp.height)
            assertEquals("$kind corner is cut away", 0, Color.alpha(bmp.getPixel(1, 1)))
            assertTrue("$kind body is nearly opaque frosted glass", Color.alpha(bmp.getPixel(w / 2, h - 3 * 20)) >= 230)
            // not a flat colour: it has ring, text and shapes
            val distinct = (0 until 400).map { bmp.getPixel((it * 7919) % w, (it * 104729) % h) }.toSet().size
            assertTrue("$kind has detail", distinct > 5)
        }
    }

    @Test fun waterFillsTheWholeTileAsYouDrink() {
        val (w, h) = px(WidgetKind.WATER)
        fun bottomMiddle(oz: Float): Int {
            val bmp = WidgetRenderer.render(WidgetKind.WATER, WidgetData.Sample.copy(waterOz = oz), colors(false), w, h)
            return bmp.getPixel(w / 4, h - 12)     // low on the tile, away from the text and badge
        }
        val c = colors(false)
        assertNear("empty: just glass", c.glass, bottomMiddle(0f))
        assertNear("full: water to the top", c.water, bottomMiddle(64f))
        assertNotEquals(bottomMiddle(0f), bottomMiddle(40f))
    }

    @Test fun theTodayWidgetsAddFoodButtonIsThePrimaryColour() {
        val c = colors(false)
        val (w, h) = px(WidgetKind.TODAY)
        val bmp = WidgetRenderer.render(WidgetKind.TODAY, WidgetData.Sample, c, w, h)
        assertNear("Add food", c.primary, bmp.getPixel((340 * 3), (140 * 3)))   // inside the Add food pill, right of its label
    }

    @Test fun theSuggestedMealIsHighlightedInQuickLog() {
        val c = colors(false)
        val (w, h) = px(WidgetKind.QUICK_LOG)
        fun circleTop(meal: String, index: Int): Int {
            val bmp = WidgetRenderer.render(WidgetKind.QUICK_LOG, WidgetData.Sample.copy(suggestedMeal = meal), c, w, h)
            val gap = (338f - 5 * 62f) / 5f
            val x = 10f + gap / 2f + index * (62f + gap) + 31f
            return bmp.getPixel((x * 3).toInt(), (16 * 3))
        }
        assertNear("Lunch suggested", c.primary, circleTop("Lunch", 1))
        assertTrue("Dinner not suggested", distance(c.primary, circleTop("Lunch", 2)) > 120)
        assertNear("Dinner suggested", c.primary, circleTop("Dinner", 2))
    }

    @Test fun sizesOtherThanTheArtworkAreStretchedWithoutBreaking() {
        val bmp = WidgetRenderer.render(WidgetKind.TODAY, WidgetData.Sample, colors(true), 900, 420)
        assertEquals(900, bmp.width)
        assertEquals(0, Color.alpha(bmp.getPixel(1, 1)))
        assertTrue(Color.alpha(bmp.getPixel(450, 210)) >= 230)
    }

    /** Colours drawn with gradients and a see-through tile land close to, not exactly on, the token. */
    private fun distance(a: Int, b: Int) = Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b))
    private fun assertNear(what: String, expected: Int, actual: Int) =
        assertTrue("$what: expected ~#${Integer.toHexString(expected)} got #${Integer.toHexString(actual)}", distance(expected, actual) < 90)

    /** -Pbytelog.screenshots=<dir> keeps previews of every widget, light and dark. */
    @Test fun savePreviews() {
        val dir = System.getProperty("bytelog.screenshots") ?: return
        File(dir).mkdirs()
        for (kind in WidgetKind.values()) for (dark in listOf(false, true)) {
            val (w, h) = px(kind)
            val bmp = WidgetRenderer.render(kind, WidgetData.Sample, colors(dark), w, h)
            File(dir, "widget_${kind.name.lowercase()}${if (dark) "_dark" else ""}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
