package com.bytelog.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onNavigateBack: () -> Unit
) {
    val serverUrlPref by settingsRepository.serverUrl.collectAsState(initial = "http://10.0.2.2:3000")
    val apiKeyPref by settingsRepository.apiKey.collectAsState(initial = "")
    val calPref by settingsRepository.targetCalories.collectAsState(initial = 2200f)
    val pPref by settingsRepository.targetProtein.collectAsState(initial = 160f)
    val cPref by settingsRepository.targetCarbs.collectAsState(initial = 220f)
    val fPref by settingsRepository.targetFat.collectAsState(initial = 70f)

    var serverUrl by remember { mutableStateOf(serverUrlPref) }
    var apiKey by remember { mutableStateOf(apiKeyPref ?: "") }
    var targetCalories by remember { mutableStateOf(calPref.roundToInt().toString()) }
    var targetProtein by remember { mutableStateOf(pPref.roundToInt().toString()) }
    var targetCarbs by remember { mutableStateOf(cPref.roundToInt().toString()) }
    var targetFat by remember { mutableStateOf(fPref.roundToInt().toString()) }

    LaunchedEffect(serverUrlPref, apiKeyPref, calPref, pPref, cPref, fPref) {
        serverUrl = serverUrlPref
        apiKey = apiKeyPref ?: ""
        targetCalories = calPref.roundToInt().toString()
        targetProtein = pPref.roundToInt().toString()
        targetCarbs = cPref.roundToInt().toString()
        targetFat = fPref.roundToInt().toString()
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
                    text = "Settings & Targets",
                    color = ByteCream,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            // Sparky Fitness Backend Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ByteSurfaceDark)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "SPARKY FITNESS SERVER",
                    color = ByteTomato,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL", color = ByteCreamMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = ByteTomato,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key / Bearer Token", color = ByteCreamMuted) },
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

            // Macro Targets Section
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
            }

            // Save Button
            Button(
                onClick = {
                    scope.launch {
                        settingsRepository.saveServerConfig(serverUrl, null, apiKey.ifBlank { null })
                        settingsRepository.saveMacroTargets(
                            targetCalories.toFloatOrNull() ?: 2200f,
                            targetProtein.toFloatOrNull() ?: 160f,
                            targetCarbs.toFloatOrNull() ?: 220f,
                            targetFat.toFloatOrNull() ?: 70f
                        )
                        snackbarHostState.showSnackbar("Settings saved successfully!")
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
        }
    }
}
