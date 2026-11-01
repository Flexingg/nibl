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

    val planRepository by lazy { com.bytelog.app.data.repository.PlanRepository(database.plannedEntryDao(), foodRepository) }

    val healthConnectSync by lazy {
        com.bytelog.app.health.HealthConnectSync(
            this, com.bytelog.app.health.HealthConnectStore(this), database.foodEntryDao(), database.waterLogDao()
        )
    }

    /** True while Nibl is on screen: Health Connect is only read and written then. */
    @Volatile var inForeground = false
    private var healthJob: kotlinx.coroutines.Job? = null

    /**
     * Writes recent days to Health Connect a few seconds after the diary settles (when it's turned on, permitted
     * and Nibl is open).
     */
    fun requestHealthSync(delayMs: Long = 3_000) {
        healthJob?.cancel()
        healthJob = applicationScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(delayMs)
            if (!inForeground) return@launch
            if (!settingsRepository.healthConnectWrite.first()) return@launch
            if (!com.bytelog.app.health.HealthConnectStore.hasPermissions(this@ByteLogApplication)) return@launch
            healthConnectSync.syncDays()
        }
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

    val familyRepository by lazy {
        com.bytelog.app.data.family.FamilyRepository(sparkyApiClient, foodRepository)
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

        // 2a. Health Connect: what Nibl wrote (to spot it coming back through Sparky), and rewriting days as they change
        com.bytelog.app.health.HealthConnectEcho.init(this)
        database.invalidationTracker.addObserver(object : androidx.room.InvalidationTracker.Observer(arrayOf("food_entries", "water_log_entries")) {
            override fun onInvalidated(tables: Set<String>) = requestHealthSync()
        })

        // 2b. Keep the home screen widgets current as the diary, water and goals change
        com.bytelog.app.widget.WidgetSync.start(this, applicationScope)

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
