package com.bytelog.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bytelog_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token")
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_USER_EMAIL = stringPreferencesKey("user_email")

        val KEY_TARGET_CALORIES = floatPreferencesKey("target_calories")
        val KEY_TARGET_PROTEIN = floatPreferencesKey("target_protein")
        val KEY_TARGET_CARBS = floatPreferencesKey("target_carbs")
        val KEY_TARGET_FAT = floatPreferencesKey("target_fat")
        val KEY_TARGET_WATER_OZ = floatPreferencesKey("target_water_oz")
        val KEY_DEFAULT_CONTAINER_OZ = floatPreferencesKey("default_container_oz")
        val KEY_DEFAULT_CONTAINER_NAME = stringPreferencesKey("default_container_name")
        val KEY_WEIGHT_UNIT = stringPreferencesKey("weight_unit")

        val KEY_AUTO_ADJUST_CALORIES = androidx.datastore.preferences.core.booleanPreferencesKey("auto_adjust_calories")
        val KEY_CALORIE_GOAL_MODE = stringPreferencesKey("calorie_goal_mode")
        val KEY_CALORIE_GOAL_OFFSET = floatPreferencesKey("calorie_goal_offset")
        val KEY_LAST_ADAPTIVE_TDEE = floatPreferencesKey("last_adaptive_tdee")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: "http://10.0.2.2:3010"
    }

    val authToken: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTH_TOKEN]
    }

    val apiKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_API_KEY]
    }

    val userEmail: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_EMAIL]
    }

    val targetCalories: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_TARGET_CALORIES] ?: 2200f
    }

    val targetProtein: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_TARGET_PROTEIN] ?: 160f
    }

    val targetCarbs: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_TARGET_CARBS] ?: 220f
    }

    val targetFat: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_TARGET_FAT] ?: 70f
    }

    val targetWaterOz: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_TARGET_WATER_OZ] ?: 64f
    }

    val defaultContainerOz: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_CONTAINER_OZ] ?: 8f
    }

    val defaultContainerName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_CONTAINER_NAME] ?: "Glass"
    }

    val weightUnit: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_WEIGHT_UNIT] ?: "lbs"
    }

    val autoAdjustCalories: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_ADJUST_CALORIES] ?: true
    }

    val calorieGoalMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CALORIE_GOAL_MODE] ?: "cut"
    }

    val calorieGoalOffset: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_CALORIE_GOAL_OFFSET] ?: -500f
    }

    val lastAdaptiveTdee: Flow<Float?> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_ADAPTIVE_TDEE]
    }

    suspend fun saveAutoAdjustCalories(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_ADJUST_CALORIES] = enabled
        }
    }

    suspend fun saveCalorieGoal(mode: String, offset: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CALORIE_GOAL_MODE] = mode
            prefs[KEY_CALORIE_GOAL_OFFSET] = offset
        }
    }

    suspend fun saveLastAdaptiveTdee(tdee: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_ADAPTIVE_TDEE] = tdee
        }
    }

    suspend fun saveWeightUnit(unit: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WEIGHT_UNIT] = unit
        }
    }

    suspend fun saveAuthToken(token: String?) {
        context.dataStore.edit { prefs ->
            if (token != null) prefs[KEY_AUTH_TOKEN] = token else prefs.remove(KEY_AUTH_TOKEN)
        }
    }

    suspend fun saveAuthTokenAndEmail(token: String?, email: String?) {
        context.dataStore.edit { prefs ->
            if (token != null) prefs[KEY_AUTH_TOKEN] = token else prefs.remove(KEY_AUTH_TOKEN)
            if (email != null) prefs[KEY_USER_EMAIL] = email else prefs.remove(KEY_USER_EMAIL)
        }
    }

    suspend fun saveServerConfig(
        url: String,
        token: String? = null,
        apiKey: String? = null,
        preserveExistingTokenIfNull: Boolean = true
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = url
            if (token != null) {
                prefs[KEY_AUTH_TOKEN] = token
            } else if (!preserveExistingTokenIfNull) {
                prefs.remove(KEY_AUTH_TOKEN)
            }
            if (!apiKey.isNullOrBlank()) {
                prefs[KEY_API_KEY] = apiKey
            } else {
                prefs.remove(KEY_API_KEY)
            }
        }
    }

    suspend fun saveWaterTargetOz(targetOz: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TARGET_WATER_OZ] = targetOz
        }
    }

    suspend fun saveDefaultContainer(oz: Float, name: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_CONTAINER_OZ] = oz
            prefs[KEY_DEFAULT_CONTAINER_NAME] = name
        }
    }

    suspend fun saveMacroTargets(
        calories: Float,
        protein: Float,
        carbs: Float,
        fat: Float,
        waterGoalOz: Float? = null
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TARGET_CALORIES] = calories
            prefs[KEY_TARGET_PROTEIN] = protein
            prefs[KEY_TARGET_CARBS] = carbs
            prefs[KEY_TARGET_FAT] = fat
            if (waterGoalOz != null && waterGoalOz > 0f) {
                prefs[KEY_TARGET_WATER_OZ] = waterGoalOz
            }
        }
    }
}
