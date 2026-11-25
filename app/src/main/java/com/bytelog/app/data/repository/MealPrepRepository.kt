package com.bytelog.app.data.repository

import com.bytelog.app.data.local.db.PrepBatchDao
import com.bytelog.app.data.local.model.BatchConsumption
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.PrepBatch
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.recipes.BatchMath
import com.bytelog.app.recipes.Macros
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import com.bytelog.app.data.remote.createCustomFood

/** A batch together with what is left of it. */
data class BatchStatus(
    val batch: PrepBatch,
    val remainingFraction: Float
) {
    val total: Macros get() = Macros(batch.calories, batch.protein, batch.carbs, batch.fat)
    val remainingPortions: Float? get() = batch.portions?.let { it * remainingFraction }
    val remainingGrams: Float? get() = batch.cookedWeightG?.takeIf { it > 0f }?.let { it * remainingFraction }
    val isEmpty: Boolean get() = BatchMath.isFinished(remainingFraction, batch.isWeighed)

    /** Grams in one portion when the batch has both a weight and a portion count. */
    val gramsPerPortion: Float? get() {
        val w = batch.cookedWeightG ?: return null
        val p = batch.portions ?: return null
        return if (w > 0f && p > 0) w / p else null
    }

    /** Nutrients of one portion (or of 100 g for a weighed batch without portions). */
    val perPortion: Macros get() = when {
        batch.portions != null && batch.portions > 0 -> total / batch.portions.toFloat()
        batch.cookedWeightG != null && batch.cookedWeightG > 0f -> total * (100f / batch.cookedWeightG)
        else -> total
    }

    fun daysOld(now: Long = System.currentTimeMillis()): Long =
        ChronoUnit.DAYS.between(toDate(batch.cookedAt), toDate(now))

    /** Days until best-by: 0 = today, negative = past. Null when no best-by date. */
    fun daysLeft(now: Long = System.currentTimeMillis()): Long? =
        batch.bestBy?.let { ChronoUnit.DAYS.between(toDate(now), toDate(it)) }

    private fun toDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
}

class MealPrepRepository(
    private val prepBatchDao: PrepBatchDao,
    private val foodRepository: FoodRepository,
    private val sparkyApiClient: SparkyApiClient,
    private val appScope: CoroutineScope
) {
    val activeBatches: Flow<List<BatchStatus>> = combine(
        prepBatchDao.observeActive(),
        prepBatchDao.observeAllConsumptions()
    ) { batches, consumptions ->
        val byBatch = consumptions.groupBy { it.batchId }
        batches.map { b ->
            val consumed = byBatch[b.id].orEmpty().map { it.fraction } + b.discardedFraction
            BatchStatus(b, BatchMath.remainingFraction(consumed))
        }
    }

    suspend fun getBatch(id: String): PrepBatch? = withContext(Dispatchers.IO) { prepBatchDao.get(id) }

    suspend fun startBatch(
        name: String,
        templateId: String?,
        total: Macros,
        portions: Int?,
        cookedWeightG: Float?,
        scale: Float,
        location: String,
        shelfLifeDays: Int?,
        ingredientsJson: String = "[]",
        cookedAt: Long = System.currentTimeMillis()
    ): PrepBatch = withContext(Dispatchers.IO) {
        val batch = PrepBatch(
            id = UUID.randomUUID().toString(),
            templateId = templateId,
            name = name.trim().ifBlank { "Meal prep" },
            cookedAt = cookedAt,
            bestBy = shelfLifeDays?.let { cookedAt + it * DAY_MS },
            location = location,
            calories = total.calories,
            protein = total.protein,
            carbs = total.carbs,
            fat = total.fat,
            portions = portions?.takeIf { it > 0 },
            cookedWeightG = cookedWeightG?.takeIf { it > 0f },
            scale = scale,
            ingredientsJson = ingredientsJson
        )
        prepBatchDao.upsert(batch)
        createSnapshotFoodInBackground(batch)
        batch
    }

    /**
     * Keeps a frozen copy of the batch in Sparky as a custom food (so it can be logged from the
     * web too). It is not added to the local food cache, so it never clutters search.
     */
    private fun createSnapshotFoodInBackground(batch: PrepBatch) {
        appScope.launch(Dispatchers.IO) {
            val weighed = batch.cookedWeightG != null && batch.cookedWeightG > 0f
            val (size, unit, per) = when {
                weighed -> Triple(batch.cookedWeightG!!, "g", Macros(batch.calories, batch.protein, batch.carbs, batch.fat))
                batch.portions != null -> Triple(1f, PrepBatch.UNIT_PORTION, Macros(batch.calories, batch.protein, batch.carbs, batch.fat) / batch.portions.toFloat())
                else -> Triple(1f, "serving", Macros(batch.calories, batch.protein, batch.carbs, batch.fat))
            }
            val res = sparkyApiClient.createCustomFood(
                name = batch.name,
                brand = batchLabel(batch),
                servingSize = size,
                servingUnit = unit,
                calories = per.calories,
                protein = per.protein,
                carbs = per.carbs,
                fat = per.fat
            )
            res.getOrNull()?.let { food ->
                prepBatchDao.get(batch.id)?.let { prepBatchDao.upsert(it.copy(sparkyFoodId = food.id)) }
            }
        }
    }

    /**
     * Logs part of a batch to the diary through the normal offline-first entry path and
     * records the link. Returns the created entry, or null if the amount was invalid.
     */
    suspend fun logFromBatch(
        batchId: String,
        date: String,
        mealTypeId: String,
        mealName: String,
        portions: Float? = null,
        grams: Float? = null
    ): FoodEntry? = withContext(Dispatchers.IO) {
        val batch = prepBatchDao.get(batchId) ?: return@withContext null
        val fraction = BatchMath.fractionFor(batch.portions, batch.cookedWeightG, portions, grams)
            ?: return@withContext null
        if (fraction <= 0f) return@withContext null
        val macros = Macros(batch.calories, batch.protein, batch.carbs, batch.fat) * fraction
        val useGrams = grams != null && batch.cookedWeightG != null
        val entry = FoodEntry(
            id = UUID.randomUUID().toString(),
            foodId = batch.sparkyFoodId,
            foodName = batch.name,
            brandName = batchLabel(batch),
            mealTypeId = mealTypeId,
            mealName = mealName,
            quantity = if (useGrams) grams!! else portions ?: 1f,
            unit = if (useGrams) "g" else PrepBatch.UNIT_PORTION,
            calories = macros.calories,
            protein = macros.protein,
            carbs = macros.carbs,
            fat = macros.fat,
            entryDate = date,
            entryTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT
        )
        // Link first: commitMultiAdd may re-key the entry with its server id, and the DAO follows that.
        prepBatchDao.insertConsumption(
            BatchConsumption(
                foodEntryId = entry.id,
                batchId = batch.id,
                portions = if (useGrams) null else entry.quantity,
                grams = if (useGrams) grams else null,
                fraction = fraction
            )
        )
        foodRepository.commitMultiAdd(listOf(entry))

        val remaining = BatchMath.remainingFraction(
            prepBatchDao.consumptionsFor(batch.id).map { it.fraction } + batch.discardedFraction
        )
        if (BatchMath.isFinished(remaining, batch.isWeighed)) {
            prepBatchDao.upsert(batch.copy(status = PrepBatch.STATUS_FINISHED, finishedAt = System.currentTimeMillis()))
        }
        entry
    }

    suspend fun moveTo(batchId: String, location: String, shelfLifeDays: Int?) = withContext(Dispatchers.IO) {
        val b = prepBatchDao.get(batchId) ?: return@withContext
        prepBatchDao.upsert(
            b.copy(
                location = location,
                bestBy = shelfLifeDays?.let { System.currentTimeMillis() + it * DAY_MS } ?: b.bestBy
            )
        )
    }

    /** Throws away what is left. The waste is recorded but never logged. */
    suspend fun discardRemaining(batchId: String) = withContext(Dispatchers.IO) {
        val b = prepBatchDao.get(batchId) ?: return@withContext
        val consumed = prepBatchDao.consumptionsFor(batchId).sumOf { it.fraction.toDouble() }.toFloat()
        prepBatchDao.upsert(
            b.copy(
                status = PrepBatch.STATUS_DISCARDED,
                finishedAt = System.currentTimeMillis(),
                discardedFraction = (1f - consumed).coerceIn(0f, 1f)
            )
        )
    }

    suspend fun archive(batchId: String) = withContext(Dispatchers.IO) {
        val b = prepBatchDao.get(batchId) ?: return@withContext
        prepBatchDao.upsert(b.copy(status = PrepBatch.STATUS_FINISHED, finishedAt = System.currentTimeMillis()))
    }

    suspend fun reopen(batchId: String) = withContext(Dispatchers.IO) {
        val b = prepBatchDao.get(batchId) ?: return@withContext
        prepBatchDao.upsert(b.copy(status = PrepBatch.STATUS_ACTIVE, finishedAt = null, discardedFraction = 0f))
    }

    /** Deletes the batch record. Diary entries already logged from it are kept. */
    suspend fun deleteBatch(batchId: String) = withContext(Dispatchers.IO) {
        prepBatchDao.deleteBatchWithConsumptions(batchId)
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val DEFAULT_FRIDGE_DAYS = 4
        const val DEFAULT_FREEZER_DAYS = 60

        fun batchLabel(batch: PrepBatch): String {
            val date = Instant.ofEpochMilli(batch.cookedAt).atZone(ZoneId.systemDefault()).toLocalDate()
            return "Meal prep · " + date.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        }
    }
}
