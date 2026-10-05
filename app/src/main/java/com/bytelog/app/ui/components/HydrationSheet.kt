package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.WaterLogEntry
import com.bytelog.app.data.remote.WaterContainer
import com.bytelog.app.data.repository.ML_PER_FL_OZ
import com.bytelog.app.data.repository.waterOz
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val HydrationCyan = Color(0xFF38BDF8)
private val HydrationCyanSubtle = Color(0x2638BDF8)

@Composable
fun HydrationSheet(
    todayLogs: List<WaterLogEntry>,
    containers: List<WaterContainer>,
    currentTotalOz: Float,
    targetOz: Float,
    defaultContainerOz: Float,
    defaultContainerName: String,
    onLogWaterOz: (amountOz: Float, containerId: Int?, containerName: String?) -> Unit,
    onSetDefaultContainer: (oz: Float, name: String) -> Unit,
    onDeleteLog: (WaterLogEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var customAmountText by remember { mutableStateOf(defaultContainerOz.roundToInt().toString()) }
    var selectedContainerName by remember { mutableStateOf(defaultContainerName) }
    var selectedContainerId by remember { mutableStateOf<Int?>(null) }

    val amountOz = customAmountText.toFloatOrNull() ?: 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(ByteSurfaceDark)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header
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
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(HydrationCyanSubtle),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WaterDrop,
                        contentDescription = "Water",
                        tint = HydrationCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = "Hydration Tracker",
                        color = ByteCream,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${currentTotalOz.roundToInt()} oz consumed of ${targetOz.roundToInt()} oz goal",
                        color = ByteCreamMuted,
                        fontSize = 12.sp
                    )
                }
            }

            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = ByteCream
                )
            }
        }

        // Quick Container Pills (from Sparky, if available)
        if (containers.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "SAVED CONTAINERS",
                    color = ByteCreamMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(containers) { container ->
                        val containerOz = container.volumeOz
                        val cleanName = container.name
                            .replace("591 ml", "20 oz", ignoreCase = true)
                            .replace("591ml", "20 oz", ignoreCase = true)
                        val isCurrentDefault = defaultContainerName.equals(cleanName, ignoreCase = true) ||
                            defaultContainerName.equals(container.name, ignoreCase = true)

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(ByteSurfaceVariantDark)
                                .border(
                                    width = if (isCurrentDefault) 1.dp else 0.dp,
                                    color = if (isCurrentDefault) HydrationCyan else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    customAmountText = containerOz.roundToInt().toString()
                                    selectedContainerName = cleanName
                                    selectedContainerId = container.id
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "$cleanName (${containerOz.roundToInt()} oz)",
                                color = ByteCream,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // Custom Amount Display with Quick Increment Pills in oz
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ByteSurfaceVariantDark)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (customAmountText.isEmpty()) "0" else customAmountText,
                    color = ByteCream,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Text(
                    text = "fl oz",
                    color = HydrationCyan,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Quick Amount Presets in oz: +8, +12, +16, +20, +24, +32
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(8f, 12f, 16f, 20f, 24f, 32f).forEach { preset ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(HydrationCyanSubtle)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                customAmountText = preset.toInt().toString()
                                selectedContainerId = null
                                selectedContainerName = "${preset.toInt()} oz"
                            }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+${preset.toInt()}",
                            color = HydrationCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Custom In-App Numpad
        CustomNumpad(
            currentValue = customAmountText,
            onValueChange = {
                customAmountText = it
                selectedContainerId = null
                selectedContainerName = "$it oz"
            },
            onQuickAddGrams = { add ->
                val q = (customAmountText.toFloatOrNull() ?: 0f) + add
                customAmountText = q.roundToInt().toString()
            },
            onMultiply = { factor ->
                val base = customAmountText.toFloatOrNull() ?: 8f
                val q = base * factor
                customAmountText = q.roundToInt().toString()
            }
        )

        // Action Buttons Row: Set as Default Container & Log Water
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Set Default Container Button
            OutlinedButton(
                onClick = {
                    if (amountOz > 0f) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val name = if (selectedContainerName.isNotBlank()) selectedContainerName else "${amountOz.roundToInt()} oz Glass"
                        onSetDefaultContainer(amountOz, name)
                    }
                },
                enabled = amountOz > 0f,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HydrationCyan),
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Set Default", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            // Primary Log Water Button
            Button(
                onClick = {
                    if (amountOz > 0f) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLogWaterOz(amountOz, selectedContainerId, selectedContainerName)
                        customAmountText = defaultContainerOz.roundToInt().toString()
                    }
                },
                enabled = amountOz > 0f,
                colors = ButtonDefaults.buttonColors(
                    containerColor = HydrationCyan,
                    contentColor = ByteSurfaceDark,
                    disabledContainerColor = HydrationCyan.copy(alpha = 0.3f),
                    disabledContentColor = ByteCreamMuted
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1.5f)
                    .height(50.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WaterDrop,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Log ${amountOz.roundToInt()} oz",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        HorizontalDivider(color = ByteSurfaceVariantDark)

        // Today's Itemized Timeline Section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "TODAY'S DRINKS (${todayLogs.size})",
                color = ByteCreamMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )

            if (todayLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ByteSurfaceVariantDark.copy(alpha = 0.5f))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No drinks logged for this date yet.",
                        color = ByteCreamMuted,
                        fontSize = 13.sp
                    )
                }
            } else {
                todayLogs.forEach { log ->
                    val drinkOz = log.waterOz()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ByteSurfaceVariantDark)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(HydrationCyanSubtle),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WaterDrop,
                                    contentDescription = null,
                                    tint = HydrationCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            Column {
                                Text(
                                    text = "${drinkOz.roundToInt()} oz",
                                    color = ByteCream,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                val timeLabel = formatTimestamp(log.loggedAt)
                                val containerLabel = log.containerName ?: "Drink"
                                Text(
                                    text = if (timeLabel.isNotEmpty()) "$containerLabel • $timeLabel" else containerLabel,
                                    color = ByteCreamMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDeleteLog(log)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete drink",
                                tint = ByteCreamMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return try {
        val instant = Instant.parse(iso)
        val localTime = instant.atZone(ZoneId.systemDefault()).toLocalTime()
        localTime.format(DateTimeFormatter.ofPattern("h:mm a"))
    } catch (_: Exception) {
        if (iso.contains("T")) {
            iso.substringAfter("T").take(5)
        } else {
            iso
        }
    }
}
