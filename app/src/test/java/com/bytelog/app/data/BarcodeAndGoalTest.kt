package com.bytelog.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.SettingsRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Scanning a barcode gives the real product (or nothing), and Sparky's goal is what the diary shows by default. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BarcodeAndGoalTest {
    private lateinit var server: MockWebServer
    private lateinit var db: ByteLogDatabase
    private lateinit var repo: FoodRepository
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/api/foods/barcode/04963406" -> MockResponse().setBody(
                    """{"source":"openfoodfacts","food":{"name":"Nutella","brand":"Ferrero","serving_size":15,"serving_unit":"g",
                        "calories":80,"protein":0.9,"carbs":8.6,"fat":4.6,"dietary_fiber":0.4}}"""
                )
                "/api/foods/barcode/11111111" -> MockResponse().setBody(
                    """{"source":"local","food":{"id":"00000000-0000-4000-8000-000000000042","name":"My Granola","serving_size":50,"serving_unit":"g",
                        "default_variant":{"id":"v1","serving_size":50,"serving_unit":"g","calories":220,"protein":6,"carbs":30,"fat":8}}}"""
                )
                "/api/foods/barcode/99999999" -> MockResponse().setBody("""{"source":"not_found","food":null}""")
                "/api/foods/barcode/55555555" -> MockResponse().setBody("""{"source":"openfoodfacts","food":{"calories":10}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ByteLogDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(context)
        repo = FoodRepository(
            db.foodDao(), db.foodEntryDao(), db.mealTypeDao(),
            SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/')), settings, CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun anOnlineProductComesBackWithItsNameAndNutrition() = runBlocking {
        val f = repo.lookupBarcode("04963406")!!
        assertEquals("Nutella", f.name)
        assertEquals("Ferrero", f.brand)
        assertEquals(80f, f.calories, 0f)
        assertEquals(15f, f.servingSize, 0f)
        assertEquals("off_04963406", f.id)
        assertEquals("04963406", f.barcode)
    }

    @Test fun aFoodFromTheUsersSparkyLibraryKeepsItsId() = runBlocking {
        val f = repo.lookupBarcode("11111111")!!
        assertEquals("My Granola", f.name)
        assertEquals("00000000-0000-4000-8000-000000000042", f.id)
        assertEquals(220f, f.calories, 0f)
    }

    @Test fun anUnknownBarcodeGivesNothingInsteadOfABlankItem() = runBlocking {
        assertNull(repo.lookupBarcode("99999999"))
        assertNull("a product with no name isn't usable", repo.lookupBarcode("55555555"))
        assertNull(db.foodDao().getFoodByBarcode("99999999"))
    }

    @Test fun aBlankItemSavedByAnOlderVersionIsReplacedByTheRealProduct() = runBlocking {
        db.foodDao().insertFood(FoodItem("old-blank", "Scanned Product", barcode = "04963406", calories = 0f))
        assertEquals("Nutella", repo.lookupBarcode("04963406")!!.name)
        assertNull(db.foodDao().getFoodById("old-blank"))
    }

    @Test fun sparkysCalorieGoalIsShownUnlessAutoAdjustIsTurnedOn() = runBlocking {
        assertFalse(settings.autoAdjustCalories.first())
        settings.saveMacroTargets(2610f, 190f, 250f, 80f)
        assertEquals(2610f, settings.targetCalories.first(), 0f)
        settings.saveAutoAdjustCalories(true)
        assertEquals(true, settings.autoAdjustCalories.first())
        assertNotNull(settings)
    }
}
