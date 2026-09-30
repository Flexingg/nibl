package com.bytelog.app.data.local.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "food_entries",
    indices = [
        Index(value = ["entryDate"]),
        Index(value = ["mealTypeId"]),
        Index(value = ["syncStatus"])
    ]
)
data class FoodEntry(
    @PrimaryKey
    val id: String,
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
    val entryDate: String, // YYYY-MM-DD
    val entryTime: String? = null, // HH:mm:ss
    val syncStatus: String = SYNC_STATUS_PENDING_INSERT,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SYNC_STATUS_SYNCED = "synced"
        const val SYNC_STATUS_PENDING_INSERT = "pending_insert"
        const val SYNC_STATUS_PENDING_UPDATE = "pending_update"
        const val SYNC_STATUS_PENDING_DELETE = "pending_delete"
    }
}
