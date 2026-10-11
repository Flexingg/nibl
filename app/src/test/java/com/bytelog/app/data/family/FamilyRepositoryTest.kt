package com.bytelog.app.data.family

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Family access against a fake Sparky: who is listed, reading their day, splitting and copying a meal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FamilyRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val sam = "00000000-0000-4000-8000-0000000000a1"   // can manage diary
    private val kim = "00000000-0000-4000-8000-0000000000b2"   // view only
    private val foodId = "00000000-0000-4000-8000-0000000000f1"

    private class Call(val method: String, val path: String, val body: String, val cookie: String?)
    private val calls = CopyOnWriteArrayList<Call>()
    @Volatile private var refuseFor: String? = null   // user id Sparky refuses entries for

    private lateinit var server: MockWebServer
    private lateinit var db: ByteLogDatabase
    private lateinit var food: FoodRepository
    private lateinit var family: FamilyRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                calls += Call(request.method.orEmpty(), path, body, request.getHeader("Cookie"))
                return when {
                    path == "/api/identity/users/accessible-users" -> MockResponse().setBody(
                        """[{"user_id":"$sam","full_name":"Sam Rivera","email":"sam@example.com","permissions":{"can_manage_diary":true,"can_view_reports":true},"access_end_date":null},
                            {"user_id":"$kim","full_name":"","email":"kim@example.com","permissions":{"can_view_reports":true},"access_end_date":"2027-01-01"}]"""
                    )
                    path.startsWith("/api/food-entries/by-date/") && path.contains("userId=$sam") -> MockResponse().setBody(
                        """[{"id":"e1","food_name":"Oatmeal","meal_type":"breakfast","meal_type_id":"00000000-0000-4000-8000-00000000c001","entry_date":"2026-10-10","quantity":1,"unit":"bowl","calories":300,"protein":10,"carbs":50,"fat":6},
                            {"id":"e2","food_name":"Chicken","meal_type":"Lunch","entry_date":"2026-10-10","quantity":150,"unit":"g","calories":250,"protein":46,"carbs":0,"fat":5}]"""
                    )
                    path.startsWith("/api/food-entries/by-date/") -> MockResponse().setBody("[]")
                    path.startsWith("/api/goals/by-date/") -> MockResponse().setBody("""{"calories":2400,"protein":180,"carbs":250,"fat":70}""")
                    path == "/api/meal-types" -> MockResponse().setBody("""[{"id":"00000000-0000-4000-8000-00000000c001","name":"breakfast","sort_order":0},{"id":"00000000-0000-4000-8000-00000000c003","name":"dinner","sort_order":2}]""")
                    request.method == "GET" && path == "/api/foods/$foodId" -> MockResponse().setBody(
                        """{"id":"$foodId","name":"Lasagna","default_variant":{"id":"var-1","serving_size":300,"serving_unit":"g","calories":600}}"""
                    )
                    request.method == "POST" && path == "/api/food-entries/" -> {
                        val o = json.parseToJsonElement(body).jsonObject
                        val forUser = o["user_id"]?.jsonPrimitive?.content
                        if (forUser != null && forUser == refuseFor) MockResponse().setResponseCode(403).setBody("""{"error":"Forbidden: no diary access"}""")
                        else MockResponse().setResponseCode(201).setBody("""{"id":"srv-${calls.size}"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        val api = SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        food = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), api, SettingsRepository(context), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        family = FamilyRepository(api, food)
        runBlocking { db.foodDao().insertFood(FoodItem(foodId, "Lasagna", servingSize = 300f, calories = 600f)) }
    }

    @After fun tearDown() { server.shutdown() }

    private fun dinner(id: String, qty: Float, kcal: Float) = FoodEntry(
        id = id, foodId = foodId, foodName = "Lasagna", mealTypeId = "dinner-me", mealName = "Dinner", quantity = qty, unit = "g",
        calories = kcal, protein = kcal / 20, carbs = kcal / 10, fat = kcal / 25, entryDate = "2026-10-10", syncStatus = FoodEntry.SYNC_STATUS_SYNCED
    )
    private val kims get() = runBlocking { family.members().getOrThrow().first { it.userId == kim } }
    private val sams get() = runBlocking { family.members().getOrThrow().first { it.userId == sam } }
    private fun posts(forUser: String) = calls.filter { it.method == "POST" && it.path == "/api/food-entries/" && it.body.contains("\"user_id\":\"$forUser\"") }
    private fun field(c: Call, k: String) = json.parseToJsonElement(c.body).jsonObject[k]!!.jsonPrimitive.content

    @Test fun membersComeWithWhatTheyAllowed() = runBlocking {
        val ms = family.members().getOrThrow()
        assertEquals(2, ms.size)
        assertEquals("Sam Rivera", ms[0].displayName); assertEquals("S", ms[0].initial)
        assertTrue(ms[0].canWriteDiary); assertTrue(ms[0].canViewDiary)
        assertEquals("kim", ms[1].displayName)                       // no name: the start of the email
        assertFalse(ms[1].canWriteDiary); assertTrue(ms[1].canViewDiary)
        assertEquals("2027-01-01", ms[1].accessEnds)
    }

    @Test fun aMembersDayIsReadFromTheirDiaryWithTheirGoals() = runBlocking {
        val day = family.memberDay(sams, "2026-10-10").getOrThrow()
        assertEquals(2, day.entries.size)
        assertEquals(550f, day.calories, 0.01f)
        assertEquals(2400f, day.goals!!.calories, 0f)
        assertTrue(calls.any { it.path.contains("by-date/2026-10-10?userId=$sam") })
        val goalsCall = calls.last { it.path.startsWith("/api/goals/by-date/") }
        assertEquals("sparky_active_user_id=$sam", goalsCall.cookie)   // goals are read in their context
    }

    @Test fun splittingSendsEachPersonTheirPercentAndShrinksYours() = runBlocking {
        val mine = dinner("m1", 300f, 600f)
        db.foodEntryDao().insertEntry(mine)
        val out = family.shareMeal(listOf(mine), listOf(Share(null, 60), Share(sams, 40)), ShareMode.SPLIT)

        assertEquals(1, out.size); assertTrue(out[0].ok); assertEquals(1, out[0].sent)
        val sent = posts(sam).single()
        assertEquals(foodId, field(sent, "food_id")); assertEquals("var-1", field(sent, "variant_id"))
        assertEquals("120.0", field(sent, "quantity"))                // 40% of 300 g
        assertEquals("240.0", field(sent, "calories"))                // 40% of 600
        assertFalse("meal type is Sam's own (id from their meal types)", sent.body.contains("dinner-me"))
        assertEquals("00000000-0000-4000-8000-00000000c003", field(sent, "meal_type_id"))
        assertEquals("2026-10-10", field(sent, "entry_date"))
        val left = db.foodEntryDao().getEntriesForDateOnce("2026-10-10").single()
        assertEquals(180f, left.quantity, 0.01f)                      // yours: 60%
        assertEquals(360f, left.calories, 0.01f)
    }

    @Test fun whatCantBeSentStaysWithYou() = runBlocking {
        refuseFor = sam
        val mine = dinner("m1", 300f, 600f)
        db.foodEntryDao().insertEntry(mine)
        val out = family.shareMeal(listOf(mine), listOf(Share(null, 50), Share(sams, 50)), ShareMode.SPLIT)
        assertFalse(out.single().ok)
        assertTrue(out.single().error!!.contains("no diary access"))
        val left = db.foodEntryDao().getEntriesForDateOnce("2026-10-10").single()
        assertEquals("nothing was taken from you", 300f, left.quantity, 0.01f)
        assertEquals(600f, left.calories, 0.01f)
    }

    @Test fun copyingGivesEveryoneTheWholeMealAndLeavesYoursAlone() = runBlocking {
        val mine = dinner("m1", 300f, 600f)
        db.foodEntryDao().insertEntry(mine)
        val out = family.shareMeal(listOf(mine), listOf(Share(sams, 100)), ShareMode.COPY)
        assertTrue(out.single().ok)
        assertEquals("300.0", field(posts(sam).single(), "quantity"))
        delay(300)
        assertEquals(300f, db.foodEntryDao().getEntriesForDateOnce("2026-10-10").single().quantity, 0.01f)
    }

    @Test fun aMealCopiedFromAMemberBecomesNewEntriesInYourDiary() = runBlocking {
        val day = family.memberDay(sams, "2026-10-10").getOrThrow()
        family.copyToMe(day.entries.filter { it.mealName == "Lunch" }, "2026-10-11", "lunch", "Lunch")
        val mine = db.foodEntryDao().getEntriesForDateOnce("2026-10-11").single()
        assertEquals("Chicken", mine.foodName)
        assertEquals("lunch", mine.mealTypeId)
        assertNull(mine.foodId)
        assertTrue("new id, not Sam's", mine.id != "e2")
        assertEquals(250f, mine.calories, 0.01f)
    }
}
