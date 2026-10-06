package com.bytelog.app.ui.recipes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.PrepBatch
import com.bytelog.app.data.repository.BatchStatus
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteOutlineDark
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroProtein

/** "What's left" text for a batch card: "3 of 5 portions left" or "820 g of 1450 g left". */
fun remainingLabel(s: BatchStatus): String = when {
    s.isEmpty -> "All eaten"
    s.batch.portions != null && s.remainingPortions != null ->
        "${fmt1(Math.round(s.remainingPortions!! * 10f) / 10f)} of ${s.batch.portions} left"
    s.remainingGrams != null -> "${fmt0(s.remainingGrams!!)} of ${fmt0(s.batch.cookedWeightG!!)} g left"
    else -> "${fmt0(s.remainingFraction * 100)}% left"
}

fun freshnessLabel(s: BatchStatus): Pair<String, Boolean> {
    val days = s.daysOld()
    val age = when (days) {
        0L -> "Made today"
        1L -> "Made yesterday"
        else -> "Made $days days ago"
    }
    val left = s.daysLeft() ?: return age to false
    return when {
        s.batch.location == PrepBatch.LOCATION_FREEZER -> "$age · frozen" to false
        left < 0 -> "$age · past best-by" to true
        left == 0L -> "$age · eat today" to true
        left == 1L -> "$age · best by tomorrow" to false
        else -> "$age · $left days left" to false
    }
}

/**
 * The "fridge": a row of cards for active meal-prep batches.
 * Tap logs one portion (or one portion's weight) to [targetMealName]; long-press opens options.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FridgeStrip(
    batches: List<BatchStatus>,
    targetMealName: String,
    onQuickLog: (BatchStatus) -> Unit,
    onOpen: (BatchStatus) -> Unit
) {
    if (batches.isEmpty()) return
    val haptic = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Kitchen, null, tint = MacroProtein, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Meal prep", color = ByteCream, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("Tap to log 1 to $targetMealName · hold for more", color = ByteTextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(batches, key = { it.batch.id }) { s ->
                val (fresh, warn) = freshnessLabel(s)
                Column(
                    Modifier
                        .width(190.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(ByteSurfaceDark)
                        .border(1.dp, if (warn) ByteTomato.copy(alpha = 0.6f) else ByteOutlineDark.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .combinedClickable(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (s.isEmpty || s.batch.portions == null) onOpen(s) else onQuickLog(s)
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpen(s)
                            }
                        )
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.batch.location == PrepBatch.LOCATION_FREEZER) {
                            Icon(Icons.Default.AcUnit, "Frozen", tint = MacroProtein, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(s.batch.name, color = ByteCream, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(fresh, color = if (warn) ByteTomato else ByteTextSecondary, fontSize = 11.sp, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { s.remainingFraction },
                        color = MacroCarbs,
                        trackColor = ByteSurfaceVariantDark,
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(remainingLabel(s), color = ByteCream, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (s.isEmpty) ByteSurfaceVariantDark else MacroProtein)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        val per = s.perPortion
                        val label = when {
                            s.isEmpty -> "Archive"
                            s.batch.portions != null -> "+ 1 portion · ${fmt0(per.calories)} kcal"
                            else -> "Log by weight"
                        }
                        Text(label, color = if (s.isEmpty) ByteCream else ByteSurfaceDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
