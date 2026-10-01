package com.bytelog.app.data.analytics

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.WeightEntry
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class DayChartPoint(
    val date: String,
    val displayDate: String,
    val calories: Float,
    val actualWeight: Float?,
    val smoothedWeight: Float?,
    val targetCalories: Float,
    val isToday: Boolean
)

data class AdaptiveTdeeCalculation(
    val tdee: Float,
    val confidence: String, // "HIGH", "MEDIUM", "LOW"
    val weightTrend: Float?,
    val weightChangeRatePerWeek: Float?,
    val avgDailyIntake: Float?,
    val recommendedCalories: Float,
    val goalMode: String,
    val goalOffset: Float,
    val daysOfData: Int,
    val isFallback: Boolean,
    val explanation: String
)

data class AutoAdjustedMacros(
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float
)

object AdaptiveTdeeEngine {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val displayFormatter = DateTimeFormatter.ofPattern("MMM d")

    private const val ENERGY_DENSITY_KCAL_PER_KG = 7700.0f
    private const val LBS_TO_KG = 0.45359237f
    private const val KG_TO_LBS = 2.20462262f

    fun normalizeWeights(weights: List<WeightEntry>, targetUnit: String): List<WeightEntry> {
        val isLbs = targetUnit.equals("lbs", ignoreCase = true)
        return weights.filter { it.weight > 0f }.map { entry ->
            val entryUnit = entry.unit.ifBlank { targetUnit }
            val converted = if (entryUnit.equals("kg", ignoreCase = true) && isLbs) {
                (entry.weight * KG_TO_LBS * 10).toInt() / 10f
            } else if (entryUnit.equals("lbs", ignoreCase = true) && !isLbs) {
                (entry.weight * LBS_TO_KG * 10).toInt() / 10f
            } else {
                entry.weight
            }
            entry.copy(weight = converted, unit = targetUnit)
        }
    }

    /**
     * MacroFactor & Sparky-aligned Adaptive TDEE calculation.
     */
    fun calculate(
        allEntries: List<FoodEntry>,
        allWeights: List<WeightEntry>,
        targetDateStr: String,
        goalOffset: Float = -500f,
        goalMode: String = "cut",
        weightUnit: String = "lbs",
        fallbackTdee: Float = 2200f
    ): AdaptiveTdeeCalculation {
        val targetDate = try {
            LocalDate.parse(targetDateStr, dateFormatter)
        } catch (_: Exception) {
            LocalDate.now()
        }

        val windowDays = 28
        val startDate = targetDate.minusDays((windowDays - 1).toLong())

        // Map calories by date
        val caloriesByDate = allEntries.groupBy { it.entryDate }
            .mapValues { (_, entries) -> entries.sumOf { it.calories.toDouble() }.toFloat() }

        // Sort normalized weights by date
        val sortedWeights = normalizeWeights(allWeights, weightUnit).sortedBy { it.entryDate }

        // Collect dates in 28-day window
        val dateList = (0 until windowDays).map { startDate.plusDays(it.toLong()).format(dateFormatter) }

        // Interpolate weights for each day
        val interpolatedWeights = mutableMapOf<String, Float>()
        for (dStr in dateList) {
            val direct = sortedWeights.find { it.entryDate == dStr }
            if (direct != null) {
                interpolatedWeights[dStr] = direct.weight
            } else {
                val prev = sortedWeights.lastOrNull { it.entryDate < dStr }
                val next = sortedWeights.firstOrNull { it.entryDate > dStr }
                if (prev != null && next != null) {
                    val pDate = LocalDate.parse(prev.entryDate, dateFormatter).toEpochDay()
                    val nDate = LocalDate.parse(next.entryDate, dateFormatter).toEpochDay()
                    val curDate = LocalDate.parse(dStr, dateFormatter).toEpochDay()
                    val fraction = if (nDate > pDate) (curDate - pDate).toFloat() / (nDate - pDate) else 0.5f
                    interpolatedWeights[dStr] = prev.weight + (next.weight - prev.weight) * fraction
                } else if (prev != null) {
                    interpolatedWeights[dStr] = prev.weight
                } else if (next != null) {
                    interpolatedWeights[dStr] = next.weight
                }
            }
        }

        // 7-day Simple Moving Average (SMA) smoothing
        val smoothedWeights = mutableMapOf<String, Float>()
        for (i in 0 until windowDays) {
            val curDateStr = dateList[i]
            val windowStart = (i - 6).coerceAtLeast(0)
            val subList = (windowStart..i).mapNotNull { interpolatedWeights[dateList[it]] }
            if (subList.isNotEmpty()) {
                smoothedWeights[curDateStr] = subList.sum() / subList.size
            }
        }

        // Filter valid calorie logs (days with >= 200 kcal)
        val validIntakes = dateList.mapNotNull { dStr ->
            val cals = caloriesByDate[dStr] ?: 0f
            if (cals >= 200f) cals else null
        }
        val daysOfData = validIntakes.size

        val currentWeightTrend = smoothedWeights[targetDateStr] ?: sortedWeights.lastOrNull()?.weight
        val isLbs = weightUnit.equals("lbs", ignoreCase = true)

        if (daysOfData < 7 || sortedWeights.size < 2) {
            val neededDays = (7 - daysOfData).coerceAtLeast(0)
            val explanation = if (daysOfData < 7) {
                "Calibrating: log $neededDays more days with >= 200 kcal for dynamic expenditure."
            } else {
                "Calibrating: record at least 2 weigh-ins to calculate weight trend."
            }
            val recCals = (fallbackTdee + goalOffset).coerceAtLeast(1200f)
            return AdaptiveTdeeCalculation(
                tdee = fallbackTdee,
                confidence = "LOW",
                weightTrend = currentWeightTrend,
                weightChangeRatePerWeek = null,
                avgDailyIntake = if (validIntakes.isNotEmpty()) validIntakes.sum() / validIntakes.size else null,
                recommendedCalories = recCals,
                goalMode = goalMode,
                goalOffset = goalOffset,
                daysOfData = daysOfData,
                isFallback = true,
                explanation = explanation
            )
        }

        val avgDailyIntake = validIntakes.sum() / validIntakes.size
        val startTrend = smoothedWeights[dateList.first()] ?: currentWeightTrend ?: 70f
        val endTrend = smoothedWeights[dateList.last()] ?: currentWeightTrend ?: 70f
        val deltaWeight = endTrend - startTrend

        val deltaWeightKg = if (isLbs) deltaWeight * LBS_TO_KG else deltaWeight
        val dailyDeltaKg = deltaWeightKg / windowDays
        val weeklyDeltaWeight = deltaWeight * (7.0f / windowDays)

        // Energy gap
        val weightChangeCalories = dailyDeltaKg * ENERGY_DENSITY_KCAL_PER_KG
        val rawTdee = avgDailyIntake - weightChangeCalories

        // Plausibility clamp
        val clampedTdee = rawTdee.coerceIn((fallbackTdee - 800f).coerceAtLeast(1200f), fallbackTdee + 800f)
            .coerceIn(1200f, 4500f)

        val confidence = when {
            daysOfData >= 21 && sortedWeights.size >= 8 -> "HIGH"
            daysOfData >= 14 && sortedWeights.size >= 4 -> "MEDIUM"
            else -> "LOW"
        }

        val recommendedCalories = (clampedTdee + goalOffset).coerceAtLeast(1200f)

        val explanation = when {
            confidence == "HIGH" -> "High confidence: based on $daysOfData days of nutrition and weight trend."
            confidence == "MEDIUM" -> "Medium confidence: adapting steadily with $daysOfData days of data."
            else -> "Calibrating: adapting to your metabolism with $daysOfData days of logs."
        }

        return AdaptiveTdeeCalculation(
            tdee = clampedTdee,
            confidence = confidence,
            weightTrend = currentWeightTrend,
            weightChangeRatePerWeek = weeklyDeltaWeight,
            avgDailyIntake = avgDailyIntake,
            recommendedCalories = recommendedCalories,
            goalMode = goalMode,
            goalOffset = goalOffset,
            daysOfData = daysOfData,
            isFallback = false,
            explanation = explanation
        )
    }

    /**
     * Generates points for the Weight vs Calories Chart over any range (7, 14, 30, 90 days).
     */
    fun buildChartPoints(
        allEntries: List<FoodEntry>,
        allWeights: List<WeightEntry>,
        rangeDays: Int = 30,
        targetDateStr: String,
        targetCalories: Float = 2200f,
        weightUnit: String = "lbs"
    ): List<DayChartPoint> {
        val targetDate = try {
            LocalDate.parse(targetDateStr, dateFormatter)
        } catch (_: Exception) {
            LocalDate.now()
        }

        val startDate = targetDate.minusDays((rangeDays - 1).toLong())
        val caloriesByDate = allEntries.groupBy { it.entryDate }
            .mapValues { (_, entries) -> entries.sumOf { it.calories.toDouble() }.toFloat() }

        val sortedWeights = normalizeWeights(allWeights, weightUnit).sortedBy { it.entryDate }
        val weightsByDate = sortedWeights.associateBy { it.entryDate }

        val dateList = (0 until rangeDays).map { startDate.plusDays(it.toLong()) }

        val interpolated = mutableMapOf<String, Float>()
        for (date in dateList) {
            val dStr = date.format(dateFormatter)
            val direct = weightsByDate[dStr]?.weight
            if (direct != null) {
                interpolated[dStr] = direct
            } else {
                val prev = sortedWeights.lastOrNull { it.entryDate < dStr }?.weight
                val next = sortedWeights.firstOrNull { it.entryDate > dStr }?.weight
                if (prev != null && next != null) {
                    interpolated[dStr] = (prev + next) / 2f
                } else if (prev != null) {
                    interpolated[dStr] = prev
                } else if (next != null) {
                    interpolated[dStr] = next
                }
            }
        }

        val smoothed = mutableMapOf<String, Float>()
        for (i in 0 until rangeDays) {
            val dStr = dateList[i].format(dateFormatter)
            val windowStart = (i - 6).coerceAtLeast(0)
            val sub = (windowStart..i).mapNotNull { interpolated[dateList[it].format(dateFormatter)] }
            if (sub.isNotEmpty()) {
                smoothed[dStr] = sub.sum() / sub.size
            }
        }

        val todayStr = LocalDate.now().format(dateFormatter)

        return dateList.map { date ->
            val dStr = date.format(dateFormatter)
            val disp = date.format(displayFormatter)
            val cal = caloriesByDate[dStr] ?: 0f
            val actWeight = weightsByDate[dStr]?.weight
            val smWeight = smoothed[dStr] ?: actWeight

            DayChartPoint(
                date = dStr,
                displayDate = disp,
                calories = cal,
                actualWeight = actWeight,
                smoothedWeight = smWeight,
                targetCalories = targetCalories,
                isToday = dStr == todayStr
            )
        }
    }

    /**
     * Computes macro distribution for a calorie target (MacroFactor style).
     */
    fun computeMacros(calories: Float, weightKg: Float = 75f): AutoAdjustedMacros {
        // High protein: ~2.0g per kg of bodyweight
        val proteinGrams = (weightKg * 2.0f).coerceIn(120f, 240f)
        val proteinCals = proteinGrams * 4f

        // Fat: ~28% of total calories
        val fatCals = (calories * 0.28f).coerceAtLeast(400f)
        val fatGrams = fatCals / 9f

        // Carbs: remaining calories
        val remainingCals = (calories - proteinCals - fatCals).coerceAtLeast(200f)
        val carbsGrams = remainingCals / 4f

        return AutoAdjustedMacros(
            calories = calories,
            protein = proteinGrams,
            carbs = carbsGrams,
            fat = fatGrams
        )
    }
}
