package com.bytelog.app

import android.app.Application
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            appScope = applicationScope
        )
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize API client configuration from saved settings
        applicationScope.launch {
            val url = settingsRepository.serverUrl.first()
            val token = settingsRepository.authToken.first()
            val apiKey = settingsRepository.apiKey.first()
            sparkyApiClient.updateConfig(url, token, apiKey)
        }
    }
}
