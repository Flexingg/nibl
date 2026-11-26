package com.bytelog.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.BuildConfig
import com.bytelog.app.data.repository.SettingsRepository
import com.bytelog.app.data.update.AppUpdater
import com.bytelog.app.data.update.UpdateInfo
import com.bytelog.app.data.update.UpdateState
import kotlinx.coroutines.launch

/** Starts the download and install, or asks Android for the install permission first. */
private fun startUpdate(context: android.content.Context, updater: AppUpdater, info: UpdateInfo, scope: kotlinx.coroutines.CoroutineScope) {
    if (!updater.canInstall()) {
        runCatching { context.startActivity(updater.installPermissionIntent()) }
        // The state says what's needed; tapping Update again after allowing it carries on
        scope.launch { updater.downloadAndInstall(info) }
        return
    }
    scope.launch {
        // Downloaded already: straight to installing
        if (updater.state.value is UpdateState.Ready) updater.installReady(quietly = false) else updater.downloadAndInstall(info)
    }
}

/**
 * Home-screen banner when a newer version is on GitHub: Update, or Later (hides it until a newer version).
 * Also shows download progress and problems while an update is under way.
 */
@Composable
fun UpdateBanner(updater: AppUpdater, settings: SettingsRepository, modifier: Modifier = Modifier) {
    val state by updater.state.collectAsState()
    val dismissed by settings.dismissedUpdate.collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showNotes by remember { mutableStateOf<UpdateInfo?>(null) }
    val cs = MaterialTheme.colorScheme

    val info = when (val s = state) {
        is UpdateState.Available -> s.info.takeIf { it.version != dismissed }
        is UpdateState.Downloading -> s.info
        is UpdateState.Installing -> s.info
        is UpdateState.NeedsPermission -> s.info
        is UpdateState.Failed -> s.info
        is UpdateState.Ready -> s.info.takeIf { it.version != dismissed }
        else -> null
    }
    val ready = state is UpdateState.Ready
    AnimatedVisibility(info != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(), modifier = modifier) {
        val i = info ?: return@AnimatedVisibility
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.primaryContainer).padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (ready) "Nibl ${i.version} is ready to install" else "Nibl ${i.version} is available", color = cs.onPrimaryContainer, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(statusLine(state, i), color = cs.onPrimaryContainer.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 2)
                }
            }
            (state as? UpdateState.Downloading)?.let { d ->
                if (d.progress != null) LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp))
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (i.notes.isNotBlank()) TextButton(onClick = { showNotes = i }) { Text("What's new", color = cs.onPrimaryContainer) }
                if (state is UpdateState.Available || state is UpdateState.Failed || ready) {
                    TextButton(onClick = { scope.launch { settings.saveDismissedUpdate(i.version) }; if (state is UpdateState.Failed) updater.onBannerDismissed(i) }) {
                        Text("Later", color = cs.onPrimaryContainer)
                    }
                }
                if (state !is UpdateState.Downloading && state !is UpdateState.Installing) {
                    TextButton(onClick = { startUpdate(context, updater, i, scope) }) {
                        Text(when { state is UpdateState.Failed -> "Try again"; ready -> "Install"; else -> "Update" }, color = cs.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    showNotes?.let { ReleaseNotesDialog(it) { showNotes = null } }
}

private fun statusLine(state: UpdateState, info: UpdateInfo): String = when (state) {
    is UpdateState.Downloading -> state.progress?.let { "Downloading… ${(it * 100).toInt()}%" } ?: "Downloading…"
    is UpdateState.Installing -> "Confirm the install when Android asks."
    is UpdateState.NeedsPermission -> "Allow Nibl to install apps, then tap Update again."
    is UpdateState.Failed -> state.message
    is UpdateState.Ready -> "Downloaded. " + (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) "It installs when you leave the app, or now." else "Tap Install.") + " Keeps all your data."
    else -> "You're on ${BuildConfig.VERSION_NAME}. Updating keeps all your data." + if (info.apkSize > 0) " (${info.apkSize / 1_000_000} MB)" else ""
}

@Composable
private fun ReleaseNotesDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's new in ${info.version}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(info.notes, fontSize = 14.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Settings card: this version, check now, check automatically, and the update when there is one. */
@Composable
fun AppUpdateCard(updater: AppUpdater, settings: SettingsRepository, modifier: Modifier = Modifier) {
    val state by updater.state.collectAsState()
    val auto by settings.autoCheckUpdates.collectAsState(initial = true)
    val autoInstall by settings.autoInstallUpdates.collectAsState(initial = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var showNotes by remember { mutableStateOf<UpdateInfo?>(null) }

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("APP UPDATES", color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nibl ${BuildConfig.VERSION_NAME}", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when (val s = state) {
                        UpdateState.Idle -> "Updates come from the app's GitHub releases."
                        UpdateState.Checking -> "Checking GitHub…"
                        is UpdateState.UpToDate -> s.latest?.let { "You're up to date: GitHub's latest is $it." } ?: "You're up to date."
                        is UpdateState.Available -> "Version ${s.info.version} is available (you have ${BuildConfig.VERSION_NAME})."
                        is UpdateState.Downloading -> s.progress?.let { "Downloading ${s.info.version}… ${(it * 100).toInt()}%" } ?: "Downloading ${s.info.version}…"
                        is UpdateState.Installing -> "Confirm the install when Android asks."
                        is UpdateState.NeedsPermission -> "Allow Nibl to install apps, then tap Update again."
                        is UpdateState.Failed -> s.message
                        is UpdateState.Ready -> "Version ${s.info.version} is downloaded and ready to install."
                    },
                    color = if (state is UpdateState.Failed) cs.error else cs.onSurfaceVariant, fontSize = 12.sp
                )
            }
            val info = when (val s = state) {
                is UpdateState.Available -> s.info
                is UpdateState.NeedsPermission -> s.info
                is UpdateState.Failed -> s.info
                is UpdateState.Ready -> s.info
                else -> null
            }
            when {
                info != null -> TextButton(onClick = { startUpdate(context, updater, info, scope) }) {
                    Text(if (state is UpdateState.Ready) "Install" else "Update", fontWeight = FontWeight.Bold)
                }
                state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing -> Box(Modifier.size(48.dp))
                else -> TextButton(onClick = {
                    scope.launch {
                        updater.check()
                        settings.saveLastUpdateCheck(System.currentTimeMillis())
                    }
                }) { Text("Check now") }
            }
        }
        (state as? UpdateState.Downloading)?.let { d ->
            if (d.progress != null) LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        (state as? UpdateState.Available)?.info?.takeIf { it.notes.isNotBlank() }?.let { i ->
            TextButton(onClick = { showNotes = i }, modifier = Modifier.padding(start = 0.dp)) { Text("What's new in ${i.version}") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Check automatically", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Looks for a new version when you open the app and tells you on the home screen.", color = cs.onSurfaceVariant, fontSize = 11.sp)
            }
            Switch(checked = auto, onCheckedChange = { scope.launch { settings.saveAutoCheckUpdates(it) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Update automatically", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Downloads a new version in the background and installs it when you leave the app, so it never interrupts you. " +
                        "Android may ask you to confirm the first time; after that it updates by itself.",
                    color = cs.onSurfaceVariant, fontSize = 11.sp
                )
            }
            Switch(checked = autoInstall && auto, enabled = auto, onCheckedChange = { scope.launch { settings.saveAutoInstallUpdates(it) } })
        }
    }
    showNotes?.let { ReleaseNotesDialog(it) { showNotes = null } }
}
