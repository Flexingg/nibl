package com.bytelog.app.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.bytelog.app.data.local.model.BatchConsumption
import com.bytelog.app.data.local.model.IngredientAlias
import com.bytelog.app.data.local.model.PrepBatch
import com.bytelog.app.data.local.model.RecipeDetails
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDetailsDao {
    @Query("SELECT * FROM recipe_details")
    fun getAll(): Flow<List<RecipeDetails>>

    @Query("SELECT * FROM recipe_details WHERE templateId = :templateId LIMIT 1")
    fun observe(templateId: String): Flow<RecipeDetails?>

    @Query("SELECT * FROM recipe_details WHERE templateId = :templateId LIMIT 1")
    suspend fun get(templateId: String): RecipeDetails?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(details: RecipeDetails)

    @Query("DELETE FROM recipe_details WHERE templateId = :templateId")
    suspend fun delete(templateId: String)
}

@Dao
interface PrepBatchDao {
    @Query("SELECT * FROM prep_batches WHERE status = 'active' ORDER BY cookedAt DESC")
    fun observeActive(): Flow<List<PrepBatch>>

    @Query("SELECT * FROM prep_batches WHERE status != 'active' AND (finishedAt IS NULL OR finishedAt >= :since) ORDER BY finishedAt DESC")
    fun observeRecentlyClosed(since: Long): Flow<List<PrepBatch>>

    @Query("SELECT * FROM prep_batches WHERE id = :id LIMIT 1")
    suspend fun get(id: String): PrepBatch?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(batch: PrepBatch)

    @Query("DELETE FROM prep_batches WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM batch_consumptions")
    fun observeAllConsumptions(): Flow<List<BatchConsumption>>

    @Query("SELECT * FROM batch_consumptions WHERE batchId = :batchId")
    suspend fun consumptionsFor(batchId: String): List<BatchConsumption>

    @Query("SELECT * FROM batch_consumptions WHERE foodEntryId = :entryId LIMIT 1")
    suspend fun consumptionForEntry(entryId: String): BatchConsumption?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConsumption(c: BatchConsumption)

    @Query("DELETE FROM batch_consumptions WHERE foodEntryId IN (:entryIds)")
    suspend fun deleteConsumptionsForEntries(entryIds: List<String>)

    @Query("DELETE FROM batch_consumptions WHERE batchId = :batchId")
    suspend fun deleteConsumptionsForBatch(batchId: String)

    /** Keeps the link when a pending diary entry is re-keyed with its server id after sync. */
    @Query("UPDATE batch_consumptions SET foodEntryId = :newId WHERE foodEntryId = :oldId")
    suspend fun remapEntryId(oldId: String, newId: String)

    @Query("UPDATE prep_batches SET status = 'active', finishedAt = NULL WHERE id IN (:ids) AND status = 'finished'")
    suspend fun reactivateFinished(ids: List<String>)

    @Query("SELECT * FROM batch_consumptions WHERE foodEntryId IN (:entryIds)")
    suspend fun consumptionsForEntries(entryIds: List<String>): List<BatchConsumption>

    /** A diary entry from a batch was deleted: give the food back to the batch. */
    @Transaction
    suspend fun onEntriesDeleted(entryIds: List<String>): List<BatchConsumption> {
        if (entryIds.isEmpty()) return emptyList()
        val removed = consumptionsForEntries(entryIds)
        if (removed.isEmpty()) return emptyList()
        deleteConsumptionsForEntries(entryIds)
        reactivateFinished(removed.map { it.batchId }.distinct())
        return removed
    }

    /** A diary entry from a batch was edited: keep the consumed share in step with its quantity. */
    @Transaction
    suspend fun onEntryQuantityChanged(entryId: String, newQuantity: Float) {
        val c = consumptionForEntry(entryId) ?: return
        val old = c.grams ?: c.portions ?: return
        if (old <= 0f || newQuantity <= 0f) return
        val ratio = newQuantity / old
        insertConsumption(
            c.copy(
                grams = c.grams?.let { newQuantity },
                portions = if (c.grams == null) newQuantity else c.portions,
                fraction = c.fraction * ratio
            )
        )
        if (ratio < 1f) reactivateFinished(listOf(c.batchId))
    }

    @Transaction
    suspend fun deleteBatchWithConsumptions(batchId: String) {
        deleteConsumptionsForBatch(batchId)
        delete(batchId)
    }
}

@Dao
interface IngredientAliasDao {
    @Query("SELECT * FROM ingredient_aliases WHERE normalizedName = :name LIMIT 1")
    suspend fun get(name: String): IngredientAlias?

    @Query("SELECT * FROM ingredient_aliases")
    suspend fun getAll(): List<IngredientAlias>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alias: IngredientAlias)
}
