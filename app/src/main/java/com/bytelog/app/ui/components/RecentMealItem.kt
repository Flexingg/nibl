package com.bytelog.app.ui.components

import com.bytelog.app.data.local.model.FoodEntry

/** A meal from recent days that can be copied into the day open in the Diary. */
data class RecentMealItem(
    val date: String,
    val displayDate: String,
    val mealName: String,
    val mealTypeId: String,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val foodSummary: String,
    val entries: List<FoodEntry>
)
