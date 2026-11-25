package com.bytelog.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.ui.components.MoveCopyItemsDialog
import com.bytelog.app.ui.components.MoveCopyMode
import com.bytelog.app.ui.components.relativeDayLabel
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
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class TargetDatePickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun datesReadRelativeToToday() {
        val today = LocalDate.of(2026, 10, 12)
        assertEquals("Today", relativeDayLabel(today, today))
        assertEquals("Tomorrow", relativeDayLabel(today.plusDays(1), today))
        assertEquals("Yesterday", relativeDayLabel(today.minusDays(1), today))
        assertEquals("in 5 days", relativeDayLabel(today.plusDays(5), today))
        assertEquals("3 days ago", relativeDayLabel(today.minusDays(3), today))
    }

    @Test fun theDialogStepsDaysAndKeepsTheQuickButtons() {
        val source = LocalDate.now().minusDays(4).toString()
        var result: String? = null
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                MoveCopyItemsDialog(
                    initialMode = MoveCopyMode.COPY, title = "Meal: Dinner", subtitle = "2 items • 700 kcal", sourceDate = source,
                    defaultMealName = "Dinner", mealTypes = MealType.DEFAULT_MEAL_TYPES, onDismiss = {},
                    onConfirm = { _, date, _, _, _ -> result = date }
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Yesterday").assertExists()
        compose.onNodeWithText("Tomorrow").assertExists()
        compose.onNodeWithText("4 days ago").assertExists()          // starts on the source day
        shot("move_copy_date_picker")

        compose.onNodeWithContentDescription("Next day").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("3 days ago").assertExists()
        compose.onNodeWithText("Today").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pick a date").assertTextContains(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.getDefault())), substring = true)
        compose.onNodeWithText("Copy to Dinner").performClick()
        assertEquals(LocalDate.now().toString(), result)
    }

    private fun food(id: String, name: String, qty: Float, unit: String, kcal: Float) = com.bytelog.app.data.local.model.FoodEntry(
        id = id, foodName = name, mealTypeId = "dinner", mealName = "Dinner", quantity = qty, unit = unit,
        calories = kcal, protein = 1f, carbs = 1f, fat = 1f, entryDate = LocalDate.now().toString()
    )

    @Test fun copyingOneFoodTakesANewAmount() {
        // The amount picker on its own: a text field inside a Robolectric dialog never idles
        var scale = 1f
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer).padding(20.dp)
                ) {
                    val s = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(1f) }
                    com.bytelog.app.ui.components.CopyAmountPicker(listOf(food("1", "Rice", 150f, "g", 200f)), s.value) { s.value = it; scale = it }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("AMOUNT").assertExists()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("226 kcal", substring = true).assertExists()   // 170 g of 150 g at 200 kcal
        shot("copy_amount_one_food")
        assertEquals(170f / 150f, scale, 0.001f)
    }

    @Test fun copyingSeveralFoodsTakesAMultiplier() {
        var scale = 0f
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                MoveCopyItemsDialog(
                    initialMode = MoveCopyMode.COPY, title = "Meal: Dinner", subtitle = "2 items • 700 kcal", sourceDate = LocalDate.now().toString(),
                    defaultMealName = "Dinner", mealTypes = MealType.DEFAULT_MEAL_TYPES, onDismiss = {},
                    entries = listOf(food("1", "Rice", 150f, "g", 200f), food("2", "Chicken", 1f, "serving", 500f)),
                    onConfirm = { _, _, _, _, s -> scale = s }
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("2 foods · 700 kcal").assertExists()
        compose.onNodeWithText("1½×").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("2 foods · 1050 kcal at 1½×").assertExists()
        shot("copy_amount_several_foods")
        compose.onNodeWithText("Copy to Dinner").performClick()
        assertEquals(1.5f, scale, 0.001f)
    }

    @Test fun theDatePickerLooksRightOnItsOwn() {
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer)
                        .padding(20.dp)
                ) {
                    val state = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(LocalDate.now().minusDays(4).toString()) }
                    androidx.compose.material3.Text("TARGET DATE", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    com.bytelog.app.ui.components.TargetDatePicker(state.value, { state.value = it }, LocalDate.now().minusDays(4).toString(), androidx.compose.material3.MaterialTheme.colorScheme.primary)
                }
            }
        }
        compose.waitForIdle()
        shot("target_date_picker")
    }

    private fun shot(name: String) {
        val dir = System.getProperty("bytelog.screenshots") ?: return
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
