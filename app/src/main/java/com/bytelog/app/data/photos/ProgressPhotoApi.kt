package com.bytelog.app.data.photos

import com.bytelog.app.data.remote.SparkyApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

/**
 * Sparky Fitness Check-in / Progress Photos API client.
 * Uses [SparkyApiClient]'s authenticated [okhttp3.OkHttpClient] and base URL.
 */
class ProgressPhotoApi(private val api: SparkyApiClient) {

    private val baseUrl: String
        get() = api.currentBaseUrl().trimEnd('/')

    private val client
        get() = api.httpClient()

    private val json
        get() = api.jsonCodec()

    /** GET /api/measurements/check-in-photos */
    suspend fun listGallery(): Result<List<ProgressPhotoItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/measurements/check-in-photos")
                .get()
                .build()

            client.newCall(req).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Failed to load gallery (HTTP ${response.code}): ${body.take(200)}")
                }
                if (body.isBlank()) return@runCatching emptyList()
                val root = json.parseToJsonElement(body)
                parsePhotoList(root)
            }
        }
    }

    /** GET /api/measurements/check-in-photos/dates */
    suspend fun listDates(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/measurements/check-in-photos/dates")
                .get()
                .build()

            client.newCall(req).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Failed to load photo dates (HTTP ${response.code}): ${body.take(200)}")
                }
                if (body.isBlank()) return@runCatching emptyList()
                val root = json.parseToJsonElement(body)
                parseDateList(root)
            }
        }
    }

    /** GET /api/measurements/check-in-photos/$date */
    suspend fun getPhotosForDate(date: String): Result<List<ProgressPhotoItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/measurements/check-in-photos/$date")
                .get()
                .build()

            client.newCall(req).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 404) return@runCatching emptyList()
                if (!response.isSuccessful) {
                    throw IOException("Failed to load photos for $date (HTTP ${response.code}): ${body.take(200)}")
                }
                if (body.isBlank()) return@runCatching emptyList()
                val root = json.parseToJsonElement(body)
                parsePhotoList(root, fallbackDate = date)
            }
        }
    }

    /** GET /api/measurements/check-in-photos/file/$id */
    suspend fun downloadPhotoBytes(id: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/measurements/check-in-photos/file/$id")
                .header("Accept", "image/*, */*")
                .get()
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to download photo $id (HTTP ${response.code})")
                }
                val bytes = response.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    throw IOException("Empty photo payload for $id")
                }
                bytes
            }
        }
    }

    /** POST /api/measurements/check-in-photos/$date/${pose.apiValue} (multipart/form-data, field name "photo") */
    suspend fun uploadPhoto(
        date: String,
        pose: PhotoPose,
        file: File
    ): Result<ProgressPhotoItem> = withContext(Dispatchers.IO) {
        runCatching {
            val mediaType = "image/jpeg".toMediaType()
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    name = "photo",
                    filename = file.name.ifBlank { "${date}_${pose.apiValue}.jpg" },
                    body = file.asRequestBody(mediaType)
                )
                .build()

            val req = Request.Builder()
                .url("$baseUrl/api/measurements/check-in-photos/$date/${pose.apiValue}")
                .post(multipartBody)
                .build()

            client.newCall(req).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Failed to upload photo (HTTP ${response.code}): ${body.take(200)}")
                }
                if (body.isBlank()) {
                    throw IOException("Upload succeeded but server returned an empty body")
                }
                val root = json.parseToJsonElement(body)
                parseSinglePhoto(root, fallbackDate = date, fallbackPose = pose)
                    ?: throw IOException("Could not parse uploaded photo response")
            }
        }
    }

    /** DELETE /api/measurements/check-in-photos/photo/$id */
    suspend fun deletePhoto(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/measurements/check-in-photos/photo/$id")
                .delete()
                .build()

            client.newCall(req).execute().use { response ->
                if (response.code == 404) return@runCatching Unit
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    throw IOException("Failed to delete photo $id (HTTP ${response.code}): ${body.take(200)}")
                }
                Unit
            }
        }
    }

    // ---- Flexible JSON parsers to handle Sparky server response shapes ----

    private fun parseDateList(root: JsonElement): List<String> {
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                (root["dates"] ?: root["data"] ?: root["items"]) as? JsonArray
            }
            else -> null
        } ?: return emptyList()

        return array.mapNotNull { elem ->
            when (elem) {
                is JsonPrimitive -> normalizeDate(elem.contentOrNull)
                is JsonObject -> normalizeDate(
                    elem.str("date") ?: elem.str("entry_date") ?: elem.str("entryDate") ?: elem.str("check_in_date")
                )
                else -> null
            }
        }.distinct().sortedDescending()
    }

    private fun parsePhotoList(
        root: JsonElement,
        fallbackDate: String? = null
    ): List<ProgressPhotoItem> {
        return when (root) {
            is JsonArray -> root.mapNotNull { item ->
                (item as? JsonObject)?.let { parsePhotoObject(it, fallbackDate = fallbackDate) }
            }
            is JsonObject -> {
                val wrapped = (root["photos"]
                    ?: root["data"]
                    ?: root["items"]
                    ?: root["gallery"]
                    ?: root["checkInPhotos"]
                    ?: root["check_in_photos"])
                if (wrapped is JsonArray) {
                    return wrapped.mapNotNull { elem ->
                        (elem as? JsonObject)?.let { parsePhotoObject(it, fallbackDate = fallbackDate) }
                    }
                }
                // Check if this object itself is a single photo record
                parsePhotoObject(root, fallbackDate = fallbackDate)?.let { return listOf(it) }

                // Fallback: map keyed by pose ("front"/"side"/"back") or by date ("YYYY-MM-DD")
                val results = mutableListOf<ProgressPhotoItem>()
                for ((key, value) in root) {
                    val keyAsPose = PhotoPose.fromApiValue(key)
                    val keyAsDate = if (keyAsPose == null && key.length >= 10 && key[4] == '-' && key[7] == '-') {
                        key.take(10)
                    } else null
                    when (value) {
                        is JsonObject -> {
                            parsePhotoObject(
                                value,
                                fallbackDate = keyAsDate ?: fallbackDate,
                                fallbackPose = keyAsPose
                            )?.let { results.add(it) }
                        }
                        is JsonArray -> {
                            value.forEach { elem ->
                                (elem as? JsonObject)?.let {
                                    parsePhotoObject(
                                        it,
                                        fallbackDate = keyAsDate ?: fallbackDate,
                                        fallbackPose = keyAsPose
                                    )?.let { parsed -> results.add(parsed) }
                                }
                            }
                        }
                        else -> Unit
                    }
                }
                results
            }
            else -> emptyList()
        }
    }

    private fun parseSinglePhoto(
        root: JsonElement,
        fallbackDate: String? = null,
        fallbackPose: PhotoPose? = null
    ): ProgressPhotoItem? {
        val obj = root as? JsonObject ?: return null
        val nested = (obj["photo"] ?: obj["data"] ?: obj["item"]) as? JsonObject
        return parsePhotoObject(nested ?: obj, fallbackDate = fallbackDate, fallbackPose = fallbackPose)
    }

    private fun parsePhotoObject(
        obj: JsonObject,
        fallbackDate: String? = null,
        fallbackPose: PhotoPose? = null
    ): ProgressPhotoItem? {
        val id = obj.str("id")
            ?: obj.str("photo_id")
            ?: obj.str("photoId")
            ?: obj.str("file_id")
            ?: obj.str("fileId")
            ?: return null

        val date = normalizeDate(
            obj.str("entry_date")
                ?: obj.str("date")
                ?: obj.str("entryDate")
                ?: obj.str("check_in_date")
                ?: obj.str("checkInDate")
                ?: obj.str("taken_at")
                ?: obj.str("created_at")
        ) ?: normalizeDate(fallbackDate) ?: return null

        val pose = PhotoPose.fromApiValue(
            obj.str("pose")
                ?: obj.str("angle")
                ?: obj.str("photo_type")
                ?: obj.str("type")
                ?: obj.str("view")
        ) ?: fallbackPose ?: PhotoPose.FRONT

        val weightKg = obj.float("weight_kg")
            ?: obj.float("weightKg")
            ?: obj.float("weight")
            ?: obj.float("weight_lbs")?.div(2.2046226f)
            ?: obj.float("weightLbs")?.div(2.2046226f)

        return ProgressPhotoItem(
            id = id,
            date = date,
            pose = pose,
            weightKg = weightKg,
            localPath = null,
            isSynced = true
        )
    }

    private fun normalizeDate(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.length < 10) return null
        val candidate = trimmed.take(10)
        return if (candidate[4] == '-' && candidate[7] == '-') candidate else null
    }

    private fun JsonObject.str(key: String): String? {
        val prim = this[key] as? JsonPrimitive ?: return null
        if (prim is JsonNull) return null
        return prim.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.float(key: String): Float? {
        val prim = this[key] as? JsonPrimitive ?: return null
        if (prim is JsonNull) return null
        return prim.floatOrNull ?: prim.contentOrNull?.toFloatOrNull()
    }
}
