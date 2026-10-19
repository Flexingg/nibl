package com.bytelog.app.widget

import android.appwidget.AppWidgetManager
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.ByteLogApplication
import com.bytelog.app.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The widgets are drawn as bitmaps but shown through RemoteViews layouts, which only allow certain views (a plain
 * View is refused, and the launcher then shows "error loading widget"). These inflate the real layouts the way a
 * launcher does and tap them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetInflationTest {
    private val app: ByteLogApplication get() = ApplicationProvider.getApplicationContext()

    private fun place(kind: WidgetKind, layout: Int): View {
        val shadow = shadowOf(AppWidgetManager.getInstance(app))
        val id = shadow.createWidget(kind.providerClass(), layout)
        runBlocking { WidgetUpdater.update(app, kind, intArrayOf(id), WidgetData.Sample) }
        return shadow.getViewFor(id)
    }

    private val layouts = mapOf(
        WidgetKind.TODAY to R.layout.widget_today, WidgetKind.CALORIES to R.layout.widget_calories,
        WidgetKind.WATER to R.layout.widget_water, WidgetKind.QUICK_LOG to R.layout.widget_quick_log, WidgetKind.FAMILY to R.layout.widget_family
    )

    @Test fun everyWidgetInflatesAndShowsItsPicture() {
        for ((kind, layout) in layouts) {
            val view = place(kind, layout)
            val img = view.findViewById<ImageView>(R.id.img_light)
            assertNotNull("$kind has its picture", img.drawable)
            assertEquals("$kind light look shows by day", View.VISIBLE, img.visibility)
            assertEquals("$kind dark look hidden by day", View.GONE, view.findViewById<View>(R.id.img_dark).visibility)
        }
    }

    @Test fun tappingTheTodayWidgetDoesTheRightThings() {
        val view = place(WidgetKind.TODAY, R.layout.widget_today)
        view.findViewById<View>(R.id.tap_add).performClick()
        val add = shadowOf(app).nextStartedActivity
        val addDest = add.getStringExtra(WidgetLinks.EXTRA_DEST)
        assertTrue(addDest, addDest in setOf("search:${WidgetData.Sample.suggestedMeal}", "search:${suggestedMealAt(java.time.LocalTime.now())}"))

        view.findViewById<View>(R.id.tap_open).performClick()
        assertEquals("diary", shadowOf(app).nextStartedActivity.getStringExtra(WidgetLinks.EXTRA_DEST))

        view.findViewById<View>(R.id.tap_water).performClick()
        assertTrue(shadowOf(app).broadcastIntents.any { it.action == WidgetLinks.ACTION_ADD_WATER })
    }

    @Test fun quickLogButtonsOpenTheirMealAndTheScanner() {
        val view = place(WidgetKind.QUICK_LOG, R.layout.widget_quick_log)
        val expected = mapOf(R.id.tap_breakfast to "search:Breakfast", R.id.tap_lunch to "search:Lunch", R.id.tap_dinner to "search:Dinner", R.id.tap_snack to "search:Snack")
        for ((id, dest) in expected) {
            view.findViewById<View>(id).performClick()
            assertEquals(dest, shadowOf(app).nextStartedActivity.getStringExtra(WidgetLinks.EXTRA_DEST))
        }
        // Scan goes to the meal for the time of day: the sample's, or now's if the widget's own refresh got there first
        view.findViewById<View>(R.id.tap_scan).performClick()
        val scan = shadowOf(app).nextStartedActivity.getStringExtra(WidgetLinks.EXTRA_DEST)
        assertTrue(scan, scan in setOf("scan:${WidgetData.Sample.suggestedMeal}", "scan:${suggestedMealAt(java.time.LocalTime.now())}"))
    }

    @Test fun theFamilyWidgetOpensFamilyAndTheShareSheet() {
        val view = place(WidgetKind.FAMILY, R.layout.widget_family)
        view.findViewById<View>(R.id.tap_open).performClick()
        assertEquals("family", shadowOf(app).nextStartedActivity.getStringExtra(WidgetLinks.EXTRA_DEST))
        view.findViewById<View>(R.id.tap_share).performClick()
        assertEquals("share:Lunch", shadowOf(app).nextStartedActivity.getStringExtra(WidgetLinks.EXTRA_DEST))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "night")
class WidgetNightInflationTest {
    @Test fun inTheDarkTheDarkLookShows() {
        val app = ApplicationProvider.getApplicationContext<ByteLogApplication>()
        val shadow = shadowOf(AppWidgetManager.getInstance(app))
        val id = shadow.createWidget(WaterWidgetProvider::class.java, R.layout.widget_water)
        runBlocking { WidgetUpdater.update(app, WidgetKind.WATER, intArrayOf(id), WidgetData.Sample) }
        val view = shadow.getViewFor(id)
        assertEquals(View.GONE, view.findViewById<View>(R.id.img_light).visibility)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.img_dark).visibility)
        assertNotNull(view.findViewById<ImageView>(R.id.img_dark).drawable)
    }
}
