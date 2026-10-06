package com.bytelog.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.WeightEntry
import com.bytelog.app.ui.theme.ByteCardDark
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroProtein
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BodyweightCard(
    currentEntry: WeightEntry?,
    previousEntry: WeightEntry?,
    weightUnit: String,
    onOpenLogDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onOpenLogDialog()
                },
                onDoubleClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenLogDialog()
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenLogDialog()
                }
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = ByteCardDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row
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
                            .background(MacroCarbs.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MonitorWeight,
                            contentDescription = "Bodyweight",
                            tint = MacroCarbs,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = "Bodyweight",
                        color = ByteCream,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Edit or Add button
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(ByteSurfaceVariantDark.copy(alpha = 0.6f))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenLogDialog()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (currentEntry != null) Icons.Default.Edit else Icons.Default.Add,
                        contentDescription = "Log Weight",
                        tint = ByteCream,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Body content
            if (currentEntry != null) {
                val weightVal = displayWeight(currentEntry, weightUnit)
                val unit = weightUnit
                val prevDisplayWeight = previousEntry?.let { displayWeight(it, weightUnit) }

                val delta = if (prevDisplayWeight != null && previousEntry.entryDate != currentEntry.entryDate) {
                    weightVal - prevDisplayWeight
                } else null

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format(Locale.US, "%.1f", weightVal),
                            color = ByteCream,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = unit,
                            color = ByteTextSecondary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }

                    if (delta != null) {
                        val isLoss = delta < 0
                        val deltaColor = if (isLoss) MacroProtein else ByteTomato
                        val deltaIcon = if (isLoss) Icons.AutoMirrored.Filled.TrendingDown else Icons.AutoMirrored.Filled.TrendingUp
                        val sign = if (delta > 0) "+" else ""

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(deltaColor.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = deltaIcon,
                                contentDescription = null,
                                tint = deltaColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "$sign${String.format(Locale.US, "%.1f", delta)} $unit",
                                color = deltaColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text(
                            text = "Logged today",
                            color = ByteTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                // Not logged yet today
                val lastWeight = previousEntry?.let { displayWeight(it, weightUnit) }
                val unit = weightUnit

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "No weight logged today",
                            color = ByteTextSecondary,
                            fontSize = 14.sp
                        )
                        if (lastWeight != null && lastWeight > 0f) {
                            Text(
                                text = "Last: ${String.format(Locale.US, "%.1f", lastWeight)} $unit",
                                color = ByteCream.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MacroCarbs.copy(alpha = 0.15f))
                            .border(1.dp, MacroCarbs.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Record Check-In",
                            color = MacroCarbs,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/** A weigh-in expressed in the unit the user chose, rounded down to one decimal like the card shows it. */
fun displayWeight(entry: WeightEntry, targetUnit: String): Float {
    val entryIsLbs = entry.unit.ifBlank { targetUnit }.equals("lbs", ignoreCase = true)
    val targetIsLbs = targetUnit.equals("lbs", ignoreCase = true)
    return when {
        entryIsLbs && !targetIsLbs -> (entry.weight * 0.45359237f * 10).toInt() / 10f
        !entryIsLbs && targetIsLbs -> (entry.weight * 2.20462262f * 10).toInt() / 10f
        else -> entry.weight
    }
}

/** Change since the previous weigh-in on another day, or null when there is nothing to compare. */
fun weightChange(current: WeightEntry?, previous: WeightEntry?, targetUnit: String): Float? {
    if (current == null || previous == null || previous.entryDate == current.entryDate) return null
    return displayWeight(current, targetUnit) - displayWeight(previous, targetUnit)
}
