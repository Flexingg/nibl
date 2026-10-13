package com.bytelog.app.widget

import android.content.Context
import com.bytelog.app.ByteLogApplication
import com.bytelog.app.data.repository.ML_PER_FL_OZ
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/** A person on the Family widget: calories today against their goal. */
data class FamilyPerson(val name: String, val kcal: Int, val goal: Int)

/** Everything the home screen widgets show, for one day. */
data class WidgetData(
    val date: String,
    val eaten: Int,
    val goal: Int,
    val protein: Int, val proteinGoal: Int,
    val carbs: Int, val carbsGoal: Int,
    val fat: Int, val fatGoal: Int,
    val waterOz: Float,
    val waterGoalOz: Float,
    /** What one tap on the water widget adds. */
    val bottleOz: Float,
    /** The meal that fits the time of day: highlighted in Quick log, offered for sharing. */
    val suggestedMeal: String,
    /** The user first, then family members who share their diary. */
    val family: List<FamilyPerson>
) {
    val left: Int get() = (goal - eaten).coerceAtLeast(0)

    companion object {
        /** Sample numbers for previews. */
        val Sample = WidgetData(
            date = "2026-10-13", eaten = 1020, goal = 1700,
            protein = 69, proteinGoal = 166, carbs = 94, carbsGoal = 140, fat = 29, fatGoal = 53,
            waterOz = 42f, waterGoalOz = 64f, bottleOz = 20f, suggestedMeal = "Lunch",
            family = listOf(FamilyPerson("You", 1020, 1700), FamilyPerson("Sam", 1420, 2400), FamilyPerson("Kim", 340, 2100), FamilyPerson("Alex", 1980, 2200))
        )
    }
}

/** Which meal fits the time of day. */
fun suggestedMealAt(time: LocalTime): String = when {
    time < LocalTime.of(10, 30) -> "Breakfast"
    time < LocalTime.of(15, 0) -> "Lunch"
    time < LocalTime.of(20, 30) -> "Dinner"
    else -> "Snack"
}

/** Family members' calories, kept from the last time the app (or the widget's own refresh) fetched them. */
object WidgetFamilyCache {
    private const val PREFS = "widget_family"
    private const val KEY = "people"
    private const val KEY_DATE = "date"

    fun save(context: Context, date: String, people: List<FamilyPerson>) {
        val arr = JSONArray()
        people.forEach { arr.put(JSONObject().put("name", it.name).put("kcal", it.kcal).put("goal", it.goal)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).putString(KEY_DATE, date).apply()
    }

    /** The cached people; their calories count only if they were fetched [today], else they show as not yet eaten. */
    fun load(context: Context, today: String): List<FamilyPerson> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val fresh = prefs.getString(KEY_DATE, null) == today
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                FamilyPerson(o.getString("name"), if (fresh) o.getInt("kcal") else 0, o.getInt("goal"))
            }
        }.getOrDefault(emptyList())
    }
}

object WidgetDataLoader {
    suspend fun load(app: ByteLogApplication, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): WidgetData {
        val date = now.toLocalDate().toString()
        val settings = app.settingsRepository
        val entries = app.database.foodEntryDao().getEntriesForDate(date).first()
        val waterMl = app.database.waterLogDao().getTotalWaterForDate(date).first()
        val goal = settings.targetCalories.first().roundToInt()
        val eaten = entries.sumOf { it.calories.toDouble() }.roundToInt()
        val others = WidgetFamilyCache.load(app, date)
        return WidgetData(
            date = date, eaten = eaten, goal = goal,
            protein = entries.sumOf { it.protein.toDouble() }.roundToInt(), proteinGoal = settings.targetProtein.first().roundToInt(),
            carbs = entries.sumOf { it.carbs.toDouble() }.roundToInt(), carbsGoal = settings.targetCarbs.first().roundToInt(),
            fat = entries.sumOf { it.fat.toDouble() }.roundToInt(), fatGoal = settings.targetFat.first().roundToInt(),
            waterOz = waterMl / ML_PER_FL_OZ, waterGoalOz = settings.targetWaterOz.first(),
            bottleOz = settings.defaultContainerOz.first(),
            suggestedMeal = suggestedMealAt(now.toLocalTime()),
            family = listOf(FamilyPerson("You", eaten, goal)) + others
        )
    }

    /** Fetches family members' calories from Sparky into the cache (up to three of them). */
    suspend fun refreshFamily(app: ByteLogApplication) {
        val today = LocalDate.now().toString()
        val members = app.familyRepository.members().getOrNull() ?: return
        val people = members.filter { it.canViewDiary }.take(3).mapNotNull { m ->
            app.familyRepository.memberDay(m, today).getOrNull()?.let { d ->
                FamilyPerson(m.displayName.substringBefore(' '), d.calories.roundToInt(), (d.goals?.calories ?: 0f).roundToInt())
            }
        }
        WidgetFamilyCache.save(app, today, people)
    }
}
