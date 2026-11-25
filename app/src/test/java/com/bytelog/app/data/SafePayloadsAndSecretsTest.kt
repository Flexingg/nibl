package com.bytelog.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.remote.upsertWeight
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.dataStore
import com.bytelog.app.research.SecretCipher
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SafePayloadsAndSecretsTest {
    private lateinit var server: MockWebServer
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /** Reversible stand-in for the Keystore cipher; "broken" fails like a backup restored onto another phone. */
    private class FakeCipher(var broken: Boolean = false) : SecretCipher {
        override fun encrypt(plain: String) = "X" + plain.reversed()
        override fun decrypt(token: String): String? = if (broken) null else token.removePrefix("X").reversed()
    }

    @Before fun setUp() { server = MockWebServer().apply { start() }; runBlocking { context.dataStore.edit { it.clear() } } }
    @After fun tearDown() = server.shutdown()

    @Test fun foodNamesWithAnyCharactersMakeValidRequests() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"srv-1"}"""))
        val api = SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        val name = "Mom's \"best\" pie \\ with\nnewline\tand tab"
        val entry = FoodEntry(id = "l-1", foodName = name, brandName = "Brand \\ Co", mealTypeId = "lunch", mealName = "Lunch",
            quantity = 1f, calories = Float.NaN, protein = 2f, carbs = 3f, fat = Float.POSITIVE_INFINITY, entryDate = "2026-11-25", entryTime = "08:15:00")
        assertEquals("srv-1", api.createFoodEntry(entry, "food-1", "var-1").getOrThrow())
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject
        assertEquals("the name arrives exactly as typed", name, body["food_name"]!!.jsonPrimitive.content)
        assertEquals("Brand \\ Co", body["brand_name"]!!.jsonPrimitive.content)
        assertEquals("not a number goes as 0", 0.0, body["calories"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(0.0, body["fat"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("08:15", body["entry_time"]!!.jsonPrimitive.content)
    }

    @Test fun otherRequestsAreValidJsonToo() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        val api = SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        api.upsertWeight("2026-11-25", 82.5f, null)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject
        assertEquals("82.5", body["weight"]!!.jsonPrimitive.content)
    }

    @Test fun theSparkyLoginIsStoredEncrypted() = runBlocking {
        val cipher = FakeCipher()
        val settings = SettingsRepository(context, cipher)
        settings.saveServerConfig("https://sparky.example", token = "tok-123", apiKey = "key-456")
        val raw = context.dataStore.data.first()
        assertTrue(raw[SettingsRepository.KEY_AUTH_TOKEN]!!.startsWith("enc1:"))
        assertTrue("no plain token on disk", !raw[SettingsRepository.KEY_AUTH_TOKEN]!!.contains("tok-123"))
        assertEquals("tok-123", settings.authToken.first())
        assertEquals("key-456", settings.apiKey.first())

        // Restored onto another phone: unreadable, so the user signs in again rather than the app breaking
        cipher.broken = true
        assertNull(settings.authToken.first())
    }

    @Test fun aLoginSavedInPlainTextStillWorksAndIsEncryptedOnStart() = runBlocking {
        context.dataStore.edit { it[SettingsRepository.KEY_AUTH_TOKEN] = "old-token" }
        val settings = SettingsRepository(context, FakeCipher())
        assertEquals("old-token", settings.authToken.first())
        settings.migrateSecrets()
        assertTrue(context.dataStore.data.first()[SettingsRepository.KEY_AUTH_TOKEN]!!.startsWith("enc1:"))
        assertEquals("old-token", settings.authToken.first())
    }
}
