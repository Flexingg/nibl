package com.bytelog.app.ui.health

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import com.bytelog.app.health.BackfillPreset
import com.bytelog.app.health.BackfillProgress
import com.bytelog.app.health.DailyHealthSnapshot
import com.bytelog.app.health.ExtendedSyncStatus
import com.bytelog.app.health.HealthConnectBackfill
import com.bytelog.app.health.HealthConnectExtendedSync
import com.bytelog.app.health.HealthConnectWorker
import com.bytelog.app.health.HealthMetricGroup
import com.bytelog.app.health.HealthSyncConfig
import com.bytelog.app.ui.components.ScreenHeader
import com.bytelog.app.ui.components.rememberScrollFraction
import com.bytelog.app.ui.theme.ByteCardDark
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.FluidRadius
import com.bytelog.app.ui.theme.LocalByteExtraColors
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Detailed Health Connect management screen: live daily summary, permission status & grant flow,
 * per-metric sync toggles, 30-day chunked historical backfill, and manual sync status.
 */
@Composable
fun HealthConnectDetailScreen(
    extendedSync: HealthConnectExtendedSync,
    backfill: HealthConnectBackfill,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val extra = LocalByteExtraColors.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val scrollFraction = rememberScrollFraction(listState)

    val available = remember { extendedSync.isAvailable() }
    val snapshots by extendedSync.snapshots.collectAsState()
    val config by extendedSync.config.collectAsState()
    val syncStatus by extendedSync.status.collectAsState()
    val backfillProgress by backfill.progress.collectAsState()

    var grantedPermissions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedPreset by remember { mutableStateOf(BackfillPreset.D90) }

    val todayKey = remember { LocalDate.now().toString() }
    val todaySnapshot = snapshots[todayKey]

    LaunchedEffect(available) {
        extendedSync.loadConfig()
        if (available) {
            grantedPermissions = extendedSync.getGrantedPermissions()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        grantedPermissions = granted
        if (granted.isNotEmpty()) {
            scope.launch { extendedSync.syncRecent(days = 7, uploadToSparky = true) }
        }
    }

    val totalPermissions = HealthMetricGroup.ALL_PERMISSIONS.size
    val grantedCount = HealthMetricGroup.ALL_PERMISSIONS.count { it in grantedPermissions }
    val hasHistoryPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in grantedPermissions
    val hasBackgroundPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in grantedPermissions

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ByteInkDark
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            ScreenHeader(
                title = "Health Connect",
                subtitle = if (available) "$grantedCount of $totalPermissions permissions granted" else "Not installed",
                onBack = onBack,
                fraction = scrollFraction
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Today's Live Summary Card
                item(key = "summary") {
                    TodayHealthSummaryCard(
                        snapshot = todaySnapshot,
                        accentSuccess = extra.success
                    )
                }

                // 2. Permissions Card
                item(key = "permissions") {
                    PermissionsCard(
                        available = available,
                        grantedCount = grantedCount,
                        totalPermissions = totalPermissions,
                        hasHistory = hasHistoryPermission,
                        hasBackground = hasBackgroundPermission,
                        onGrantAll = {
                            if (!available) {
                                val market = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=com.google.android.apps.healthdata")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(market) }
                            } else {
                                permissionLauncher.launch(HealthMetricGroup.ALL_PERMISSIONS)
                            }
                        }
                    )
                }

                // 3. Per-Metric Sync Toggles Card
                item(key = "toggles") {
                    MetricTogglesCard(
                        config = config,
                        onUpdate = { transform ->
                            scope.launch {
                                val next = extendedSync.updateConfig(transform)
                                if (next.backgroundSyncEnabled) {
                                    if (!hasBackgroundPermission && available) {
                                        permissionLauncher.launch(
                                            HealthMetricGroup.BACKGROUND.permissions + HealthMetricGroup.ALL_PERMISSIONS
                                        )
                                    }
                                    HealthConnectWorker.schedule(context)
                                } else {
                                    HealthConnectWorker.cancel(context)
                                }
                            }
                        }
                    )
                }

                // 4. Historical Backfill Card
                item(key = "backfill") {
                    HistoricalBackfillCard(
                        selectedPreset = selectedPreset,
                        onSelectPreset = { selectedPreset = it },
                        hasHistoryPermission = hasHistoryPermission,
                        progress = backfillProgress,
                        available = available,
                        onRequestHistoryPermission = {
                            if (available) {
                                permissionLauncher.launch(HealthMetricGroup.HISTORY.permissions)
                            }
                        },
                        onStart = { backfill.start(scope, selectedPreset.days) },
                        onCancel = { backfill.cancel() },
                        onRetry = { backfill.retry(scope) }
                    )
                }

                // 5. Sync Now & Last Sync Summary
                item(key = "sync_now") {
                    SyncNowCard(
                        available = available,
                        status = syncStatus,
                        onSyncNow = {
                            scope.launch {
                                extendedSync.syncRecent(days = 7, uploadToSparky = true)
                                grantedPermissions = extendedSync.getGrantedPermissions()
                            }
                        }
                    )
                }

                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

@Composable
private fun TodayHealthSummaryCard(
    snapshot: DailyHealthSnapshot?,
    accentSuccess: Color
) {
    val numberFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val stepsText = snapshot?.steps?.takeIf { it > 0 }?.let { numberFmt.format(it) } ?: "—"
    val activeCalText = snapshot?.activeCaloriesKcal?.takeIf { it > 0.0 }
        ?.let { "${it.roundToInt()} kcal" } ?: "—"
    val sleepText = snapshot?.sleepMinutes?.takeIf { it > 0 }
        ?.let { formatMinutes(it) } ?: "—"
    val restingHrText = snapshot?.restingHrBpm?.let { "$it bpm" } ?: "—"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluidRadius.lg))
            .background(ByteCardDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Today's Summary", color = ByteCream, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (snapshot != null) "Live cache" else "No data yet",
                color = ByteTextSecondary,
                fontSize = 12.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SummaryMetricTile(
                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                label = "Steps",
                value = stepsText,
                tint = accentSuccess,
                modifier = Modifier.weight(1f)
            )
            SummaryMetricTile(
                icon = Icons.Default.LocalFireDepartment,
                label = "Active kcal",
                value = activeCalText,
                tint = ByteTomato,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SummaryMetricTile(
                icon = Icons.Default.Bedtime,
                label = "Sleep",
                value = sleepText,
                tint = MacroProtein,
                modifier = Modifier.weight(1f)
            )
            SummaryMetricTile(
                icon = Icons.Default.Favorite,
                label = "Resting HR",
                value = restingHrText,
                tint = MacroFat,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SummaryMetricTile(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(FluidRadius.md))
            .background(ByteSurfaceVariantDark.copy(alpha = 0.6f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(label, color = ByteTextSecondary, fontSize = 11.sp)
            Text(value, color = ByteCream, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PermissionsCard(
    available: Boolean,
    grantedCount: Int,
    totalPermissions: Int,
    hasHistory: Boolean,
    hasBackground: Boolean,
    onGrantAll: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val extra = LocalByteExtraColors.current
    val allGranted = available && grantedCount == totalPermissions

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluidRadius.lg))
            .background(ByteCardDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (allGranted) extra.success.copy(alpha = 0.16f) else cs.tertiaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (allGranted) Icons.Default.CheckCircle else Icons.Default.Security,
                    contentDescription = null,
                    tint = if (allGranted) extra.success else cs.onTertiaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text("Permissions", color = ByteCream, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !available -> "Health Connect app is not installed"
                        allGranted -> "All $totalPermissions permissions granted"
                        else -> "$grantedCount of $totalPermissions granted"
                    },
                    color = ByteTextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        if (available) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PermissionStatusPill("History (>30d)", hasHistory, extra.success)
                PermissionStatusPill("Background", hasBackground, extra.success)
            }
        }

        Button(
            onClick = onGrantAll,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(FluidRadius.md),
            colors = ButtonDefaults.buttonColors(containerColor = ByteTomato, contentColor = ByteOnTomato)
        ) {
            Text(
                text = when {
                    !available -> "Install Health Connect"
                    allGranted -> "Manage Permissions"
                    else -> "Grant Permissions"
                },
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PermissionStatusPill(label: String, granted: Boolean, successColor: Color) {
    val tint = if (granted) successColor else ByteTextSecondary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = if (granted) "$label · Granted" else "$label · Off",
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MetricTogglesCard(
    config: HealthSyncConfig,
    onUpdate: ((HealthSyncConfig) -> HealthSyncConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluidRadius.lg))
            .background(ByteCardDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Sync Metrics", color = ByteCream, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Choose which Health Connect data types Nibl reads and forwards to SparkyFitness.",
            color = ByteTextSecondary,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(6.dp))

        MetricToggleRow(
            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
            title = "Steps & Activity",
            subtitle = "Steps, active & total calories, distance",
            checked = config.syncActivitySteps,
            onCheckedChange = { on -> onUpdate { it.copy(syncActivitySteps = on) } }
        )
        HorizontalDivider(color = ByteSurfaceVariantDark.copy(alpha = 0.6f))
        MetricToggleRow(
            icon = Icons.Default.MonitorWeight,
            title = "Weight & Body Fat (Read)",
            subtitle = "Import scale readings into Nibl without duplicates",
            checked = config.syncWeightBodyComp,
            onCheckedChange = { on -> onUpdate { it.copy(syncWeightBodyComp = on) } }
        )
        HorizontalDivider(color = ByteSurfaceVariantDark.copy(alpha = 0.6f))
        MetricToggleRow(
            icon = Icons.Default.MonitorWeight,
            title = "Write Nibl Weight to Health Connect",
            subtitle = "Bidirectional sync when you log weight in Nibl",
            checked = config.writeWeightToHealthConnect,
            onCheckedChange = { on -> onUpdate { it.copy(writeWeightToHealthConnect = on) } }
        )
        HorizontalDivider(color = ByteSurfaceVariantDark.copy(alpha = 0.6f))
        MetricToggleRow(
            icon = Icons.Default.Bedtime,
            title = "Sleep",
            subtitle = "Sleep duration and stages",
            checked = config.syncSleep,
            onCheckedChange = { on -> onUpdate { it.copy(syncSleep = on) } }
        )
        HorizontalDivider(color = ByteSurfaceVariantDark.copy(alpha = 0.6f))
        MetricToggleRow(
            icon = Icons.Default.Favorite,
            title = "Heart Rate & Vitals",
            subtitle = "Heart rate, resting HR, HRV, SpO2, glucose",
            checked = config.syncHeartRateVitals,
            onCheckedChange = { on -> onUpdate { it.copy(syncHeartRateVitals = on) } }
        )
        HorizontalDivider(color = ByteSurfaceVariantDark.copy(alpha = 0.6f))
        MetricToggleRow(
            icon = Icons.Default.FitnessCenter,
            title = "Workouts",
            subtitle = "Exercise sessions and durations",
            checked = config.syncExercise,
            onCheckedChange = { on -> onUpdate { it.copy(syncExercise = on) } }
        )
        HorizontalDivider(color = ByteSurfaceVariantDark.copy(alpha = 0.6f))
        MetricToggleRow(
            icon = Icons.Default.Schedule,
            title = "Background Sync",
            subtitle = "Automatically sync last 3 days every 2 hours",
            checked = config.backgroundSyncEnabled,
            onCheckedChange = { on -> onUpdate { it.copy(backgroundSyncEnabled = on) } }
        )
    }
}

@Composable
private fun MetricToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = ByteTextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = ByteCream, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = ByteTextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HistoricalBackfillCard(
    selectedPreset: BackfillPreset,
    onSelectPreset: (BackfillPreset) -> Unit,
    hasHistoryPermission: Boolean,
    progress: BackfillProgress,
    available: Boolean,
    onRequestHistoryPermission: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val needsHistory = (selectedPreset.days == null || selectedPreset.days > 30) && !hasHistoryPermission

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluidRadius.lg))
            .background(ByteCardDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MacroCarbs.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.History, contentDescription = null, tint = MacroCarbs, modifier = Modifier.size(18.dp))
            }
            Column {
                Text("Historical Backfill", color = ByteCream, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Sync past Health Connect history in 30-day chunks", color = ByteTextSecondary, fontSize = 12.sp)
            }
        }

        // Range Chips: 30d, 90d, 6m, 1y, All
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FluidRadius.md))
                .background(ByteSurfaceVariantDark)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            BackfillPreset.entries.forEach { preset ->
                val selected = preset == selectedPreset
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(FluidRadius.sm))
                        .background(if (selected) ByteTomato else Color.Transparent)
                        .clickable(enabled = !progress.isRunning) { onSelectPreset(preset) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = preset.label,
                        color = if (selected) ByteOnTomato else ByteTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        if (needsHistory && available) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FluidRadius.sm))
                    .background(MacroCarbs.copy(alpha = 0.12f))
                    .border(1.dp, MacroCarbs.copy(alpha = 0.3f), RoundedCornerShape(FluidRadius.sm))
                    .clickable { onRequestHistoryPermission() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Grant History permission to read >30 days",
                    color = MacroCarbs,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Text("Grant", color = MacroCarbs, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (progress.isRunning || progress.totalWindows > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50)),
                    color = ByteTomato,
                    trackColor = ByteSurfaceVariantDark
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = progress.currentWindowLabel.ifBlank { "Syncing..." },
                        color = ByteTextSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "${progress.completedWindows}/${progress.totalWindows} · ${progress.recordsUploaded} uploaded",
                        color = ByteCream,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (progress.error != null) {
            Text(
                text = "Backfill error: ${progress.error}",
                color = cs.error,
                fontSize = 12.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (progress.isRunning) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(FluidRadius.md)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cancel Backfill")
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = available,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(FluidRadius.md),
                    colors = ButtonDefaults.buttonColors(containerColor = ByteTomato, contentColor = ByteOnTomato)
                ) {
                    Text("Start Backfill (${selectedPreset.label})", fontWeight = FontWeight.SemiBold)
                }
                if (progress.error != null) {
                    OutlinedButton(
                        onClick = onRetry,
                        shape = RoundedCornerShape(FluidRadius.md)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncNowCard(
    available: Boolean,
    status: ExtendedSyncStatus,
    onSyncNow: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluidRadius.lg))
            .background(ByteCardDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Manual Sync", color = ByteCream, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                val subtitle = when {
                    status.isSyncing -> "Syncing last 7 days..."
                    status.lastSyncEpochMs > 0L -> {
                        val ago = DateUtils.getRelativeTimeSpanString(
                            status.lastSyncEpochMs,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        )
                        "Last synced $ago · ${status.lastRecordsUploaded} uploaded" +
                            if (status.lastWeightsImported > 0) " · ${status.lastWeightsImported} weights imported" else ""
                    }
                    else -> "Sync last 7 days to cache & SparkyFitness"
                }
                Text(subtitle, color = ByteTextSecondary, fontSize = 12.sp)
            }
        }

        if (status.error != null) {
            Text(status.error, color = cs.error, fontSize = 12.sp)
        }

        Button(
            onClick = onSyncNow,
            enabled = available && !status.isSyncing,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(FluidRadius.md),
            colors = ButtonDefaults.buttonColors(containerColor = ByteTomato, contentColor = ByteOnTomato)
        ) {
            if (status.isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = ByteOnTomato
                )
                Spacer(Modifier.width(8.dp))
                Text("Syncing...")
            } else {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sync Now", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
