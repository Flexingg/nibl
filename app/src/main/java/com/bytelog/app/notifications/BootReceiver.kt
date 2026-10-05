package com.bytelog.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bytelog.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pendingResult = goAsync()
            val scope = CoroutineScope(Dispatchers.IO)

            scope.launch {
                try {
                    val settingsRepo = SettingsRepository(context)
                    if (settingsRepo.notificationsEnabled.first()) {
                        val hour = settingsRepo.reminderHour.first()
                        val minute = settingsRepo.reminderMinute.first()
                        DailyReminderScheduler.scheduleDailyReminder(context, hour, minute)
                    }
                } catch (_: Exception) {
                    // Ignore errors during boot
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
