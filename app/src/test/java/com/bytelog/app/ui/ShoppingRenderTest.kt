package com.bytelog.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onFirst
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.PlanRepository
import com.bytelog.app.data.repository.PriceRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.SparkyPrice
import com.bytelog.app.ui.components.PriceDraft
import com.bytelog.app.ui.components.PriceForm
import com.bytelog.app.ui.screens.ShoppingListScreen
import com.bytelog.app.ui.theme.ByteLogTheme
import com.bytelog.app.ui.viewmodel.ShoppingListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** The shopping list and the price form, rendered. Keep PNGs with -Pbytelog.screenshots=<dir>. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class ShoppingRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: ByteLogDatabase
    private lateinit var foods: FoodRepository
    private lateinit var plans: PlanRepository
    private lateinit var prices: PriceRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        val api = SparkyApiClient(baseUrl = "http://127.0.0.1:9")
        foods = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, SettingsRepository(context), scope, db.prepBatchDao())
        plans = PlanRepository(db.plannedEntryDao(), foods)
        prices = PriceRepository(db.foodPriceDao(), null)
        db.foodDao().insertFood(FoodItem("f-yog", "Greek Yogurt", brand = "Fage", servingSize = 170f, servingUnit = "g", calories = 150f))
        fun e(id: String, d: Int, food: String, qty: Float, unit: String, foodId: String? = null, brand: String? = null, meal: String = "Dinner") = FoodEntry(
            id, foodId, food, brand, meal.lowercase(), meal, qty, unit, 300f, 20f, 20f, 10f, LocalDate.now().plusDays(d.toLong()).toString(), FoodEntry.SYNC_STATUS_PENDING_INSERT
        )
        plans.plan((1..4).map { e("y$it", it, "Greek Yogurt", 1f, "serving", "f-yog", "Fage", "Breakfast") } +
            (1..4).map { e("b$it", it, "Banana", 1f, "piece", meal = "Breakfast") } +
            listOf(e("s1", 1, "Salmon", 150f, "g"), e("s2", 3, "Salmon", 150f, "g"), e("r1", 2, "Brown Rice", 1f, "cup"), e("r2", 4, "Brown Rice", 1f, "cup"),
                e("c1", 2, "Chicken Breast", 200f, "g"), e("t1", 4, "Tortillas", 2f, "piece", brand = "Mission")))
        prices.save(null, "Greek Yogurt", "Fage", SparkyPrice("Costco", 6.49f, 48f, "oz"))
        prices.save(null, "Banana", null, SparkyPrice("Aldi", 0.25f, 1f, "each"))
        prices.save(null, "Salmon", null, SparkyPrice("Costco", 12.99f, 1f, "lb"))
        prices.save(null, "Brown Rice", null, SparkyPrice("Aldi", 2.19f, 2f, "lb"))
        Unit
    }

    @After fun tearDown() { db.close(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private fun shot(name: String) {
        val dir = System.getProperty("bytelog.screenshots") ?: return
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            check(System.currentTimeMillis() < end) { "Timed out waiting" }
            Thread.sleep(25)
        }
    }

    @Test fun shoppingList() {
        val vm = ShoppingListViewModel(plans, prices, foods)
        var dark by mutableStateOf(false)
        compose.setContent { ByteLogTheme(darkTheme = dark, dynamicColor = false) { ShoppingListScreen(vm, onBack = {}) } }
        waitFor { compose.onAllNodes(hasText("Aldi", substring = true), true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Got Banana").performClick()
        waitFor { runBlocking { db.foodPriceDao().checks().first().isNotEmpty() } }
        waitFor { compose.onAllNodes(hasText("5 of 6 to get"), true).fetchSemanticsNodes().isNotEmpty() }
        shot("30-shopping-list")
        dark = true
        shot("31-shopping-list-dark")
        assertTrue(vm.shareText().contains("Salmon"))
    }

    @Test fun priceForm() {
        compose.setContent {
            ByteLogTheme(darkTheme = false, dynamicColor = false) {
                var d by remember { mutableStateOf(PriceDraft(store = "Costco", price = "6.49", quantity = "48", unit = "oz")) }
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                    PriceForm(d, { d = it }, listOf("serving", "cup", "g", "oz"), hint = "Optional. Used for the shopping list's costs, and saved on this food in Sparky.")
                }
            }
        }
        waitFor { compose.onAllNodes(hasText("Costco"), true).fetchSemanticsNodes().isNotEmpty() }
        shot("32-price-form")
    }

    @Test fun mealPlanner() {
        val vm = com.bytelog.app.ui.viewmodel.MealPlannerViewModel(plans, foods, null)
        compose.setContent {
            ByteLogTheme(darkTheme = false, dynamicColor = false) {
                com.bytelog.app.ui.screens.MealPlannerScreen(vm, onBack = {}, onAddFood = { _, _, _ -> }, onShoppingList = {})
            }
        }
        waitFor { compose.onAllNodes(hasText("Tomorrow"), true).fetchSemanticsNodes().isNotEmpty() }
        waitFor { compose.onAllNodes(hasText("Greek Yogurt", substring = true), true).fetchSemanticsNodes().isNotEmpty() }
        shot("33-planner")
        compose.onAllNodes(hasText("Greek Yogurt · Fage"), true).onFirst().performClick()
        waitFor { compose.onAllNodes(hasText("Unit"), true).fetchSemanticsNodes().isNotEmpty() }
        shot("34-planner-edit")
    }

    @Test fun homeCards() {
        val days = (0..6).map { com.bytelog.app.ui.diary.PlanDaySummary(LocalDate.now().plusDays(it.toLong()), if (it in 1..4) 1800f + it * 100 else 0f, if (it in 1..4) 5 else 0) }
        compose.setContent {
            ByteLogTheme(darkTheme = false, dynamicColor = false) {
                val vm = remember { ShoppingListViewModel(plans, prices, foods) }
                val st by vm.state.collectAsState()
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    com.bytelog.app.ui.diary.MealPlanHomeCard(days, 2400f, onOpen = {})
                    com.bytelog.app.ui.diary.ShoppingHomeCard(st.list, st.checked, onOpen = {})
                }
            }
        }
        waitFor { compose.onAllNodes(hasText("to get", substring = true), true).fetchSemanticsNodes().isNotEmpty() }
        shot("35-home-plan-cards")
    }

    @Test fun receiptReview() {
        val vm = com.bytelog.app.ui.viewmodel.ReceiptViewModel({ Result.success(emptyList()) }, prices, plans, foods)
        runBlocking {
            vm.review("""
                COSTCO WHOLESALE
                11/14/26
                E 1234567 KS GRK YOG 48OZ   6.49 N
                E 987654 ATLANTIC SALMON 2.1LB   21.99 N
                BANANAS 4 CT  1.00
                E 44444 PAPER TOWELS   19.99
                TOTAL  49.47
            """.trimIndent().lines().map { com.bytelog.app.data.receipts.OcrLine(it) })
        }
        compose.setContent {
            ByteLogTheme(darkTheme = false, dynamicColor = false) {
                com.bytelog.app.ui.screens.ReceiptScreen(vm, onBack = {}, onShoppingList = {})
            }
        }
        waitFor { compose.onAllNodes(hasText("Save", substring = true), true).fetchSemanticsNodes().isNotEmpty() }
        shot("36-receipt-review")
    }
}
