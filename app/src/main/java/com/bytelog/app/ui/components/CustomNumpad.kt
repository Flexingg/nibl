package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato

@Composable
fun CustomNumpad(
    currentValue: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onQuickAddGrams: (Float) -> Unit = {},
    onMultiply: (Float) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ByteSurfaceDark)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Quick Multiplier / Increment Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QuickChip(label = "½x", onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onMultiply(0.5f)
            }, modifier = Modifier.weight(1f))

            QuickChip(label = "1x", onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onMultiply(1f)
            }, modifier = Modifier.weight(1f))

            QuickChip(label = "2x", onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onMultiply(2f)
            }, modifier = Modifier.weight(1f))

            QuickChip(label = "+10g", onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onQuickAddGrams(10f)
            }, modifier = Modifier.weight(1f))

            QuickChip(label = "+25g", onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onQuickAddGrams(25f)
            }, modifier = Modifier.weight(1f))

            QuickChip(label = "+50g", onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onQuickAddGrams(50f)
            }, modifier = Modifier.weight(1f))
        }

        // Row 1: 1, 2, 3
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NumpadKey("1", modifier = Modifier.weight(1f)) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onValueChange(appendDigit(currentValue, "1"))
            }
            NumpadKey("2", modifier = Modifier.weight(1f)) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onValueChange(appendDigit(currentValue, "2"))
            }
            NumpadKey("3", modifier = Modifier.weight(1f)) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onValueChange(appendDigit(currentValue, "3"))
            }
        }

        // Row 2: 4, 5, 6
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NumpadKey("4", modifier = Modifier.weight(1f)) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onValueChange(appendDigit(currentValue, "4"))
            }
            NumpadKey("5", modifier = Modifier.weight(1f)) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onValueChange(appendDigit(currentValue, "5"))
            }
            NumpadKey("6", modifier = Modifier.weight(1f)) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onValueChange(appendDigit(currentValue, "6"))
            }
        }

        // Row 3: 7, 8, 9
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NumpadKey("7", modifier = Modifier.weight(1f)) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onValueChange(appendDigit(currentValue, "7"))
            }
            NumpadKey("8", modifier = Modifier.weight(1f)) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onValueChange(appendDigit(currentValue, "8"))
            }
            NumpadKey("9", modifier = Modifier.weight(1f)) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onValueChange(appendDigit(currentValue, "9"))
            }
        }

        // Row 4: ., 0, ⌫
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NumpadKey(".", modifier = Modifier.weight(1f)) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (!currentValue.contains(".")) {
                    onValueChange(if (currentValue.isEmpty()) "0." else "$currentValue.")
                }
            }
            NumpadKey("0", modifier = Modifier.weight(1f)) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onValueChange(appendDigit(currentValue, "0"))
            }
            NumpadKey("⌫", modifier = Modifier.weight(1f)) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (currentValue.isNotEmpty()) {
                    onValueChange(currentValue.dropLast(1))
                }
            }
        }
    }
}

private fun appendDigit(current: String, digit: String): String {
    return if (current == "0") digit else current + digit
}

@Composable
fun NumpadKey(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ByteSurfaceVariantDark)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = ByteCream,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun QuickChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ByteSurfaceVariantDark)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = ByteTomato,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
