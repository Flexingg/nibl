package com.bytelog.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.WaterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** Logging a drink counts once: the local copy must not stay pending beside the entry Sparky returns. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class WaterLoggingTest {
    private val date = "2026-10-06"
    private lateinit var server: MockWebServer
    private lateinit var db: ByteLogDatabase
    private lateinit var repo: WaterRepository
    private val serverLog = CopyOnWriteArrayList<String>()          // JSON objects Sparky "stored"
    private val posts = ConcurrentLinkedQueue<String>()
    private val createdContainers = ConcurrentLinkedQueue<String>()
    @Volatile private var acceptDrinks = true
    @Volatile private var containers = """[{"id":1,"name":"Bottle","volume":591.47,"unit":"oz","is_primary":true,"servings_per_container":1}]"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/api/water-containers" -> MockResponse().setBody(containers)
                request.method == "POST" && request.path == "/api/water-containers" -> {
                    val body = request.body.readUtf8(); createdContainers += body
                    MockResponse().setBody("""{"id":7,"name":"Sip","volume":${Regex("\"volume\":([0-9.]+)").find(body)!!.groupValues[1]},"unit":"ml","is_primary":false}""")
                }
                request.method == "POST" && request.path == "/api/v2/measurements/water-intake" ->
                    if (!acceptDrinks) MockResponse().setResponseCode(503) else {
                        posts += request.body.readUtf8()
                        serverLog += """{"id":"srv-${serverLog.size + 1}","water_ml":591.47,"entry_date":"$date","container_id":1,"container_name":"Bottle","created_at":"2026-10-06T08:00:00Z"}"""
                        MockResponse().setBody("""{"water_ml":${591.47f * serverLog.size}}""")
                    }
                request.method == "GET" && request.path == "/api/v2/measurements/water-intake/$date/log" ->
                    MockResponse().setBody(serverLog.joinToString(prefix = "[", postfix = "]", separator = ","))
                request.method == "GET" && request.path == "/api/v2/measurements/water-intake/$date" ->
                    MockResponse().setBody("""{"water_ml":${591.47f * serverLog.size}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        val api = SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        repo = WaterRepository(db.waterLogDao(), api, SettingsRepository(context), CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    @After fun tearDown() { server.shutdown(); db.close() }

    private suspend fun total() = repo.getTotalWaterOzForDate(date).first()
    private suspend fun count() = repo.getLogsForDate(date).first().size
    private suspend fun until(what: String, check: suspend () -> Boolean) {
        try { withTimeout(10_000) { while (!check()) delay(25) } } catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }
    /** Lets any in-flight background sync finish, so a late duplicate would show up. */
    private suspend fun settle() = delay(600)

    @Test fun oneTapLogsOneBottleNotTwo() = runBlocking {
        repo.syncWaterForDate(date)                           // learns Sparky's containers, like opening the app
        repo.quickAddWaterOz(date, 20f, containerName = "20 oz Bottle")

        until("the drink to reach Sparky") { posts.size == 1 }
        until("the diary total") { total() > 19f }
        settle()
        assertEquals("20 oz, not 40", 20f, total(), 0.1f)
        assertEquals("one entry", 1, count())
        assertEquals("one request to Sparky", 1, posts.size)
        assertTrue("it used the existing container", posts.single().contains("\"container_id\":1"))
        // A later full sync (opening the app again) keeps it at one drink
        repo.syncWaterForDate(date)
        assertEquals(20f, total(), 0.1f); assertEquals(1, count())
    }

    @Test fun twoTapsLogTwoDrinks() = runBlocking {
        repo.syncWaterForDate(date)
        repo.quickAddWaterOz(date, 20f, containerName = "20 oz Bottle")
        until("first") { posts.size == 1 }
        repo.quickAddWaterOz(date, 20f, containerName = "20 oz Bottle")
        until("second") { posts.size == 2 }
        until("both counted") { total() > 39f }
        settle()
        assertEquals(40f, total(), 0.1f); assertEquals(2, count())
    }

    @Test fun aDrinkSparkyRefusesStaysOnThePhoneOnce() = runBlocking {
        repo.syncWaterForDate(date)
        acceptDrinks = false
        repo.quickAddWaterOz(date, 20f, containerName = "20 oz Bottle")
        until("the local entry") { count() == 1 }
        settle()
        assertEquals(20f, total(), 0.1f); assertEquals(1, count())
    }

    @Test fun aNewContainerIsCreatedInMillilitres() = runBlocking {
        containers = "[]"
        repo.syncWaterForDate(date)
        repo.quickAddWaterOz(date, 8f, containerName = "Sip")
        until("container creation") { createdContainers.size == 1 }
        val body = createdContainers.single()
        assertTrue(body, body.contains("\"unit\":\"ml\""))
        val volume = Regex("\"volume\":([0-9.]+)").find(body)!!.groupValues[1].toFloat()
        assertEquals(236.6f, volume, 0.5f)                    // 8 oz, not "8" ml
    }

    @Test fun aDrinkLoggedWhileSparkyRefusedIsSentOnTheNextSync() = runBlocking {
        repo.syncWaterForDate(date)
        acceptDrinks = false
        repo.quickAddWaterOz(date, 20f, containerName = "20 oz Bottle")
        until("the local entry") { count() == 1 }
        settle()
        assertEquals(0, posts.size)
        acceptDrinks = true
        repo.syncWaterForDate(date)                        // the sync sends it first, then downloads
        assertEquals("sent once", 1, posts.size)
        assertEquals(20f, total(), 0.1f); assertEquals(1, count())
        assertEquals(0, repo.countPending())
    }
}
