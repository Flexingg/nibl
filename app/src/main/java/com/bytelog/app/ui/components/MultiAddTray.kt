package com.bytelog.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.ui.diary.MorphButton
import com.bytelog.app.ui.logging.Portion
import com.bytelog.app.ui.logging.QuickPortionEditor
import com.bytelog.app.ui.logging.unitLabel
import com.bytelog.app.ui.logging.formatQuantity
import kotlin.math.roundToInt

/**
 * The logging tray. The food you just added sits on top with its amount editor open, so adjusting the quantity or
 * unit is right there; earlier picks are chips below it (tap one to edit it). One button logs everything.
 */
@Composable
fun MultiAddTray(
    stagedEntries: List<FoodEntry>,
    targetMealName: String,
    onLogAll: () -> Unit,
    onRemoveEntry: (FoodEntry) -> Unit,
    onEditEntry: (FoodEntry) -> Unit,
    modifier: Modifier = Modifier,
    activeEntryId: String? = null,
    foodFor: (String) -> FoodItem? = { null },
    unitsFor: (String) -> List<String> = { emptyList() },
    onPortionChange: (String, Portion) -> Unit = { _, _ -> },
    compact: Boolean = false
) {
    if (stagedEntries.isEmpty()) return

    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val active = stagedEntries.firstOrNull { it.id == activeEntryId } ?: stagedEntries.last()
    val activeFood = if (compact) null else foodFor(active.id)
    val totalCalories = stagedEntries.sumOf { it.calories.toDouble() }.roundToInt()

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(cs.surfaceContainer)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 500f)),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // The item being adjusted
        if (activeFood != null) {
            AnimatedContent(
                targetState = active.id,
                transitionSpec = {
                    (fadeIn(spring(stiffness = 600f)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 600f), initialScale = 0.94f))
                        .togetherWith(fadeOut(spring(stiffness = 900f)))
                        .using(SizeTransform(clip = false))
                },
                label = "active_staged"
            ) { id ->
                val entry = stagedEntries.firstOrNull { it.id == id } ?: active
                val food = foodFor(id) ?: activeFood
                QuickPortionEditor(
                    food = food,
                    portion = Portion(entry.quantity, entry.unit),
                    units = unitsFor(id),
                    onChange = { onPortionChange(id, it) },
                    onRemove = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onRemoveEntry(entry) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(cs.surfaceContainerHigh)
                        .padding(14.dp)
                )
            }
        }

        // Everything else in the tray
        val others = if (activeFood != null) stagedEntries.filter { it.id != active.id } else stagedEntries
        if (others.isNotEmpty()) {
            val rowState = rememberLazyListState()
            LaunchedEffect(others.size) { rowState.animateScrollToItem(others.lastIndex.coerceAtLeast(0)) }
            LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 4.dp)) {
                items(others, key = { it.id }) { entry ->
                    Column(
                        Modifier
                            .animateItem()
                            .widthIn(max = 180.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(cs.surfaceContainerHighest)
                            .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onEditEntry(entry) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(entry.foodName, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${formatQuantity(entry.quantity)} ${unitLabel(entry.unit, entry.quantity)} · ${entry.calories.roundToInt()} kcal",
                            color = cs.onSurfaceVariant, fontSize = 11.5.sp, maxLines = 1
                        )
                    }
                }
            }
        }

        // Log everything
        MorphButton(
            { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLogAll() },
            Modifier.fillMaxWidth().height(56.dp),
            cs.primary,
            description = "Log ${stagedEntries.size} to $targetMealName"
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    if (stagedEntries.size == 1) "Log to $targetMealName" else "Log ${stagedEntries.size} to $targetMealName",
                    color = cs.onPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold
                )
                Box(
                    Modifier.padding(start = 10.dp).clip(RoundedCornerShape(50)).background(cs.onPrimary.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) { Text("$totalCalories kcal", color = cs.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
