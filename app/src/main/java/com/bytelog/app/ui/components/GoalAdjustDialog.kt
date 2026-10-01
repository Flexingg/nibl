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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bytelog.app.data.analytics.AdaptiveTdeeEngine
import com.bytelog.app.ui.theme.ByteCardDark
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun GoalAdjustDialog(
    currentTdee: Float,
    isAutoAdjustEnabled: Boolean,
    currentGoalMode: String,
    currentGoalOffset: Float,
    confidence: String,
    onDismiss: () -> Unit,
    onSaveGoal: (enabled: Boolean, mode: String, offset: Float) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    var enabled by remember { mutableStateOf(isAutoAdjustEnabled) }
    var selectedOffset by remember { mutableFloatStateOf(currentGoalOffset) }
    var selectedMode by remember { mutableStateOf(currentGoalMode) }

    data class GoalPreset(
        val title: String,
        val subtitle: String,
        val mode: String,
        val offset: Float
    )

    val presets = remember {
        listOf(
            GoalPreset("Aggressive Cut", "-1.0 lb/wk (-500 kcal)", "cut", -500f),
            GoalPreset("Moderate Cut", "-0.5 lb/wk (-250 kcal)", "cut", -250f),
            GoalPreset("Maintenance", "Maintain Weight (0 kcal)", "maintain", 0f),
            GoalPreset("Lean Bulk", "+0.5 lb/wk (+250 kcal)", "bulk", 250f),
            GoalPreset("Muscle Bulk", "+1.0 lb/wk (+500 kcal)", "bulk", 500f)
        )
    }

    val computedCalories = if (enabled) {
        (currentTdee + selectedOffset).coerceAtLeast(1200f)
    } else {
        currentTdee
    }

    val computedMacros = remember(computedCalories) {
        AdaptiveTdeeEngine.computeMacros(computedCalories)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = ByteCardDark,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(ByteTomato.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = ByteTomato,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Auto-Adjust Goals",
                                color = ByteCream,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "MacroFactor Adaptive TDEE",
                                color = ByteTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = ByteTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Toggle Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ByteSurfaceVariantDark)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Dynamic Calorie Target",
                            color = ByteCream,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Auto-updates daily targets from actual weight & food logs",
                            color = ByteTextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            enabled = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ByteCream,
                            checkedTrackColor = ByteTomato,
                            uncheckedThumbColor = ByteCream.copy(alpha = 0.6f),
                            uncheckedTrackColor = ByteSurfaceVariantDark
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Current Metabolic Stats Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ByteSurfaceVariantDark.copy(alpha = 0.6f))
                        .padding(14.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Metabolic Expenditure (TDEE):",
                                color = ByteTextSecondary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "${currentTdee.roundToInt()} kcal",
                                color = ByteTomato,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Calibration Status:",
                                color = ByteTextSecondary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = confidence,
                                color = when (confidence) {
                                    "HIGH" -> MacroProtein
                                    "MEDIUM" -> MacroCarbs
                                    else -> ByteTextSecondary
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Presets list
                Text(
                    text = "SELECT GOAL STRATEGY",
                    color = ByteTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { preset ->
                        val isSelected = enabled && selectedOffset == preset.offset && selectedMode == preset.mode
                        val borderMod = if (isSelected) Modifier.border(1.5.dp, ByteTomato, RoundedCornerShape(12.dp)) else Modifier

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) ByteTomato.copy(alpha = 0.12f) else ByteSurfaceVariantDark)
                                .then(borderMod)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedOffset = preset.offset
                                    selectedMode = preset.mode
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = preset.title,
                                        color = if (isSelected) ByteTomato else ByteCream,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = preset.subtitle,
                                        color = ByteTextSecondary,
                                        fontSize = 12.sp
                                    )
                                }

                                val sign = if (preset.offset > 0) "+" else ""
                                Text(
                                    text = if (preset.offset == 0f) "0" else "$sign${preset.offset.roundToInt()}",
                                    color = if (isSelected) ByteTomato else ByteTextSecondary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Dynamic Target Preview Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ByteTomato.copy(alpha = 0.1f))
                        .border(1.dp, ByteTomato.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Target Daily Intake",
                                color = ByteCream,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${computedCalories.roundToInt()} kcal",
                                color = ByteTomato,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "P: ${computedMacros.protein.roundToInt()}g",
                                color = MacroProtein,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "C: ${computedMacros.carbs.roundToInt()}g",
                                color = MacroCarbs,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "F: ${computedMacros.fat.roundToInt()}g",
                                color = MacroFat,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Save Button
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSaveGoal(enabled, selectedMode, selectedOffset)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ByteTomato)
                ) {
                    Text(
                        text = "Apply Goal Plan",
                        color = ByteCream,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
