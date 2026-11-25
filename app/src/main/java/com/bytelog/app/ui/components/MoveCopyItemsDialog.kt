package com.bytelog.app.ui.components

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.window.Dialog
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.ui.theme.ByteCardDark
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroProtein
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class MoveCopyMode {
    MOVE, COPY
}

@Composable
fun MoveCopyItemsDialog(
    initialMode: MoveCopyMode = MoveCopyMode.MOVE,
    title: String,
    subtitle: String? = null,
    sourceDate: String,
    defaultMealName: String,
    mealTypes: List<MealType>,
    onDismiss: () -> Unit,
    /** The foods being moved or copied; a copy can change their amount. */
    entries: List<com.bytelog.app.data.local.model.FoodEntry> = emptyList(),
    /** [scale]: how much of each food to copy (1 = as logged). Always 1 for a move. */
    onConfirm: (mode: MoveCopyMode, targetDate: String, targetMealTypeId: String, targetMealName: String, scale: Float) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }
    val today = remember { LocalDate.now().format(dateFormatter) }
    val tomorrow = remember { LocalDate.now().plusDays(1).format(dateFormatter) }
    val yesterday = remember { LocalDate.now().minusDays(1).format(dateFormatter) }

    var mode by remember { mutableStateOf(initialMode) }
    var targetDate by remember { mutableStateOf(sourceDate) }
    var targetMealName by remember { mutableStateOf(defaultMealName) }
    // A copy's amount: one food takes a new amount, several take a multiplier
    var scale by remember { mutableStateOf(1f) }

    val effectiveMealTypes = remember(mealTypes) {
        if (mealTypes.isNotEmpty()) mealTypes else MealType.DEFAULT_MEAL_TYPES
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ByteCardDark,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (mode == MoveCopyMode.MOVE) ByteTomato.copy(alpha = 0.2f)
                                    else MacroProtein.copy(alpha = 0.2f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (mode == MoveCopyMode.MOVE) Icons.AutoMirrored.Filled.DriveFileMove else Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = if (mode == MoveCopyMode.MOVE) ByteTomato else MacroProtein,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "${if (mode == MoveCopyMode.MOVE) "Move" else "Copy"} $title",
                                color = ByteCream,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            subtitle?.let {
                                Text(
                                    text = it,
                                    color = ByteTextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
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

                Spacer(modifier = Modifier.height(16.dp))

                // Mode Toggle: [ Move ] vs [ Copy ]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(ByteSurfaceDark)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (mode == MoveCopyMode.MOVE) ByteTomato else ByteSurfaceDark)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                mode = MoveCopyMode.MOVE
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                                contentDescription = null,
                                tint = if (mode == MoveCopyMode.MOVE) ByteOnTomato else ByteCreamMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Move",
                                color = if (mode == MoveCopyMode.MOVE) ByteOnTomato else ByteCreamMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (mode == MoveCopyMode.COPY) MacroProtein else ByteSurfaceDark)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                mode = MoveCopyMode.COPY
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = if (mode == MoveCopyMode.COPY) ByteInkDark else ByteCreamMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Copy",
                                color = if (mode == MoveCopyMode.COPY) ByteInkDark else ByteCreamMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Target Date Section
                Text(
                    text = "TARGET DATE",
                    color = ByteCreamMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Quick days (Today, Tomorrow, Yesterday, Same Day) and a calendar for any other day
                TargetDatePicker(
                    date = targetDate,
                    onDate = { targetDate = it },
                    sourceDate = sourceDate,
                    accent = if (mode == MoveCopyMode.MOVE) ByteTomato else MacroProtein
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Target Meal Section
                Text(
                    text = "TARGET MEAL",
                    color = ByteCreamMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(effectiveMealTypes, key = { it.id }) { mt ->
                        val cleanName = mt.name.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        val isSelected = cleanName.equals(targetMealName, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (isSelected) (if (mode == MoveCopyMode.MOVE) ByteTomato else MacroProtein)
                                    else ByteSurfaceVariantDark
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    targetMealName = cleanName
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = cleanName,
                                color = if (isSelected) ByteCream else ByteCreamMuted,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                if (mode == MoveCopyMode.COPY && entries.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    CopyAmountPicker(entries = entries, scale = scale, onScale = { scale = it })
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons: Cancel and Confirm
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text("Cancel", color = ByteTextSecondary, fontSize = 14.sp)
                    }

                    val buttonColor = if (mode == MoveCopyMode.MOVE) ByteTomato else MacroProtein
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val cleanTargetDate = targetDate.trim()
                            val cleanTargetMeal = targetMealName.trim()
                            val matched = effectiveMealTypes.find { it.name.trim().equals(cleanTargetMeal, ignoreCase = true) }
                            val targetTypeId = matched?.id ?: cleanTargetMeal.lowercase()
                            onConfirm(mode, cleanTargetDate, targetTypeId, cleanTargetMeal, if (mode == MoveCopyMode.COPY) scale else 1f)
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(2f)
                            .height(48.dp),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${if (mode == MoveCopyMode.MOVE) "Move" else "Copy"} to $targetMealName",
                            color = ByteCream,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * How much to copy. One food: its amount, typed or stepped with − and +, with the calories it comes to. Several foods
 * (or a whole meal): a multiplier (¼× to 3×, or typed), with the total it comes to.
 */
@Composable
internal fun CopyAmountPicker(
    entries: List<com.bytelog.app.data.local.model.FoodEntry>,
    scale: Float,
    onScale: (Float) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val totalKcal = entries.sumOf { it.calories.toDouble() } * scale
    Text("AMOUNT", color = ByteCreamMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    Spacer(modifier = Modifier.height(6.dp))
    if (entries.size == 1) {
        val e = entries.single()
        val step = com.bytelog.app.data.repository.CopyAmount.step(e.unit)
        val qty = e.quantity * scale
        var text by remember(e.id) { mutableStateOf(com.bytelog.app.data.repository.CopyAmount.amount(qty)) }
        fun set(q: Float) {
            val clean = q.coerceAtLeast(step / 4f)
            text = com.bytelog.app.data.repository.CopyAmount.amount(clean)
            onScale(com.bytelog.app.data.repository.CopyAmount.scaleFor(e, clean))
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { set(qty - step) }, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(ByteSurfaceVariantDark)) {
                Icon(Icons.Default.Remove, contentDescription = "Less", tint = ByteCream)
            }
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v.filter { it.isDigit() || it == '.' }
                    text.toFloatOrNull()?.takeIf { it > 0f }?.let { onScale(com.bytelog.app.data.repository.CopyAmount.scaleFor(e, it)) }
                },
                singleLine = true,
                suffix = { Text(e.unit, color = ByteCreamMuted) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { set(qty + step) }, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(ByteSurfaceVariantDark)) {
                Icon(Icons.Default.Add, contentDescription = "More", tint = ByteCream)
            }
        }
        Text(
            "${totalKcal.toInt()} kcal" + if (scale != 1f) " (logged: ${com.bytelog.app.data.repository.CopyAmount.amount(e.quantity)} ${e.unit})" else "",
            color = ByteCreamMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
        )
    } else {
        var custom by remember { mutableStateOf(false) }
        var customText by remember { mutableStateOf("") }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            items(com.bytelog.app.data.repository.CopyAmount.PRESETS) { p ->
                val on = !custom && kotlin.math.abs(scale - p) < 0.001f
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) MacroProtein else ByteSurfaceVariantDark)
                        .clickable { custom = false; onScale(p); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(com.bytelog.app.data.repository.CopyAmount.label(p), color = if (on) ByteInkDark else ByteCreamMuted,
                        fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                }
            }
            item {
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(if (custom) MacroProtein else ByteSurfaceVariantDark)
                        .clickable { custom = true }.padding(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Other", color = if (custom) ByteInkDark else ByteCreamMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
        if (custom) {
            androidx.compose.material3.OutlinedTextField(
                value = customText,
                onValueChange = { v -> customText = v.filter { it.isDigit() || it == '.' }; customText.toFloatOrNull()?.takeIf { it > 0f }?.let(onScale) },
                singleLine = true, placeholder = { Text("e.g. 1.25") }, suffix = { Text("×", color = ByteCreamMuted) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }
        Text(
            "${entries.size} foods · ${totalKcal.toInt()} kcal" + if (scale != 1f) " at ${com.bytelog.app.data.repository.CopyAmount.label(scale)}" else "",
            color = ByteCreamMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

