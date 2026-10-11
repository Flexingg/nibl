package com.bytelog.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.family.FamilyRepository
import com.bytelog.app.data.family.Share
import com.bytelog.app.data.family.ShareMode
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.remote.FamilyMember
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.ui.family.FamilyScreen
import com.bytelog.app.ui.family.MemberDiaryScreen
import com.bytelog.app.ui.family.ShareMealContent
import com.bytelog.app.ui.theme.ByteLogTheme
import com.bytelog.app.ui.viewmodel.FamilyViewModel
import com.bytelog.app.ui.viewmodel.MemberViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class FamilyScreensRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sam = "00000000-0000-4000-8000-0000000000a1"
    private val kim = "00000000-0000-4000-8000-0000000000b2"
    private val today = LocalDate.now().toString()
    private lateinit var server: MockWebServer
    private lateinit var db: ByteLogDatabase
    private lateinit var family: FamilyRepository
    private lateinit var food: FoodRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                return when {
                    p == "/api/identity/users/accessible-users" -> MockResponse().setBody(
                        """[{"user_id":"$sam","full_name":"Sam Rivera","email":"sam@x.com","permissions":{"can_manage_diary":true}},
                            {"user_id":"$kim","full_name":"Kim Lee","email":"kim@x.com","permissions":{"can_view_reports":true}}]"""
                    )
                    p.startsWith("/api/food-entries/by-date/") && p.contains("userId=$sam") -> MockResponse().setBody(
                        """[{"id":"e1","food_name":"Greek Yogurt","meal_type":"Breakfast","entry_date":"$today","quantity":170,"unit":"g","calories":100,"protein":17,"carbs":6,"fat":1},
                            {"id":"e2","food_name":"Chicken Burrito Bowl","meal_type":"Lunch","entry_date":"$today","quantity":1,"unit":"bowl","calories":820,"protein":52,"carbs":88,"fat":28}]"""
                    )
                    p.startsWith("/api/food-entries/by-date/") && p.contains("userId=$kim") -> MockResponse().setBody(
                        """[{"id":"k1","food_name":"Salad","meal_type":"Lunch","entry_date":"$today","quantity":1,"unit":"bowl","calories":340,"protein":12,"carbs":30,"fat":18}]"""
                    )
                    p.startsWith("/api/goals/by-date/") -> MockResponse().setBody("""{"calories":2400,"protein":180,"carbs":250,"fat":70}""")
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
    }

    @After fun tearDown() { server.shutdown() }

    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            check(System.currentTimeMillis() < end) { "Timed out waiting" }
            Thread.sleep(25)
        }
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

    @Test fun familyListShowsEachPersonsDay() {
        val vm = FamilyViewModel(family)
        compose.setContent { ByteLogTheme(darkTheme = true) { FamilyScreen(vm, {}, {}) } }
        waitFor { vm.state.value.today.size == 2 }
        compose.waitForIdle()
        compose.onAllNodesWithText("Sam Rivera").fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onNodeWithText("You can log meals for them").assertExists()
        compose.onNodeWithText("View only").assertExists()
        shot("family_list")
    }

    @Test fun aMembersDayShowsTheirMealsAndCopiesOneToYou() {
        val vm = MemberViewModel(family, food, sam)
        compose.setContent { ByteLogTheme(darkTheme = false) { MemberDiaryScreen(vm, {}) } }
        waitFor { vm.state.value.day != null && vm.state.value.week.isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("Chicken Burrito Bowl").assertExists()
        shot("family_member_day")
        compose.onNodeWithContentDescription("Copy Lunch to my diary").performClick()
        waitFor { runCatching { kotlinx.coroutines.runBlocking { db.foodEntryDao().getEntriesForDateOnce(today) } }.getOrNull()?.size == 1 }
        val copied = kotlinx.coroutines.runBlocking { db.foodEntryDao().getEntriesForDateOnce(today) }.single()
        assertEquals("Chicken Burrito Bowl", copied.foodName)
    }

    @Test fun theShareSheetSplitsByPercentWithMyShareShrinking() {
        val members = listOf(
            FamilyMember(sam, "Sam Rivera", "sam@x.com", mapOf("can_manage_diary" to true)),
            FamilyMember(kim, "Kim Lee", "kim@x.com", mapOf("can_view_reports" to true))
        )
        val entries = listOf(
            FoodEntry("1", foodName = "Lasagna", mealTypeId = "dinner", mealName = "Dinner", quantity = 300f, unit = "g", calories = 600f, protein = 30f, carbs = 55f, fat = 25f, entryDate = today)
        )
        var result: Pair<List<Share>, ShareMode>? = null
        compose.setContent { ByteLogTheme(darkTheme = true) { 
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer)) {
                ShareMealContent("Dinner", entries, members) { s, m -> result = s to m }
            }
        } }
        compose.waitForIdle()
        shot("share_sheet_empty")
        compose.onNodeWithText("Sam Rivera").performClick()          // include Sam: 50/50
        compose.waitForIdle()
        compose.onNodeWithContentDescription("More for Sam Rivera").performClick()   // Sam 55, me 45
        compose.waitForIdle()
        shot("share_sheet_split")
        compose.onNodeWithText("Split with 1 person").performClick()
        val (shares, mode) = result!!
        assertEquals(ShareMode.SPLIT, mode)
        assertEquals(listOf(45, 55), shares.map { it.percent })
        assertEquals(listOf<String?>(null, sam), shares.map { it.member?.userId })
        assertNotNull(shares)
    }
}
