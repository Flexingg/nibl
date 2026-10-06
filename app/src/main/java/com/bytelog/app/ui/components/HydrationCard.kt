package com.bytelog.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import kotlin.math.roundToInt

private val HydrationCyan = Color(0xFF38BDF8)
private val HydrationCyanSubtle = Color(0x2638BDF8)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HydrationCard(
    currentOz: Float,
    targetOz: Float,
    defaultOz: Float = 20f,
    defaultContainerName: String = "Bottle",
    onLogDefault: () -> Unit,
    onOpenCustom: () -> Unit,
    onUndoLast: () -> Unit,
    onLogDoubleDrink: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val progress = if (targetOz > 0f) (currentOz / targetOz).coerceIn(0f, 1f) else 0f
    val animatedProgress = animateBouncy(progress, "water_progress_oz").coerceIn(0f, 1f)
    val percentage = (progress * 100).roundToInt()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ByteSurfaceDark)
            .combinedClickable(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onOpenCustom()
                },
                onDoubleClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onUndoLast()
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenCustom()
                }
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SplashingDrop(currentOz, size = 34.dp, iconSize = 18.dp)

                Column {
                    Text(
                        text = "HYDRATION",
                        color = ByteCreamMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "${currentOz.roundToInt()} / ${targetOz.roundToInt()} oz",
                        color = ByteCream,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Percentage Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(HydrationCyanSubtle)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "$percentage%",
                        color = HydrationCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Edit/More Icon
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(ByteSurfaceVariantDark),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Customize Water",
                        tint = ByteCreamMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // Progress Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(ByteSurfaceVariantDark)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(HydrationCyan)
            )
        }

        // Speed Logging Action Buttons: Default Container, Custom Amount, Undo
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Primary / Default Container Button (Fast 1-tap logging)
            Box(
                modifier = Modifier
                    .weight(1.3f)
                    .pressBounce(0.93f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ByteSurfaceVariantDark)
                    .border(1.dp, HydrationCyan.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .combinedClickable(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onLogDefault()
                        },
                        onDoubleClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (onLogDoubleDrink != null) onLogDoubleDrink() else {
                                onLogDefault()
                                onLogDefault()
                            }
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (onLogDoubleDrink != null) onLogDoubleDrink() else {
                                onLogDefault()
                                onLogDefault()
                            }
                        }
                    )
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalDrink,
                        contentDescription = null,
                        tint = HydrationCyan,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "+ ${defaultOz.roundToInt()} oz",
                        color = ByteCream,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = defaultContainerName,
                        color = ByteCreamMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }

            // 2. Custom Amount Button
            Box(
                modifier = Modifier
                    .weight(1f)
                    .pressBounce(0.93f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ByteSurfaceVariantDark)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpenCustom()
                    }
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = ByteCreamMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Custom",
                        color = ByteCream,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "oz",
                        color = HydrationCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 3. Undo / Decrement Button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .pressBounce(0.88f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ByteSurfaceVariantDark)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onUndoLast()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Undo Drink",
                    tint = ByteCreamMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
