package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.ui.logging.Portion
import com.bytelog.app.ui.logging.Portions
import kotlin.math.roundToInt

/** A suggested food with the amount one tap adds. */
data class TogetherPick(val food: FoodItem, val portion: Portion)

/**
 * "Often with Oatmeal": foods you usually have with what you've just picked, one tap each to add them at your
 * usual amount. Shown in the tray right after something is added.
 */
@Composable
fun TogetherStrip(label: String, picks: List<TogetherPick>, onAdd: (FoodItem) -> Unit, modifier: Modifier = Modifier) {
    if (picks.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(picks, key = { it.food.id }) { pick ->
                val kcal = Portions.macros(pick.food, pick.portion).calories.roundToInt()
                Row(
                    Modifier
                        .widthIn(max = 230.dp)
                        .clip(RoundedCornerShape(50))
                        .background(cs.secondaryContainer.copy(alpha = 0.7f))
                        .clickable(onClickLabel = "Add to tray") { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onAdd(pick.food) }
                        .padding(start = 8.dp, end = 12.dp, top = 7.dp, bottom = 7.dp)
                        .semantics { contentDescription = "Add ${pick.food.name}, ${pick.portion.label}, $kcal calories" },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(pick.food.name, color = cs.onSecondaryContainer, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("  $kcal", color = cs.onSecondaryContainer.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}
