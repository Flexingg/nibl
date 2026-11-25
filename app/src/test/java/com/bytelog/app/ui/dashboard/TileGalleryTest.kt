package com.bytelog.app.ui.dashboard

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.bytelog.app.data.analytics.AdaptiveTdeeCalculation
import com.bytelog.app.data.analytics.DayChartPoint
import com.bytelog.app.data.caffeine.CaffeineStatus
import com.bytelog.app.data.fasting.FastingLog
import com.bytelog.app.data.local.model.DailySummary
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.PlannedEntry
import com.bytelog.app.data.local.model.PrepBatch
import com.bytelog.app.data.repository.BatchStatus
import com.bytelog.app.data.repository.DayTotal
import com.bytelog.app.data.repository.FitOption
import com.bytelog.app.data.repository.HomeTile
import com.bytelog.app.data.repository.ShoppingListResult
import com.bytelog.app.data.repository.TileSize
import com.bytelog.app.data.repository.WeeklyReview
import com.bytelog.app.ui.diary.PlanDaySummary
import com.bytelog.app.ui.theme.ByteLogTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Every tile at every size with a realistic day behind it: more size, more to see. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1500dp-xhdpi")
class TileGalleryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.now()
    private fun food(name: String, kcal: Float) = FoodEntry(id = name, foodName = name, mealTypeId = "l", mealName = "Lunch", quantity = 1f, unit = "serving",
        calories = kcal, protein = 20f, carbs = 30f, fat = 10f, entryDate = today.toString())

    private val data = DashboardData(
        summary = DailySummary(today.toString(), totalCalories = 1340f, totalProtein = 96f, totalCarbs = 140f, totalFat = 42f),
        waterOz = 48f, waterGoalOz = 96f, drinkOz = 16f, drinks = 3,
        weight = 182.4f, weightUnit = "lbs", weightDelta = -0.6f,
        recentWeights = listOf(185.2f, 184.6f, 184.9f, 183.8f, 183.4f, 183.0f, 182.4f),
        weeklyReview = WeeklyReview(today.minusDays(7), today.minusDays(1),
            (7 downTo 1).map { DayTotal(today.minusDays(it.toLong()), listOf(2100f, 1850f, 2300f, 1950f, 0f, 2050f, 1900f)[7 - it], 120f, it != 3) },
            2200f, 160f, 2025, 128, 210, 70, 6, 4, 3, 12, -1.2f),
        activeFast = FastingLog("f", startTime = Instant.now().minusSeconds(13 * 3600 + 25 * 60).toString(),
            targetEndTime = Instant.now().plusSeconds(3 * 3600 - 25 * 60).toString(), fastingType = "16:8"),
        sinceLastMealMs = null, nowMs = System.currentTimeMillis(),
        caffeine = CaffeineStatus(activeMgNow = 142f, atBedtimeMg = 38f, latestSafeDoseTime = "14:30", targetBedtime = "22:30"),
        batches = listOf(
            BatchStatus(PrepBatch("b1", name = "Chicken chili", cookedAt = 0L, calories = 2400f, protein = 180f, carbs = 200f, fat = 80f, portions = 6), 0.5f),
            BatchStatus(PrepBatch("b2", name = "Overnight oats", cookedAt = 0L, calories = 1600f, protein = 60f, carbs = 220f, fat = 40f, portions = 4), 0.25f)
        ),
        family = listOf(Triple("Sam", 1450f, 2000f), Triple("Alex", 900f, 1800f), Triple("Jo", 2100f, 2200f)),
        planned = listOf(
            PlannedEntry("p1", foodName = "Greek yogurt", mealTypeId = "s", mealName = "Snack", quantity = 1f, calories = 150f, protein = 15f, carbs = 8f, fat = 4f, entryDate = today.toString()),
            PlannedEntry("p2", foodName = "Salmon bowl", mealTypeId = "d", mealName = "Dinner", quantity = 1f, calories = 620f, protein = 40f, carbs = 60f, fat = 20f, entryDate = today.toString())
        ),
        weekPlan = (0..6).map { PlanDaySummary(today.plusDays(it.toLong()), listOf(1900f, 2100f, 0f, 1800f, 0f, 0f, 2000f)[it], listOf(5, 6, 0, 4, 0, 0, 5)[it]) },
        shopping = ShoppingListResult(emptyList()), shoppingChecked = emptySet(),
        fits = listOf(FitOption(listOf(food("Turkey wrap", 420f))), FitOption(listOf(food("Protein shake", 180f), food("Banana", 105f)))),
        days = (13 downTo 0).map { d ->
            DayChartPoint(today.minusDays(d.toLong()).toString(), "", listOf(2100f, 1850f, 2300f, 1950f, 2400f, 2050f, 1900f)[d % 7], null,
                185f - (13 - d) * 0.2f, 2200f, d == 0)
        },
        tdee = AdaptiveTdeeCalculation(2480f, "MEDIUM", -0.4f, -0.6f, 2060f, 1980f, "lose", -500f, 21, false, ""),
        meals = listOf("Breakfast" to 420f, "Lunch" to 680f, "Snack" to 240f),
        mealNow = "Dinner",
        mealShortcuts = listOf("Dinner" to "Chicken chili, Rice"),
        recipes = listOf("Chicken chili", "Overnight oats", "Salmon bowl"),
        mealieHost = "mealie.home",
        health = HealthSummary(true, System.currentTimeMillis() - 7 * 60_000L, 18, 4, null),
        photos = listOf(today.minusDays(3).toString() to suspend { null }, today.minusDays(33).toString() to suspend { null })
    )

    private val actions = DashboardActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private var shown by androidx.compose.runtime.mutableStateOf(emptyList<HomeTile>())
    private var started = false

    private fun page(name: String, tiles: List<HomeTile>) {
        shown = tiles
        if (!started) { started = true; setUp() }
        compose.waitForIdle()
        shot(name)
    }

    private fun setUp() {
        compose.setContent {
            ByteLogTheme(darkTheme = true) {
                val faces = rememberTileFaces(data, actions)
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(TILE_GAP)
                ) {
                    shown.forEach { t ->
                        TileRow(listOf(2, 1, 1)) {
                            SummaryTile(faces(t), TileSize.HALF, height = TILE_TALL)
                            SummaryTile(faces(t), TileSize.SMALL, height = TILE_TALL)
                            SummaryTile(faces(t), TileSize.SMALL)
                        }
                        SummaryTile(faces(t), TileSize.FULL)
                    }
                }
            }
        }
    }

    @Test fun everyTileGrowsWithItsSize() {
        page("tiles_1", listOf(HomeTile.CALORIES, HomeTile.WATER, HomeTile.WEIGHT))
        assertTrue(compose.onAllNodesWithText("Lunch", substring = true).fetchSemanticsNodes().isNotEmpty())
        page("tiles_2", listOf(HomeTile.WEEKLY_REVIEW, HomeTile.FASTING, HomeTile.CAFFEINE))
        page("tiles_3", listOf(HomeTile.REPORTS, HomeTile.PROGRESS_PHOTOS, HomeTile.MEALIE))
        page("tiles_4", listOf(HomeTile.HEALTH_CONNECT, HomeTile.LEFTOVERS, HomeTile.FAMILY))
        page("tiles_5", listOf(HomeTile.PLANNED, HomeTile.MEAL_PLAN, HomeTile.SHOPPING))
        page("tiles_6", listOf(HomeTile.WHAT_FITS, HomeTile.QUICK_LOG, HomeTile.TRENDS))
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
