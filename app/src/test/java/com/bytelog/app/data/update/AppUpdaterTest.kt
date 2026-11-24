package com.bytelog.app.data.update

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdaterTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun release(tag: String = "v1.27.0", draft: Boolean = false) = """{"tag_name":"$tag","draft":$draft,"prerelease":false,
        "html_url":"https://github.com/Flexingg/nibl/releases/tag/$tag",
        "body":"## Nibl $tag\n\nTen improvements.\n\n### Photo guesses\n- **Your own model** estimates [photos](https://x.y).\n![shot](https://img)\n\n### 📱 Install\nDownload it below.",
        "assets":[
          {"name":"Nibl-$tag-debug.apk","size":50,"browser_download_url":"https://dl/debug.apk"},
          {"name":"Nibl-$tag.apk","size":40,"browser_download_url":"https://dl/release.apk"}]}"""

    private fun updater(current: String = "1.26.0", debug: Boolean = false) =
        AppUpdater(ApplicationProvider.getApplicationContext(), current, debug, apiBase = server.url("").toString().trimEnd('/'))

    @Test fun versionsCompareByNumberNotText() {
        assertTrue(AppUpdater.isNewer("1.27.0", "1.26.0"))
        assertTrue(AppUpdater.isNewer("1.26.10", "1.26.9"))
        assertTrue(AppUpdater.isNewer("2.0", "1.99.99"))
        assertTrue(AppUpdater.isNewer("v1.26.1", "1.26.0"))
        assertFalse(AppUpdater.isNewer("1.26.0", "1.26.0"))
        assertFalse(AppUpdater.isNewer("1.25.7", "1.26.0"))
        assertFalse(AppUpdater.isNewer("1.26", "1.26.0"))
    }

    @Test fun aReleaseBuildUpdatesFromTheReleaseApkAndADebugBuildFromTheDebugOne() {
        val r = Json.parseToJsonElement(release()) as JsonObject
        val rel = AppUpdater.parseRelease(r, debugBuild = false)!!
        assertEquals("1.27.0", rel.version); assertEquals("v1.27.0", rel.tag)
        assertEquals("https://dl/release.apk", rel.apkUrl); assertEquals(40L, rel.apkSize)
        assertEquals("https://dl/debug.apk", AppUpdater.parseRelease(r, debugBuild = true)!!.apkUrl)
        assertNull("drafts are skipped", AppUpdater.parseRelease(Json.parseToJsonElement(release(draft = true)) as JsonObject, false))
    }

    @Test fun releaseNotesBecomePlainText() {
        val notes = AppUpdater.parseRelease(Json.parseToJsonElement(release()) as JsonObject, false)!!.notes
        assertEquals("Ten improvements.\n\nPhoto guesses\n• Your own model estimates photos.", notes)
    }

    @Test fun aNewerReleaseIsOffered() = runBlocking {
        server.enqueue(MockResponse().setBody(release()))
        val u = updater()
        val info = u.check().getOrThrow()!!
        assertEquals("1.27.0", info.version)
        assertTrue(u.state.value is UpdateState.Available)
        val req = server.takeRequest()
        assertEquals("/repos/Flexingg/nibl/releases/latest", req.path)
    }

    @Test fun theSameVersionIsUpToDate() = runBlocking {
        server.enqueue(MockResponse().setBody(release("v1.26.0")))
        val u = updater()
        assertNull(u.check().getOrThrow())
        assertEquals(UpdateState.UpToDate("1.26.0"), u.state.value)
    }

    @Test fun gitHubErrorsAreReported() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        val u = updater()
        assertTrue(u.check().isFailure)
        val s = u.state.value as UpdateState.Failed
        assertTrue(s.message, s.message.contains("limiting"))
    }

    @Test fun checksOnlyWhenDue() = runBlocking {
        val u = updater()
        val now = 10 * AppUpdater.CHECK_EVERY_MS
        assertFalse(u.checkIfDue(lastCheck = now - 1000, now = now))
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody(release()))
        assertTrue(u.checkIfDue(lastCheck = now - AppUpdater.CHECK_EVERY_MS - 1, now = now))
        assertEquals(1, server.requestCount)
    }

    @Test fun aFailedUpdateSetAsideIsAvailableAgain() {
        val u = updater()
        val info = AppUpdater.parseRelease(Json.parseToJsonElement(release()) as JsonObject, false)!!
        u.onBannerDismissed(info)
        assertTrue("nothing changes unless it had failed", u.state.value is UpdateState.Idle)
    }
}
