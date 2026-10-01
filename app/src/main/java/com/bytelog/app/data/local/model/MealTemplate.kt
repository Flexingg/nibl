package com.bytelog.app.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
data class TemplateIngredient(
    val foodId: String,
    val foodName: String,
    val quantity: Float,
    val unit: String,
    val calories: Float = 0f,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f
)

@Serializable
@Entity(tableName = "meal_templates")
data class MealTemplate(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String? = null,
    val servingSize: Float = 1f,
    val servingUnit: String = "serving",
    val totalServings: Float = 1f,
    val calories: Float = 0f,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f,
    val foodsJson: String = "[]",
    val isPublic: Boolean = false,
    val createdAt: String? = null,
    val syncStatus: Int = SYNC_STATUS_SYNCED
) {
    companion object {
        const val SYNC_STATUS_PENDING = 0
        const val SYNC_STATUS_SYNCED = 1
    }
}
