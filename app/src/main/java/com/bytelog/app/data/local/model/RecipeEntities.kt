package com.bytelog.app.data.local.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ByteLog-owned recipe extras for a Sparky meal template (directions, times, source,
 * storage life). Kept in its own table so template refreshes from Sparky never wipe it.
 */
@Entity(tableName = "recipe_details")
data class RecipeDetails(
    @PrimaryKey val templateId: String,
    val prepTimeMinutes: Int? = null,
    val cookTimeMinutes: Int? = null,
    val sourceUrl: String? = null,
    val imageUrl: String? = null,
    val stepsJson: String = "[]",
    val notes: String? = null,
    val fridgeDays: Int? = null,
    val freezerDays: Int? = null,
    val storageNotes: String? = null,
    val rawIngredientLinesJson: String = "[]",
    val siteNutritionJson: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * A cooked batch of food in the fridge or freezer. Nutrients are a frozen snapshot of the
 * whole batch at cook time. Remaining amount is derived from [BatchConsumption] rows.
 */
@Entity(tableName = "prep_batches", indices = [Index(value = ["status"])])
data class PrepBatch(
    @PrimaryKey val id: String,
    val templateId: String? = null,
    val name: String,
    val cookedAt: Long,
    val bestBy: Long? = null,
    val location: String = LOCATION_FRIDGE,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val portions: Int? = null,
    val cookedWeightG: Float? = null,
    val scale: Float = 1f,
    val ingredientsJson: String = "[]",
    val sparkyFoodId: String? = null,
    val status: String = STATUS_ACTIVE,
    val finishedAt: Long? = null,
    val discardedFraction: Float = 0f
) {
    val isWeighed: Boolean get() = cookedWeightG != null && cookedWeightG > 0f

    companion object {
        const val LOCATION_FRIDGE = "fridge"
        const val LOCATION_FREEZER = "freezer"
        const val STATUS_ACTIVE = "active"
        const val STATUS_FINISHED = "finished"
        const val STATUS_DISCARDED = "discarded"
        const val UNIT_PORTION = "portion"
    }
}

/** Links one diary entry to the batch it was scooped from. Deleting the entry gives the food back. */
@Entity(tableName = "batch_consumptions", indices = [Index(value = ["batchId"])])
data class BatchConsumption(
    @PrimaryKey val foodEntryId: String,
    val batchId: String,
    val portions: Float? = null,
    val grams: Float? = null,
    val fraction: Float,
    val loggedAt: Long = System.currentTimeMillis()
)

/** Remembers which food the user picked for an ingredient name so the next import maps it automatically. */
@Entity(tableName = "ingredient_aliases")
data class IngredientAlias(
    @PrimaryKey val normalizedName: String,
    val foodId: String,
    val foodName: String,
    val servingSize: Float,
    val servingUnit: String,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val gramsPerUnitJson: String = "{}",
    val updatedAt: Long = System.currentTimeMillis()
)
