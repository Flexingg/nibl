package com.bytelog.app.ui.caffeine

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.caffeine.CaffeineStatus
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.LocalByteExtraColors
import com.bytelog.app.ui.theme.MacroCarbs
import kotlin.math.roundToInt

/**
 * Compact Diary home card summarizing active caffeine, today's total, bedtime projection / cutoff pill,
 * and quick `+ Coffee` / `Open` actions. Styled to match `HydrationCard`.
 */
@Composable
fun CaffeineCard(
    status: CaffeineStatus?,
    latestItem: com.bytelog.app.data.caffeine.CaffeineRepository.LastCaffeineItem? = null,
    onQuickLogItem: (name: String, mg: Float) -> Unit,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val extras = LocalByteExtraColors.current
    val accent = MacroCarbs

    val activeMg = status?.activeMgNow?.roundToInt() ?: 0
    val totalMg = status?.totalTodayMg?.roundToInt() ?: 0
    val atBedtimeMg = status?.atBedtimeMg?.roundToInt() ?: 0
    val thresholdMg = (status?.thresholdMg ?: 100f).coerceAtLeast(1f)
    val bedtimeLabel = status?.targetBedtime?.take(5) ?: "22:30"

    val resolvedLatest = status?.doses
        ?.filter { it.mg > 0f && it.name.isNotBlank() }
        ?.maxByOrNull { com.bytelog.app.data.caffeine.CaffeineMath.parseDoseEpochMs(it.at) ?: 0L }
        ?.let { com.bytelog.app.data.caffeine.CaffeineRepository.LastCaffeineItem(it.name, it.mg) }
        ?: latestItem

    val rawProgress = ((status?.atBedtimeMg ?: 0f) / thresholdMg).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(targetValue = rawProgress, label = "caffeine_bedtime_progress")

    val (pillText, pillColor) = when (status?.cutoffState?.lowercase()) {
        "over_threshold", "exceeded" -> "Over at bed" to cs.error
        "past_cutoff", "cutoff_passed" -> "Past cutoff" to cs.error
        "caution", "warning" -> "Near limit" to accent
        else -> {
            val cutoff = status?.latestSafeDoseTime?.take(5)
            if (!cutoff.isNullOrBlank()) "Cutoff $cutoff" to extras.success else "Sleep safe" to extras.success
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ByteSurfaceDark)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onOpenDetail()
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header row
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
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalCafe,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column {
                    Text(
                        text = "CAFFEINE",
                        color = ByteCreamMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "$activeMg mg active",
                        color = ByteCream,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Cutoff / bedtime status pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(pillColor.copy(alpha = 0.16f))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(
                    text = pillText,
                    color = pillColor,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Secondary stats line
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Today: $totalMg mg",
                color = ByteCreamMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Bedtime ($bedtimeLabel): $atBedtimeMg / ${thresholdMg.roundToInt()} mg",
                color = ByteCreamMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Bedtime threshold bar
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
                    .background(if (atBedtimeMg >= thresholdMg) cs.error else accent)
            )
        }

        // Quick action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1.35f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ByteSurfaceVariantDark)
                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (resolvedLatest != null) {
                            onQuickLogItem(resolvedLatest.name, resolvedLatest.mg)
                        } else {
                            onOpenDetail()
                        }
                    }
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(15.dp)
                    )
                    if (resolvedLatest != null) {
                        Text(
                            text = resolvedLatest.name,
                            color = ByteCream,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            text = "${resolvedLatest.mg.roundToInt()} mg",
                            color = ByteCreamMuted,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    } else {
                        Text(
                            text = "Log caffeine",
                            color = ByteCream,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ByteSurfaceVariantDark)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpenDetail()
                    }
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Open",
                        color = ByteCream,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = ByteCreamMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
