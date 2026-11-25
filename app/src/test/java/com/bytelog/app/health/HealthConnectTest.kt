package com.bytelog.app.health

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.WaterLogEntry
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Writing the diary to Health Connect without doubling up with the SparkyFitness phone app. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class HealthConnectTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now: Instant = Instant.parse("2026-11-01T20:00:00Z")
    private val day = "2026-11-01"
    private val sparkyApp = "com.SparkyApps.SparkyFitnessMobile"

    private fun food(id: String, name: String, kcal: Float, meal: String = "Lunch", time: String? = null, source: String? = null, status: String = FoodEntry.SYNC_STATUS_SYNCED) =
        FoodEntry(id = id, foodName = name, mealTypeId = meal.lowercase(), mealName = meal, quantity = 1f, unit = "serving",
            calories = kcal, protein = 10f, carbs = 20f, fat = 5f, entryDate = day, entryTime = time, syncStatus = status, source = source)

    // ----- the plan -----

    @Test fun sparkysRecordIdsGiveBackItsEntryIds() {
        val id = "3f6c2b8e-1d2a-4c55-9a1b-0c3e5d7f9a10"
        assertEquals(id, HealthConnectPlan.sparkyEntryId("sparky-nutrition-$id-1730491200000"))
        assertEquals("42", HealthConnectPlan.sparkyWaterId("sparky-water-entry-42-1730491200000"))
        assertNull(HealthConnectPlan.sparkyEntryId("nibl-nutrition-x-1"))
        assertNull(HealthConnectPlan.sparkyEntryId(null))
    }

    @Test fun foodAlreadyWrittenBySparkyOrImportedOrNotYetEatenIsLeftOut() {
        val entries = listOf(
            food("a", "Oatmeal", 300f, "Breakfast"),
            food("b", "Turkey sandwich", 450f),
            food("c", "Steps snack", 120f, source = "Health Connect"),          // came from Health Connect
            food("d", "Deleted", 100f, status = FoodEntry.SYNC_STATUS_PENDING_DELETE),
            food("e", "Late dinner", 700f, "Dinner", time = "23:30:00"),         // not eaten yet
            food("f", "Apple", 95f, "Snacks")
        )
        val existing = listOf(
            ExistingRecord("sparky-nutrition-a-1730000000000", sparkyApp, "Oatmeal", 300.0, start = now),
            ExistingRecord("sparky-nutrition-zz-1730000000000", sparkyApp, "Turkey sandwich", 450.4, start = now),  // another id, same food
            ExistingRecord("x", "com.other.app", "Apple", 95.0, start = now)                                          // another app: not Sparky's
        )
        val plan = HealthConnectPlan.foodToWrite(entries, existing, zone, now, version = 7)
        // "b" isn't matched by name: its twin has an entry id, so ids decide, and "zz" isn't "b"
        assertEquals(listOf("b", "f"), plan.map { it.entry.id })
        assertEquals("nibl-nutrition-f", plan.last().clientRecordId)   // the same every time, so it's updated in place
        assertEquals(Instant.parse("2026-11-01T15:00:00Z"), plan.last().start)   // snacks: 3 pm, as Sparky does
    }

    @Test fun foodIsWrittenOnlyOnceSparkyHasIt() {
        // Its id changes when Sparky takes it; written before, the record would be orphaned and imported twice
        val entries = listOf(food("new", "Bagel", 280f, status = FoodEntry.SYNC_STATUS_PENDING_INSERT), food("up", "Banana", 105f, status = FoodEntry.SYNC_STATUS_UPLOADED))
        assertEquals(listOf("up"), HealthConnectPlan.foodToWrite(entries, emptyList(), zone, now, 1).map { it.entry.id })
    }

    @Test fun sparkysImportsOfWhatNiblWroteAreRecognisedWithoutTheirRecordId() {
        // Sparky's diary list doesn't say which Health Connect record an import came from
        val server = listOf(
            food("s1", "Oatmeal", 300f, "Breakfast"),
            food("s2", "Banana", 105f),
            food("hc1", "oatmeal ", 300f, "Breakfast", source = "Health Connect"),   // the app's copy of s1
            food("hc2", "Oatmeal", 300f, "Breakfast", source = "Health Connect"),    // an older copy of it
            food("hc3", "Bagel", 280f, source = "Health Connect"),                   // the copy of one not uploaded yet
            food("hc4", "Protein bar", 200f, "Snacks", source = "Health Connect")    // from another app: stays
        )
        val local = listOf(food("l1", "Bagel", 280f, status = FoodEntry.SYNC_STATUS_PENDING_INSERT))
        assertEquals(listOf("s1", "s2", "hc4"), HealthConnectEcho.dropFoodEchoes(server, local).map { it.id })
        // A family member's day, with nothing on this phone: the list alone is enough
        assertEquals(listOf("s1", "s2", "hc3", "hc4"), HealthConnectEcho.dropFoodEchoes(server).map { it.id })
    }

    @Test fun drinksImportedBackAreRecognisedToo() {
        val server = listOf(
            WaterLogEntry(id = "w1", entryDate = day, waterMl = 591f),
            WaterLogEntry(id = "w2", entryDate = day, waterMl = 591.4f, source = "Health Connect"),
            WaterLogEntry(id = "w3", entryDate = day, waterMl = 350f, source = "Health Connect")
        )
        assertEquals(listOf("w1", "w3"), HealthConnectEcho.dropWaterEchoes(server).map { it.id })
    }

    @Test fun recordsFromSparkyWithoutAnEntryIdMatchByNameAndCalories() {
        val entries = listOf(food("b", "Turkey Sandwich ", 450f), food("g", "Turkey sandwich", 450f))
        val existing = listOf(ExistingRecord(null, sparkyApp, "turkey sandwich", 450.0, start = now))
        // One record covers one entry: the second sandwich is still written
        assertEquals(listOf("g"), HealthConnectPlan.foodToWrite(entries, existing, zone, now, 1).map { it.entry.id })
    }

    @Test fun drinksFollowTheSameRules() {
        val logs = listOf(
            WaterLogEntry(id = "41", entryDate = day, waterMl = 500f, loggedAt = "2026-11-01T09:00:00Z"),
            WaterLogEntry(id = "42", entryDate = day, waterMl = 250f, loggedAt = "2026-11-01T10:00:00Z"),
            WaterLogEntry(id = "43", entryDate = day, waterMl = 330f, loggedAt = "2026-11-01T11:00:00Z", source = "Health Connect"),
            WaterLogEntry(id = "44", entryDate = day, waterMl = 591f, loggedAt = "2026-11-01T12:00:00Z")
        )
        val existing = listOf(
            ExistingRecord("sparky-water-entry-41-1", sparkyApp, ml = 500.0, start = Instant.parse("2026-11-01T09:00:00Z")),
            ExistingRecord(null, sparkyApp, ml = 591.0, start = Instant.parse("2026-11-01T12:01:00Z"))
        )
        assertEquals(listOf("42"), HealthConnectPlan.waterToWrite(logs, existing, zone, now, 1).map { it.log.id })
    }

    // ----- the sync -----

    private class FakeStore : HealthStore {
        val nutrition = mutableListOf<ExistingRecord>()
        val hydration = mutableListOf<ExistingRecord>()
        val ours = mutableMapOf<String, Pair<String, String>>()   // clientRecordId -> (uuid, name)
        var writes = 0
        var failReads = false
        override suspend fun readNutrition(from: Instant, to: Instant): List<ExistingRecord> {
            if (failReads) throw SecurityException("Reading Health Connect isn't allowed")
            return nutrition + ours.filterKeys { it.startsWith("nibl-nutrition") }.map { (k, v) -> ExistingRecord(k, "com.bytelog.app", v.second, start = from) }
        }
        override suspend fun readHydration(from: Instant, to: Instant) = hydration.toList()
        override suspend fun writeFood(records: List<FoodRecord>, version: Long, zone: ZoneId): List<String> {
            writes++
            return records.map { r -> "uuid-${r.entry.id}-$version".also { ours[r.clientRecordId] = it to r.entry.foodName } }
        }
        override suspend fun writeWater(records: List<WaterRecord>, version: Long, zone: ZoneId) =
            records.map { r -> "uuid-w${r.log.id}".also { ours[r.clientRecordId] = it to "water" } }
        override suspend fun deleteFood(clientRecordIds: List<String>) { clientRecordIds.forEach { ours.remove(it) } }
        override suspend fun deleteWater(clientRecordIds: List<String>) { clientRecordIds.forEach { ours.remove(it) } }
        fun foodNames() = ours.filterKeys { it.startsWith("nibl-nutrition") }.values.map { it.second }.sorted()
    }

    private lateinit var db: ByteLogDatabase
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("health_connect", Context.MODE_PRIVATE).edit().clear().commit()
        HealthConnectEcho.init(context)
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer()
    }

    @After fun tearDown() { db.close(); runCatching { server.shutdown() }; scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private fun sync(store: FakeStore) = HealthConnectSync(ApplicationProvider.getApplicationContext(), store, db.foodEntryDao(), db.waterLogDao(), { zone }, { now })

    @Test fun writesTheDayOnceRewritesWhenSparkyCatchesUpAndNeverGuesses() = runBlocking {
        db.foodEntryDao().insertEntries(listOf(food("a", "Oatmeal", 300f, "Breakfast"), food("b", "Turkey sandwich", 450f)))
        db.waterLogDao().insertLogs(listOf(WaterLogEntry(id = "41", entryDate = day, waterMl = 500f, loggedAt = "2026-11-01T09:00:00Z")))
        val store = FakeStore()
        val hc = sync(store)

        var s = hc.syncDays(listOf(day))
        assertEquals(listOf("Oatmeal", "Turkey sandwich"), store.foodNames())
        assertEquals(3, s.written)
        assertEquals(1, store.writes)

        // Nothing changed: nothing rewritten (Health Connect limits writes)
        hc.syncDays(listOf(day))
        assertEquals(1, store.writes)

        // The SparkyFitness app writes the oatmeal too: Nibl takes its copy back
        store.nutrition += ExistingRecord("sparky-nutrition-a-1730000000000", sparkyApp, "Oatmeal", 300.0, start = now)
        s = hc.syncDays(listOf(day))
        assertEquals(listOf("Turkey sandwich"), store.foodNames())
        assertEquals(1, s.leftToSparky)

        // Can't read what's there: write nothing rather than risk doubling up
        db.foodEntryDao().insertEntry(food("c", "Apple", 95f, "Snacks"))
        store.failReads = true
        s = hc.syncDays(listOf(day))
        assertNotNull(s.error)
        assertEquals(listOf("Turkey sandwich"), store.foodNames())

        // Turned off: everything Nibl wrote is taken back
        store.failReads = false
        hc.removeAll()
        assertTrue(store.ours.isEmpty())
    }

    @Test fun foodNiblWroteThatSparkyImportsBackIsntDownloadedAgain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db.foodEntryDao().insertEntry(food("a", "Oatmeal", 300f, "Breakfast"))
        val store = FakeStore()
        sync(store).syncDays(listOf(day))
        val uuid = store.ours.values.single().first
        assertTrue(HealthConnectEcho.isEcho("Health Connect", uuid))

        // Sparky now has the oatmeal twice: Nibl's own entry, and a copy imported from Health Connect by Sparky's app
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path?.startsWith("/api/food-entries/by-date/") == true) MockResponse().setBody(
                """[{"id":"a","food_name":"Oatmeal","meal_type":"breakfast","quantity":1,"unit":"serving","calories":300,"entry_date":"$day"},
                    {"id":"copy","food_name":"Oatmeal","meal_type":"breakfast","quantity":1,"unit":"serving","calories":300,"entry_date":"$day","source":"Health Connect","source_id":"$uuid"},
                    {"id":"watch","food_name":"Smoothie","meal_type":"snacks","quantity":1,"unit":"serving","calories":210,"entry_date":"$day","source":"Health Connect","source_id":"someone-else"}]"""
            ) else MockResponse().setResponseCode(404)
        }
        server.start()
        db.mealTypeDao().insertMealTypes(MealType.DEFAULT_MEAL_TYPES)
        val repo = FoodRepository(db.foodDao(), db.foodEntryDao(), db.mealTypeDao(), SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/')),
            SettingsRepository(context), scope, db.prepBatchDao())
        repo.syncDateWithServer(day)
        val names = db.foodEntryDao().getEntriesForDateOnce(day).map { it.id }.sorted()
        assertEquals(listOf("a", "watch"), names)
        // ...and food that came from another app through Health Connect is never written back there
        val smoothie = db.foodEntryDao().getEntriesForDateOnce(day).first { it.id == "watch" }
        assertEquals("Health Connect", smoothie.source)
        assertTrue(HealthConnectPlan.foodToWrite(listOf(smoothie), emptyList(), zone, now, 1).isEmpty())
    }
}
