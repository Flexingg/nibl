package com.bytelog.app.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "weight_entries")
data class WeightEntry(
    @PrimaryKey
    val entryDate: String, // Format: YYYY-MM-DD
    val weight: Float,
    val unit: String = "lbs", // "lbs" or "kg"
    val bodyFatPercentage: Float? = null,
    val syncStatus: Int = SYNC_STATUS_PENDING,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SYNC_STATUS_PENDING = 0
        const val SYNC_STATUS_SYNCED = 1
    }
}
