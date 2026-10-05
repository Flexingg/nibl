package com.bytelog.app

import android.app.Application
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ByteLogApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { ByteLogDatabase.getDatabase(this, applicationScope) }
    val sparkyApiClient by lazy { SparkyApiClient() }
    val settingsRepository by lazy { SettingsRepository(this) }

    val foodRepository by lazy {
        FoodRepository(
            foodDao = database.foodDao(),
            foodEntryDao = database.foodEntryDao(),
            mealTypeDao = database.mealTypeDao(),
            sparkyApiClient = sparkyApiClient,
            settingsRepository = settingsRepository,
            appScope = applicationScope,
            prepBatchDao = database.prepBatchDao()
        )
    }

    val waterRepository by lazy {
        com.bytelog.app.data.repository.WaterRepository(
            waterLogDao = database.waterLogDao(),
            sparkyApiClient = sparkyApiClient,
            settingsRepository = settingsRepository,
            appScope = applicationScope
        )
    }

    val weightRepository by lazy {
        com.bytelog.app.data.repository.WeightRepository(
            weightDao = database.weightDao(),
            sparkyApiClient = sparkyApiClient,
            settingsRepository = settingsRepository,
            appScope = applicationScope
        )
    }

    val mealTemplateRepository by lazy {
        com.bytelog.app.data.repository.MealTemplateRepository(
            mealTemplateDao = database.mealTemplateDao(),
            sparkyApiClient = sparkyApiClient,
            appScope = applicationScope
        )
    }

    val recipeRepository by lazy {
        com.bytelog.app.data.repository.RecipeRepository(
            recipeDetailsDao = database.recipeDetailsDao(),
            ingredientAliasDao = database.ingredientAliasDao(),
            foodDao = database.foodDao(),
            foodRepository = foodRepository,
            mealTemplateRepository = mealTemplateRepository,
            sparkyApiClient = sparkyApiClient
        )
    }

    val mealPrepRepository by lazy {
        com.bytelog.app.data.repository.MealPrepRepository(
            prepBatchDao = database.prepBatchDao(),
            foodRepository = foodRepository,
            sparkyApiClient = sparkyApiClient,
            appScope = applicationScope
        )
    }

    val chatRepository by lazy {
        com.bytelog.app.data.repository.SparkyChatRepository(
            sparkyApiClient = sparkyApiClient,
            foodRepository = foodRepository,
            weightRepository = weightRepository,
            appScope = applicationScope,
            settingsRepository = settingsRepository
        )
    }

    override fun onCreate() {
        super.onCreate()

        // 1. Purge any leftover demo starter foods
        applicationScope.launch(Dispatchers.IO) {
            database.foodDao().deleteDemoFoods()
        }

        // 2. Dynamically keep API client configuration in sync with Settings in real-time
        applicationScope.launch {
            combine(
                settingsRepository.serverUrl,
                settingsRepository.authToken,
                settingsRepository.apiKey
            ) { url, token, key ->
                sparkyApiClient.updateConfig(url, token, key)
            }.collect()
        }

        // 3. Initialize daily reminder notification channel and active schedule
        com.bytelog.app.notifications.DailyReminderScheduler.createNotificationChannel(this)
        applicationScope.launch {
            try {
                if (settingsRepository.notificationsEnabled.first()) {
                    val hour = settingsRepository.reminderHour.first()
                    val minute = settingsRepository.reminderMinute.first()
                    com.bytelog.app.notifications.DailyReminderScheduler.scheduleDailyReminder(this@ByteLogApplication, hour, minute)
                }
            } catch (_: Exception) {}
        }
    }
}
