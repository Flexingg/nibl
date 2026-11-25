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

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
        if (enabled && granted) {
            Text(
                "If the SparkyFitness phone app also syncs Nutrition from Health Connect, it copies this food back into Sparky. " +
                    "Nibl hides those copies, but Sparky's website counts them: turn off Nutrition in that app's Health Connect sync " +
                    "(or this switch) to keep Sparky's totals right.",
                color = cs.onSurfaceVariant, fontSize = 12.sp
            )
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
