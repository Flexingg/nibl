package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.research.ProviderId
import com.bytelog.app.research.ResearchConfig
import com.bytelog.app.research.ResearchFactory
import com.bytelog.app.ui.recipes.Pill
import com.bytelog.app.ui.recipes.byteFieldColors
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroProtein
import kotlinx.coroutines.launch

/**
 * Settings card for the web research assistant: which model, with the user's own API key. The key is stored
 * encrypted on this phone and is sent only to that provider.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResearchAssistantCard(settings: SettingsRepository, modifier: Modifier = Modifier) {
    val config by settings.researchConfig.collectAsState(initial = ResearchConfig())
    val scope = rememberCoroutineScope()
    val provider = config.provider

    var keyDraft by remember(provider) { mutableStateOf("") }
    var modelDraft by remember(provider, config.models) { mutableStateOf(config.modelFor(provider)) }
    var tavilyDraft by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember(provider) { mutableStateOf<String?>(null) }
    var testOk by remember(provider) { mutableStateOf(false) }
    var models by remember(provider) { mutableStateOf<List<String>>(emptyList()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ByteSurfaceDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("WEB RESEARCH ASSISTANT", color = ByteTomato, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
        Text(
            "In Sparky chat, switch on \"Web search\" to let a model search the internet and read your Sparky food library and diary. " +
                "It can log what it finds straight into your diary (you approve each change), or pass the job to Sparky's own AI. " +
                "It uses your own API key, stored encrypted on this phone and sent only to the provider you pick. " +
                "Your messages, and the Sparky data it looks up to answer, go to that provider.",
            color = ByteCreamMuted, fontSize = 12.sp
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderId.entries.forEach { p ->
                Pill(p.label, MacroProtein, filled = provider == p) { scope.launch { settings.saveResearchProvider(p) } }
            }
        }

        val saved = config.keyFor(provider) != null
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it.trim() },
            label = { Text(if (saved) "API key saved — type to replace" else "${provider.label} API key (${provider.keyHint})") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = byteFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("Save key", ByteTomato, filled = keyDraft.isNotBlank()) {
                if (keyDraft.isNotBlank()) scope.launch { settings.saveResearchKey(provider, keyDraft); keyDraft = ""; testMessage = null }
            }
            if (saved) Pill("Remove key", MacroCarbs) { scope.launch { settings.saveResearchKey(provider, ""); testMessage = null } }
            if (saved) Text("Saved on this phone", color = ByteCreamMuted, fontSize = 11.sp)
        }

        OutlinedTextField(
            value = modelDraft,
            onValueChange = { modelDraft = it },
            label = { Text("Model") },
            singleLine = true,
            colors = byteFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("Save model", MacroProtein, filled = modelDraft.trim() != config.modelFor(provider)) {
                scope.launch { settings.saveResearchModel(provider, modelDraft.trim().let { if (it == provider.defaultModel) "" else it }) }
            }
            Pill(if (testing) "Checking…" else "Test key & list models", MacroCarbs) {
                if (testing) return@Pill
                val llm = ResearchFactory.provider(config)
                if (llm == null) {
                    testOk = false; testMessage = "Save an API key first."
                } else {
                    testing = true
                    scope.launch {
                        llm.listModels().fold(
                            onSuccess = { list -> models = list; testOk = true; testMessage = "Key works. ${list.size} models available." },
                            onFailure = { e -> testOk = false; testMessage = e.message ?: "The check failed." }
                        )
                        testing = false
                    }
                }
            }
        }
        testMessage?.let { Text(it, color = if (testOk) MacroProtein else ByteTomato, fontSize = 12.sp) }
        if (models.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                models.take(14).forEach { m -> Pill(m, MacroProtein, filled = m == modelDraft) { modelDraft = m } }
            }
        }

        if (provider == ProviderId.DEEPSEEK) {
            OutlinedTextField(
                value = tavilyDraft,
                onValueChange = { tavilyDraft = it.trim() },
                label = { Text(if (!config.tavilyKey.isNullOrBlank()) "Tavily key saved — type to replace" else "Tavily key for web search (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = byteFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Save Tavily key", MacroProtein, filled = tavilyDraft.isNotBlank()) {
                    if (tavilyDraft.isNotBlank()) scope.launch { settings.saveTavilyKey(tavilyDraft); tavilyDraft = "" }
                }
                if (!config.tavilyKey.isNullOrBlank()) Pill("Remove", MacroCarbs) { scope.launch { settings.saveTavilyKey("") } }
            }
        }
        Text(ResearchFactory.searchDescription(config), color = ByteCreamMuted, fontSize = 11.sp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ask before changing my data", color = ByteCream, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Recommended. Web pages can contain text meant to trick an AI, so each message it sends to Sparky's AI to log or change something needs your approval.",
                    color = ByteCreamMuted, fontSize = 11.sp
                )
            }
            Switch(
                checked = config.askBeforeWriting,
                onCheckedChange = { scope.launch { settings.saveAskBeforeWriting(it) } },
                colors = SwitchDefaults.colors(checkedTrackColor = ByteTomato)
            )
        }
    }
}
