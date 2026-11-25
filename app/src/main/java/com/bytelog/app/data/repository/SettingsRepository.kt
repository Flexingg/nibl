package com.bytelog.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        val KEY_WEEKLY_REVIEW_DISMISSED = androidx.datastore.preferences.core.stringPreferencesKey("weekly_review_dismissed")

        val KEY_AUTO_ADJUST_CALORIES = androidx.datastore.preferences.core.booleanPreferencesKey("auto_adjust_calories")
        val KEY_CALORIE_GOAL_MODE = stringPreferencesKey("calorie_goal_mode")
        val KEY_CALORIE_GOAL_OFFSET = floatPreferencesKey("calorie_goal_offset")
        val KEY_LAST_ADAPTIVE_TDEE = floatPreferencesKey("last_adaptive_tdee")
        val KEY_SPARKY_TOOLS = androidx.datastore.preferences.core.stringSetPreferencesKey("sparky_tool_categories")

        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DEVICE_COLORS = androidx.datastore.preferences.core.booleanPreferencesKey("device_colors")
        val KEY_REDUCE_TRANSPARENCY = androidx.datastore.preferences.core.booleanPreferencesKey("reduce_transparency")
        val KEY_UI_SIZE = stringPreferencesKey("ui_size")
        val KEY_HOME_LAYOUT = stringPreferencesKey("home_layout")
        val KEY_HOME_TILES = stringPreferencesKey("home_tiles")
        val KEY_HC_LEAVE_TO_SPARKY_APP = androidx.datastore.preferences.core.booleanPreferencesKey("hc_leave_to_sparky_app")
        val KEY_FAB_MENU = stringPreferencesKey("fab_menu_layout")
        val KEY_UNANSWERED_CREATES = stringPreferencesKey("sync_unanswered_creates")
        val KEY_GITHUB_TOKEN = stringPreferencesKey("update_github_token")
        val KEY_UPDATE_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("update_auto_check")
        val KEY_UPDATE_LAST_CHECK = androidx.datastore.preferences.core.longPreferencesKey("update_last_check")
        val KEY_UPDATE_DISMISSED = stringPreferencesKey("update_dismissed_version")
        val KEY_PLANNER = stringPreferencesKey("planner_config")
        val KEY_HEALTH_CONNECT_WRITE = androidx.datastore.preferences.core.booleanPreferencesKey("health_connect_write")
        val KEY_RESEARCH_PROVIDER = stringPreferencesKey("research_provider")
        val KEY_RESEARCH_TAVILY = stringPreferencesKey("research_tavily_key")
        val KEY_RESEARCH_ASK_WRITES = androidx.datastore.preferences.core.booleanPreferencesKey("research_ask_before_writing")
        val KEY_RESEARCH_PHOTOS = androidx.datastore.preferences.core.booleanPreferencesKey("research_photos_with_own_model")
        fun researchKeyPref(p: com.bytelog.app.research.ProviderId) = stringPreferencesKey("research_key_${p.name.lowercase()}")
        fun researchModelPref(p: com.bytelog.app.research.ProviderId) = stringPreferencesKey("research_model_${p.name.lowercase()}")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: "http://10.0.2.2:3010"
    }

    /** The Sparky session token. Stored encrypted with a Keystore key; older plain copies are still read (and encrypted on [migrateSecrets]). */
    val authToken: Flow<String?> = context.dataStore.data.map { prefs -> readSecret(prefs[KEY_AUTH_TOKEN]) }

    /** The Sparky API key, stored encrypted like [authToken]. */
    val apiKey: Flow<String?> = context.dataStore.data.map { prefs -> readSecret(prefs[KEY_API_KEY]) }

    /** Marks values written encrypted; anything without it is an older plain copy. */
    private val secretPrefix = "enc1:"

    private fun readSecret(stored: String?): String? = when {
        stored == null -> null
        stored.startsWith(secretPrefix) -> cipher.decrypt(stored.removePrefix(secretPrefix))
        else -> stored
    }

    // If the Keystore can't be used on this phone, keep the value as before rather than lose the login
    private fun sealSecret(plain: String): String = runCatching { secretPrefix + cipher.encrypt(plain) }.getOrDefault(plain)

    /** Encrypts a Sparky token or API key saved in plain text by an earlier version. Safe to call any time. */
    suspend fun migrateSecrets() {
        context.dataStore.edit { prefs ->
            listOf(KEY_AUTH_TOKEN, KEY_API_KEY).forEach { key ->
                val v = prefs[key]
                if (v != null && !v.startsWith(secretPrefix)) prefs[key] = sealSecret(v)
            }
        }
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

    /** The last day of the week whose review was closed, so it doesn't come back for that week. */
    val weeklyReviewDismissed: Flow<String> = context.dataStore.data.map { prefs -> prefs[KEY_WEEKLY_REVIEW_DISMISSED] ?: "" }

    suspend fun dismissWeeklyReview(weekEnd: String) {
        context.dataStore.edit { it[KEY_WEEKLY_REVIEW_DISMISSED] = weekEnd }
    }

    val weightUnit: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_WEIGHT_UNIT] ?: "lbs"
    }

    val autoAdjustCalories: Flow<Boolean> = context.dataStore.data.map { prefs ->
        // Off unless chosen: the goal synced from Sparky is the one shown, not the app's own estimate
        prefs[KEY_AUTO_ADJUST_CALORIES] ?: false
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
            askBeforeWriting = prefs[KEY_RESEARCH_ASK_WRITES] ?: true,
            photosWithOwnModel = prefs[KEY_RESEARCH_PHOTOS] ?: true
        )
    }

    /** Light, dark or follow the system (default). */
    val themeMode: Flow<com.bytelog.app.ui.theme.ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE]?.let { runCatching { com.bytelog.app.ui.theme.ThemeMode.valueOf(it) }.getOrNull() }
            ?: com.bytelog.app.ui.theme.ThemeMode.SYSTEM
    }

    /** Use the device's wallpaper colours (Material You) where available. On by default. */
    val useDeviceColors: Flow<Boolean> = context.dataStore.data.map { it[KEY_DEVICE_COLORS] ?: true }

    suspend fun saveThemeMode(mode: com.bytelog.app.ui.theme.ThemeMode) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    /** Solid surfaces instead of frosted glass (easier to read on busy backgrounds). */
    val reduceTransparency: Flow<Boolean> = context.dataStore.data.map { it[KEY_REDUCE_TRANSPARENCY] ?: false }

    suspend fun saveReduceTransparency(enabled: Boolean) {
        context.dataStore.edit { it[KEY_REDUCE_TRANSPARENCY] = enabled }
    }

    /** How big the app is drawn (Settings → Appearance → Size). Compact by default. */
    val uiSize: Flow<com.bytelog.app.ui.theme.UiSize> = context.dataStore.data.map { com.bytelog.app.ui.theme.UiSize.fromName(it[KEY_UI_SIZE]) }

    suspend fun saveUiSize(size: com.bytelog.app.ui.theme.UiSize) {
        context.dataStore.edit { it[KEY_UI_SIZE] = size.name }
    }

    /** Write the diary's food and water to Health Connect. */
    val healthConnectWrite: Flow<Boolean> = context.dataStore.data.map { it[KEY_HEALTH_CONNECT_WRITE] ?: false }

    suspend fun saveHealthConnectWrite(enabled: Boolean) {
        context.dataStore.edit { it[KEY_HEALTH_CONNECT_WRITE] = enabled }
    }

    /** How the meal planner shows the plan. */
    val plannerConfig: Flow<PlannerConfig> = context.dataStore.data.map { PlannerConfig.parse(it[KEY_PLANNER]) }

    suspend fun savePlannerConfig(config: PlannerConfig) {
        context.dataStore.edit { it[KEY_PLANNER] = config.encode() }
    }

    /**
     * The home dashboard's tiles ([HomeTiles] encoding). Someone who only arranged the older home sections gets
     * those, turned into tiles.
     */
    val homeTiles: Flow<HomeTiles> = context.dataStore.data.map { prefs ->
        prefs[KEY_HOME_TILES]?.let { HomeTiles.parse(it) }
            ?: prefs[KEY_HOME_LAYOUT]?.let { HomeTiles.fromLayout(HomeLayout.parse(it)) }
            ?: HomeTiles.DEFAULT
    }

    /**
     * With the SparkyFitness phone app installed, leave Health Connect to it (it writes the diary there and sends
     * Health Connect data to Sparky), so nothing is counted twice. On unless turned off.
     */
    val hcLeaveToSparkyApp: Flow<Boolean> = context.dataStore.data.map { it[KEY_HC_LEAVE_TO_SPARKY_APP] ?: true }

    suspend fun saveHcLeaveToSparkyApp(on: Boolean) {
        context.dataStore.edit { it[KEY_HC_LEAVE_TO_SPARKY_APP] = on }
    }

    suspend fun saveHomeTiles(tiles: HomeTiles) {
        context.dataStore.edit { it[KEY_HOME_TILES] = tiles.encode() }
    }

    /**
     * Diary entries whose upload got no answer (the connection dropped), with when: Sparky may have saved them, so
     * the next upload looks for them there first instead of sending a second copy.
     */
    suspend fun unansweredCreates(): Map<String, Long> = context.dataStore.data.first()[KEY_UNANSWERED_CREATES].orEmpty()
        .split(';').mapNotNull { part -> part.split('=').takeIf { it.size == 2 }?.let { (id, at) -> at.toLongOrNull()?.let { id to it } } }.toMap()

    suspend fun markUnansweredCreate(id: String, at: Long) {
        context.dataStore.edit { prefs ->
            val map = decodeUnanswered(prefs[KEY_UNANSWERED_CREATES]) + (id to at)
            prefs[KEY_UNANSWERED_CREATES] = map.entries.joinToString(";") { "${it.key}=${it.value}" }
        }
    }

    suspend fun clearUnansweredCreate(id: String) {
        context.dataStore.edit { prefs ->
            val map = decodeUnanswered(prefs[KEY_UNANSWERED_CREATES])
            if (id in map) prefs[KEY_UNANSWERED_CREATES] = (map - id).entries.joinToString(";") { "${it.key}=${it.value}" }
        }
    }

    private fun decodeUnanswered(raw: String?): Map<String, Long> = raw.orEmpty().split(';')
        .mapNotNull { part -> part.split('=').takeIf { it.size == 2 }?.let { (id, at) -> at.toLongOrNull()?.let { id to it } } }.toMap()

    /** The order of the home screen's + menu, and which actions are hidden. */
    val fabMenuLayout: Flow<FabMenuLayout> = context.dataStore.data.map { FabMenuLayout.parse(it[KEY_FAB_MENU]) }

    suspend fun saveFabMenuLayout(layout: FabMenuLayout) {
        context.dataStore.edit { it[KEY_FAB_MENU] = layout.encode() }
    }

    /** A read-only GitHub token for updates while the repository is private. Stored encrypted. */
    val githubToken: Flow<String?> = context.dataStore.data.map { readSecret(it[KEY_GITHUB_TOKEN]) }

    suspend fun saveGithubToken(token: String?) {
        context.dataStore.edit { prefs ->
            val t = token?.trim().orEmpty()
            if (t.isEmpty()) prefs.remove(KEY_GITHUB_TOKEN) else prefs[KEY_GITHUB_TOKEN] = sealSecret(t)
        }
    }

    /** Look for a new version on GitHub by itself (at most every half hour). On by default. */
    val autoCheckUpdates: Flow<Boolean> = context.dataStore.data.map { it[KEY_UPDATE_AUTO] ?: true }

    suspend fun saveAutoCheckUpdates(enabled: Boolean) {
        context.dataStore.edit { it[KEY_UPDATE_AUTO] = enabled }
    }

    /** When the app last looked for an update (epoch millis, 0 = never). */
    val lastUpdateCheck: Flow<Long> = context.dataStore.data.map { it[KEY_UPDATE_LAST_CHECK] ?: 0L }

    suspend fun saveLastUpdateCheck(at: Long) {
        context.dataStore.edit { it[KEY_UPDATE_LAST_CHECK] = at }
    }

    /** A version whose home-screen banner the user dismissed ("Later"); a newer one shows again. */
    val dismissedUpdate: Flow<String?> = context.dataStore.data.map { it[KEY_UPDATE_DISMISSED] }

    suspend fun saveDismissedUpdate(version: String) {
        context.dataStore.edit { it[KEY_UPDATE_DISMISSED] = version }
    }

    suspend fun saveUseDeviceColors(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DEVICE_COLORS] = enabled }
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

    suspend fun savePhotosWithOwnModel(enabled: Boolean) {
        context.dataStore.edit { it[KEY_RESEARCH_PHOTOS] = enabled }
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
            if (token != null) prefs[KEY_AUTH_TOKEN] = sealSecret(token) else prefs.remove(KEY_AUTH_TOKEN)
        }
    }

    suspend fun saveAuthTokenAndEmail(token: String?, email: String?) {
        context.dataStore.edit { prefs ->
            if (token != null) prefs[KEY_AUTH_TOKEN] = sealSecret(token) else prefs.remove(KEY_AUTH_TOKEN)
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
                prefs[KEY_AUTH_TOKEN] = sealSecret(token)
            } else if (!preserveExistingTokenIfNull) {
                prefs.remove(KEY_AUTH_TOKEN)
            }
            if (!apiKey.isNullOrBlank()) {
                prefs[KEY_API_KEY] = sealSecret(apiKey)
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
