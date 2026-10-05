package com.bytelog.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.remote.WaterContainer
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.WaterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sparky stores a water container's volume in ml and keeps `unit` as a display label only,
 * so a 20 oz bottle comes back as {"volume": 591, "unit": "oz"}. ByteLog used to read that as 591 oz.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class WaterContainerTest {

    private lateinit var server: MockWebServer
    private lateinit var db: ByteLogDatabase

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/water-containers" ->
                    MockResponse().setBody(
                        """[{"id":1,"name":"Bottle","volume":591.47,"unit":"oz","is_primary":true,"servings_per_container":1},
                            {"id":2,"name":"Glass","volume":250,"unit":"ml","is_primary":false,"servings_per_container":1}]"""
                    )
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ByteLogDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun containerVolumeIsAlwaysMillilitres() {
        val bottle = WaterContainer(1, "Bottle", 591.47f, "oz")
        assertEquals(20f, bottle.volumeOz, 0.01f)
        assertEquals(591.47f, bottle.volumeMl, 0.001f)
        assertEquals(8.45f, WaterContainer(2, "Glass", 250f, "ml").volumeOz, 0.01f)
    }

    @Test
    fun syncAdoptsThePrimaryContainerAs20Oz() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        // The wrong value an earlier build saved
        settings.saveDefaultContainer(591.47f, "Bottle")
        val api = SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        val repo = WaterRepository(db.waterLogDao(), api, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO))

        repo.syncWaterForDate("2026-10-05")

        assertEquals(20f, settings.defaultContainerOz.first(), 0.01f)
        assertEquals("Bottle", settings.defaultContainerName.first())
    }
}
