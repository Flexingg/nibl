package com.bytelog.app.data.local.model

data class DailySummary(
    val date: String,
    val totalCalories: Float = 0f,
    val targetCalories: Float = 2200f,
    val totalProtein: Float = 0f,
    val targetProtein: Float = 160f,
    val totalCarbs: Float = 0f,
    val targetCarbs: Float = 220f,
    val totalFat: Float = 0f,
    val targetFat: Float = 70f
) {
    val remainingCalories: Float
        get() = (targetCalories - totalCalories).coerceAtLeast(0f)

    val calorieProgress: Float
        get() = if (targetCalories > 0f) (totalCalories / targetCalories).coerceIn(0f, 1f) else 0f

    val proteinProgress: Float
        get() = if (targetProtein > 0f) (totalProtein / targetProtein).coerceIn(0f, 1f) else 0f

    val carbsProgress: Float
        get() = if (targetCarbs > 0f) (totalCarbs / targetCarbs).coerceIn(0f, 1f) else 0f

    val fatProgress: Float
        get() = if (targetFat > 0f) (totalFat / targetFat).coerceIn(0f, 1f) else 0f
}
