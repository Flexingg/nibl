package com.bytelog.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bytelog.app.data.remote.PhotoMealEstimate
import com.bytelog.app.data.remote.PhotoMealItem
import com.bytelog.app.ui.logging.PhotoEstimateContent
import com.bytelog.app.ui.theme.ByteLogTheme
import com.bytelog.app.ui.viewmodel.FastSearchViewModel.PhotoState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The photo estimate sheet: pick between the model's guesses for each food, or edit one, then add to the tray. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class PhotoGuessesRenderTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun item(name: String, grams: Float, kcal: Float, p: Float, c: Float, f: Float, portion: String? = null, why: String? = null, confidence: String = "medium") =
        PhotoMealItem(name, null, grams, portion, kcal, p, c, f, confidence = confidence, reason = why)

    private val estimate = PhotoMealEstimate(
        summary = "Chicken rice bowl", confidence = "medium", confidenceReason = "The sauce hides some of the rice.",
        items = listOf(
            item("Grilled chicken breast", 120f, 198f, 37f, 0f, 4f, "1 fillet", "Grill marks, no skin").copy(
                alternatives = listOf(item("Grilled chicken thigh", 120f, 250f, 30f, 0f, 14f, "1 fillet", "Could be thigh", "low"))
            ),
            item("White rice", 150f, 195f, 4f, 42f, 0f, "1 cup").copy(
                alternatives = listOf(item("White rice", 220f, 286f, 6f, 62f, 1f, "1.5 cups", "Bigger bowl"))
            ),
            item("Teriyaki sauce", 30f, 45f, 1f, 9f, 0f, "2 tbsp", "Glossy brown glaze")
        ),
        questions = listOf("Was the chicken cooked in oil?"),
        estimatedBy = "Claude"
    )

    private fun shot(name: String) {
        val dir = System.getProperty("bytelog.screenshots") ?: return
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun pickAGuessOrEditAFoodThenAddThem() {
        var added: List<PhotoMealItem> = emptyList()
        compose.setContent {
            ByteLogTheme(darkTheme = true, dynamicColor = false) {
                Surface {
                    Column {
                        PhotoEstimateContent(PhotoState.Ready(null, estimate), "Dinner", onAdd = { added = it }, onRetake = {}, onGallery = {}, onReestimate = {})
                    }
                }
            }
        }
        // Different foods are labelled by name, different portions of the same food by amount
        compose.onNodeWithText("Grilled chicken thigh · 250 kcal").assertIsDisplayed()
        compose.onNodeWithText("220 g · 1.5 cups · 286 kcal").assertIsDisplayed()
        compose.onNodeWithText("Add 3 for Dinner · 438 kcal").assertIsDisplayed()
        shot("photo-guesses")

        // Picking the second guess swaps the food in
        compose.onNodeWithText("Grilled chicken thigh · 250 kcal").performClick()
        compose.onNodeWithText("Add 3 for Dinner · 490 kcal").assertIsDisplayed()

        compose.onNodeWithText("Add 3 for Dinner · 490 kcal").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Grilled chicken thigh", "White rice", "Teriyaki sauce"), added.map { it.name })
        compose.onNodeWithContentDescription("Edit White rice").assertIsDisplayed()
    }

    @Test fun editingTheWeightScalesTheNutrition() {
        val rice = estimate.items[1]
        val double = com.bytelog.app.ui.logging.scaleToGrams(rice, 300f)!!
        assertEquals(300f, double.grams); assertEquals(390f, double.calories); assertEquals(84f, double.carbs)
        assertEquals(null, com.bytelog.app.ui.logging.scaleToGrams(rice.copy(grams = 0f), 100f))
        assertEquals("150 g · 1 cup", com.bytelog.app.ui.logging.amountText(rice))
    }
}
