package com.bytelog.app.data.remote

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealTemplate
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.TemplateIngredient
import com.bytelog.app.data.local.model.WeightEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

// Sparky's AI: chat, MCP tools, photo estimates, label scans and the adaptive TDEE. Part of [SparkyApiClient].

/**
 * Fetch Adaptive TDEE calculation from Sparky Fitness Server.
 */
suspend fun SparkyApiClient.fetchAdaptiveTdee(date: String? = null): Result<AdaptiveTdeeResult> = withContext(Dispatchers.IO) {
    try {
        val url = if (date != null) "$baseUrl/api/adaptive-tdee?date=$date" else "$baseUrl/api/adaptive-tdee"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val elem = json.parseToJsonElement(body).jsonObject
                val tdee = elem["tdee"]?.jsonPrimitive?.floatOrNull ?: 2200f
                val confidence = elem["confidence"]?.jsonPrimitive?.content ?: "LOW"
                val weightTrend = elem["weightTrend"]?.jsonPrimitive?.floatOrNull
                val isFallback = elem["isFallback"]?.jsonPrimitive?.booleanOrNull ?: false
                val fallbackReason = elem["fallbackReason"]?.jsonPrimitive?.content
                val avgIntake = elem["avgIntake"]?.jsonPrimitive?.floatOrNull
                val daysOfData = elem["daysOfData"]?.jsonPrimitive?.intOrNull ?: 0
                val weightChangeKg = elem["weightChangeKg"]?.jsonPrimitive?.floatOrNull
                val dailyWeightChangeKg = elem["dailyWeightChangeKg"]?.jsonPrimitive?.floatOrNull
                val weightChangeCalories = elem["weightChangeCalories"]?.jsonPrimitive?.floatOrNull
                val rawTdee = elem["rawTdee"]?.jsonPrimitive?.floatOrNull
                val wasClamped = elem["wasClamped"]?.jsonPrimitive?.booleanOrNull ?: false

                Result.success(
                    AdaptiveTdeeResult(
                        tdee = tdee,
                        confidence = confidence,
                        weightTrend = weightTrend,
                        isFallback = isFallback,
                        fallbackReason = fallbackReason,
                        avgIntake = avgIntake,
                        daysOfData = daysOfData,
                        weightChangeKg = weightChangeKg,
                        dailyWeightChangeKg = dailyWeightChangeKg,
                        weightChangeCalories = weightChangeCalories,
                        rawTdee = rawTdee,
                        wasClamped = wasClamped
                    )
                )
            } else {
                Result.failure(IOException("Fetch adaptive TDEE failed: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * One JSON-RPC call to Sparky's MCP endpoint (`POST /mcp`, stateless Streamable HTTP). Returns the `result`
 * object; a JSON-RPC `error` becomes a failure. The server answers with plain JSON; an event-stream reply
 * is handled too. `tools/list` and `tools/call` need no `initialize` first.
 */
suspend fun SparkyApiClient.mcpRequest(method: String, params: kotlinx.serialization.json.JsonObject? = null): Result<kotlinx.serialization.json.JsonObject> = withContext(Dispatchers.IO) {
    try {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
            put("id", kotlinx.serialization.json.JsonPrimitive(1))
            put("method", kotlinx.serialization.json.JsonPrimitive(method))
            if (params != null) put("params", params)
        }
        val request = Request.Builder()
            .url("$baseUrl/mcp")
            .header("Accept", "application/json, text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        aiClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val hint = when (response.code) {
                    401, 403 -> " Sign in again or check your API key in Settings."
                    404 -> " This Sparky server has no MCP endpoint."
                    else -> ""
                }
                return@use Result.failure(IOException("Sparky MCP request failed: HTTP ${response.code}.$hint"))
            }
            val root = SparkyApiClient.parseMcpBody(body, response.header("Content-Type"))
                ?: return@use Result.failure(IOException("Sparky MCP sent a reply that isn't JSON-RPC."))
            val error = root["error"] as? kotlinx.serialization.json.JsonObject
            if (error != null) {
                return@use Result.failure(IOException("Sparky MCP error: ${error["message"]?.jsonPrimitive?.content ?: "unknown"}"))
            }
            val result = root["result"] as? kotlinx.serialization.json.JsonObject
                ?: return@use Result.failure(IOException("Sparky MCP reply had no result."))
            Result.success(result)
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Send chat messages to Sparky Fitness AI Chatbot (/api/chat).
 * The Sparky server runs the LLM, executes fitness tools (food logging, weight recording, checkins),
 * and returns the assistant reply along with executed tools.
 */
suspend fun SparkyApiClient.sendChatMessage(
    messages: List<ChatMessageInput>,
    toolCategories: List<String>? = null
): Result<ChatMessageOutput> = withContext(Dispatchers.IO) {
    try {
        val serviceConfigId = fetchActiveServiceConfigId()
        if (serviceConfigId.isNullOrBlank()) {
            return@withContext Result.failure(
                IOException("No active AI service configuration found on Sparky server. Please configure an AI service in Sparky Fitness web settings.")
            )
        }

        val jsonArray = kotlinx.serialization.json.buildJsonArray {
            for (m in messages) {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("role", kotlinx.serialization.json.JsonPrimitive(m.role))
                    put("content", kotlinx.serialization.json.JsonPrimitive(m.content))
                    if (!m.imageBase64.isNullOrBlank()) {
                        val formattedImage = if (m.imageBase64.startsWith("data:")) {
                            m.imageBase64
                        } else {
                            "data:image/jpeg;base64,${m.imageBase64}"
                        }
                        val partsArray = kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                                put("text", kotlinx.serialization.json.JsonPrimitive(m.content))
                            })
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("type", kotlinx.serialization.json.JsonPrimitive("image"))
                                put("image", kotlinx.serialization.json.JsonPrimitive(formattedImage))
                            })
                        }
                        put("parts", partsArray)
                    }
                })
            }
        }
        val requestBodyObj = kotlinx.serialization.json.buildJsonObject {
            put("messages", jsonArray)
            put("service_config_id", kotlinx.serialization.json.JsonPrimitive(serviceConfigId))
            if (!toolCategories.isNullOrEmpty()) {
                put("toolCategories", kotlinx.serialization.json.buildJsonArray {
                    toolCategories.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                })
            }
        }
        val body = requestBodyObj.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/api/chat")
            .post(body)
            .build()

        aiClient.newCall(request).execute().use { response ->
            val respBody = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val root = json.parseToJsonElement(respBody).jsonObject
                val replyContent = root["content"]?.jsonPrimitive?.content ?: ""
                val action = root["action"]?.jsonPrimitive?.content
                val executedToolsArray = root["executedTools"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
                val executedToolsList = mutableListOf<String>()
                var askUserPrompt: ChatAskUserPrompt? = null

                for (el in executedToolsArray) {
                    if (el is kotlinx.serialization.json.JsonObject) {
                        val toolName = el["name"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content.orEmpty()
                        if (toolName.isNotBlank()) {
                            executedToolsList.add(toolName)
                        }
                        if (toolName == "sparky_ask_user") {
                            val argsObj = el["args"]?.takeIf { it is kotlinx.serialization.json.JsonObject }?.jsonObject
                            if (argsObj != null) {
                                val question = argsObj["question"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
                                val mode = argsObj["mode"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
                                val optionsArray = argsObj["options"]?.takeIf { it is kotlinx.serialization.json.JsonArray }?.jsonArray
                                val options = optionsArray?.mapNotNull { opt ->
                                    opt.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
                                } ?: emptyList()
                                if (options.isNotEmpty()) {
                                    askUserPrompt = ChatAskUserPrompt(
                                        question = question,
                                        options = options,
                                        mode = mode
                                    )
                                }
                            }
                        }
                    } else if (el is kotlinx.serialization.json.JsonPrimitive) {
                        executedToolsList.add(el.content)
                    }
                }

                if (askUserPrompt == null) {
                    askUserPrompt = SparkyApiClient.extractAskUserPromptFromText(replyContent)
                }

                Result.success(
                    ChatMessageOutput(
                        id = UUID.randomUUID().toString(),
                        role = "assistant",
                        content = replyContent,
                        action = action,
                        executedTools = executedToolsList,
                        askUserPrompt = askUserPrompt
                    )
                )
            } else {
                if (response.code == 404) {
                    cachedServiceConfigId = null
                }
                var errorMsg = when (response.code) {
                    401 -> "Authentication error (HTTP 401). Please check your Sparky credentials in Settings."
                    403 -> "Access forbidden (HTTP 403)."
                    404 -> "AI service not configured or endpoint not found on server (HTTP 404)."
                    else -> "Chat request failed: HTTP ${response.code}"
                }
                try {
                    val parsed = json.parseToJsonElement(respBody).jsonObject
                    val serverError = parsed["error"]?.jsonPrimitive?.content
                        ?: parsed["message"]?.jsonPrimitive?.content
                    if (!serverError.isNullOrBlank()) {
                        errorMsg += " ($serverError)"
                    }
                } catch (_: Exception) {}
                Result.failure(IOException(errorMsg))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Load recent chat history from Sparky Fitness server (/api/chat/sparky-chat-history).
 */
suspend fun SparkyApiClient.fetchChatHistory(): Result<List<ChatHistoryItem>> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url("$baseUrl/api/chat/sparky-chat-history")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val content = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(content).jsonArray
                val items = root.mapNotNull { el ->
                    val obj = el.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                    val msgType = obj["message_type"]?.jsonPrimitive?.content ?: "assistant"
                    val text = obj["content"]?.jsonPrimitive?.content ?: ""
                    val createdAt = obj["created_at"]?.jsonPrimitive?.content
                    ChatHistoryItem(
                        id = id,
                        message_type = msgType,
                        content = text,
                        created_at = createdAt
                    )
                }
                Result.success(items)
            } else {
                Result.failure(IOException("Failed to fetch chat history: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Clear all user chat history on Sparky Fitness server (/api/chat/clear-all-history).
 */
suspend fun SparkyApiClient.clearChatHistory(): Result<Boolean> = withContext(Dispatchers.IO) {
    try {
        val body = "{}".toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/chat/clear-all-history")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(IOException("Failed to clear chat history: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Estimate nutrition from food photo using Sparky Fitness Server (/api/foods/estimate-food-photo).
 */
suspend fun SparkyApiClient.estimateFoodPhoto(
    base64: String,
    mimeType: String = "image/jpeg",
    description: String? = null
): Result<FoodPhotoEstimateResult> = withContext(Dispatchers.IO) {
    try {
        val cleanBase64 = if (base64.contains(",")) base64.substringAfter(",") else base64
        val payloadObj = kotlinx.serialization.json.buildJsonObject {
            put("images", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("image", kotlinx.serialization.json.JsonPrimitive(cleanBase64))
                    put("mime_type", kotlinx.serialization.json.JsonPrimitive(mimeType))
                })
            })
            if (!description.isNullOrBlank()) {
                put("description", kotlinx.serialization.json.JsonPrimitive(description))
            }
        }
        val body = payloadObj.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/foods/estimate-food-photo")
            .post(body)
            .build()

        aiClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val content = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(content).jsonObject
                val mealSummary = root["meal_summary"]?.jsonPrimitive?.content ?: "Estimated Meal"
                val totals = root["totals"]?.jsonObject
                val calories = totals?.get("calories_kcal")?.jsonPrimitive?.floatOrNull ?: 0f
                val protein = totals?.get("protein_g")?.jsonPrimitive?.floatOrNull ?: 0f
                val carbs = totals?.get("carbs_g")?.jsonPrimitive?.floatOrNull ?: 0f
                val fat = totals?.get("fat_g")?.jsonPrimitive?.floatOrNull ?: 0f
                val fiber = totals?.get("fiber_g")?.jsonPrimitive?.floatOrNull ?: 0f
                val totalGrams = totals?.get("total_grams")?.jsonPrimitive?.floatOrNull ?: 100f

                Result.success(
                    FoodPhotoEstimateResult(
                        mealSummary = mealSummary,
                        calories = calories,
                        protein = protein,
                        carbs = carbs,
                        fat = fat,
                        fiber = fiber,
                        servingSize = totalGrams,
                        servingUnit = "g"
                    )
                )
            } else {
                val err = response.body?.string().orEmpty()
                Result.failure(IOException("Food photo estimation failed: HTTP ${response.code} $err"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Reads a nutrition label from a photo with Sparky's AI (POST /api/foods/scan-label). Fails with Sparky's
 * own message, e.g. when no AI service is set up.
 */
suspend fun SparkyApiClient.scanNutritionLabel(imageBase64: String, mimeType: String = "image/jpeg"): Result<LabelScan> = withContext(Dispatchers.IO) {
    try {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("image", kotlinx.serialization.json.JsonPrimitive(if (imageBase64.contains(",")) imageBase64.substringAfter(",") else imageBase64))
            put("mime_type", kotlinx.serialization.json.JsonPrimitive(mimeType))
        }
        val request = Request.Builder()
            .url("$baseUrl/api/foods/scan-label")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        aiClient.newCall(request).await().use { response ->
            val content = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                LabelScan.parse(content)?.let { Result.success(it) }
                    ?: Result.failure(IOException("Couldn't read the nutrition facts on that label. Try a sharper, closer photo."))
            } else {
                val msg = runCatching { json.parseToJsonElement(content).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                Result.failure(IOException(when {
                    response.code == 422 && msg?.contains("AI", ignoreCase = true) == true -> "Label scanning needs an AI service set up in Sparky ($msg)"
                    msg != null -> msg
                    else -> "Label scan failed: HTTP ${response.code}"
                }))
            }
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Result.failure(e)
    }
}

/**
 * Estimates a photographed meal food by food: each item Sparky's AI sees, with its weight, calories and macros.
 * [note] is anything the photo can't show ("cooked in butter", "the bowl is 400 g").
 */
suspend fun SparkyApiClient.estimateMealPhoto(
    images: List<Pair<String, String>>,
    note: String? = null
): Result<PhotoMealEstimate> = withContext(Dispatchers.IO) {
    try {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("images", kotlinx.serialization.json.buildJsonArray {
                images.forEach { (b64, mime) ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("image", kotlinx.serialization.json.JsonPrimitive(if (b64.contains(",")) b64.substringAfter(",") else b64))
                        put("mime_type", kotlinx.serialization.json.JsonPrimitive(mime))
                    })
                }
            })
            if (!note.isNullOrBlank()) put("description", kotlinx.serialization.json.JsonPrimitive(note))
        }
        val request = Request.Builder()
            .url("$baseUrl/api/foods/estimate-food-photo")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        aiClient.newCall(request).execute().use { response ->
            val content = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                SparkyApiClient.parsePhotoEstimate(content)?.let { Result.success(it) }
                    ?: Result.failure(IOException("Sparky's photo estimate couldn't be read"))
            } else {
                val msg = runCatching { json.parseToJsonElement(content).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                Result.failure(IOException(msg ?: "Photo estimate failed: HTTP ${response.code}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
