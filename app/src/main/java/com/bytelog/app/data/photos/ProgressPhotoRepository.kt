package com.bytelog.app.data.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.UUID

/**
 * Offline-capable repository for Progress Photos.
 * - Caches photos & metadata index JSON under `context.filesDir/progress_photos/`
 * - Syncs with Sparky Fitness via [ProgressPhotoApi]
 * - Maintains an in-memory [LruCache] for smooth gallery, split-compare, and time-lapse rendering.
 */
class ProgressPhotoRepository(
    context: Context,
    private val api: ProgressPhotoApi
) {
    private val appContext = context.applicationContext
    private val photosDir = File(appContext.filesDir, "progress_photos").apply { mkdirs() }
    private val indexFile = File(photosDir, "index.json")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val stateMutex = Mutex()

    private val memoryCache: LruCache<String, Bitmap> = run {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        val cacheKb = (maxKb / 12).coerceIn(4 * 1024, 32 * 1024)
        object : LruCache<String, Bitmap>(cacheKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val _photos = MutableStateFlow<List<ProgressPhotoItem>>(loadIndexSync())
    val photos: StateFlow<List<ProgressPhotoItem>> = _photos.asStateFlow()

    /**
     * Fetches the gallery from Sparky and merges it with any local cached/unsynced photos.
     */
    suspend fun refresh(): Result<List<ProgressPhotoItem>> = withContext(Dispatchers.IO) {
        val remoteResult = api.listGallery()
        remoteResult.map { remoteItems ->
            stateMutex.withLock {
                val existing = _photos.value
                val existingById = existing.associateBy { it.id }

                val mergedRemote = remoteItems.map { item ->
                    val existingLocalPath = existingById[item.id]?.localPath
                        ?.takeIf { File(it).exists() }
                        ?: cacheFileFor(item.id).takeIf { it.exists() }?.absolutePath
                    item.copy(localPath = existingLocalPath, isSynced = true)
                }

                val remoteDatePoseKeys = mergedRemote.map { "${it.date}|${it.pose.apiValue}" }.toSet()
                val localOnly = existing.filter { local ->
                    !local.isSynced &&
                        local.localPath != null &&
                        File(local.localPath).exists() &&
                        "${local.date}|${local.pose.apiValue}" !in remoteDatePoseKeys
                }

                val combined = sortPhotos(mergedRemote + localOnly)
                _photos.value = combined
                saveIndexSync(combined)
                combined
            }
        }
    }

    /**
     * Loads a downsampled, EXIF-upright [Bitmap] for [item].
     * Checks memory cache -> disk cache -> authenticated download from Sparky (and caches to disk).
     */
    suspend fun loadBitmap(item: ProgressPhotoItem, maxDim: Int = 1280): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${item.id}_$maxDim"
        memoryCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return@withContext it }

        var diskFile = item.localPath?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
            ?: cacheFileFor(item.id).takeIf { it.exists() && it.length() > 0L }

        if (diskFile == null && item.isSynced) {
            val bytes = api.downloadPhotoBytes(item.id).getOrNull() ?: return@withContext null
            val target = cacheFileFor(item.id)
            runCatching {
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                diskFile = target
                attachLocalPath(item.id, target.absolutePath)
            }.onFailure { return@withContext null }
        }

        val file = diskFile ?: return@withContext null
        val decoded = decodeSampledBitmap(file, maxDim) ?: return@withContext null
        memoryCache.put(cacheKey, decoded)
        decoded
    }

    /**
     * Returns the best reference photo for the camera ghost overlay:
     * 1. Prefers yesterday's photo (`targetDate - 1 day`) of the same [pose].
     * 2. Falls back to the most recent prior photo (`date < targetDate`) of that [pose].
     */
    suspend fun getReferencePhotoForOverlay(
        pose: PhotoPose,
        targetDate: String
    ): ProgressPhotoItem? = withContext(Dispatchers.IO) {
        val current = _photos.value.ifEmpty {
            val loaded = loadIndexSync()
            if (loaded.isNotEmpty()) _photos.value = loaded
            loaded
        }

        val parsedTarget = runCatching { LocalDate.parse(targetDate) }.getOrNull()
        val yesterday = parsedTarget?.minusDays(1)?.toString()

        val samePose = current.filter { it.pose == pose }
        if (samePose.isEmpty()) return@withContext null

        if (yesterday != null) {
            samePose.firstOrNull { it.date == yesterday }?.let { return@withContext it }
        }

        // Fallback: most recent photo strictly before targetDate
        val prior = samePose
            .filter { it.date < targetDate }
            .maxByOrNull { it.date }
        if (prior != null) return@withContext prior

        // If the user has only a photo from today or later, return null so we don't ghost-overlay the same day
        null
    }

    /**
     * Compresses [sourceFile] to JPEG (max 1600px on longest side, EXIF-upright), saves locally,
     * and uploads to Sparky. If the network upload fails, the photo remains cached locally as unsynced.
     */
    suspend fun saveAndUploadPhoto(
        date: String,
        pose: PhotoPose,
        sourceFile: File
    ): Result<ProgressPhotoItem> = withContext(Dispatchers.IO) {
        val tempLocalId = "local_${date}_${pose.apiValue}_${UUID.randomUUID().toString().take(8)}"
        val localFile = cacheFileFor(tempLocalId)

        val compressedOk = compressToJpeg(sourceFile, localFile, maxDim = 1600, quality = 88)
        if (!compressedOk) {
            return@withContext Result.failure(IllegalStateException("Could not decode or compress captured photo"))
        }

        val uploadResult = api.uploadPhoto(date, pose, localFile)
        if (uploadResult.isSuccess) {
            val uploaded = uploadResult.getOrThrow()
            val finalFile = cacheFileFor(uploaded.id)
            if (finalFile.absolutePath != localFile.absolutePath) {
                runCatching {
                    localFile.copyTo(finalFile, overwrite = true)
                    localFile.delete()
                }
            }
            val syncedItem = uploaded.copy(
                localPath = if (finalFile.exists()) finalFile.absolutePath else localFile.absolutePath,
                isSynced = true
            )
            stateMutex.withLock {
                val filtered = _photos.value.filterNot {
                    it.id == syncedItem.id || (it.date == date && it.pose == pose)
                }
                val updated = sortPhotos(filtered + syncedItem)
                _photos.value = updated
                saveIndexSync(updated)
            }
            evictCacheForId(syncedItem.id)
            Result.success(syncedItem)
        } else {
            // Preserve offline local copy so the user never loses a captured check-in photo
            val offlineItem = ProgressPhotoItem(
                id = tempLocalId,
                date = date,
                pose = pose,
                weightKg = null,
                localPath = localFile.absolutePath,
                isSynced = false
            )
            stateMutex.withLock {
                val filtered = _photos.value.filterNot { it.date == date && it.pose == pose && !it.isSynced }
                val updated = sortPhotos(filtered + offlineItem)
                _photos.value = updated
                saveIndexSync(updated)
            }
            Result.failure(uploadResult.exceptionOrNull() ?: Exception("Upload failed; saved locally"))
        }
    }

    /**
     * Deletes a photo from Sparky (if synced) and removes its local cache + index entry.
     */
    suspend fun deletePhoto(item: ProgressPhotoItem): Result<Unit> = withContext(Dispatchers.IO) {
        if (item.isSynced) {
            val remoteDelete = api.deletePhoto(item.id)
            if (remoteDelete.isFailure) {
                return@withContext remoteDelete
            }
        }

        item.localPath?.let { path -> runCatching { File(path).delete() } }
        runCatching { cacheFileFor(item.id).delete() }
        evictCacheForId(item.id)

        stateMutex.withLock {
            val updated = _photos.value.filterNot { it.id == item.id }
            _photos.value = updated
            saveIndexSync(updated)
        }
        Result.success(Unit)
    }

    /**
     * Copies a content [Uri] (from system gallery picker) into a temporary cache file ready for preview & upload.
     */
    suspend fun copyUriToTempFile(uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            val outFile = File(appContext.cacheDir, "progress_import_${System.currentTimeMillis()}.jpg")
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@runCatching null
            outFile.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()
    }

    // ---- Internal helpers ----

    private suspend fun attachLocalPath(id: String, path: String) {
        stateMutex.withLock {
            val current = _photos.value
            val idx = current.indexOfFirst { it.id == id }
            if (idx >= 0 && current[idx].localPath != path) {
                val mutable = current.toMutableList()
                mutable[idx] = mutable[idx].copy(localPath = path)
                _photos.value = mutable
                saveIndexSync(mutable)
            }
        }
    }

    private fun cacheFileFor(id: String): File {
        val safe = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(photosDir, "photo_$safe.jpg")
    }

    private fun evictCacheForId(id: String) {
        val prefix = "${id}_"
        memoryCache.snapshot().keys
            .filter { it.startsWith(prefix) }
            .forEach { memoryCache.remove(it) }
    }

    private fun sortPhotos(items: List<ProgressPhotoItem>): List<ProgressPhotoItem> {
        return items.sortedWith(
            compareByDescending<ProgressPhotoItem> { it.date }
                .thenBy { it.pose.ordinal }
                .thenBy { it.id }
        )
    }

    private fun loadIndexSync(): List<ProgressPhotoItem> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val raw = indexFile.readText()
            if (raw.isBlank()) emptyList()
            else sortPhotos(json.decodeFromString<List<ProgressPhotoItem>>(raw))
        }.getOrDefault(emptyList())
    }

    private fun saveIndexSync(items: List<ProgressPhotoItem>) {
        runCatching {
            photosDir.mkdirs()
            val tmp = File(photosDir, "index.json.tmp")
            tmp.writeText(json.encodeToString(items))
            if (!tmp.renameTo(indexFile)) {
                indexFile.writeText(json.encodeToString(items))
                tmp.delete()
            }
        }
    }

    private fun compressToJpeg(source: File, dest: File, maxDim: Int, quality: Int): Boolean {
        return runCatching {
            val bitmap = decodeSampledBitmap(source, maxDim) ?: return false
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            scaled.recycle()
            dest.exists() && dest.length() > 0L
        }.getOrDefault(false)
    }

    private fun decodeSampledBitmap(file: File, maxDim: Int): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= maxDim ||
                bounds.outHeight / (sampleSize * 2) >= maxDim
            ) {
                sampleSize *= 2
            }

            val decoded = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            ) ?: return null

            applyExifOrientation(file, decoded)
        }.getOrNull()
    }

    private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        return runCatching {
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed !== bitmap) bitmap.recycle()
            transformed
        }.getOrDefault(bitmap)
    }
}
