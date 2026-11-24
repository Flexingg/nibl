package com.bytelog.app.ui.fasting

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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.bytelog.app.data.fasting.FastingLog
import com.bytelog.app.data.fasting.FastingProtocol
import com.bytelog.app.data.fasting.MetabolicZone
import com.bytelog.app.data.fasting.formatFastingTimer
import com.bytelog.app.ui.components.animateBouncy
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.LocalByteExtraColors
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Compact Diary home card for Active Fasting.
 * Shows elapsed time, progress toward target, current metabolic zone pill,
 * and 1-tap Start Fast / End Fast + Details actions.
 */
@Composable
fun FastingCard(
    activeFast: FastingLog?,
    defaultProtocolCode: String = "16-8",
    onStartFast: () -> Unit,
    onEndFast: () -> Unit,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(activeFast?.id, activeFast?.startTime) {
        if (activeFast != null && activeFast.isActive) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }

    val isFasting = activeFast != null && activeFast.isActive
    val elapsedMs = if (isFasting) activeFast!!.elapsedMillis(Instant.ofEpochMilli(nowMillis)) else 0L
    val targetMs = if (isFasting) activeFast!!.targetDurationMillis() else {
        (FastingProtocol.fromCode(defaultProtocolCode).fastHours * 3_600_000L).toLong()
    }
    val rawProgress = if (isFasting && targetMs > 0L) (elapsedMs.toFloat() / targetMs.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedProgress = animateBouncy(rawProgress, "fasting_card_progress").coerceIn(0f, 1f)
    val percentage = (rawProgress * 100f).roundToInt()

    val zone = MetabolicZone.forElapsedMillis(elapsedMs)
    val zoneColor = metabolicZoneColor(zone)
    val accentColor = if (isFasting) zoneColor else ByteTomato
    val protocolLabel = FastingProtocol.fromCode(activeFast?.fastingType ?: defaultProtocolCode).label

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ByteSurfaceDark)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onOpenDetails()
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
                        .background(accentColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isFasting) Icons.Default.LocalFireDepartment else Icons.Default.Timer,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column {
                    Text(
                        text = if (isFasting) "FASTING • $protocolLabel" else "FASTING",
                        color = ByteCreamMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = if (isFasting) formatFastingTimer(elapsedMs) else "Ready ($protocolLabel)",
                        color = ByteCream,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isFasting) {
                    // Metabolic zone pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(zoneColor.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = zone.label,
                            color = zoneColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Percentage pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(ByteSurfaceVariantDark)
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "$percentage%",
                            color = ByteCream,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(ByteSurfaceVariantDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Open Fasting",
                            tint = ByteCreamMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Progress bar
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
                    .background(accentColor)
            )
        }

        // Action row: Start/End Fast + Details
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1.35f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isFasting) ByteSurfaceVariantDark else ByteTomato)
                    .then(
                        if (isFasting) Modifier.border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        else Modifier
                    )
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (isFasting) onEndFast() else onStartFast()
                    }
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isFasting) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isFasting) accentColor else ByteOnTomato,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (isFasting) "End Fast" else "Start Fast",
                        color = if (isFasting) ByteCream else ByteOnTomato,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ByteSurfaceVariantDark)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpenDetails()
                    }
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Details",
                        color = ByteCream,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = ByteCreamMuted,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun metabolicZoneColor(zone: MetabolicZone): Color = when (zone) {
    MetabolicZone.BLOOD_SUGAR -> MacroCarbs
    MetabolicZone.POST_ABSORPTIVE -> MacroProtein
    MetabolicZone.FAT_BURNING -> ByteTomato
    MetabolicZone.KETOSIS -> LocalByteExtraColors.current.success
    MetabolicZone.DEEP_AUTOPHAGY -> MacroFat
}
