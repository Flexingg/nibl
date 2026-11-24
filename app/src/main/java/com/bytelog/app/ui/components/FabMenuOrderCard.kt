package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.repository.FabMenuLayout
import com.bytelog.app.ui.diary.fabActionIcon

/**
 * Settings card for the home screen's + menu: move each action up or down (the bottom one sits next to the
 * + button) and switch off the ones you don't use. Settings always stays, since the menu is how you get there.
 */
@Composable
fun FabMenuOrderCard(saved: FabMenuLayout, onChange: (FabMenuLayout) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    // Edited here and saved as it changes; the stored copy catches up a moment later
    var layout by remember { mutableStateOf(saved) }
    LaunchedEffect(saved) { if (saved != layout) layout = saved }
    fun update(next: FabMenuLayout) { layout = next; onChange(next) }
    fun move(from: Int, to: Int) { update(layout.move(from, to)); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ADD MENU", color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                Text("The + button's menu, top to bottom. The last one sits next to the + button.", color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
            if (!layout.isDefault) {
                Text(
                    "Reset", color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { update(FabMenuLayout.DEFAULT) }.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        layout.order.forEachIndexed { index, action ->
            val shown = action !in layout.hidden
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(50)).background(cs.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(fabActionIcon(action), contentDescription = null, tint = if (shown) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    action.label, modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = if (shown) cs.onSurface else cs.onSurfaceVariant
                )
                IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move ${action.label} up")
                }
                IconButton(onClick = { move(index, index + 1) }, enabled = index < layout.order.lastIndex) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move ${action.label} down")
                }
                if (action.hideable) {
                    Switch(checked = shown, onCheckedChange = { update(layout.toggle(action, it)) })
                } else {
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Lock, contentDescription = "Always shown", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
