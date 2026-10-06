package com.bytelog.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.bytelog.app.notifications.DailyReminderScheduler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.remote.ConnectionTestResult
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.MealTemplateRepository
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.repository.WaterRepository
import com.bytelog.app.data.repository.WeightRepository
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    foodRepository: FoodRepository,
    waterRepository: WaterRepository? = null,
    weightRepository: WeightRepository? = null,
    mealTemplateRepository: MealTemplateRepository? = null,
    onNavigateBack: () -> Unit
) {
    val serverUrlPref by settingsRepository.serverUrl.collectAsState(initial = "http://10.0.2.2:3010")
    val authTokenPref by settingsRepository.authToken.collectAsState(initial = null)
    val apiKeyPref by settingsRepository.apiKey.collectAsState(initial = null)
    val savedEmailPref by settingsRepository.userEmail.collectAsState(initial = null)

    val calPref by settingsRepository.targetCalories.collectAsState(initial = 2200f)
    val pPref by settingsRepository.targetProtein.collectAsState(initial = 160f)
    val cPref by settingsRepository.targetCarbs.collectAsState(initial = 220f)
    val fPref by settingsRepository.targetFat.collectAsState(initial = 70f)
    val wPref by settingsRepository.targetWaterOz.collectAsState(initial = 64f)
    val defContOzPref by settingsRepository.defaultContainerOz.collectAsState(initial = 20f)
    val defContNamePref by settingsRepository.defaultContainerName.collectAsState(initial = "Bottle")

    val notifEnabledPref by settingsRepository.notificationsEnabled.collectAsState(initial = false)
    val reminderHourPref by settingsRepository.reminderHour.collectAsState(initial = 20)
    val reminderMinutePref by settingsRepository.reminderMinute.collectAsState(initial = 0)
    val reminderSmartNudgePref by settingsRepository.reminderSmartNudge.collectAsState(initial = true)

    var serverUrl by remember { mutableStateOf(serverUrlPref) }
    var apiKey by remember { mutableStateOf(apiKeyPref ?: "") }
    var email by remember { mutableStateOf(savedEmailPref ?: "") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var targetCalories by remember { mutableStateOf(calPref.roundToInt().toString()) }
    var targetProtein by remember { mutableStateOf(pPref.roundToInt().toString()) }
    var targetCarbs by remember { mutableStateOf(cPref.roundToInt().toString()) }
    var targetFat by remember { mutableStateOf(fPref.roundToInt().toString()) }
    var targetWater by remember { mutableStateOf(wPref.roundToInt().toString()) }

    var defaultContainerOz by remember { mutableStateOf(defContOzPref.roundToInt().toString()) }
    var defaultContainerName by remember { mutableStateOf(defContNamePref) }

    var notifEnabled by remember { mutableStateOf(notifEnabledPref) }
    var reminderHour by remember { mutableStateOf(reminderHourPref) }
    var reminderMinute by remember { mutableStateOf(reminderMinutePref) }
    var reminderSmartNudge by remember { mutableStateOf(reminderSmartNudgePref) }
    var showTimePickerDialog by remember { mutableStateOf(false) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionTestResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    var isSigningIn by remember { mutableStateOf(false) }
    var isManualSyncing by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            notifEnabled = true
            scope.launch {
                settingsRepository.saveNotificationSettings(true, reminderHour, reminderMinute, reminderSmartNudge)
                DailyReminderScheduler.scheduleDailyReminder(context, reminderHour, reminderMinute)
                val h = if (reminderHour == 0) 12 else if (reminderHour > 12) reminderHour - 12 else reminderHour
                val m = String.format(java.util.Locale.US, "%02d", reminderMinute)
                val ampm = if (reminderHour >= 12) "PM" else "AM"
                snackbarHostState.showSnackbar("Daily reminders enabled for $h:$m $ampm")
            }
        } else {
            notifEnabled = false
            scope.launch {
                settingsRepository.saveNotificationSettings(false, reminderHour, reminderMinute, reminderSmartNudge)
                snackbarHostState.showSnackbar("Notification permission denied. Reminders cannot be posted.")
            }
        }
    }

    LaunchedEffect(
        serverUrlPref, apiKeyPref, savedEmailPref, calPref, pPref, cPref, fPref, wPref,
        defContOzPref, defContNamePref, notifEnabledPref, reminderHourPref, reminderMinutePref, reminderSmartNudgePref
    ) {
        serverUrl = serverUrlPref
        apiKey = apiKeyPref ?: ""
        if (email.isBlank() && !savedEmailPref.isNullOrBlank()) {
            email = savedEmailPref.orEmpty()
        }
        targetCalories = calPref.roundToInt().toString()
        targetProtein = pPref.roundToInt().toString()
        targetCarbs = cPref.roundToInt().toString()
        targetFat = fPref.roundToInt().toString()
        targetWater = wPref.roundToInt().toString()
        defaultContainerOz = defContOzPref.roundToInt().toString()
        defaultContainerName = defContNamePref
        notifEnabled = notifEnabledPref
        reminderHour = reminderHourPref
        reminderMinute = reminderMinutePref
        reminderSmartNudge = reminderSmartNudgePref
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = ByteInkDark,
        topBar = {
            com.bytelog.app.ui.components.ScreenHeader(
                title = "Settings",
                subtitle = "Server, goals, reminders and AI",
                onBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Sparky Fitness Backend & Diagnostic Connection Test
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ByteSurfaceDark)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SPARKY FITNESS SERVER",
                        color = ByteTomato,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                }

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL", color = ByteCreamMuted) },
                    placeholder = { Text("http://10.0.2.2:3010 or http://192.168.1.x:3010", color = ByteCreamMuted.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = ByteTomato,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Test Connection Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                isTestingConnection = true
                                // Save entered URL and token first so SparkyApiClient points to right host
                                settingsRepository.saveServerConfig(
                                    url = serverUrl,
                                    apiKey = apiKey.ifBlank { null },
                                    preserveExistingTokenIfNull = true
                                )
                                delay(150)
                                val result = foodRepository.testConnection()
                                connectionTestResult = result
                                isTestingConnection = false
                            }
                        },
                        enabled = !isTestingConnection,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ByteSurfaceVariantDark,
                            contentColor = ByteCream
                        )
                    ) {
                        if (isTestingConnection) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = ByteTomato
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing...", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = ByteTomato
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test Connection", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                isManualSyncing = true
                                val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                                val res = foodRepository.syncDateWithServer(today)
                                waterRepository?.syncWaterForDate(today)
                                weightRepository?.syncWeightForDate(today)
                                weightRepository?.repairKgStoredAsLbs()
                                weightRepository?.syncWeightHistory()
                                mealTemplateRepository?.refreshTemplates()

                                res.fold(
                                    onSuccess = { count ->
                                        snackbarHostState.showSnackbar("Manual sync complete: foods ($count), weights, water & templates updated!")
                                    },
                                    onFailure = { err ->
                                        snackbarHostState.showSnackbar("Sync failed: ${err.message ?: "Connection error"}")
                                    }
                                )
                                isManualSyncing = false
                            }
                        },
                        enabled = !isManualSyncing,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ByteSurfaceVariantDark,
                            contentColor = ByteCream
                        )
                    ) {
                        if (isManualSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = ByteTomato
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Syncing...", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = ByteTomato
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Manual Sync", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Connection Test Feedback Banner
                connectionTestResult?.let { res ->
                    val bgColor = if (res.isSuccess) Color(0xFF064E3B).copy(alpha = 0.6f) else Color(0xFF450A0A).copy(alpha = 0.6f)
                    val borderColor = if (res.isSuccess) Color(0xFF10B981) else Color(0xFFEF4444)
                    val iconVector = if (res.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error
                    val iconTint = if (res.isSuccess) Color(0xFF34D399) else Color(0xFFF87171)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(bgColor)
                            .border(1.dp, borderColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (res.isSuccess) "Connection Verified" else "Connection Failed",
                                color = ByteCream,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = res.message,
                                color = ByteCreamMuted,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            // 2. Authentication Section: Fast Sign-In or API Key
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ByteSurfaceDark)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "AUTHENTICATION",
                    color = ByteTomato,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )

                if (!authTokenPref.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF064E3B).copy(alpha = 0.4f))
                            .border(1.dp, Color(0xFF10B981).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF34D399))
                                )
                                Text(
                                    text = "Active Sparky Session",
                                    color = Color(0xFF34D399),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = savedEmailPref ?: "Connected Account",
                                color = ByteCream,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    settingsRepository.clearSession()
                                    connectionTestResult = null
                                    snackbarHostState.showSnackbar("Signed out of Sparky account.")
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ByteTomato)
                        ) {
                            Text("Sign Out", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Sign in with Email / Password directly
                Text(
                    text = if (!authTokenPref.isNullOrBlank()) "Switch Account / Re-authenticate" else "Sign in with Sparky Fitness Account",
                    color = ByteCream,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email", color = ByteCreamMuted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = ByteTomato,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password", color = ByteCreamMuted) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                tint = ByteCreamMuted
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = ByteTomato,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (email.isBlank() || password.isBlank()) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Please enter both email and password")
                            }
                            return@Button
                        }
                        scope.launch {
                            isSigningIn = true
                            // Ensure server URL is persisted before signing in
                            settingsRepository.saveServerConfig(serverUrl, preserveExistingTokenIfNull = true)
                            delay(150)
                            val loginResult = foodRepository.loginWithEmail(email, password)
                            loginResult.fold(
                                onSuccess = { token ->
                                    settingsRepository.saveAuthTokenAndEmail(token, email)
                                    password = ""
                                    snackbarHostState.showSnackbar("Logged in successfully! Token stored.")
                                    // Automatically test connection
                                    val test = foodRepository.testConnection()
                                    connectionTestResult = test
                                },
                                onFailure = { error ->
                                    snackbarHostState.showSnackbar("Login failed: ${error.message ?: "Invalid credentials"}")
                                }
                            )
                            isSigningIn = false
                        }
                    },
                    enabled = !isSigningIn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ByteTomato)
                ) {
                    if (isSigningIn) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ByteCream
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Signing in...", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ByteCream)
                    } else {
                        Text("Sign In & Save Session Token", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ByteCream)
                    }
                }

                HorizontalDivider(
                    color = ByteSurfaceVariantDark,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // Alternatively, manual API Key / Session Token
                Text(
                    text = "Optional Direct API Key (Headless / Admin)",
                    color = ByteCreamMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key (leave blank if signed in above)", color = ByteCreamMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = ByteTomato,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 3. Data & Cache Management
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ByteSurfaceDark)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "DATA & CACHE",
                    color = ByteTomato,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )

                Text(
                    text = "Purge cached search foods and starter data so only your genuine Sparky Fitness entries remain.",
                    color = ByteCreamMuted,
                    fontSize = 12.sp
                )

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            foodRepository.clearLocalFoodCache()
                            snackbarHostState.showSnackbar("Local food database cache purged successfully.")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171))
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear Local Food Cache", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Sparky AI tool access (read & write categories)
            val sparkyTools by settingsRepository.sparkyToolCategories.collectAsState(initial = com.bytelog.app.data.remote.SparkyTools.allSlugs)
            com.bytelog.app.ui.components.SparkyToolsCard(
                enabled = sparkyTools,
                onChange = { scope.launch { settingsRepository.saveSparkyToolCategories(it) } }
            )

            // Web research assistant (own API key, web search + Sparky tools)
            com.bytelog.app.ui.components.ResearchAssistantCard(settings = settingsRepository)

            // 4. Macro Targets Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ByteSurfaceDark)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "DAILY MACRO GOALS",
                    color = ByteTomato,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )

                OutlinedTextField(
                    value = targetCalories,
                    onValueChange = { targetCalories = it },
                    label = { Text("Target Calories (kcal)", color = ByteTomato) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = ByteTomato,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = targetProtein,
                        onValueChange = { targetProtein = it },
                        label = { Text("Prot (g)", color = MacroProtein) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ByteCream,
                            unfocusedTextColor = ByteCream,
                            focusedBorderColor = MacroProtein,
                            unfocusedBorderColor = ByteSurfaceVariantDark
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = targetCarbs,
                        onValueChange = { targetCarbs = it },
                        label = { Text("Carb (g)", color = MacroCarbs) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ByteCream,
                            unfocusedTextColor = ByteCream,
                            focusedBorderColor = MacroCarbs,
                            unfocusedBorderColor = ByteSurfaceVariantDark
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = targetFat,
                        onValueChange = { targetFat = it },
                        label = { Text("Fat (g)", color = MacroFat) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ByteCream,
                            unfocusedTextColor = ByteCream,
                            focusedBorderColor = MacroFat,
                            unfocusedBorderColor = ByteSurfaceVariantDark
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = targetWater,
                    onValueChange = { targetWater = it },
                    label = { Text("Target Water (oz)", color = MacroProtein) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = MacroProtein,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "DEFAULT WATER CONTAINER",
                    color = MacroProtein,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = defaultContainerOz,
                        onValueChange = { defaultContainerOz = it },
                        label = { Text("Size (oz)", color = MacroProtein) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ByteCream,
                            unfocusedTextColor = ByteCream,
                            focusedBorderColor = MacroProtein,
                            unfocusedBorderColor = ByteSurfaceVariantDark
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = defaultContainerName,
                        onValueChange = { defaultContainerName = it },
                        label = { Text("Container Name", color = ByteCreamMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ByteCream,
                            unfocusedTextColor = ByteCream,
                            focusedBorderColor = MacroProtein,
                            unfocusedBorderColor = ByteSurfaceVariantDark
                        ),
                        modifier = Modifier.weight(1.5f)
                    )
                }

                // Quick Container Presets (Bottle 20 oz default)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        Pair("Bottle", 20f),
                        Pair("Glass", 8f),
                        Pair("Large Bottle", 32f),
                        Pair("Shaker", 24f)
                    ).forEach { (name, oz) ->
                        val isSelected = defaultContainerName.equals(name, ignoreCase = true) &&
                                defaultContainerOz == oz.roundToInt().toString()
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MacroProtein.copy(alpha = 0.2f) else ByteSurfaceVariantDark)
                                .border(
                                    width = if (isSelected) 1.dp else 0.dp,
                                    color = if (isSelected) MacroProtein else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    defaultContainerName = name
                                    defaultContainerOz = oz.roundToInt().toString()
                                    scope.launch {
                                        settingsRepository.saveDefaultContainer(oz, name)
                                        waterRepository?.setDefaultContainer(oz, name)
                                    }
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${oz.toInt()} oz",
                                color = if (isSelected) MacroProtein else ByteCream,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // 5. Daily Logging Reminders Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ByteSurfaceDark)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(ByteTomato.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (notifEnabled) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                                contentDescription = null,
                                tint = ByteTomato,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "DAILY LOGGING REMINDERS",
                                color = ByteTomato,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = if (notifEnabled) "Scheduled for ${formatReminderTime(reminderHour, reminderMinute)}" else "Disabled",
                                color = ByteCreamMuted,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Switch(
                        checked = notifEnabled,
                        onCheckedChange = { enable ->
                            if (enable) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    val hasPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!hasPermission) {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        return@Switch
                                    }
                                }
                                notifEnabled = true
                                scope.launch {
                                    settingsRepository.saveNotificationSettings(true, reminderHour, reminderMinute, reminderSmartNudge)
                                    DailyReminderScheduler.scheduleDailyReminder(context, reminderHour, reminderMinute)
                                    snackbarHostState.showSnackbar("Daily reminder set for ${formatReminderTime(reminderHour, reminderMinute)}")
                                }
                            } else {
                                notifEnabled = false
                                scope.launch {
                                    settingsRepository.saveNotificationSettings(false, reminderHour, reminderMinute, reminderSmartNudge)
                                    DailyReminderScheduler.cancelDailyReminder(context)
                                    snackbarHostState.showSnackbar("Daily reminders turned off")
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ByteCream,
                            checkedTrackColor = ByteTomato,
                            uncheckedThumbColor = ByteCreamMuted,
                            uncheckedTrackColor = ByteSurfaceVariantDark
                        )
                    )
                }

                if (notifEnabled) {
                    HorizontalDivider(color = ByteSurfaceVariantDark, thickness = 1.dp)

                    // Time presets
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "REMINDER TIME",
                                color = ByteCreamMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = formatReminderTime(reminderHour, reminderMinute),
                                color = ByteCream,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        // Quick Preset Times: 8:30 AM (Breakfast), 1:00 PM (Lunch), 8:00 PM (Dinner), 9:30 PM (Night)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                Triple("8:30 AM", 8, 30),
                                Triple("1:00 PM", 13, 0),
                                Triple("8:00 PM", 20, 0),
                                Triple("9:30 PM", 21, 30)
                            ).forEach { (label, h, m) ->
                                val isSelected = reminderHour == h && reminderMinute == m
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) ByteTomato.copy(alpha = 0.2f) else ByteSurfaceVariantDark)
                                        .border(
                                            width = if (isSelected) 1.dp else 0.dp,
                                            color = if (isSelected) ByteTomato else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            reminderHour = h
                                            reminderMinute = m
                                            scope.launch {
                                                settingsRepository.saveNotificationSettings(true, h, m, reminderSmartNudge)
                                                DailyReminderScheduler.scheduleDailyReminder(context, h, m)
                                                snackbarHostState.showSnackbar("Reminder time updated to $label")
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) ByteTomato else ByteCream,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Custom Time Picker Button
                        OutlinedButton(
                            onClick = { showTimePickerDialog = true },
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ByteCream)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = ByteTomato
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Custom Time (${formatReminderTime(reminderHour, reminderMinute)})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Smart Nudge Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Smart Log Check",
                                color = ByteCream,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Personalizes reminder if meals are already logged today",
                                color = ByteCreamMuted,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = reminderSmartNudge,
                            onCheckedChange = { checked ->
                                reminderSmartNudge = checked
                                scope.launch {
                                    settingsRepository.saveNotificationSettings(notifEnabled, reminderHour, reminderMinute, checked)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ByteCream,
                                checkedTrackColor = ByteTomato,
                                uncheckedThumbColor = ByteCreamMuted,
                                uncheckedTrackColor = ByteSurfaceVariantDark
                            )
                        )
                    }

                    // Send Test Notification Button
                    OutlinedButton(
                        onClick = {
                            DailyReminderScheduler.sendTestNotification(context)
                            scope.launch {
                                snackbarHostState.showSnackbar("Test notification sent! Check your notification shade.")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ByteCream)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = ByteCreamMuted
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Send Test Notification",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }

            // 6. Save Configuration Button
            Button(
                onClick = {
                    scope.launch {
                        settingsRepository.saveServerConfig(
                            url = serverUrl,
                            apiKey = apiKey.ifBlank { null },
                            preserveExistingTokenIfNull = true
                        )
                        val contOz = defaultContainerOz.toFloatOrNull() ?: 20f
                        val contName = defaultContainerName.ifBlank { "Bottle" }
                        settingsRepository.saveDefaultContainer(contOz, contName)
                        waterRepository?.setDefaultContainer(contOz, contName)
                        settingsRepository.saveMacroTargets(
                            calories = targetCalories.toFloatOrNull() ?: 2200f,
                            protein = targetProtein.toFloatOrNull() ?: 160f,
                            carbs = targetCarbs.toFloatOrNull() ?: 220f,
                            fat = targetFat.toFloatOrNull() ?: 70f,
                            waterGoalOz = targetWater.toFloatOrNull() ?: 64f
                        )
                        snackbarHostState.showSnackbar("Configuration saved successfully!")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ByteTomato)
            ) {
                Text(
                    text = "Save Configuration",
                    color = ByteCream,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Custom Time Picker Dialog
        if (showTimePickerDialog) {
            var dialogHour by remember { mutableStateOf(reminderHour) }
            var dialogMinute by remember { mutableStateOf(reminderMinute) }

            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showTimePickerDialog = false }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(ByteSurfaceDark)
                        .padding(20.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Set Reminder Time",
                            color = ByteCream,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Hour Stepper
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(onClick = { dialogHour = (dialogHour + 1) % 24 }) {
                                    Icon(Icons.Default.Add, null, tint = ByteCream)
                                }
                                val h12 = if (dialogHour == 0) 12 else if (dialogHour > 12) dialogHour - 12 else dialogHour
                                Text(
                                    text = String.format(java.util.Locale.US, "%02d", h12),
                                    color = ByteCream,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(onClick = { dialogHour = if (dialogHour == 0) 23 else dialogHour - 1 }) {
                                    Icon(Icons.Default.Remove, null, tint = ByteCream)
                                }
                            }

                            Text(
                                text = ":",
                                color = ByteCream,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )

                            // Minute Stepper
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(onClick = { dialogMinute = (dialogMinute + 5) % 60 }) {
                                    Icon(Icons.Default.Add, null, tint = ByteCream)
                                }
                                Text(
                                    text = String.format(java.util.Locale.US, "%02d", dialogMinute),
                                    color = ByteCream,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(onClick = { dialogMinute = if (dialogMinute < 5) 55 else dialogMinute - 5 }) {
                                    Icon(Icons.Default.Remove, null, tint = ByteCream)
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            // AM / PM Toggle
                            val isPm = dialogHour >= 12
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (!isPm) ByteTomato else ByteSurfaceVariantDark)
                                        .clickable {
                                            if (isPm) dialogHour -= 12
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("AM", color = ByteCream, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isPm) ByteTomato else ByteSurfaceVariantDark)
                                        .clickable {
                                            if (!isPm) dialogHour += 12
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("PM", color = ByteCream, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(onClick = { showTimePickerDialog = false }) {
                                Text("Cancel", color = ByteCreamMuted)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    reminderHour = dialogHour
                                    reminderMinute = dialogMinute
                                    showTimePickerDialog = false
                                    scope.launch {
                                        settingsRepository.saveNotificationSettings(notifEnabled, dialogHour, dialogMinute, reminderSmartNudge)
                                        if (notifEnabled) {
                                            DailyReminderScheduler.scheduleDailyReminder(context, dialogHour, dialogMinute)
                                        }
                                        snackbarHostState.showSnackbar("Reminder set for ${formatReminderTime(dialogHour, dialogMinute)}")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ByteTomato)
                            ) {
                                Text("Save Time", color = ByteCream, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatReminderTime(hour: Int, minute: Int): String {
    val h = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
    val m = String.format(java.util.Locale.US, "%02d", minute)
    val ampm = if (hour >= 12) "PM" else "AM"
    return "$h:$m $ampm"
}
