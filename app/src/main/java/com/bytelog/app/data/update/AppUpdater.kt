package com.bytelog.app.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** A newer build published on GitHub. */
data class UpdateInfo(
    /** "1.27.0" */
    val version: String,
    /** "v1.27.0" */
    val tag: String,
    /** What changed, from the release notes, as plain text. */
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val pageUrl: String
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: String?) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** [progress] is 0..1, or null while the size is unknown. */
    data class Downloading(val info: UpdateInfo, val progress: Float?) : UpdateState
    /** Downloaded; Android's installer is (or is about to be) asking the user to confirm. */
    data class Installing(val info: UpdateInfo) : UpdateState
    /** Android needs the user to let Nibl install apps before it can update itself. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
    /** Downloaded and waiting to install: one tap, or by itself when the user leaves the app. */
    data class Ready(val info: UpdateInfo, val apk: File) : UpdateState
}

/**
 * Keeps Nibl up to date from its GitHub releases: finds the latest release, downloads its APK and hands it to
 * Android's package installer, which asks the user to confirm. The releases are signed with the same key as the
 * installed app, so the update installs in place and keeps all data.
 */
class AppUpdater(
    private val context: Context,
    private val currentVersion: String,
    /** True for a debug build, which updates from the release's debug APK so it stays a debug build. */
    private val debugBuild: Boolean,
    private val repo: String = REPO,
    private val apiBase: String = "https://api.github.com",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** GitHub's ETag and answer for the latest release, so an unchanged answer costs nothing. */
    @Volatile private var etag: String? = null
    @Volatile private var lastBody: String? = null

    /** True when the last check got a full answer (not an error, and not a release still uploading its APK). */
    @Volatile var lastCheckComplete: Boolean = false
        private set
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Asks GitHub for the latest release. Returns the update when it's newer than this build, else null. */
    suspend fun check(): Result<UpdateInfo?> {
        when (val s = _state.value) {
            is UpdateState.Downloading -> return Result.success(s.info)
            is UpdateState.Installing -> return Result.success(s.info)
            is UpdateState.Ready -> return Result.success(s.info)
            else -> {}
        }
        _state.value = UpdateState.Checking
        lastCheckComplete = false
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url("$apiBase/repos/$repo/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Nibl-Android")
                    // An unchanged answer comes back as 304, which GitHub doesn't count against the rate limit
                    .apply { etag?.let { header("If-None-Match", it) } }
                    .build()
                client.newCall(request).execute().use { r ->
                    val body = if (r.code == 304) lastBody.orEmpty() else r.body?.string().orEmpty()
                    // GitHub answers 404 when it won't show the releases (the repository isn't public): that's not "up to date"
                    if (r.code == 404) throw IllegalStateException("GitHub doesn't show Nibl's releases. The repository may not be public.")
                    if (!r.isSuccessful && r.code != 304) throw IllegalStateException(if (r.code == 403) "GitHub is limiting requests right now. Try again later." else "GitHub answered HTTP ${r.code}.")
                    if (r.code == 200) { etag = r.header("ETag"); lastBody = body }
                    val release = Json.parseToJsonElement(body) as? JsonObject ?: throw IllegalStateException("GitHub sent an unreadable answer.")
                    // A release is published a few minutes before its APKs finish uploading: that isn't "up to
                    // date", so it doesn't count as a finished check and the next one comes soon
                    val info = parseRelease(release, debugBuild)
                    lastCheckComplete = info != null || release.text("tag_name")?.let { !isNewer(it, currentVersion) } == true
                    info
                }
            }
        }
        result.fold(
            onSuccess = { info ->
                _state.value = if (info != null && isNewer(info.version, currentVersion)) UpdateState.Available(info)
                else UpdateState.UpToDate(info?.version)
            },
            onFailure = {
                _state.value = UpdateState.Failed("Couldn't check for updates: ${it.message ?: it.javaClass.simpleName}")
            }
        )
        return result.map { info -> info?.takeIf { isNewer(it.version, currentVersion) } }
    }

    /**
     * Downloads [info]'s APK in the background so the update is ready to install. Returns true when it is. A copy
     * downloaded before (complete, same version) is used again.
     */
    suspend fun prepare(info: UpdateInfo): Boolean {
        (_state.value as? UpdateState.Ready)?.let { if (it.info.version == info.version && it.apk.exists()) return true }
        _state.value = UpdateState.Downloading(info, null)
        val apk = runCatching { download(info) }.getOrElse {
            _state.value = UpdateState.Failed("The download failed: ${it.message ?: it.javaClass.simpleName}", info)
            return false
        }
        _state.value = UpdateState.Ready(info, apk)
        return true
    }

    /** Downloads [info]'s APK (unless it's ready already) and starts the install. */
    suspend fun downloadAndInstall(info: UpdateInfo) {
        if (!canInstall()) { _state.value = UpdateState.NeedsPermission(info); return }
        if (!prepare(info)) return
        installReady(quietly = false)
    }

    /** True while an install was started without the user there: Android's confirmation, if it wants one, waits. */
    @Volatile internal var installingQuietly = false
        private set

    /**
     * Installs the ready update. [quietly]: started by Nibl itself as the user leaves the app. Android 12 and later
     * install an app's own update without asking once that app has installed it before; otherwise the update
     * stays ready and the user taps Install next time.
     */
    suspend fun installReady(quietly: Boolean): Boolean {
        val ready = _state.value as? UpdateState.Ready ?: return false
        if (!canInstall()) { if (!quietly) _state.value = UpdateState.NeedsPermission(ready.info); return false }
        installingQuietly = quietly
        _state.value = UpdateState.Installing(ready.info)
        return runCatching { withContext(Dispatchers.IO) { install(ready.apk) } }.fold(
            onSuccess = { true },
            onFailure = {
                _state.value = UpdateState.Failed("Couldn't start the install: ${it.message ?: it.javaClass.simpleName}", ready.info)
                false
            }
        )
    }

    /** A quiet install needed the user to confirm: it stays ready for when they're back. */
    internal fun onConfirmationNeededWhileAway() {
        val info = (_state.value as? UpdateState.Installing)?.info ?: return
        val apk = File(File(context.cacheDir, "updates"), "Nibl-${info.tag}.apk")
        _state.value = if (apk.exists()) UpdateState.Ready(info, apk) else UpdateState.Available(info)
    }

    /** True when Android lets Nibl install packages (the user allowed "Install unknown apps" for it). */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** The system screen where the user lets Nibl install apps. */
    fun installPermissionIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Called by [UpdateInstallReceiver] when the installer finishes without replacing the app. */
    internal fun onInstallFinished(success: Boolean, message: String?) {
        val info = (_state.value as? UpdateState.Installing)?.info
        _state.value = when {
            success -> UpdateState.UpToDate(info?.version)
            info != null -> UpdateState.Failed(message?.takeIf { it.isNotBlank() } ?: "The update wasn't installed.", info)
            else -> UpdateState.Failed(message ?: "The update wasn't installed.")
        }
    }

    /** The user set a failed update aside ("Later"): it goes back to simply being available. */
    fun onBannerDismissed(info: UpdateInfo) {
        if (_state.value is UpdateState.Failed) _state.value = UpdateState.Available(info)
    }


    /**
     * Looks for an update unless a complete check happened in the last [CHECK_EVERY_MS]. Returns true only when this
     * check completed, so the caller records it: a failed check, or a release still uploading, is tried again next time.
     */
    suspend fun checkIfDue(lastCheck: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (now - lastCheck < CHECK_EVERY_MS && lastCheck <= now) return false
        check()
        return lastCheckComplete
    }

    /** Back to the available update after the user cancelled Android's confirmation. */
    internal fun onInstallCancelled() = onConfirmationNeededWhileAway()

    private suspend fun download(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "Nibl-${info.tag}.apk")
        // Downloaded already (all of it): no need to fetch it again
        if (target.exists() && info.apkSize > 0 && target.length() == info.apkSize) return@withContext target
        dir.listFiles()?.forEach { it.delete() }   // older or partial downloads
        val request = Request.Builder().url(info.apkUrl).header("User-Agent", "Nibl-Android").build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val body = r.body ?: throw IllegalStateException("empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize.takeIf { it > 0 }
            var read = 0L
            var lastShown = -1
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total != null) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastShown) { lastShown = pct; _state.value = UpdateState.Downloading(info, (read.toFloat() / total).coerceIn(0f, 1f)) }
                        }
                    }
                }
            }
            if (info.apkSize > 0 && read != info.apkSize) throw IllegalStateException("the download was incomplete")
        }
        target
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, sessionId, Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName), flags)
            session.commit(callback.intentSender)
        }
    }


    companion object {
        const val REPO = "Flexingg/nibl"

        /** How often the app looks for an update by itself. */
        const val CHECK_EVERY_MS = 10 * 60 * 1000L

        /** Reads a GitHub release: its version and the APK this build should update from. Null when it has none. */
        fun parseRelease(release: JsonObject, debugBuild: Boolean): UpdateInfo? {
            if (release.bool("draft") == true || release.bool("prerelease") == true) return null
            val tag = release.text("tag_name") ?: return null
            val apks = (release["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { it.text("name")?.endsWith(".apk", ignoreCase = true) == true }
            val isDebug = { a: JsonObject -> a.text("name")!!.contains("debug", ignoreCase = true) }
            val asset = apks.firstOrNull { isDebug(it) == debugBuild } ?: apks.firstOrNull() ?: return null
            return UpdateInfo(
                version = tag.trim().removePrefix("v").removePrefix("V"),
                tag = tag,
                notes = plainNotes(release.text("body").orEmpty()),
                apkUrl = asset.text("browser_download_url") ?: return null,
                apkSize = (asset["size"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: 0L,
                pageUrl = release.text("html_url") ?: "https://github.com/$REPO/releases/latest"
            )
        }

        /** True when [candidate] ("1.27.0") is a later version than [current] ("1.26.0"). Missing parts count as 0. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').substringBefore('+')
                .split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val a = parts(candidate); val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /** Release notes (Markdown) as plain text: no headings marks, images, link targets or install boilerplate. */
        fun plainNotes(markdown: String): String = markdown
            .substringBefore("### 📱 Install")
            .lineSequence()
            .map { line ->
                line.replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
                    .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
                    .replace(Regex("""^#{1,6}\s*"""), "")
                    .replace("**", "").replace("`", "")
                    .replace(Regex("""^\s*[-*]\s+"""), "• ")
                    .trimEnd()
            }
            .filterNot { it.startsWith("Nibl v", ignoreCase = true) && it.length < 24 }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        private fun JsonObject.text(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
    }
}
