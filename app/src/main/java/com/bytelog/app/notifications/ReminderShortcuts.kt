package com.bytelog.app.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bytelog.app.ByteLogApplication
import com.bytelog.app.R
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.MealPatterns
import com.bytelog.app.data.repository.MealShortcuts
import com.bytelog.app.ui.viewmodel.RecipesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * Buttons on the logging reminder that log the meal it is time for without opening the app: "Log usual dinner"
 * and "Same as yesterday", when there is something to repeat and that meal isn't logged yet today.
 */
object ReminderShortcuts {
    const val ACTION_LOG = "com.bytelog.app.ACTION_REMINDER_LOG"
    const val EXTRA_KIND = "kind"
    const val EXTRA_MEAL_ID = "meal_id"
    const val EXTRA_MEAL_NAME = "meal_name"
    const val KIND_USUAL = "usual"
    const val KIND_YESTERDAY = "yesterday"

    /** The meal it is time for, and what could be repeated for it today (null when it is already logged). */
    suspend fun forNow(repo: FoodRepository, today: LocalDate = LocalDate.now(), now: LocalTime = LocalTime.now()): Pair<MealType, MealShortcuts>? {
        val types = repo.getAllMealTypes().first().ifEmpty { MealType.DEFAULT_MEAL_TYPES }
        val meal = RecipesViewModel.mealForNow(types, now) ?: return null
        if (repo.getEntriesForDate(today.toString()).first().any { MealPatterns.sameMeal(it.mealName, meal.name) }) return null
        val sc = MealShortcuts.of(repo.mealHistory(today).first(), meal.name, today)
        return if (sc.isEmpty) null else meal to sc
    }

    fun actions(context: Context, meal: MealType, sc: MealShortcuts): List<NotificationCompat.Action> {
        val name = displayName(meal.name)
        val out = mutableListOf<NotificationCompat.Action>()
        if (sc.showUsual) out += action(context, KIND_USUAL, meal, "Log usual ${name.lowercase()}", 1)
        if (sc.yesterday.isNotEmpty()) out += action(context, KIND_YESTERDAY, meal, "Same as yesterday", 2)
        return out
    }

    private fun action(context: Context, kind: String, meal: MealType, label: String, code: Int): NotificationCompat.Action {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ACTION_LOG
            putExtra(EXTRA_KIND, kind)
            putExtra(EXTRA_MEAL_ID, meal.id)
            putExtra(EXTRA_MEAL_NAME, displayName(meal.name))
        }
        val pi = PendingIntent.getBroadcast(context, 3000 + code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, label, pi).build()
    }

    fun displayName(name: String) = name.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    /** Logs the shortcut (worked out again now, so it is current) and returns the entries logged. */
    suspend fun log(repo: FoodRepository, kind: String, mealId: String, mealName: String, today: LocalDate = LocalDate.now()): List<FoodEntry> {
        val sc = MealShortcuts.of(repo.mealHistory(today).first(), mealName, today)
        val source = if (kind == KIND_USUAL) sc.usual.orEmpty() else sc.yesterday
        if (source.isEmpty()) return emptyList()
        val copies = MealPatterns.copiesFor(source, today.toString(), mealId, mealName)
        repo.commitMultiAdd(copies)
        return copies
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderShortcuts.ACTION_LOG) return
        val app = context.applicationContext as? ByteLogApplication ?: return
        val kind = intent.getStringExtra(ReminderShortcuts.EXTRA_KIND) ?: return
        val mealId = intent.getStringExtra(ReminderShortcuts.EXTRA_MEAL_ID) ?: return
        val mealName = intent.getStringExtra(ReminderShortcuts.EXTRA_MEAL_NAME) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val logged = ReminderShortcuts.log(app.foodRepository, kind, mealId, mealName)
                val kcal = logged.sumOf { it.calories.toDouble() }.roundToInt()
                val text = when {
                    logged.isEmpty() -> "Nothing to repeat for $mealName. Open Nibl to log it."
                    logged.size == 1 -> "Logged ${logged[0].foodName} to $mealName · $kcal kcal"
                    else -> "Logged ${logged.size} foods to $mealName · $kcal kcal"
                }
                DailyReminderScheduler.createNotificationChannel(context)
                val done = NotificationCompat.Builder(context, DailyReminderScheduler.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(if (logged.isEmpty()) "Nibl" else "Logged ✓")
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setTimeoutAfter(8_000)
                    .setContentIntent(DailyReminderScheduler.openDiaryIntent(context))
                    .build()
                try {
                    NotificationManagerCompat.from(context).notify(DailyReminderScheduler.NOTIFICATION_ID, done)
                } catch (_: SecurityException) { }
            } finally {
                pending.finish()
            }
        }
    }
}
