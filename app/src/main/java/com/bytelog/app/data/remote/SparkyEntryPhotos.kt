package com.bytelog.app.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.bytelog.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Handles uploading, deleting, fetching, disk-caching, and locally indexing food entry photos
 * against Sparky Fitness Server.
 *
 * The local entryId -> relativePaths index is persisted in `context.filesDir/entry_photo_index.json`
 * so Diary rows can display photo thumbnails without requiring a Room schema migration.
 */
class SparkyEntryPhotos(private val api: SparkyApiClient) {

    private val indexLock = Any()
    private val indexSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    @Volatile
    private var indexLoaded = false
    private val _photoIndex = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val photoIndex: StateFlow<Map<String, List<String>>> = _photoIndex.asStateFlow()

    /** Small in-memory bitmap cache keyed by "$relativePath@$maxDim" (~8 MB budget). */
    private val memoryCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    /**
     * Uploads [newFiles] and preserves [keepPaths] for a food entry (or meal entry when [isMealEntry] is true).
     *
     * Sparky's multipart handler requires the text field `"images"` (JSON array of kept paths followed by
     * `"__new__0"`, `"__new__1"`, ...) to precede all repeated `"images"` file parts.
     */
    suspend fun uploadEntryPhotos(
        entryId: String,
        keepPaths: List<String>,
        newFiles: List<File>,
        isMealEntry: Boolean = false
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = api.currentBaseUrl().trimEnd('/')
            val endpoint = if (isMealEntry) {
                "$baseUrl/api/food-entry-meals/$entryId/image"
            } else {
                "$baseUrl/api/food-entries/$entryId/image"
            }

            val manifestEntries = buildList {
                addAll(keepPaths)
                newFiles.indices.forEach { idx -> add("__new__$idx") }
            }
            val manifestJson = JsonArray(manifestEntries.map { JsonPrimitive(it) }).toString()

            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                // Text field MUST precede the repeated file parts
                .addFormDataPart("images", manifestJson)

            newFiles.forEachIndexed { idx, file ->
                val filename = file.name.ifBlank { "photo_$idx.jpg" }
                multipartBuilder.addFormDataPart(
                    "images",
                    filename,
                    file.asRequestBody(guessMediaType(filename))
                )
            }

            val request = Request.Builder()
                .url(endpoint)
                .post(multipartBuilder.build())
                .build()

            api.httpClient().newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Photo upload failed (HTTP ${response.code}): ${bodyStr.take(200)}")
                    )
                }
                val parsedPaths = parseImagesResponse(bodyStr)
                Result.success(parsedPaths)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Deletes all photos attached to [entryId] on Sparky. */
    suspend fun clearEntryPhotos(
        entryId: String,
        isMealEntry: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = api.currentBaseUrl().trimEnd('/')
            val endpoint = if (isMealEntry) {
                "$baseUrl/api/food-entry-meals/$entryId/image"
            } else {
                "$baseUrl/api/food-entries/$entryId/image"
            }

            val request = Request.Builder()
                .url(endpoint)
                .delete()
                .build()

            api.httpClient().newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val bodyStr = response.body?.string().orEmpty()
                    Result.failure(IOException("Failed to clear photos (HTTP ${response.code}): ${bodyStr.take(200)}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches `{baseUrl}$relativePath` with disk caching under `context.cacheDir/entry_photos/`
     * and safe downsampling to [maxDim] pixels on the longest edge.
     */
    suspend fun fetchEntryPhotoBitmap(
        context: Context,
        relativePath: String,
        maxDim: Int = 1024
    ): Bitmap? = withContext(Dispatchers.IO) {
        val trimmed = relativePath.trim()
        if (trimmed.isEmpty()) return@withContext null

        val memKey = "$trimmed@$maxDim"
        memoryCache.get(memKey)?.let { cached ->
            if (!cached.isRecycled) return@withContext cached
        }

        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        val cacheFile = File(cacheDir, "${sha256Hex(trimmed)}.img")

        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            val url = resolveUrl(trimmed)
            val downloaded = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "image/*")
                    .get()
                    .build()
                api.httpClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val body = response.body ?: return@use false
                    val tmpFile = File(cacheDir, "${cacheFile.name}.tmp")
                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tmpFile.length() > 0L) {
                        if (cacheFile.exists()) cacheFile.delete()
                        tmpFile.renameTo(cacheFile)
                    } else {
                        tmpFile.delete()
                        false
                    }
                }
            }.getOrDefault(false)
            if (!downloaded) return@withContext null
        }

        val decoded = decodeSampledFile(cacheFile, maxDim)
        if (decoded != null) {
            memoryCache.put(memKey, decoded)
        }
        decoded
    }

    /**
     * Loads a photo from [uri], rotates per EXIF, scales down to at most [maxDim] on the longest side,
     * and compresses to JPEG at [quality]% into a temporary cache file ready for upload.
     */
    suspend fun compressUriToJpegFile(
        context: Context,
        uri: Uri,
        maxDim: Int = 1600,
        quality: Int = 85
    ): File? = withContext(Dispatchers.IO) {
        try {
            val loaded = ImageUtils.loadScaledFromUri(context, uri, maxDim = maxDim) ?: return@withContext null
            val scaled = if (loaded.width > maxDim || loaded.height > maxDim) {
                val ratio = minOf(maxDim.toFloat() / loaded.width, maxDim.toFloat() / loaded.height)
                Bitmap.createScaledBitmap(
                    loaded,
                    (loaded.width * ratio).toInt().coerceAtLeast(1),
                    (loaded.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                loaded
            }
            val dir = File(context.cacheDir, UPLOAD_DIR_NAME).apply { mkdirs() }
            val outFile = File(dir, "entry_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg")
            FileOutputStream(outFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            if (scaled !== loaded) loaded.recycle()
            outFile
        } catch (_: Exception) {
            null
        }
    }

    // ---- Local entryId -> List<String> JSON index ----

    fun getCachedPhotos(context: Context, entryId: String): List<String> {
        ensureIndexLoaded(context)
        return _photoIndex.value[entryId].orEmpty()
    }

    fun getAllCachedPhotos(context: Context): Map<String, List<String>> {
        ensureIndexLoaded(context)
        return _photoIndex.value
    }

    fun setCachedPhotos(context: Context, entryId: String, paths: List<String>) {
        synchronized(indexLock) {
            ensureIndexLoadedLocked(context)
            val next = _photoIndex.value.toMutableMap()
            val cleaned = paths.map { it.trim() }.filter { it.isNotEmpty() }
            if (cleaned.isEmpty()) next.remove(entryId) else next[entryId] = cleaned
            _photoIndex.value = next
            writeIndexLocked(context, next)
        }
    }

    fun mergeCachedPhotos(context: Context, mapping: Map<String, List<String>>) {
        if (mapping.isEmpty()) return
        synchronized(indexLock) {
            ensureIndexLoadedLocked(context)
            val next = _photoIndex.value.toMutableMap()
            mapping.forEach { (id, paths) ->
                val cleaned = paths.map { it.trim() }.filter { it.isNotEmpty() }
                if (cleaned.isEmpty()) next.remove(id) else next[id] = cleaned
            }
            _photoIndex.value = next
            writeIndexLocked(context, next)
        }
    }

    fun clearCachedPhotos(context: Context, entryId: String) {
        setCachedPhotos(context, entryId, emptyList())
    }

    private fun ensureIndexLoaded(context: Context) {
        if (indexLoaded) return
        synchronized(indexLock) {
            ensureIndexLoadedLocked(context)
        }
    }

    private fun ensureIndexLoadedLocked(context: Context) {
        if (indexLoaded) return
        val file = File(context.filesDir, INDEX_FILE_NAME)
        val loaded = if (file.exists()) {
            runCatching {
                api.jsonCodec().decodeFromString(indexSerializer, file.readText())
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        _photoIndex.value = loaded
        indexLoaded = true
    }

    private fun writeIndexLocked(context: Context, data: Map<String, List<String>>) {
        runCatching {
            val file = File(context.filesDir, INDEX_FILE_NAME)
            val tmp = File(context.filesDir, "$INDEX_FILE_NAME.tmp")
            tmp.writeText(api.jsonCodec().encodeToString(indexSerializer, data))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    // ---- Internal helpers ----

    private fun resolveUrl(relativePath: String): String {
        if (relativePath.startsWith("http://", ignoreCase = true) ||
            relativePath.startsWith("https://", ignoreCase = true)
        ) {
            return relativePath
        }
        val base = api.currentBaseUrl().trimEnd('/')
        val path = if (relativePath.startsWith("/")) relativePath else "/$relativePath"
        return "$base$path"
    }

    private fun parseImagesResponse(bodyStr: String): List<String> {
        if (bodyStr.isBlank()) return emptyList()
        return runCatching {
            val root = api.jsonCodec().parseToJsonElement(bodyStr)
            val array = when (root) {
                is JsonArray -> root
                is JsonObject -> {
                    root["images"]?.jsonArray
                        ?: root["entry"]?.jsonObject?.get("images")?.jsonArray
                        ?: root["data"]?.jsonObject?.get("images")?.jsonArray
                }
                else -> null
            }
            array?.mapNotNull { el ->
                runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun decodeSampledFile(file: File, maxDim: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun guessMediaType(filename: String) = when (filename.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png".toMediaType()
        "webp" -> "image/webp".toMediaType()
        else -> "image/jpeg".toMediaType()
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val CACHE_DIR_NAME = "entry_photos"
        private const val UPLOAD_DIR_NAME = "entry_photos_upload"
        private const val INDEX_FILE_NAME = "entry_photo_index.json"
    }
}
