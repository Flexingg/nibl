package com.bytelog.app.data.local.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Food planned for a day ahead of eating it. It doesn't count towards the day and isn't sent to Sparky until it's
 * ticked off, when it becomes an ordinary [FoodEntry].
 */
@Entity(tableName = "planned_entries", indices = [Index(value = ["entryDate"])])
data class PlannedEntry(
    @PrimaryKey val id: String,
    val foodId: String? = null,
    val foodName: String,
    val brandName: String? = null,
    val mealTypeId: String,
    val mealName: String,
    val quantity: Float,
    val unit: String = "g",
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val entryDate: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** The diary entry it becomes when eaten: on the day it was planned for, logged now. */
    fun toEntry(): FoodEntry = FoodEntry(
        id = java.util.UUID.randomUUID().toString(), foodId = foodId, foodName = foodName, brandName = brandName,
        mealTypeId = mealTypeId, mealName = mealName, quantity = quantity, unit = unit,
        calories = calories, protein = protein, carbs = carbs, fat = fat, entryDate = entryDate,
        syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT
    )

    companion object {
        fun from(e: FoodEntry) = PlannedEntry(
            id = e.id, foodId = e.foodId, foodName = e.foodName, brandName = e.brandName, mealTypeId = e.mealTypeId,
            mealName = e.mealName, quantity = e.quantity, unit = e.unit, calories = e.calories, protein = e.protein,
            carbs = e.carbs, fat = e.fat, entryDate = e.entryDate
        )
    }
}
