package com.bytelog.app.data.reports

import com.bytelog.app.data.analytics.AdaptiveTdeeCalculation
import com.bytelog.app.data.analytics.AdaptiveTdeeEngine
import com.bytelog.app.data.local.db.FoodEntryDao
import com.bytelog.app.data.local.db.WaterLogDao
import com.bytelog.app.data.local.db.WeightDao
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.health.DailyHealthSnapshot
import com.bytelog.app.health.HealthConnectExtendedSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

enum class CompareMetric(val apiKey: String, val label: String, val unit: String) {
    CALORIES("calories", "Calories", "kcal"),
    PROTEIN("protein", "Protein", "g"),
    WEIGHT("weight", "Weight", ""),
    WATER("water", "Water", "ml"),
    CAFFEINE("caffeine", "Caffeine", "mg"),
    WORKOUT_VOLUME("workout_volume", "Lifting Vol", ""),
    STEPS("steps", "Steps", "steps"),
    SLEEP("sleep", "Sleep", "hrs")
}

data class DailyMacroPoint(
    val date: String,
    val label: String,
    val calories: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double
)

data class NutritionReportData(
    val days: List<DailyMacroPoint>,
    val targetCalories: Double,
    val avgCalories: Double,
    val avgProtein: Double,
    val avgCarbs: Double,
    val avgFat: Double,
    val goalAdherencePct: Double,
    val daysLogged: Int
)

data class MicronutrientTrend(
    val def: MicronutrientDef,
    val dailyAvg: Double,
    val dailyValues: List<Pair<String, Double>>
)

data class HydrationReportData(
    val dailyWaterMl: List<Pair<String, Double>>,
    val goalMl: Double,
    val avgMl: Double,
    val currentStreakDays: Int,
    val daysMetGoal: Int
)

data class WeightTrendPoint(
    val date: String,
    val label: String,
    val actualWeight: Float?,
    val smoothedWeight: Float?,
    val bodyFatPct: Float?
)

data class WeightTdeeReportData(
    val points: List<WeightTrendPoint>,
    val weightUnit: String,
    val deltaWeight: Float?,
    val weeklyRate: Float?,
    val latestWeight: Float?,
    val latestBodyFatPct: Float?,
    val adaptiveTdee: AdaptiveTdeeCalculation?
)

data class CaffeineReportData(
    val dailyCaffeineMg: List<Pair<String, Double>>,
    val avgMg: Double,
    val peakMg: Double,
    val peakDate: String?
)

data class FastingReportData(
    val dailyHours: List<Pair<String, Double>>,
    val completedCount: Int,
    val avgDurationHours: Double
)

data class WorkoutsReportData(
    val totalWorkouts: Int = 0,
    val totalVolume: Double = 0.0,
    val totalReps: Int = 0,
    val totalSets: Int = 0,
    val totalDurationMinutes: Int = 0,
    val totalCaloriesBurned: Double = 0.0,
    val dailyVolume: List<Pair<String, Double>> = emptyList(),
    val dailySessions: List<Pair<String, Int>> = emptyList(),
    val muscleGroups: List<MuscleGroupStat> = emptyList(),
    val topPrs: List<WorkoutPrItem> = emptyList()
)

data class ActivitySleepReportData(
    val dailySnapshots: List<DailyHealthSnapshot>,
    val avgSteps: Long,
    val avgActiveCalories: Double,
    val avgSleepMinutes: Int,
    val avgRestingHr: Int?
)

data class ReportsSnapshot(
    val range: ReportRange,
    val startDate: String,
    val endDate: String,
    val isOfflineFallback: Boolean,
    val nutrition: NutritionReportData,
    val micronutrients: Map<String, MicronutrientTrend>,
    val hydration: HydrationReportData,
    val weightTdee: WeightTdeeReportData,
    val caffeine: CaffeineReportData,
    val fasting: FastingReportData,
    val workouts: WorkoutsReportData,
    val activitySleep: ActivitySleepReportData
)

data class CompareResult(
    val metricA: CompareMetric,
    val metricB: CompareMetric,
    val points: List<ComparePoint>,
    val correlation: Double?,
    val summary: String,
    val isLocalFallback: Boolean
)

/**
 * Combines SparkyFitness [ReportsApi] with local Room DAOs ([FoodEntryDao], [WaterLogDao], [WeightDao])
 * and [HealthConnectExtendedSync.snapshots] so every report section works online and offline.
 */
class ReportsRepository(
    private val reportsApi: ReportsApi,
    private val foodEntryDao: FoodEntryDao,
    private val waterLogDao: WaterLogDao,
    private val weightDao: WeightDao,
    private val settingsRepository: SettingsRepository,
    private val healthExtendedSync: HealthConnectExtendedSync
) {
    companion object {
        private const val OZ_TO_ML = 29.5735
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val LABEL_FMT = DateTimeFormatter.ofPattern("MMM d", Locale.US)

        /**
         * Computes Pearson correlation coefficient `r` in [-1.0, 1.0], or null if < 3 paired points
         * or zero variance.
         */
        fun pearsonCorrelation(xs: List<Double>, ys: List<Double>): Double? {
            if (xs.size != ys.size || xs.size < 3) return null
            val n = xs.size.toDouble()
            val meanX = xs.sum() / n
            val meanY = ys.sum() / n
            var num = 0.0
            var denX = 0.0
            var denY = 0.0
            for (i in xs.indices) {
                val dx = xs[i] - meanX
                val dy = ys[i] - meanY
                num += dx * dy
                denX += dx * dx
                denY += dy * dy
            }
            val denom = sqrt(denX * denY)
            if (denom <= 1e-9) return null
            return (num / denom).coerceIn(-1.0, 1.0)
        }

        fun describeCorrelation(r: Double?, metricA: CompareMetric, metricB: CompareMetric): String {
            if (r == null) return "Need at least 3 overlapping days to compute correlation."
            val absR = abs(r)
            val strength = when {
                absR >= 0.7 -> "Strong"
                absR >= 0.4 -> "Moderate"
                absR >= 0.2 -> "Weak"
                else -> "Little to no"
            }
            val dir = when {
                absR < 0.2 -> ""
                r > 0 -> " positive"
                else -> " negative"
            }
            val rFmt = String.format(Locale.US, "%.2f", r)
            return "$strength$dir correlation (r = $rFmt) between ${metricA.label} and ${metricB.label}."
        }
    }

    /** The exercises the user has logged on Sparky, for the Exercise Progress picker. */
    suspend fun loggedExercises(): Result<List<LoggedExercise>> = reportsApi.getLoggedExercises()

    /** One exercise's progress over [range], grouped by day, week or month. */
    suspend fun loadExerciseProgress(exercise: LoggedExercise, range: ReportRange, grouping: ProgressGrouping): Result<ExerciseProgressReport> =
        withContext(Dispatchers.IO) {
            val today = LocalDate.now()
            val start = today.minusDays((range.days - 1).toLong())
            reportsApi.getExerciseProgress(exercise.id, start.format(DATE_FMT), today.format(DATE_FMT))
                .map { ExerciseProgress.report(exercise, it, grouping) }
        }

    suspend fun weightUnit(): String = settingsRepository.weightUnit.first()

    suspend fun loadReport(range: ReportRange): ReportsSnapshot = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val start = today.minusDays((range.days - 1).toLong())
        val startDate = start.format(DATE_FMT)
        val endDate = today.format(DATE_FMT)
        val dateList = (0 until range.days).map { start.plusDays(it.toLong()) }
        val dateStrings = dateList.map { it.format(DATE_FMT) }

        // Read settings & local Room data + fetch Sparky APIs in parallel
        coroutineScope {
            val remoteNutritionDeferred = async { reportsApi.getMiniNutritionTrends(startDate, endDate).getOrNull() }
            val remoteHydrationDeferred = async { reportsApi.getHydrationNutritionRange(startDate, endDate).getOrNull() }
            val remoteFastingDeferred = async { reportsApi.getFastingRange(startDate, endDate).getOrNull() }
            val remoteWorkoutsDeferred = async { reportsApi.getExerciseDashboard(startDate, endDate).getOrNull() }

            val localEntriesDeferred = async { foodEntryDao.getEntriesInRange(startDate, endDate).first() }
            val allLocalEntriesDeferred = async { foodEntryDao.getAllEntries().first() }
            val allWeightsDeferred = async { weightDao.getAllWeightsList() }
            val localWaterByDateDeferred = async {
                dateStrings.associateWith { d ->
                    runCatching { waterLogDao.getTotalWaterForDate(d).first().toDouble() }.getOrDefault(0.0)
                }
            }

            val targetCal = settingsRepository.targetCalories.first().toDouble()
            val targetWaterOz = settingsRepository.targetWaterOz.first().toDouble()
            val targetWaterMl = targetWaterOz * OZ_TO_ML
            val weightUnit = settingsRepository.weightUnit.first()
            val goalMode = settingsRepository.calorieGoalMode.first()
            val goalOffset = settingsRepository.calorieGoalOffset.first()

            val remoteNutrition = remoteNutritionDeferred.await()
            val remoteHydration = remoteHydrationDeferred.await()
            val remoteFasting = remoteFastingDeferred.await()
            val remoteWorkouts = remoteWorkoutsDeferred.await()
            val localEntries = localEntriesDeferred.await()
            val allLocalEntries = allLocalEntriesDeferred.await()
            val allWeights = allWeightsDeferred.await()
            val localWaterByDate = localWaterByDateDeferred.await()

            val isOfflineFallback = remoteNutrition == null && remoteHydration == null && remoteFasting == null && remoteWorkouts == null

            // 1. Nutrition & Macros (merge remote + local Room)
            val remoteNutByDate = remoteNutrition.orEmpty().associateBy { it.date }
            val localByDate = localEntries.groupBy { it.entryDate }

            val macroDays = dateList.map { d ->
                val ds = d.format(DATE_FMT)
                val remote = remoteNutByDate[ds]
                val localList = localByDate[ds].orEmpty()
                val localCal = localList.sumOf { it.calories.toDouble() }
                val localP = localList.sumOf { it.protein.toDouble() }
                val localC = localList.sumOf { it.carbs.toDouble() }
                val localF = localList.sumOf { it.fat.toDouble() }

                val useLocal = localList.isNotEmpty() && (remote == null || remote.calories <= 0.0)
                DailyMacroPoint(
                    date = ds,
                    label = d.format(LABEL_FMT),
                    calories = if (useLocal) localCal else (remote?.calories ?: localCal),
                    protein = if (useLocal) localP else (remote?.protein ?: localP),
                    carbs = if (useLocal) localC else (remote?.carbs ?: localC),
                    fat = if (useLocal) localF else (remote?.fat ?: localF)
                )
            }

            val loggedMacroDays = macroDays.filter { it.calories >= 100.0 }
            val avgCal = if (loggedMacroDays.isNotEmpty()) loggedMacroDays.map { it.calories }.average() else 0.0
            val avgP = if (loggedMacroDays.isNotEmpty()) loggedMacroDays.map { it.protein }.average() else 0.0
            val avgC = if (loggedMacroDays.isNotEmpty()) loggedMacroDays.map { it.carbs }.average() else 0.0
            val avgF = if (loggedMacroDays.isNotEmpty()) loggedMacroDays.map { it.fat }.average() else 0.0
            val adherentDays = loggedMacroDays.count {
                targetCal > 0 && abs(it.calories - targetCal) / targetCal <= 0.12
            }
            val adherencePct = if (loggedMacroDays.isNotEmpty()) (adherentDays * 100.0) / loggedMacroDays.size else 0.0

            val nutritionData = NutritionReportData(
                days = macroDays,
                targetCalories = targetCal,
                avgCalories = avgCal,
                avgProtein = avgP,
                avgCarbs = avgC,
                avgFat = avgF,
                goalAdherencePct = adherencePct,
                daysLogged = loggedMacroDays.size
            )

            // 2. Micronutrients (from remote mini-nutrition-trends)
            val microMap = MicronutrientCatalog.ALL.associate { def ->
                val dailyValues = dateStrings.map { ds ->
                    val r = remoteNutByDate[ds]
                    val v = when (def.key) {
                        "fiber" -> r?.fiber ?: 0.0
                        "sodium" -> r?.sodium ?: 0.0
                        "sugars" -> r?.sugars ?: 0.0
                        "saturated_fat" -> r?.saturatedFat ?: 0.0
                        "potassium" -> r?.potassium ?: 0.0
                        "cholesterol" -> r?.cholesterol ?: 0.0
                        "calcium" -> r?.calcium ?: 0.0
                        "iron" -> r?.iron ?: 0.0
                        "vitamin_a" -> r?.vitaminA ?: 0.0
                        "vitamin_c" -> r?.vitaminC ?: 0.0
                        else -> 0.0
                    }
                    ds to v
                }
                val nonZero = dailyValues.map { it.second }.filter { it > 0.0 }
                val avg = if (nonZero.isNotEmpty()) nonZero.average() else 0.0
                def.key to MicronutrientTrend(def = def, dailyAvg = avg, dailyValues = dailyValues)
            }

            // 3. Hydration (combine remote hydration-nutrition-range + remote mini-trends + local Room WaterLogDao)
            val remoteHydByDate = remoteHydration.orEmpty().associateBy { it.date }
            val resolvedWaterGoalMl = remoteHydration?.lastOrNull { (it.waterGoalMl ?: 0.0) > 0.0 }?.waterGoalMl
                ?: targetWaterMl
            val dailyWater = dateStrings.map { ds ->
                val localW = localWaterByDate[ds] ?: 0.0
                val remoteW = remoteHydByDate[ds]?.waterMl ?: remoteNutByDate[ds]?.waterMl ?: 0.0
                ds to maxOf(localW, remoteW)
            }
            val loggedWater = dailyWater.map { it.second }.filter { it > 0.0 }
            val avgWaterMl = if (loggedWater.isNotEmpty()) loggedWater.average() else 0.0
            val daysMetWater = dailyWater.count { it.second >= resolvedWaterGoalMl * 0.95 && resolvedWaterGoalMl > 0 }
            var waterStreak = 0
            for ((_, ml) in dailyWater.asReversed()) {
                if (resolvedWaterGoalMl > 0 && ml >= resolvedWaterGoalMl * 0.95) waterStreak++ else break
            }
            val hydrationData = HydrationReportData(
                dailyWaterMl = dailyWater,
                goalMl = resolvedWaterGoalMl,
                avgMl = avgWaterMl,
                currentStreakDays = waterStreak,
                daysMetGoal = daysMetWater
            )

            // 4. Weight & TDEE (local WeightDao + AdaptiveTdeeEngine + Health Connect body fat fallback)
            val hcMap = healthExtendedSync.snapshots.value
            val chartPoints = AdaptiveTdeeEngine.buildChartPoints(
                allEntries = allLocalEntries,
                allWeights = allWeights,
                rangeDays = range.days,
                targetDateStr = endDate,
                targetCalories = targetCal.toFloat(),
                weightUnit = weightUnit
            )
            val normalizedWeightsByDate = AdaptiveTdeeEngine.normalizeWeights(allWeights, weightUnit)
                .associateBy { it.entryDate }

            val weightPoints = chartPoints.map { cp ->
                val bf = normalizedWeightsByDate[cp.date]?.bodyFatPercentage
                    ?: hcMap[cp.date]?.latestBodyFatPct?.toFloat()
                WeightTrendPoint(
                    date = cp.date,
                    label = cp.displayDate,
                    actualWeight = cp.actualWeight,
                    smoothedWeight = cp.smoothedWeight,
                    bodyFatPct = bf
                )
            }
            val smoothedValues = weightPoints.mapNotNull { it.smoothedWeight ?: it.actualWeight }
            val deltaW = if (smoothedValues.size >= 2) smoothedValues.last() - smoothedValues.first() else null
            val weeklyRate = deltaW?.let { it * (7f / range.days.coerceAtLeast(1)) }
            val latestW = weightPoints.lastOrNull { it.actualWeight != null }?.actualWeight
                ?: smoothedValues.lastOrNull()
            val latestBf = weightPoints.lastOrNull { it.bodyFatPct != null }?.bodyFatPct

            val tdeeCalc = AdaptiveTdeeEngine.calculate(
                allEntries = allLocalEntries,
                allWeights = allWeights,
                targetDateStr = endDate,
                goalOffset = goalOffset,
                goalMode = goalMode,
                weightUnit = weightUnit,
                fallbackTdee = targetCal.toFloat().coerceAtLeast(1600f)
            )
            val weightTdeeData = WeightTdeeReportData(
                points = weightPoints,
                weightUnit = weightUnit,
                deltaWeight = deltaW,
                weeklyRate = weeklyRate,
                latestWeight = latestW,
                latestBodyFatPct = latestBf,
                adaptiveTdee = tdeeCalc
            )

            // 5. Caffeine
            val dailyCaffeine = dateStrings.map { ds -> ds to (remoteNutByDate[ds]?.caffeineMg ?: 0.0) }
            val nonZeroCaf = dailyCaffeine.filter { it.second > 0.0 }
            val avgCaf = if (nonZeroCaf.isNotEmpty()) nonZeroCaf.map { it.second }.average() else 0.0
            val peakCafEntry = nonZeroCaf.maxByOrNull { it.second }
            val caffeineData = CaffeineReportData(
                dailyCaffeineMg = dailyCaffeine,
                avgMg = avgCaf,
                peakMg = peakCafEntry?.second ?: 0.0,
                peakDate = peakCafEntry?.first
            )

            // 6. Fasting
            val fasts = remoteFasting.orEmpty()
            val fastHoursByDate = fasts.groupBy { it.date }
                .mapValues { (_, list) -> list.sumOf { it.durationHours } }
            val dailyFastingHours = dateStrings.map { ds -> ds to (fastHoursByDate[ds] ?: 0.0) }
            val completedFasts = fasts.filter { it.completed }
            val avgFastHrs = if (completedFasts.isNotEmpty()) {
                completedFasts.map { it.durationHours }.average()
            } else if (fasts.isNotEmpty()) {
                fasts.map { it.durationHours }.average()
            } else {
                0.0
            }
            val fastingData = FastingReportData(
                dailyHours = dailyFastingHours,
                completedCount = completedFasts.size,
                avgDurationHours = avgFastHrs
            )

            // 6b. Lifting & Workouts
            val workoutsByDate = remoteWorkouts?.dailyPoints.orEmpty().associateBy { it.date }
            val workoutsData = WorkoutsReportData(
                totalWorkouts = remoteWorkouts?.totalWorkouts ?: 0,
                totalVolume = remoteWorkouts?.totalVolume ?: 0.0,
                totalReps = remoteWorkouts?.totalReps ?: 0,
                totalSets = remoteWorkouts?.totalSets ?: 0,
                totalDurationMinutes = remoteWorkouts?.totalDurationMinutes ?: 0,
                totalCaloriesBurned = remoteWorkouts?.totalCaloriesBurned ?: 0.0,
                dailyVolume = dateStrings.map { ds -> ds to (workoutsByDate[ds]?.volume ?: 0.0) },
                dailySessions = dateStrings.map { ds -> ds to (workoutsByDate[ds]?.sessions ?: 0) },
                muscleGroups = remoteWorkouts?.muscleGroups.orEmpty(),
                topPrs = remoteWorkouts?.topPrs.orEmpty()
            )

            // 7. Activity & Sleep (Health Connect local snapshot cache)
            val dailyHc = dateStrings.map { ds -> hcMap[ds] ?: DailyHealthSnapshot(date = ds) }
            val stepDays = dailyHc.filter { it.steps > 0L }
            val activeCalDays = dailyHc.filter { it.activeCaloriesKcal > 0.0 }
            val sleepDays = dailyHc.filter { it.sleepMinutes > 0 }
            val rhrDays = dailyHc.mapNotNull { it.restingHrBpm }

            val activitySleepData = ActivitySleepReportData(
                dailySnapshots = dailyHc,
                avgSteps = if (stepDays.isNotEmpty()) stepDays.map { it.steps }.average().toLong() else 0L,
                avgActiveCalories = if (activeCalDays.isNotEmpty()) activeCalDays.map { it.activeCaloriesKcal }.average() else 0.0,
                avgSleepMinutes = if (sleepDays.isNotEmpty()) sleepDays.map { it.sleepMinutes }.average().toInt() else 0,
                avgRestingHr = if (rhrDays.isNotEmpty()) rhrDays.average().toInt() else null
            )

            ReportsSnapshot(
                range = range,
                startDate = startDate,
                endDate = endDate,
                isOfflineFallback = isOfflineFallback,
                nutrition = nutritionData,
                micronutrients = microMap,
                hydration = hydrationData,
                weightTdee = weightTdeeData,
                caffeine = caffeineData,
                fasting = fastingData,
                workouts = workoutsData,
                activitySleep = activitySleepData
            )
        }
    }

    suspend fun loadCompare(
        metricA: CompareMetric,
        metricB: CompareMetric,
        range: ReportRange,
        cachedSnapshot: ReportsSnapshot?
    ): CompareResult = withContext(Dispatchers.IO) {
        val snapshot = cachedSnapshot ?: loadReport(range)
        val usesLocalOnly = metricA == CompareMetric.STEPS || metricA == CompareMetric.SLEEP ||
            metricA == CompareMetric.WORKOUT_VOLUME ||
            metricB == CompareMetric.STEPS || metricB == CompareMetric.SLEEP ||
            metricB == CompareMetric.WORKOUT_VOLUME

        if (!usesLocalOnly) {
            val remote = reportsApi.getCompareReport(
                metricA = metricA.apiKey,
                metricB = metricB.apiKey,
                startDate = snapshot.startDate,
                endDate = snapshot.endDate
            ).getOrNull()

            if (remote != null && remote.points.size >= 2) {
                val paired = remote.points.filter { it.valueA != null && it.valueB != null }
                val r = remote.correlation ?: pearsonCorrelation(
                    paired.map { it.valueA!! },
                    paired.map { it.valueB!! }
                )
                return@withContext CompareResult(
                    metricA = metricA,
                    metricB = metricB,
                    points = remote.points,
                    correlation = r,
                    summary = describeCorrelation(r, metricA, metricB),
                    isLocalFallback = false
                )
            }
        }

        // Local fallback using snapshot series
        val seriesA = extractMetricSeries(metricA, snapshot)
        val seriesB = extractMetricSeries(metricB, snapshot)
        val dates = snapshot.nutrition.days.map { it.date }
        val points = dates.map { d ->
            ComparePoint(
                date = d,
                valueA = seriesA[d],
                valueB = seriesB[d]
            )
        }
        val paired = points.filter { it.valueA != null && it.valueB != null }
        val r = pearsonCorrelation(paired.map { it.valueA!! }, paired.map { it.valueB!! })
        CompareResult(
            metricA = metricA,
            metricB = metricB,
            points = points,
            correlation = r,
            summary = describeCorrelation(r, metricA, metricB),
            isLocalFallback = true
        )
    }

    private fun extractMetricSeries(metric: CompareMetric, s: ReportsSnapshot): Map<String, Double?> = when (metric) {
        CompareMetric.CALORIES -> s.nutrition.days.associate { it.date to it.calories.takeIf { v -> v > 0.0 } }
        CompareMetric.PROTEIN -> s.nutrition.days.associate { it.date to it.protein.takeIf { v -> v > 0.0 } }
        CompareMetric.WEIGHT -> s.weightTdee.points.associate { it.date to (it.actualWeight ?: it.smoothedWeight)?.toDouble() }
        CompareMetric.WATER -> s.hydration.dailyWaterMl.associate { (d, v) -> d to v.takeIf { it > 0.0 } }
        CompareMetric.CAFFEINE -> s.caffeine.dailyCaffeineMg.associate { (d, v) -> d to v.takeIf { it > 0.0 } }
        CompareMetric.WORKOUT_VOLUME -> s.workouts.dailyVolume.associate { (d, v) -> d to v.takeIf { it > 0.0 } }
        CompareMetric.STEPS -> s.activitySleep.dailySnapshots.associate { it.date to it.steps.takeIf { v -> v > 0L }?.toDouble() }
        CompareMetric.SLEEP -> s.activitySleep.dailySnapshots.associate { it.date to it.sleepMinutes.takeIf { v -> v > 0 }?.div(60.0) }
    }
}
