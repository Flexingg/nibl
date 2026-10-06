package com.bytelog.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.ui.components.RecentMealItem
import com.bytelog.app.ui.navigation.NavMotion
import com.bytelog.app.ui.screens.RecentMealsScreen
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteLogTheme
import com.bytelog.app.ui.theme.LocalReduceMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** Page transitions from the Diary's top bar, and the full-page recent meals screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class NavMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String) {
        val dir = System.getProperty("bytelog.screenshots") ?: return
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private lateinit var nav: NavHostController

    private fun page(label: String, color: Color) = @androidx.compose.runtime.Composable {
        Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) { Text(label, color = Color.White) }
    }

    private fun setHost(reduce: Boolean = false) {
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                CompositionLocalProvider(LocalReduceMotion provides reduce) {
                    nav = rememberNavController()
                    NavHost(
                        navController = nav,
                        startDestination = "diary",
                        enterTransition = { NavMotion.enter(this, reduce) },
                        exitTransition = { NavMotion.exit(this, reduce) },
                        popEnterTransition = { NavMotion.popEnter(this, reduce) },
                        popExitTransition = { NavMotion.popExit(this, reduce) }
                    ) {
                        composable("diary") { page("Diary page", ByteInkDark)() }
                        composable(NavMotion.CHAT) { page("Chat page", Color(0xFF2C2420))() }
                        composable("settings") { page("Settings page", Color(0xFF221C18))() }
                        composable(NavMotion.RECENT_MEALS) { page("Recent meals page", Color(0xFF362E2A))() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun visible(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Opens [route], checks both pages are on screen part-way through, then that it settles and goes back. */
    private fun opensAndCloses(route: String, label: String, shotName: String) {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.navigate(route) }
        compose.mainClock.advanceTimeBy(120)
        assertTrue("mid-transition, the diary is still visible underneath", visible("Diary page"))
        assertTrue("mid-transition, $label is arriving", visible(label))
        shot(shotName)
        compose.mainClock.advanceTimeBy(3_000)
        assertTrue(visible(label)); assertTrue("the diary has left", !visible("Diary page"))

        compose.runOnIdle { nav.popBackStack() }
        compose.mainClock.advanceTimeBy(120)
        assertTrue("going back animates too", visible("Diary page") && visible(label))
        compose.mainClock.advanceTimeBy(3_000)
        assertTrue(visible("Diary page")); assertTrue(!visible(label))
        compose.mainClock.autoAdvance = true
    }

    @Test fun chatSettingsAndRecentMealsAnimateInAndOut() {
        setHost()
        opensAndCloses(NavMotion.CHAT, "Chat page", "15-transition-chat")
        opensAndCloses("settings", "Settings page", "16-transition-settings")
        opensAndCloses(NavMotion.RECENT_MEALS, "Recent meals page", "17-transition-recent-meals")
    }

    @Test fun withAnimationsOffPagesSwapAtOnce() {
        setHost(reduce = true)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.navigate(NavMotion.CHAT) }
        compose.mainClock.advanceTimeBy(200)   // an animated swap is still mid-way at 200 ms
        assertTrue(visible("Chat page")); assertTrue(!visible("Diary page"))
        compose.mainClock.autoAdvance = true
    }

    // ----- recent meals page -----

    private fun meal(date: String, display: String, name: String, kcal: Float, summary: String) =
        RecentMealItem(date, display, name, name.lowercase(), kcal, 30f, 40f, 12f, summary, emptyList<FoodEntry>())

    @Test fun recentMealsIsAFullPageThatCopiesTheChosenMeals() {
        val meals = listOf(
            meal("2026-10-05", "Yesterday", "Breakfast", 412f, "Greek yogurt, granola, blueberries"),
            meal("2026-10-05", "Yesterday", "Lunch", 655f, "Chipotle chicken bowl"),
            meal("2026-10-04", "Sat, Oct 4", "Dinner", 720f, "Turkey chili, rice")
        )
        var copied: Pair<List<RecentMealItem>, String>? = null
        var back = 0
        val today = LocalDate.now().toString()
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                RecentMealsScreen(today, meals, onBack = { back++ }, onCopyMeals = { m, d -> copied = m to d })
            }
        }
        compose.onNodeWithText("Copy into Today").assertExists()
        compose.onNodeWithText("1 of 3 selected").assertExists()
        compose.onNodeWithText("Copy 1 meal to Today").assertExists()
        compose.onNodeWithText("YESTERDAY").assertExists()
        compose.onNodeWithText("SAT, OCT 4").assertExists()

        compose.onNodeWithText("Turkey chili, rice").performClick()
        compose.onNodeWithText("Copy 2 meals to Today").assertExists()
        shot("18-recent-meals-page")

        compose.onNodeWithText("Clear").performClick()
        compose.onNode(hasText("Pick a meal to copy") and hasClickAction()).assertIsNotEnabled()

        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("Copy 3 meals to Today").performClick()
        assertEquals(listOf("Breakfast", "Lunch", "Dinner"), copied!!.first.map { it.mealName })
        assertEquals(today, copied!!.second)

        compose.onNode(androidx.compose.ui.test.hasContentDescription("Back")).performClick()
        assertEquals(1, back)
    }

    @Test fun recentMealsWithNothingToCopy() {
        compose.setContent { ByteLogTheme(darkTheme = true) { RecentMealsScreen("2026-10-04", emptyList(), onBack = {}, onCopyMeals = { _, _ -> }) } }
        compose.onNodeWithText("No recent meals yet").assertExists()
    }

    @Test fun dayLabels() {
        val today = LocalDate.of(2026, 10, 6)
        assertEquals("Today", com.bytelog.app.ui.screens.describeDay("2026-10-06", today))
        assertEquals("Yesterday", com.bytelog.app.ui.screens.describeDay("2026-10-05", today))
        assertEquals("Tomorrow", com.bytelog.app.ui.screens.describeDay("2026-10-07", today))
        assertTrue(com.bytelog.app.ui.screens.describeDay("2026-10-01", today).contains("Oct 1"))
        assertEquals("not a date", com.bytelog.app.ui.screens.describeDay("not a date", today))
    }
}
