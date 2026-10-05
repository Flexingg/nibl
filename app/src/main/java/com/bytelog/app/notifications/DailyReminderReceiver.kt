package com.bytelog.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

class DailyReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO)

        scope.launch {
            try {
                val settingsRepo = SettingsRepository(context)
                val isEnabled = settingsRepo.notificationsEnabled.first()
                if (!isEnabled) {
                    return@launch
                }

                val hour = settingsRepo.reminderHour.first()
                val minute = settingsRepo.reminderMinute.first()
                val smartNudge = settingsRepo.reminderSmartNudge.first()

                val today = LocalDate.now().toString()
                var hasLoggedToday = false

                if (smartNudge) {
                    try {
                        val db = ByteLogDatabase.getDatabase(context, scope)
                        val entries = db.foodEntryDao().getEntriesForDate(today).first()
                        hasLoggedToday = entries.isNotEmpty()
                    } catch (_: Exception) {
                        hasLoggedToday = false
                    }
                }

                val title: String
                val message: String

                if (smartNudge && hasLoggedToday) {
                    title = "ByteLog • Daily Check-in 🌟"
                    message = "Great progress today! Take a quick moment to log any remaining snacks and hit your water goal."
                } else {
                    title = "ByteLog • Time to log! 🍽️"
                    message = "Don't break your streak! Take 30 seconds to log today's meals and hydration."
                }

                DailyReminderScheduler.showNotification(context, title, message)

                // Re-schedule for next day
                DailyReminderScheduler.scheduleDailyReminder(context, hour, minute)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
