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
import androidx.compose.material3.Text
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

    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionTestResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    var isSigningIn by remember { mutableStateOf(false) }
    var isManualSyncing by remember { mutableStateOf(false) }

    LaunchedEffect(serverUrlPref, apiKeyPref, savedEmailPref, calPref, pPref, cPref, fPref, wPref) {
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
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = ByteInkDark
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = ByteCream
                    )
                }
                Text(
                    text = "Settings & Connectivity",
                    color = ByteCream,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

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
            }

            // 5. Save Configuration Button
            Button(
                onClick = {
                    scope.launch {
                        settingsRepository.saveServerConfig(
                            url = serverUrl,
                            apiKey = apiKey.ifBlank { null },
                            preserveExistingTokenIfNull = true
                        )
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
    }
}
