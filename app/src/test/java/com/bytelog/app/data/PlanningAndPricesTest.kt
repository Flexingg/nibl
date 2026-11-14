package com.bytelog.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.PlanRepository
import com.bytelog.app.data.repository.PriceRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.SparkyPrice
import com.bytelog.app.ui.viewmodel.FastSearchViewModel
import com.bytelog.app.ui.viewmodel.ShoppingListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue

/** Planning several days at once, copying a plan, and store and price saved on Sparky foods (same sandbox as the other Robolectric tests). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class PlanningAndPricesTest {
    private val yogurtId = "11111111-2222-3333-4444-555555555555"
    private val sharedId = "99999999-2222-3333-4444-555555555555"
    private lateinit var server: MockWebServer
    private lateinit var db: ByteLogDatabase
    private lateinit var foods: FoodRepository
    private lateinit var plans: PlanRepository
    private lateinit var prices: PriceRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val puts = ConcurrentLinkedQueue<String>()
    private val today = LocalDate.now()
    @Volatile private var variantCustom = """{"Omega 3":"1.2"}"""

    @Before fun setUp() = runBlocking {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/foods/food-variants?food_id=$yogurtId") -> MockResponse().setBody(
                        """[{"id":"v-other","food_id":"$yogurtId","is_default":false,"custom_nutrients":{}},
                            {"id":"v-yog","food_id":"$yogurtId","is_default":true,"custom_nutrients":$variantCustom}]"""
                    )
                    path.startsWith("/api/foods/food-variants?food_id=$sharedId") -> MockResponse().setBody("""[{"id":"v-shared","is_default":true,"custom_nutrients":null}]""")
                    path == "/api/foods/food-variants/v-yog" && request.method == "PUT" -> {
                        val body = request.body.readUtf8(); puts += body
                        variantCustom = (Json.parseToJsonElement(body).jsonObject["custom_nutrients"] as JsonObject).toString()
                        MockResponse().setBody("{}")
                    }
                    path == "/api/foods/food-variants/v-shared" -> MockResponse().setResponseCode(403).setBody("""{"error":"Forbidden: You do not have permission to update this food variant."}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        val api = SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        foods = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, SettingsRepository(context), scope, db.prepBatchDao())
        plans = PlanRepository(db.plannedEntryDao(), foods)
        prices = PriceRepository(db.foodPriceDao(), api) { LocalDate.of(2026, 11, 14) }
        db.mealTypeDao().insertMealTypes(MealType.DEFAULT_MEAL_TYPES)
        db.foodDao().insertFood(FoodItem(yogurtId, "Greek Yogurt", brand = "Fage", servingSize = 170f, servingUnit = "g", calories = 150f, protein = 15f, carbs = 6f, fat = 5f))
    }

    @After fun tearDown() { server.shutdown(); db.close(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private fun day(n: Int) = today.plusDays(n.toLong()).toString()

    private suspend fun <T> waitFor(read: suspend () -> T, ok: (T) -> Boolean): T = withTimeout(10_000) {
        while (true) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val v = read(); if (ok(v)) return@withTimeout v
            Thread.sleep(20)
        }
        @Suppress("UNREACHABLE_CODE") read()
    }

    @Test fun theTrayCanBePlannedOnSeveralDaysAtOnce() = runBlocking {
        val vm = FastSearchViewModel(foods, plans = plans).apply { initContext("breakfast", "Breakfast", today.toString()) }
        assertFalse(vm.isPlanning)
        vm.stageFood(db.foodDao().getFoodById(yogurtId)!!)
        vm.setPlanDays(setOf(day(1), day(2), day(4)))
        assertTrue(vm.isPlanning)
        var done = false
        vm.commitStagedTray { done = true }
        waitFor({ done }) { it }

        for (d in listOf(day(1), day(2), day(4))) {
            val planned = db.plannedEntryDao().forDate(d).first()
            assertEquals(d, listOf("Greek Yogurt"), planned.map { it.foodName })
            assertEquals("Breakfast", planned.single().mealName)
        }
        assertTrue(db.plannedEntryDao().forDate(day(3)).first().isEmpty())
        // Nothing was logged for today, and the picked days reset for next time
        assertTrue(db.foodEntryDao().getEntriesForDateOnce(today.toString()).isEmpty())
        assertTrue(vm.planDays.value.isEmpty())
    }

    @Test fun aDaysPlanCopiesToOtherDays() = runBlocking {
        plans.plan(listOf(
            FoodEntry("a", yogurtId, "Greek Yogurt", null, "breakfast", "Breakfast", 1f, "serving", 150f, 15f, 6f, 5f, day(1), FoodEntry.SYNC_STATUS_PENDING_INSERT),
            FoodEntry("b", null, "Salmon", null, "dinner", "Dinner", 150f, "g", 310f, 34f, 0f, 18f, day(1), FoodEntry.SYNC_STATUS_PENDING_INSERT)
        ))
        val source = db.plannedEntryDao().forDate(day(1)).first()
        val copies = plans.copyTo(source, setOf(day(1), day(3), day(5)))
        assertEquals("the source day is skipped", 4, copies.size)
        assertEquals(listOf("Greek Yogurt", "Salmon"), db.plannedEntryDao().forDate(day(3)).first().map { it.foodName })
        assertEquals(2, db.plannedEntryDao().forDate(day(1)).first().size)
        assertEquals(6, db.plannedEntryDao().between(day(0), day(6)).first().size)
    }

    @Test fun storeAndPriceAreSavedOnTheFoodInSparkyKeepingItsOtherCustomNutrients() = runBlocking {
        val saved = prices.save(yogurtId, "Greek Yogurt", "Fage", SparkyPrice("Costco", 4.99f, 32f, "oz"))
        assertTrue(saved.problem, saved.onSparky)
        val sent = Json.parseToJsonElement(puts.single()).jsonObject
        assertEquals(yogurtId, sent["food_id"]!!.jsonPrimitive.content)
        val custom = sent["custom_nutrients"] as JsonObject
        assertEquals("1.2", custom["Omega 3"]!!.jsonPrimitive.content)
        assertEquals("Costco", custom["nibl_store"]!!.jsonPrimitive.content)
        assertEquals(4.99, custom["nibl_price"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals("oz", custom["nibl_price_unit"]!!.jsonPrimitive.content)
        assertTrue(db.foodPriceDao().get(yogurtId)!!.savedToSparky)

        // Another phone, with nothing saved locally, reads it back from Sparky
        db.foodPriceDao().delete(yogurtId)
        val loaded = prices.load(yogurtId, "Greek Yogurt", "Fage")!!
        assertEquals("Costco", loaded.store); assertEquals(32f, loaded.quantity)

        // Clearing takes it off Sparky too
        prices.save(yogurtId, "Greek Yogurt", "Fage", SparkyPrice())
        assertFalse(puts.last().contains("nibl_"))
        assertTrue(puts.last().contains("Omega 3"))
        assertNull(db.foodPriceDao().get(yogurtId))
    }

    @Test fun foodsThatArentYoursKeepTheirPriceOnThePhone() = runBlocking {
        val shared = prices.save(sharedId, "Bananas", null, SparkyPrice("Aldi", 0.25f, 1f, "each"))
        assertFalse(shared.onSparky)
        assertTrue(shared.problem!!, shared.problem!!.contains("isn't one of your own foods"))
        assertNotNull(db.foodPriceDao().get(sharedId))

        val local = prices.save(null, "Tortillas", "Mission", SparkyPrice(price = 3.49f, quantity = 10f, unit = "each"))
        assertFalse(local.onSparky)
        assertEquals(3.49f, prices.get(null, "tortillas", "MISSION")!!.price)
    }

    @Test fun theShoppingListAddsUpThePlanWithPrices() = runBlocking {
        prices.save(yogurtId, "Greek Yogurt", "Fage", SparkyPrice("Costco", 4.99f, 32f, "oz"))
        plans.plan((1..3).map { d ->
            FoodEntry("y$d", yogurtId, "Greek Yogurt", "Fage", "breakfast", "Breakfast", 1f, "serving", 150f, 15f, 6f, 5f, day(d), FoodEntry.SYNC_STATUS_PENDING_INSERT)
        } + FoodEntry("far", null, "Pasta", null, "dinner", "Dinner", 100f, "g", 350f, 12f, 70f, 2f, day(10), FoodEntry.SYNC_STATUS_PENDING_INSERT))

        val vm = ShoppingListViewModel(plans, prices, foods)
        val s = waitFor({ vm.state.value }) { !it.loading && it.list.items.isNotEmpty() }
        // 3 servings of 170 g is 510 g, about 18 oz: one 32 oz tub. Pasta is 10 days out, past the week.
        val yogurt = s.list.items.single()
        assertEquals("510 g", yogurt.amountLabel)
        assertEquals(1, yogurt.packages); assertEquals(4.99, yogurt.cost!!, 0.001)
        assertEquals("Costco", yogurt.store)

        vm.setDays(14)
        waitFor({ vm.state.value }) { it.list.items.size == 2 }
        vm.toggle(vm.state.value.list.items.first { it.name == "Greek Yogurt" })
        val ticked = waitFor({ vm.state.value }) { it.checked.isNotEmpty() }
        assertEquals(1, ticked.left)
        assertTrue(vm.shareText().contains("☑ Greek Yogurt (Fage)"))
    }
}
