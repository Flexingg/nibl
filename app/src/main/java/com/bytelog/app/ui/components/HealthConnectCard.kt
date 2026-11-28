package com.bytelog.app.ui.components

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.bytelog.app.ByteLogApplication
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.health.HealthConnectStore
import kotlinx.coroutines.launch

/**
 * Settings: write the diary's food and water to Health Connect. What the SparkyFitness app has already written is
 * left alone, so nothing counts twice.
 */
@Composable
fun HealthConnectCard(settings: SettingsRepository) {
    val context = LocalContext.current
    val app = context.applicationContext as? ByteLogApplication
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val enabled by settings.healthConnectWrite.collectAsState(initial = false)
    val available = remember { runCatching { HealthConnectStore.isAvailable(context) }.getOrDefault(false) }
    var granted by remember { mutableStateOf(false) }
    LaunchedEffect(available) { if (available) granted = runCatching { HealthConnectStore.hasPermissions(context) }.getOrDefault(false) }
    val status = app?.healthConnectSync?.status?.collectAsState()?.value

    val askPermission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        granted = got.containsAll(HealthConnectStore.PERMISSIONS)
        if (granted) scope.launch { settings.saveHealthConnectWrite(true); app?.requestHealthSync(0) }
    }

    fun turn(on: Boolean) {
        if (!on) {
            scope.launch {
                settings.saveHealthConnectWrite(false)
                // Takes back what Nibl wrote; the SparkyFitness app's records stay
                app?.healthConnectSync?.removeAll()
            }
        } else if (!available) {
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
            runCatching { context.startActivity(market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else if (!granted) {
            askPermission.launch(HealthConnectStore.PERMISSIONS)
        } else scope.launch { settings.saveHealthConnectWrite(true); app?.requestHealthSync(0) }
    }

    CollapsibleCard("Health Connect", Icons.Default.FavoriteBorder,
        summary = when { !available -> "Not installed"; enabled && granted -> "On · sharing food and water"; else -> "Off" }, spacing = 8.dp) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { turn(!(enabled && granted)) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(16.dp)).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.FavoriteBorder, contentDescription = null, tint = cs.onTertiaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Health Connect", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (available) "Write food and water to Health Connect" else "Install Health Connect to share food and water",
                    color = cs.onSurfaceVariant, fontSize = 13.sp
                )
            }
            Switch(checked = enabled && granted, onCheckedChange = { turn(it) })
        }
        Text(
            "Meals and drinks the SparkyFitness app has already written there are left as they are, so nothing is counted twice. " +
                "Nibl writes while it's open, a few seconds after the diary changes.",
            color = cs.onSurfaceVariant, fontSize = 12.sp
        )
        // The SparkyFitness app does both Health Connect jobs for the Sparky diary; doing them here too counts things twice
        val sparkyApp = remember { com.bytelog.app.health.SparkyFitnessApp.installed(context) }
        if (sparkyApp) {
            val leave by settings.hcLeaveToSparkyApp.collectAsState(initial = true)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                    scope.launch { settings.saveHcLeaveToSparkyApp(!leave); app?.requestHealthSync(0) }
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Leave it to the SparkyFitness app", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (leave) "The SparkyFitness app is on this phone. It writes your Sparky diary (food logged in Nibl too) to Health " +
                            "Connect and sends steps, sleep and workouts to Sparky, so Nibl only reads. Nothing is counted twice."
                        else "Nibl writes too. The SparkyFitness app may import Nibl's food back into Sparky (Nibl hides those copies, " +
                            "but Sparky's website counts them) and sleep and workouts can reach Sparky twice.",
                        color = cs.onSurfaceVariant, fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = leave, onCheckedChange = { on -> scope.launch { settings.saveHcLeaveToSparkyApp(on); app?.requestHealthSync(0) } })
            }
        }
        if (enabled && granted && status != null && status.lastRun > 0L) {
            val ago = DateUtils.getRelativeTimeSpanString(status.lastRun, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            Text(
                status.error?.let { "Last try $ago: $it" }
                    ?: ("Written $ago · ${status.written} from Nibl" + if (status.leftToSparky > 0) " · ${status.leftToSparky} already from SparkyFitness" else ""),
                color = if (status.error != null) cs.error else cs.onSurfaceVariant, fontSize = 12.sp
            )
        }
    }
}
