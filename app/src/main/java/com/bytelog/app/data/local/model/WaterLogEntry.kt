package com.bytelog.app.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "water_log_entries")
data class WaterLogEntry(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val entryDate: String,            // "YYYY-MM-DD"
    val waterMl: Float,
    val containerId: Int? = null,
    val containerName: String? = null,
    val loggedAt: String? = null,     // ISO or formatted timestamp e.g. "2026-09-30T14:30:00Z"
    val syncStatus: Int = SYNC_STATUS_SYNCED
) {
    companion object {
        const val SYNC_STATUS_SYNCED = 0
        const val SYNC_STATUS_PENDING_INSERT = 1
        const val SYNC_STATUS_PENDING_DELETE = 2
    }
}
