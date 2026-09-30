package com.bytelog.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import kotlin.math.roundToInt

@Composable
fun MultiAddTray(
    stagedEntries: List<FoodEntry>,
    targetMealName: String,
    onLogAll: () -> Unit,
    onRemoveEntry: (FoodEntry) -> Unit,
    onEditEntry: (FoodEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    if (stagedEntries.isEmpty()) return

    val haptic = LocalHapticFeedback.current
    var isExpanded by remember { mutableStateOf(false) }

    val totalCalories = stagedEntries.sumOf { it.calories.toDouble() }.toFloat()
    val totalProtein = stagedEntries.sumOf { it.protein.toDouble() }.toFloat()
    val totalCarbs = stagedEntries.sumOf { it.carbs.toDouble() }.toFloat()
    val totalFat = stagedEntries.sumOf { it.fat.toDouble() }.toFloat()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(ByteSurfaceDark)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Expandable Detail Drawer of Staged Items
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    text = "Items in Tray (${stagedEntries.size})",
                    color = ByteCreamMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height((stagedEntries.size * 56).coerceAtMost(220).dp)
                ) {
                    items(stagedEntries, key = { it.id }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ByteSurfaceVariantDark)
                                .clickable { onEditEntry(entry) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.foodName,
                                    color = ByteCream,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${entry.quantity.roundToInt()}${entry.unit} • ${entry.calories.roundToInt()} kcal",
                                    color = ByteCreamMuted,
                                    fontSize = 12.sp
                                )
                            }
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onRemoveEntry(entry)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove",
                                    tint = ByteTomato.copy(alpha = 0.8f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Summary Bar & Commit Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Count Badge + Toggle Chevron + Macro Summary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp)
            ) {
                // Count Badge
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(ByteTomato),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stagedEntries.size.toString(),
                        color = ByteCream,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${totalCalories.roundToInt()} kcal",
                            color = ByteCream,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = ByteCreamMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = "P: ${totalProtein.roundToInt()}g • C: ${totalCarbs.roundToInt()}g • F: ${totalFat.roundToInt()}g",
                        color = ByteCreamMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Right: Primary Log Meal Button
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLogAll()
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ByteTomato),
                modifier = Modifier.height(46.dp)
            ) {
                Text(
                    text = "LOG TO $targetMealName".uppercase(),
                    color = ByteCream,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}
