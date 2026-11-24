package com.bytelog.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.WeightEntry
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.WeightRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Syncing must never lose what was logged on the phone: changes are sent to Sparky first, kept if Sparky
 * refuses them, and the download only removes things Sparky itself deleted.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class SyncKeepsPhoneDataTest {
    private val today = "2026-10-06"
    private val yesterday = "2026-10-05"

    /** A tiny Sparky: food entries by id, weigh-ins by date. */
    private class FakeSparky {
        val entries = ConcurrentHashMap<String, Map<String, Any?>>()
        val weights = ConcurrentHashMap<String, Float>()
        val deleted = mutableListOf<String>()
        val ids = AtomicInteger(0)
        @Volatile var online = true
        @Volatile var refuseCreates: String? = null      // error body to return for POST /api/food-entries/
        val hiddenFromDownload = mutableSetOf<String>()  // stored, but missing from the by-date list
        /** Library foods by id: name, barcode, variant id, quick-food flag, serving size. */
        val foods = ConcurrentHashMap<String, Map<String, Any?>>()
        val foodCreates = AtomicInteger(0)
        /** How Sparky answers a new entry: "plain" (the entry), "wrapped" ({"data":{...}}), or "noId" (no id at all). */
        @Volatile var createAnswer = "plain"
        /** Save the next new entries, then drop the connection before answering. */
        val dropAnswers = AtomicInteger(0)
        val entryCreates = AtomicInteger(0)
    }
    private val sparky = FakeSparky()
    private lateinit var server: MockWebServer
    private lateinit var db: ByteLogDatabase
    private lateinit var food: FoodRepository
    private lateinit var weights: WeightRepository
    private val json = Json { ignoreUnknownKeys = true }

    private fun entryJson(id: String, e: Map<String, Any?>) = buildString {
        append("{\"id\":\"$id\",\"food_name\":\"${e["food_name"]}\",\"meal_type\":\"${e["meal_type"]}\",\"entry_date\":\"${e["entry_date"]}\",")
        append("\"quantity\":${e["quantity"]},\"unit\":\"g\",\"calories\":${e["calories"]},\"protein\":1,\"carbs\":1,\"fat\":1}")
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!sparky.online) return MockResponse().setResponseCode(503)
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path == "/api/foods" -> {
                        val o = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val barcode = o["barcode"]?.jsonPrimitive?.contentOrNull
                        // Sparky hands back the food it already has for a known barcode
                        sparky.foods.entries.firstOrNull { barcode != null && it.value["barcode"] == barcode }?.let { (id, f) ->
                            return MockResponse().setResponseCode(201).setBody("{\"id\":\"$id\",\"name\":\"${f["name"]}\"}")
                        }
                        sparky.foodCreates.incrementAndGet()
                        val id = "00000000-0000-4000-8000-%012d".format(1000 + sparky.ids.incrementAndGet())
                        sparky.foods[id] = mapOf(
                            "name" to o["name"]!!.jsonPrimitive.content, "barcode" to barcode, "variant" to "var-$id",
                            "quick" to o["is_quick_food"]?.jsonPrimitive?.content, "serving_size" to o["serving_size"]?.jsonPrimitive?.content,
                            "calories" to o["calories"]?.jsonPrimitive?.content
                        )
                        MockResponse().setResponseCode(201).setBody("{\"id\":\"$id\",\"name\":\"${sparky.foods[id]!!["name"]}\",\"default_variant\":{\"id\":\"var-$id\"}}")
                    }
                    request.method == "GET" && path.startsWith("/api/foods/") -> {
                        val id = path.removePrefix("/api/foods/")
                        val f = sparky.foods[id] ?: return MockResponse().setResponseCode(404).setBody("{\"error\":\"Food not found.\"}")
                        MockResponse().setBody("{\"id\":\"$id\",\"name\":\"${f["name"]}\",\"default_variant\":{\"id\":\"${f["variant"]}\",\"serving_size\":100,\"serving_unit\":\"g\",\"calories\":100}}")
                    }
                    request.method == "POST" && path == "/api/food-entries/" -> {
                        sparky.refuseCreates?.let { return MockResponse().setResponseCode(400).setBody(it) }
                        val o = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        // Sparky's row-level security: an entry must point at a food in the library
                        val foodId = o["food_id"]?.jsonPrimitive?.contentOrNull
                        if (foodId == null || sparky.foods[foodId]?.get("variant") != o["variant_id"]?.jsonPrimitive?.contentOrNull) {
                            return MockResponse().setResponseCode(500)
                                .setBody("{\"error\":\"new row violates row-level security policy for table \\\"food_entries\\\"\"}")
                        }
                        val id = "srv-${sparky.ids.incrementAndGet()}"
                        sparky.entryCreates.incrementAndGet()
                        sparky.entries[id] = mapOf(
                            "food_id" to foodId,
                            "food_name" to o["food_name"]!!.jsonPrimitive.content, "meal_type" to o["meal_type"]!!.jsonPrimitive.content,
                            "entry_date" to o["entry_date"]!!.jsonPrimitive.content, "quantity" to o["quantity"]!!.jsonPrimitive.content,
                            "calories" to o["calories"]!!.jsonPrimitive.content
                        )
                        if (sparky.dropAnswers.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
                            return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        }
                        val body = entryJson(id, sparky.entries[id]!!)
                        MockResponse().setResponseCode(201).setBody(when (sparky.createAnswer) {
                            "wrapped" -> "{\"data\":$body}"
                            "noId" -> "{\"message\":\"created\"}"
                            else -> body
                        })
                    }
                    request.method == "PUT" && path.startsWith("/api/food-entries/") -> {
                        val id = path.removePrefix("/api/food-entries/")
                        val existing = sparky.entries[id] ?: return MockResponse().setResponseCode(404)
                        val o = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        sparky.entries[id] = existing + mapOf("quantity" to (o["quantity"]?.jsonPrimitive?.content ?: existing["quantity"]))
                        MockResponse().setBody("{}")
                    }
                    request.method == "DELETE" && path.startsWith("/api/food-entries/") -> {
                        val id = path.removePrefix("/api/food-entries/")
                        sparky.entries.remove(id); synchronized(sparky.deleted) { sparky.deleted += id }
                        MockResponse().setBody("{}")
                    }
                    request.method == "GET" && path.startsWith("/api/food-entries/by-date/") -> {
                        val date = path.removePrefix("/api/food-entries/by-date/")
                        val list = sparky.entries.filter { it.value["entry_date"] == date && it.key !in sparky.hiddenFromDownload }
                        MockResponse().setBody(list.entries.joinToString(",", "[", "]") { entryJson(it.key, it.value) })
                    }
                    request.method == "POST" && path == "/api/measurements/check-in" -> {
                        val o = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        sparky.weights[o["entry_date"]!!.jsonPrimitive.content] = o["weight"]!!.jsonPrimitive.content.toFloat()
                        MockResponse().setBody("{}")
                    }
                    request.method == "GET" && path.startsWith("/api/measurements/check-in/") -> {
                        val w = sparky.weights[path.removePrefix("/api/measurements/check-in/")]
                        if (w == null) MockResponse().setResponseCode(404) else MockResponse().setBody("{\"weight\":$w}")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        val api = SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        val settings = SettingsRepository(context)
        runBlocking { settings.saveWeightUnit("kg") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        food = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, settings, scope)
        weights = WeightRepository(db.weightDao(), api, settings, scope)
    }

    @After fun tearDown() { server.shutdown() }

    private fun entry(id: String, name: String, date: String = today, kcal: Float = 300f) = FoodEntry(
        id = id, foodName = name, mealTypeId = "lunch", mealName = "Lunch", quantity = 100f,
        calories = kcal, protein = 1f, carbs = 1f, fat = 1f, entryDate = date
    )
    private suspend fun names(date: String = today) = db.foodEntryDao().getEntriesForDate(date).first().map { it.foodName }.sorted()
    private suspend fun settle() = delay(400)
    private suspend fun until(check: suspend () -> Boolean) = withTimeout(10_000) { while (!check()) delay(25) }

    @Test fun foodLoggedOfflineSurvivesSyncAndUploadsWhenSparkyIsBack() = runBlocking {
        sparky.online = false
        food.commitMultiAdd(listOf(entry("local-1", "Oatmeal")))
        settle()
        assertTrue(food.syncDateWithServer(today).isFailure)
        assertEquals(listOf("Oatmeal"), names())                      // still there after a failed sync
        assertEquals(1, food.countPendingSync())

        sparky.online = true
        assertTrue(food.syncDateWithServer(today).isSuccess)
        assertEquals(listOf("Oatmeal"), names())                      // and after a good one
        assertEquals(listOf("Oatmeal"), sparky.entries.values.map { it["food_name"] })   // now on Sparky too
        assertEquals(0, food.countPendingSync())
    }

    @Test fun foodSparkyRefusesStaysOnThePhoneWithTheReason() = runBlocking {
        sparky.refuseCreates = """{"error":"Invalid meal type: Lunch"}"""
        food.commitMultiAdd(listOf(entry("local-1", "Salad")))
        settle()
        food.syncDateWithServer(today)
        assertEquals(listOf("Salad"), names())
        assertEquals(1, food.countPendingSync())
        assertTrue(food.lastPushError!!.contains("Invalid meal type"))
    }

    @Test fun uploadedFoodMissingFromSparkysDownloadIsKept() = runBlocking {
        food.commitMultiAdd(listOf(entry("local-1", "Rice")))
        until { sparky.entries.isNotEmpty() }
        settle()
        sparky.hiddenFromDownload += sparky.entries.keys          // e.g. filed under another day by the server
        food.syncDateWithServer(today)
        assertEquals("previously this sync deleted it", listOf("Rice"), names())
    }

    @Test fun pendingFoodOnOtherDaysIsSentToo() = runBlocking {
        sparky.online = false
        food.commitMultiAdd(listOf(entry("local-1", "Yesterday's soup", date = yesterday)))
        settle()
        sparky.online = true
        food.syncDateWithServer(today)                              // syncing today...
        assertEquals(listOf("Yesterday's soup"), sparky.entries.values.map { it["food_name"] })   // ...sends yesterday's too
        assertEquals(listOf("Yesterday's soup"), names(yesterday))
    }

    @Test fun foodDeletedOnSparkyIsRemovedHereAndSparkysNewFoodArrives() = runBlocking {
        sparky.entries["srv-a"] = mapOf("food_name" to "Toast", "meal_type" to "Breakfast", "entry_date" to today, "quantity" to "1", "calories" to "120")
        sparky.entries["srv-b"] = mapOf("food_name" to "Eggs", "meal_type" to "Breakfast", "entry_date" to today, "quantity" to "2", "calories" to "140")
        food.syncDateWithServer(today)
        assertEquals(listOf("Eggs", "Toast"), names())
        sparky.entries.remove("srv-a")                              // deleted on the website
        sparky.entries["srv-c"] = mapOf("food_name" to "Coffee", "meal_type" to "Breakfast", "entry_date" to today, "quantity" to "1", "calories" to "5")
        food.syncDateWithServer(today)
        assertEquals(listOf("Coffee", "Eggs"), names())
    }

    @Test fun deletingOfflineIsSentLaterAndDoesNotComeBack() = runBlocking {
        sparky.entries["srv-a"] = mapOf("food_name" to "Cookie", "meal_type" to "Snack", "entry_date" to today, "quantity" to "1", "calories" to "200")
        food.syncDateWithServer(today)
        val cookie = db.foodEntryDao().getEntriesForDateOnce(today).single()
        sparky.online = false
        food.deleteEntry(cookie)
        settle()
        assertEquals(emptyList<String>(), names())                  // gone from the diary straight away
        sparky.online = true
        sparky.online = false; food.syncDateWithServer(today); sparky.online = true
        assertEquals("a failed sync doesn't bring it back", emptyList<String>(), names())
        food.syncDateWithServer(today)
        assertEquals(emptyList<String>(), names())
        assertTrue(sparky.deleted.contains("srv-a"))
        assertTrue(sparky.entries.isEmpty())
    }

    @Test fun editsMadeOfflineWinOverTheDownload() = runBlocking {
        sparky.entries["srv-a"] = mapOf("food_name" to "Pasta", "meal_type" to "Dinner", "entry_date" to today, "quantity" to "100", "calories" to "350")
        food.syncDateWithServer(today)
        val pasta = db.foodEntryDao().getEntriesForDateOnce(today).single()
        sparky.online = false
        food.updateEntry(pasta.copy(quantity = 250f, calories = 875f))
        settle()
        food.syncDateWithServer(today)                              // offline: download fails, edit kept
        assertEquals(250f, db.foodEntryDao().getEntriesForDateOnce(today).single().quantity, 0.01f)
        sparky.online = true
        food.syncDateWithServer(today)
        assertEquals("250.0", sparky.entries["srv-a"]!!["quantity"])
        assertEquals(250f, db.foodEntryDao().getEntriesForDateOnce(today).single().quantity, 0.01f)
    }

    @Test fun aWeighInFromThePhoneIsNotOverwrittenBySparkysOlderOne() = runBlocking {
        sparky.weights[today] = 84.0f
        sparky.online = false
        weights.logWeight(today, 82.5f, "kg")
        settle()
        sparky.online = true
        weights.syncWeightForDate(today)
        assertEquals(82.5f, db.weightDao().getAllWeightsList().single { it.entryDate == today }.weight, 0.01f)
        assertEquals(82.5f, sparky.weights[today]!!, 0.01f)        // and Sparky now has it
        assertEquals(WeightEntry.SYNC_STATUS_SYNCED, db.weightDao().getAllWeightsList().single().syncStatus)
    }

    @Test fun entriesAreLinkedToSparkyFoodsSoSparkyAcceptsThem() = runBlocking {
        val oatsId = "00000000-0000-4000-8000-000000000001"
        sparky.foods[oatsId] = mapOf("name" to "Rolled Oats", "variant" to "var-oats")
        db.foodDao().insertFoods(
            listOf(
                com.bytelog.app.data.local.model.FoodItem(oatsId, "Rolled Oats", servingSize = 40f, calories = 150f),
                com.bytelog.app.data.local.model.FoodItem("off_123", "Peanut Butter", barcode = "0123456789012", servingSize = 32f, calories = 190f)
            )
        )
        sparky.online = false
        food.commitMultiAdd(
            listOf(
                entry("l-1", "Rolled Oats").copy(foodId = oatsId),                 // already in Sparky's library
                entry("l-2", "Peanut Butter").copy(foodId = "off_123"),            // an OpenFoodFacts result
                entry("l-3", "Peanut Butter", kcal = 95f).copy(foodId = "off_123", quantity = 16f),
                entry("l-4", "Quick add", kcal = 250f)                             // no food at all
            )
        )
        settle()
        sparky.online = true
        val report = food.pushPendingEntries()
        assertEquals("nothing left behind: ${report.error}", 0, food.countPendingSync())
        assertEquals(4, sparky.entries.size)

        val byName = sparky.entries.values.groupBy { it["food_name"] }
        assertEquals(oatsId, byName["Rolled Oats"]!!.single()["food_id"])
        val pb = sparky.foods.entries.single { it.value["name"] == "Peanut Butter" }
        assertEquals("one Sparky food for both peanut butter entries", setOf(pb.key), byName["Peanut Butter"]!!.map { it["food_id"] }.toSet())
        assertEquals("0123456789012", pb.value["barcode"])
        val quick = sparky.foods.values.single { it["name"] == "Quick add" }
        assertEquals("true", quick["quick"])
        assertEquals(250f, quick["calories"].toString().toFloat())
        assertEquals("two foods created: peanut butter and the quick add", 2, sparky.foodCreates.get())

        // The phone's OpenFoodFacts food now has Sparky's id, so logging it again needs no new food
        assertEquals(null, db.foodDao().getFoodById("off_123"))
        assertEquals("Peanut Butter", db.foodDao().getFoodById(pb.key)!!.name)
    }

    @Test fun anOpenFoodFactsFoodSparkyAlreadyHasIsReusedByBarcode() = runBlocking {
        val existing = "00000000-0000-4000-8000-000000000077"
        sparky.foods[existing] = mapOf("name" to "Peanut Butter", "barcode" to "0123456789012", "variant" to "var-pb")
        db.foodDao().insertFood(com.bytelog.app.data.local.model.FoodItem("off_123", "Peanut Butter", barcode = "0123456789012", servingSize = 32f, calories = 190f))
        food.commitMultiAdd(listOf(entry("l-1", "Peanut Butter").copy(foodId = "off_123")))
        until { food.countPendingSync() == 0 }
        assertEquals(0, sparky.foodCreates.get())
        assertEquals(existing, sparky.entries.values.single()["food_id"])
    }

    // --- Foods must never end up in the diary twice ---

    @Test fun anAnswerWithoutTheNewIdDoesNotDoubleTheFood() = runBlocking {
        sparky.createAnswer = "noId"
        food.commitMultiAdd(listOf(entry("local-1", "Banana")))
        until { sparky.entries.isNotEmpty() }
        settle()
        food.syncDateWithServer(today)
        food.syncDateWithServer(today)
        assertEquals(listOf("Banana"), names())
        assertEquals("Sparky's copy took its place", sparky.entries.keys.toList(), db.foodEntryDao().getEntriesForDateOnce(today).map { it.id })
        assertEquals(1, sparky.entries.size)
    }

    @Test fun aWrappedAnswerStillGivesTheNewId() = runBlocking {
        sparky.createAnswer = "wrapped"
        food.commitMultiAdd(listOf(entry("local-1", "Apple")))
        until { food.countPendingSync() == 0 }
        settle()
        assertEquals(sparky.entries.keys.toList(), db.foodEntryDao().getEntriesForDateOnce(today).map { it.id })
        food.syncDateWithServer(today)
        assertEquals(listOf("Apple"), names())
    }

    @Test fun anUploadWhoseAnswerWasLostIsNotSentAgain() = runBlocking {
        sparky.online = false
        food.commitMultiAdd(listOf(entry("local-1", "Yogurt")))
        settle()
        sparky.online = true
        sparky.dropAnswers.set(1)
        food.pushPendingEntries()                  // Sparky saved it, the answer never came
        food.syncDateWithServer(today)             // the retry finds it on Sparky instead of sending it again
        food.syncDateWithServer(today)
        assertEquals("one copy on Sparky (creates: ${sparky.entryCreates.get()}, ${sparky.entries.values.map { it["food_name"] }})", 1, sparky.entries.size)
        assertEquals(listOf("Yogurt"), names())
        assertEquals(0, food.countPendingSync())
    }

    @Test fun twoOfTheSameFoodLoggedOnPurposeStayTwo() = runBlocking {
        sparky.createAnswer = "noId"
        food.commitMultiAdd(listOf(entry("local-1", "Coffee"), entry("local-2", "Coffee")))
        until { sparky.entries.size == 2 }
        settle()
        food.syncDateWithServer(today)
        assertEquals(listOf("Coffee", "Coffee"), names())
        assertEquals(2, sparky.entries.size)
    }

    @Test fun repeatedSyncsNeverMultiplyTheDiary() = runBlocking {
        sparky.entries["srv-a"] = mapOf("food_name" to "Toast", "meal_type" to "Breakfast", "entry_date" to today, "quantity" to "1", "calories" to "120")
        food.commitMultiAdd(listOf(entry("local-1", "Soup")))
        until { food.countPendingSync() == 0 }
        repeat(4) { food.syncDateWithServer(today) }
        assertEquals(listOf("Soup", "Toast"), names())
        assertEquals(2, sparky.entries.size)
    }
}

