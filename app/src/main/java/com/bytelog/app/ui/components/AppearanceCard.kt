package com.bytelog.app.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.ui.theme.ThemeMode
import com.bytelog.app.ui.theme.supportsDeviceColors

/** Settings card: light/dark/system, and whether to use the device's wallpaper colours. */
@Composable
fun AppearanceCard(
    mode: ThemeMode,
    deviceColors: Boolean,
    onMode: (ThemeMode) -> Unit,
    onDeviceColors: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    reduceTransparency: Boolean = false,
    onReduceTransparency: (Boolean) -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("APPEARANCE", color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        // Connected segmented choice
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
            options.forEachIndexed { i, (m, label) ->
                val selected = m == mode
                val bg by animateColorAsState(if (selected) cs.secondaryContainer else cs.surfaceContainerHighest, label = "mode_bg")
                val fg = if (selected) cs.onSecondaryContainer else cs.onSurface
                val outer = 20.dp; val inner = 6.dp
                val shape = when (i) {
                    0 -> RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = inner, bottomEnd = inner)
                    options.lastIndex -> RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = outer, bottomEnd = outer)
                    else -> RoundedCornerShape(inner)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .pressBounce(0.94f)
                        .clip(if (selected) RoundedCornerShape(outer) else shape)
                        .background(bg)
                        .semantics { role = Role.RadioButton; this.selected = selected }
                        .clickable { onMode(m) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = fg, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = supportsDeviceColors) { onDeviceColors(!deviceColors) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Accent from wallpaper", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (supportsDeviceColors) "Take the accent colour from your wallpaper. Off uses Nibl's tomato."
                    else "Needs Android 12 or later. Using Nibl's tomato.",
                    color = cs.onSurfaceVariant, fontSize = 12.sp
                )
            }
            Switch(checked = deviceColors && supportsDeviceColors, onCheckedChange = onDeviceColors, enabled = supportsDeviceColors)
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onReduceTransparency(!reduceTransparency) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Reduce transparency", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Solid bars and menus instead of frosted glass. Easier to read.", color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
            Switch(checked = reduceTransparency, onCheckedChange = onReduceTransparency)
        }
    }
}
