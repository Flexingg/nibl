package com.bytelog.app.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "meal_types")
data class MealType(
    @PrimaryKey
    val id: String,
    val name: String,
    val sortOrder: Int,
    val defaultHour: Int = 12
) {
    companion object {
        val DEFAULT_MEAL_TYPES = listOf(
            MealType(id = "breakfast", name = "Breakfast", sortOrder = 1, defaultHour = 8),
            MealType(id = "lunch", name = "Lunch", sortOrder = 2, defaultHour = 12),
            MealType(id = "dinner", name = "Dinner", sortOrder = 3, defaultHour = 18),
            MealType(id = "snacks", name = "Snacks", sortOrder = 4, defaultHour = 15)
        )
    }
}
