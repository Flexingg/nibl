package com.bytelog.app.ui.logging

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.ui.diary.MorphButton
import com.bytelog.app.ui.theme.LocalByteExtraColors
import kotlin.math.roundToInt

/**
 * Inline amount editor for a food being logged: − / amount / + , the units that make sense for the food (switching
 * unit keeps the same amount of food), quick amounts, and live calories and macros. No sheet to open or close.
 */
@Composable
fun QuickPortionEditor(
    food: FoodItem,
    portion: Portion,
    units: List<String>,
    onChange: (Portion) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onEditFood: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    val x = LocalByteExtraColors.current
    val haptic = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    val m = Portions.macros(food, portion)

    // The amount as typed; follows outside changes (buttons, unit switches) without fighting the keyboard
    var text by remember(food.id) { mutableStateOf(formatTyped(portion.quantity)) }
    LaunchedEffect(portion.quantity) {
        if (text.toFloatOrNull() != portion.quantity) text = formatTyped(portion.quantity)
    }
    fun set(q: Float) {
        val v = Portions.tidy(q.coerceAtLeast(0f), portion.unit)
        if (v > 0f) { onChange(portion.copy(quantity = v)); text = formatTyped(v) }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(food.name, color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "P ${m.protein.roundToInt()}g · C ${m.carbs.roundToInt()}g · F ${m.fat.roundToInt()}g",
                    color = cs.onSurfaceVariant, fontSize = 12.sp
                )
            }
            Text("${m.calories.roundToInt()}", color = cs.primary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(" kcal", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.width(4.dp))
            if (onEditFood != null) {
                MorphButton(onEditFood, Modifier.size(36.dp), cs.surfaceContainerHighest, description = "Edit ${food.name}") {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(4.dp))
            }
            MorphButton(onRemove, Modifier.size(36.dp), cs.surfaceContainerHighest, description = "Remove ${food.name}") {
                Icon(Icons.Default.Close, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }

        // − amount +
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MorphButton(
                { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); set(portion.quantity - Portions.step(portion.unit, portion.quantity)) },
                Modifier.size(48.dp), cs.surfaceContainerHighest, description = "Less"
            ) { Icon(Icons.Default.Remove, contentDescription = null, tint = cs.onSurface) }
            Box(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = text,
                        onValueChange = { raw ->
                            val cleaned = raw.replace(',', '.').filter { it.isDigit() || it == '.' }
                            text = cleaned
                            cleaned.toFloatOrNull()?.takeIf { it > 0f }?.let { onChange(portion.copy(quantity = it)) }
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = cs.onSurface, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End),
                        cursorBrush = SolidColor(cs.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        modifier = Modifier.width(90.dp).semantics { contentDescription = "Amount" }
                    )
                    Text(" ${unitLabel(portion.unit, portion.quantity)}", color = cs.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            MorphButton(
                { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); set(portion.quantity + Portions.step(portion.unit, portion.quantity)) },
                Modifier.size(48.dp), cs.primaryContainer, description = "More"
            ) { Icon(Icons.Default.Add, contentDescription = null, tint = cs.onPrimaryContainer) }
        }

        // Units that make sense for this food; switching keeps the same amount of food
        if (units.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(end = 8.dp)) {
                items(units, key = { it }) { u ->
                    val selected = u.equals(portion.unit, ignoreCase = true)
                    val bg by animateColorAsState(if (selected) cs.secondaryContainer else cs.surfaceContainerHighest, label = "unit_bg")
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(bg)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val p = Portions.convert(food, portion, u)
                                onChange(p); text = formatTyped(p.quantity)
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .semantics { contentDescription = "Unit $u" }
                    ) {
                        Text(
                            if (u == Portions.SERVING) "serving (${formatQuantity(food.servingSize)} ${food.servingUnit})" else u,
                            color = if (selected) cs.onSecondaryContainer else cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Quick amounts in the current unit
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Portions.quickAmounts(food, portion.unit), key = { it }) { q ->
                val selected = kotlin.math.abs(q - portion.quantity) < 0.001f
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) x.waterContainer else cs.surfaceContainerHighest)
                        .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); set(q) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        Portion(q, portion.unit).label,
                        color = if (selected) x.onWaterContainer else cs.onSurfaceVariant, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun formatTyped(q: Float): String {
    val r = (q * 100f).roundToInt() / 100f
    return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
}
