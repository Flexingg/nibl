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

// What Sparky sends and receives: photo estimates, chat, goals, water. Part of [SparkyApiClient].

/** A photographed meal as Sparky's AI sees it. */
data class PhotoMealEstimate(
    val summary: String,
    val confidence: String,
    val confidenceReason: String?,
    val items: List<PhotoMealItem>,
    val questions: List<String> = emptyList(),
    /** Who made the estimate, e.g. "Claude", when it wasn't Sparky's AI. */
    val estimatedBy: String? = null,
    /** Set when the user's own model failed and Sparky's AI estimated instead. */
    val fallbackNote: String? = null
)

data class PhotoMealItem(
    val name: String,
    val canonicalName: String?,
    val grams: Float,
    val portion: String?,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val fiber: Float = 0f,
    val confidence: String = "medium",
    /** The model's other guesses for this food (a different food, or a different portion), best first. */
    val alternatives: List<PhotoMealItem> = emptyList(),
    /** A few words on why the model thinks so ("looks like brown rice"). */
    val reason: String? = null
)

@kotlinx.serialization.Serializable
data class FoodPhotoEstimateResult(
    val mealSummary: String,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val fiber: Float = 0f,
    val servingSize: Float = 100f,
    val servingUnit: String = "g"
)

@kotlinx.serialization.Serializable
data class ChatAskUserPrompt(
    val question: String? = null,
    val options: List<String> = emptyList(),
    val mode: String? = null
)

@kotlinx.serialization.Serializable
data class ChatMessageInput(
    val role: String,
    val content: String,
    val imageBase64: String? = null
)

data class ChatMessageOutput(
    val id: String = UUID.randomUUID().toString(),
    val role: String = "assistant",
    val content: String,
    val imageBase64: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val action: String? = null,
    val executedTools: List<String> = emptyList(),
    val askUserPrompt: ChatAskUserPrompt? = null,
    /** Pages the web research assistant used. */
    val webSources: List<com.bytelog.app.research.WebSource> = emptyList(),
    /** True for answers from the web research assistant (not Sparky's own chat). */
    val viaWeb: Boolean = false,
    /** A meal plan in the answer, which the user can add to their Planned list. */
    val mealPlan: com.bytelog.app.data.repository.MealPlanDraft? = null,
    /** Set once that plan has been added. */
    val planAdded: Boolean = false,
    /** Changes to the plan in the answer, which the user can apply. */
    val planEdit: com.bytelog.app.data.repository.PlanEditDraft? = null,
    /** Those changes as they read, against the plan when the answer came. */
    val planEditLines: List<com.bytelog.app.data.repository.PlanChangeLine> = emptyList(),
    /** Set once the changes have been applied (and cleared again by an undo). */
    val planEditApplied: Boolean = false,
    /** The web assistant's note of what it did with the user's data, sent back as history (not shown). */
    val actionNote: String? = null
)

@kotlinx.serialization.Serializable
data class ChatHistoryItem(
    val id: String,
    val message_type: String,
    val content: String,
    val created_at: String? = null
)

@kotlinx.serialization.Serializable
data class AdaptiveTdeeResult(
    val tdee: Float,
    val confidence: String = "LOW",
    val weightTrend: Float? = null,
    val isFallback: Boolean = false,
    val fallbackReason: String? = null,
    val avgIntake: Float? = null,
    val daysOfData: Int = 0,
    val weightChangeKg: Float? = null,
    val dailyWeightChangeKg: Float? = null,
    val weightChangeCalories: Float? = null,
    val rawTdee: Float? = null,
    val wasClamped: Boolean = false
)

@kotlinx.serialization.Serializable
data class UserMacroGoals(
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val waterGoalMl: Float = 2000f
)

@kotlinx.serialization.Serializable
data class WaterContainer(
    val id: Int,
    val name: String,
    /** Always millilitres: Sparky converts to ml when saving, whatever [unit] says. */
    val volume: Float,
    /** Display label only (ml, oz, liter). It does NOT change what [volume] is measured in. */
    val unit: String = "ml",
    val isPrimary: Boolean = false,
    val servingsPerContainer: Int = 1
) {
    val volumeMl: Float get() = volume
    val volumeOz: Float get() = volume / 29.5735f
}

@kotlinx.serialization.Serializable
data class WaterLogDto(
    val id: String,
    val waterMl: Float,
    val entryDate: String,
    val containerId: Int? = null,
    val containerName: String? = null,
    val createdAt: String? = null,
    val source: String? = null,
    val sourceId: String? = null
)

/**
 * Runs a request so that cancelling the coroutine also cancels the request: a search for letters already typed
 * past stops using the network instead of finishing in the background.
 */
internal suspend fun okhttp3.Call.await(): okhttp3.Response = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: IOException) { cont.resumeWith(Result.failure(e)) }
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            cont.resume(response) { _, r, _ -> r.close() }
        }
    })
}
