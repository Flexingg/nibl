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

    @Test fun aNumberHalfUnderWaterIsTwoTone() {
        for (dark in listOf(false, true)) {
            val c = colors(dark)
            val (w, h) = px(WidgetKind.WATER)
            // Water up to y=75 of the 170-unit tile, give or take the wave: across the number (baseline 94, ~30 tall)
            val d = WidgetData.Sample.copy(waterOz = WidgetData.Sample.waterGoalOz * (95f / 170f))
            val bmp = WidgetRenderer.render(WidgetKind.WATER, d, c, w, h)
            fun count(color: Int, rows: IntRange) = rows.sumOf { y -> (55..115).count { x -> distance(color, bmp.getPixel(x * 3, y * 3)) < 40 } }
            val above = 64..69; val below = 84..93
            assertTrue("dark=$dark: glass-coloured ink above the water", count(c.onGlass, above) > 3)
            assertTrue("dark=$dark: water-coloured ink below it", count(c.onWater, below) > 3)
            assertEquals("dark=$dark: no glass-coloured ink under the water", 0, count(c.onGlass, below))
        }
    }

    @Test fun theWaveMovesAndLoops() {
        val c = colors(false)
        val (w, h) = px(WidgetKind.WATER)
        val d = WidgetData.Sample.copy(waterOz = WidgetData.Sample.waterGoalOz / 2f)
        fun frame(phase: Float) = WidgetRenderer.render(WidgetKind.WATER, d, c, w, h, phase)
        fun same(a: Bitmap, b: Bitmap) = a.sameAs(b)
        assertTrue("frames differ", !same(frame(0f), frame((Math.PI / 2).toFloat())))
        // A full turn is back where it started (nearly: 2π isn't exact in floats)
        val start = frame(0f); val end = frame((2 * Math.PI).toFloat())
        val off = (0 until 2000).count { val x = (it * 7919) % w; val y = (it * 104729) % h; distance(start.getPixel(x, y), end.getPixel(x, y)) > 30 }
        assertTrue("loops seamlessly ($off pixels off)", off < 10)
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
        // The water widget's wave loop, with the waterline across the number
        val (w, h) = px(WidgetKind.WATER)
        val half = WidgetData.Sample.copy(waterOz = 36f)
        for (dark in listOf(false, true)) for (f in 0 until WidgetRenderer.WAVE_FRAMES) {
            val bmp = WidgetRenderer.render(WidgetKind.WATER, half, colors(dark), w, h, (2 * Math.PI * f / WidgetRenderer.WAVE_FRAMES).toFloat())
            File(dir, "water_wave${if (dark) "_dark" else ""}_$f.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
