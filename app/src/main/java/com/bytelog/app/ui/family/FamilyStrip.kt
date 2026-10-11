package com.bytelog.app.ui.family

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.remote.FamilyMember
import com.bytelog.app.ui.diary.WavyLinearProgress
import com.bytelog.app.ui.viewmodel.MemberToday
import kotlin.math.roundToInt

/** Family at a glance on the Diary: each person with today's calories against their goal. Tap one to open their day. */
@Composable
fun FamilyStrip(
    members: List<FamilyMember>,
    today: Map<String, MemberToday>,
    onOpenMember: (String) -> Unit,
    onOpenFamily: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainer).padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenFamily).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("FAMILY TODAY", color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, contentDescription = "Open family", tint = cs.onSurfaceVariant)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(members, key = { it.userId }) { m ->
                val t = today[m.userId]
                Column(
                    Modifier.width(132.dp).clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh)
                        .clickable { onOpenMember(m.userId) }.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(m, 28)
                        Spacer(Modifier.width(8.dp))
                        Text(m.displayName, color = cs.onSurface, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (t != null) {
                        Text("${t.calories.roundToInt()} kcal", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Black)
                        val goal = t.goal ?: 0f
                        if (goal > 0f) WavyLinearProgress((t.calories / goal).coerceIn(0f, 1f), cs.primary, Modifier.fillMaxWidth(), height = 8.dp, amplitude = 1.6.dp, wavelength = 12.dp)
                    } else {
                        Text(if (m.canViewDiary) "…" else "No access", color = cs.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
