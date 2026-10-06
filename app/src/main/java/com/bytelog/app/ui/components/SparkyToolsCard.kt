package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.remote.SparkyTools
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroProtein

/** Settings card: which tool categories Sparky AI may use from this app (read and write). */
@Composable
fun SparkyToolsCard(
    enabled: Set<String>,
    onChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ByteSurfaceDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("SPARKY AI TOOLS", color = ByteTomato, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
        Text(
            "What Sparky can do when you chat with it or import a recipe. Tools run on your Sparky server and can read and change your data.",
            color = ByteCreamMuted, fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Preset("All", enabled == SparkyTools.allSlugs) { onChange(SparkyTools.allSlugs) }
            Preset("Core", enabled == SparkyTools.coreSlugs) { onChange(SparkyTools.coreSlugs) }
            Preset("Reports only", enabled == setOf("reports")) { onChange(setOf("reports")) }
        }
        SparkyTools.categories.forEach { c ->
            val on = c.slug in enabled
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onChange(if (on) enabled - c.slug else enabled + c.slug) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        c.label + if (c.writes) "  ·  read & write" else "  ·  read only",
                        color = ByteCream, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(c.description, color = ByteCreamMuted, fontSize = 11.sp)
                }
                Switch(
                    checked = on,
                    onCheckedChange = { onChange(if (on) enabled - c.slug else enabled + c.slug) },
                    colors = SwitchDefaults.colors(checkedTrackColor = ByteTomato)
                )
            }
        }
        if (enabled.isEmpty()) {
            Text("Nothing selected: Sparky will use your server's default tools.", color = ByteCreamMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Preset(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) ByteSurfaceDark else MacroProtein,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MacroProtein else ByteSurfaceVariantDark)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}
