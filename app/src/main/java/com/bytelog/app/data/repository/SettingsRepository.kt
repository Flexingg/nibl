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

class SettingsRepository(
    private val context: Context,
    private val cipher: com.bytelog.app.research.SecretCipher = com.bytelog.app.research.KeystoreSecretCipher()
) {

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

        val KEY_NOTIFICATIONS_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("notifications_enabled")
        val KEY_REMINDER_HOUR = androidx.datastore.preferences.core.intPreferencesKey("reminder_hour")
        val KEY_REMINDER_MINUTE = androidx.datastore.preferences.core.intPreferencesKey("reminder_minute")
        val KEY_REMINDER_SMART_NUDGE = androidx.datastore.preferences.core.booleanPreferencesKey("reminder_smart_nudge")

        val KEY_AUTO_ADJUST_CALORIES = androidx.datastore.preferences.core.booleanPreferencesKey("auto_adjust_calories")
        val KEY_CALORIE_GOAL_MODE = stringPreferencesKey("calorie_goal_mode")
        val KEY_CALORIE_GOAL_OFFSET = floatPreferencesKey("calorie_goal_offset")
        val KEY_LAST_ADAPTIVE_TDEE = floatPreferencesKey("last_adaptive_tdee")
        val KEY_SPARKY_TOOLS = androidx.datastore.preferences.core.stringSetPreferencesKey("sparky_tool_categories")

        val KEY_RESEARCH_PROVIDER = stringPreferencesKey("research_provider")
        val KEY_RESEARCH_TAVILY = stringPreferencesKey("research_tavily_key")
        val KEY_RESEARCH_ASK_WRITES = androidx.datastore.preferences.core.booleanPreferencesKey("research_ask_before_writing")
        fun researchKeyPref(p: com.bytelog.app.research.ProviderId) = stringPreferencesKey("research_key_${p.name.lowercase()}")
        fun researchModelPref(p: com.bytelog.app.research.ProviderId) = stringPreferencesKey("research_model_${p.name.lowercase()}")
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
        val stored = prefs[KEY_DEFAULT_CONTAINER_OZ]
        if (stored == null || stored == 8f) 20f else stored
    }

    val defaultContainerName: Flow<String> = context.dataStore.data.map { prefs ->
        val stored = prefs[KEY_DEFAULT_CONTAINER_NAME]
        if (stored == null || stored == "Glass") "Bottle" else stored
    }

    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_NOTIFICATIONS_ENABLED] ?: false
    }

    val reminderHour: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_REMINDER_HOUR] ?: 20
    }

    val reminderMinute: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_REMINDER_MINUTE] ?: 0
    }

    val reminderSmartNudge: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_REMINDER_SMART_NUDGE] ?: true
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

    /** Tool categories Sparky AI may use from this app. Default: every category, read and write. */
    val sparkyToolCategories: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_SPARKY_TOOLS]?.let { stored -> stored.intersect(com.bytelog.app.data.remote.SparkyTools.allSlugs) }
            ?: com.bytelog.app.data.remote.SparkyTools.allSlugs
    }

    suspend fun saveSparkyToolCategories(slugs: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SPARKY_TOOLS] = slugs.intersect(com.bytelog.app.data.remote.SparkyTools.allSlugs)
        }
    }

    /**
     * Web research assistant settings. API keys are stored encrypted (Android Keystore) and only on this phone.
     */
    val researchConfig: Flow<com.bytelog.app.research.ResearchConfig> = context.dataStore.data.map { prefs ->
        val providers = com.bytelog.app.research.ProviderId.entries
        com.bytelog.app.research.ResearchConfig(
            provider = com.bytelog.app.research.ProviderId.fromName(prefs[KEY_RESEARCH_PROVIDER]),
            apiKeys = providers.mapNotNull { p -> prefs[researchKeyPref(p)]?.let(cipher::decrypt)?.let { p to it } }.toMap(),
            models = providers.mapNotNull { p -> prefs[researchModelPref(p)]?.takeIf { it.isNotBlank() }?.let { p to it } }.toMap(),
            tavilyKey = prefs[KEY_RESEARCH_TAVILY]?.let(cipher::decrypt),
            askBeforeWriting = prefs[KEY_RESEARCH_ASK_WRITES] ?: true
        )
    }

    suspend fun saveResearchProvider(provider: com.bytelog.app.research.ProviderId) {
        context.dataStore.edit { it[KEY_RESEARCH_PROVIDER] = provider.name }
    }

    /** Saves (or clears, when blank) a provider's API key. */
    suspend fun saveResearchKey(provider: com.bytelog.app.research.ProviderId, key: String) {
        context.dataStore.edit { prefs ->
            if (key.isBlank()) prefs.remove(researchKeyPref(provider)) else prefs[researchKeyPref(provider)] = cipher.encrypt(key.trim())
        }
    }

    suspend fun saveResearchModel(provider: com.bytelog.app.research.ProviderId, model: String) {
        context.dataStore.edit { prefs ->
            if (model.isBlank()) prefs.remove(researchModelPref(provider)) else prefs[researchModelPref(provider)] = model.trim()
        }
    }

    suspend fun saveTavilyKey(key: String) {
        context.dataStore.edit { prefs ->
            if (key.isBlank()) prefs.remove(KEY_RESEARCH_TAVILY) else prefs[KEY_RESEARCH_TAVILY] = cipher.encrypt(key.trim())
        }
    }

    suspend fun saveAskBeforeWriting(ask: Boolean) {
        context.dataStore.edit { it[KEY_RESEARCH_ASK_WRITES] = ask }
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

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_AUTH_TOKEN)
            prefs.remove(KEY_USER_EMAIL)
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

    suspend fun saveNotificationSettings(
        enabled: Boolean,
        hour: Int,
        minute: Int,
        smartNudge: Boolean = true
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NOTIFICATIONS_ENABLED] = enabled
            prefs[KEY_REMINDER_HOUR] = hour
            prefs[KEY_REMINDER_MINUTE] = minute
            prefs[KEY_REMINDER_SMART_NUDGE] = smartNudge
        }
    }
}
