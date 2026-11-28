package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.ui.recipes.Pill
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroProtein
import kotlinx.coroutines.launch

/**
 * Settings card: the links and shell commands that let a launcher or gesture app (OmniPrompt, XGesture, Tasker)
 * ask Sparky or find a food without opening Nibl.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickCommandsCard(settings: SettingsRepository, modifier: Modifier = Modifier) {
    val key by settings.quickCommandKey.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    CollapsibleCard("Search bar & launcher", Icons.Default.Search, modifier,
        summary = if (key != null) "Sparky on" else "Food search on") {
        Text(
            "Use Nibl from your launcher's search bar or a gesture app without opening it. Add these as custom " +
                "searches; %s is where your text goes. Results come back as a notification.",
            color = ByteCreamMuted, fontSize = 12.sp
        )

        Command("Find a food", "Matches with a Log button on each", "nibl://food?q=%s") { copy(it) }
        Command("Open Fast Search", "Opens Nibl with the search typed in", "nibl://search?q=%s") { copy(it) }

        val k = key
        if (k == null) {
            Text(
                "Asking Sparky in the background needs a key, since Sparky can change your diary. Without one, " +
                    "nibl://ask opens the chat with your question typed in.",
                color = ByteCreamMuted, fontSize = 12.sp
            )
            Pill("Turn on Ask Sparky", ByteTomato, filled = true) { scope.launch { settings.newQuickCommandKey() } }
        } else {
            Command("Ask Sparky", "The answer arrives as a notification you can reply to", "nibl://ask?key=$k&q=%s") { copy(it) }
            Command("Ask with web research", "Uses your web research assistant", "nibl://ask?key=$k&web=1&q=%s") { copy(it) }
            Command(
                "Shell / Shizuku",
                "For apps that run commands rather than open links",
                "am start -a com.bytelog.app.action.ASK --es key $k --es q \"%s\""
            ) { copy(it) }
            Text("Anyone with the key can ask Sparky to change your diary. Make a new one if it gets out.", color = ByteCreamMuted, fontSize = 11.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("New key", MacroProtein) { scope.launch { settings.newQuickCommandKey() } }
                Pill("Turn off", MacroCarbs) { scope.launch { settings.clearQuickCommandKey() } }
            }
        }
    }
}

@Composable
private fun Command(title: String, hint: String, template: String, onCopy: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ByteSurfaceDark).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, color = ByteCream, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(hint, color = ByteCreamMuted, fontSize = 11.sp)
        Text(template, color = ByteCream, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Pill("Copy", ByteTomato) { onCopy(template) }
    }
}
