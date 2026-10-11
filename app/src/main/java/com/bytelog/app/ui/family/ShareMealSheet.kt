package com.bytelog.app.ui.family

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.family.FamilySplit
import com.bytelog.app.data.family.Share
import com.bytelog.app.data.family.ShareMode
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.remote.FamilyMember
import com.bytelog.app.ui.diary.MorphButton
import kotlin.math.roundToInt

/**
 * Share one meal with family: split it by percent (each person's items are scaled to their share and yours shrink to
 * match) or copy the whole meal into their diaries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareMealSheet(
    mealName: String,
    entries: List<FoodEntry>,
    members: List<FamilyMember>,
    onDismiss: () -> Unit,
    onConfirm: (shares: List<Share>, mode: ShareMode) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        ShareMealContent(mealName, entries, members, onConfirm)
    }
}

/** The sheet's contents (also rendered on their own in tests, since a bottom sheet draws in its own window). */
@Composable
internal fun ShareMealContent(
    mealName: String,
    entries: List<FoodEntry>,
    members: List<FamilyMember>,
    onConfirm: (shares: List<Share>, mode: ShareMode) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val totalKcal = entries.sumOf { it.calories.toDouble() }.toFloat()
    val writable = remember(members) { members.filter { it.canWriteDiary } }

    var mode by remember { mutableStateOf(ShareMode.SPLIT) }
    // Who's in: the user always is; members are picked. Percents run over [me] + picked members in list order.
    val picked = remember { mutableStateListOf<String>() }
    val people: List<FamilyMember?> = listOf<FamilyMember?>(null) + writable.filter { it.userId in picked }
    var percents by remember { mutableStateOf(listOf(100)) }

    fun resetEven() { percents = FamilySplit.even(1 + picked.size) }
    fun toggle(m: FamilyMember) {
        if (m.userId in picked) picked.remove(m.userId) else picked.add(m.userId)
        resetEven()
    }

    val splitting = mode == ShareMode.SPLIT
    val total = percents.sum()
    val canConfirm = picked.isNotEmpty() && (!splitting || total == 100)

    run {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text("Share $mealName", color = cs.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("${entries.size} item${if (entries.size == 1) "" else "s"} • ${totalKcal.roundToInt()} kcal", color = cs.onSurfaceVariant, fontSize = 13.sp)
            }

            // Split or copy
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(cs.surfaceContainerHighest).padding(4.dp)) {
                ModeTab("Split meal", splitting, Modifier.weight(1f)) { mode = ShareMode.SPLIT; resetEven() }
                ModeTab("Copy meal", !splitting, Modifier.weight(1f)) { mode = ShareMode.COPY }
            }
            Text(
                if (splitting) "Everyone gets their percent of every item, in their own diary. Yours shrinks to your share."
                else "Each person you pick gets the whole meal added to their diary. Yours stays as it is.",
                color = cs.onSurfaceVariant, fontSize = 13.sp
            )

            if (writable.isEmpty()) {
                Text(
                    "No one has let you add to their diary yet. Ask them to turn on \"manage diary\" for you in Sparky's Family Access settings.",
                    color = cs.onSurface, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.secondaryContainer).padding(14.dp)
                )
            }

            if (splitting) PersonRow("You", null, true, percents.getOrElse(0) { 100 }, totalKcal, steps = true, onToggle = {},
                onMinus = { percents = FamilySplit.adjust(percents, 0, percents[0] - 5) },
                onPlus = { percents = FamilySplit.adjust(percents, 0, percents[0] + 5) })

            writable.forEach { m ->
                val on = m.userId in picked
                val idx = people.indexOfFirst { it?.userId == m.userId }
                val pct = if (splitting && on && idx >= 0) percents.getOrElse(idx) { 0 } else 100
                PersonRow(
                    m.displayName, m, on, pct, totalKcal, steps = splitting, onToggle = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); toggle(m) },
                    onMinus = { if (idx >= 0) percents = FamilySplit.adjust(percents, idx, percents[idx] - 5) },
                    onPlus = { if (idx >= 0) percents = FamilySplit.adjust(percents, idx, percents[idx] + 5) }
                )
            }
            members.filter { !it.canWriteDiary }.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(m, 36)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(m.displayName, color = cs.onSurface.copy(alpha = 0.5f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("View only, can't add to their diary", color = cs.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }

            if (splitting && picked.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (total == 100) "Adds up to 100%" else "Adds up to $total% — needs to be 100%",
                        color = if (total == 100) cs.primary else cs.error, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = ::resetEven) { Text("Split evenly") }
                }
            }

            MorphButton(
                { if (canConfirm) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val shares = if (splitting) people.mapIndexed { i, m -> Share(m, percents[i]) }
                    else picked.mapNotNull { id -> writable.firstOrNull { it.userId == id } }.map { Share(it, 100) }
                    onConfirm(shares, mode)
                } },
                Modifier.fillMaxWidth().height(56.dp), if (canConfirm) cs.primary else cs.surfaceContainerHighest,
                description = if (splitting) "Split meal" else "Copy meal"
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Check, null, tint = if (canConfirm) cs.onPrimary else cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            picked.isEmpty() -> "Pick who to share with"
                            splitting -> "Split with ${picked.size} ${if (picked.size == 1) "person" else "people"}"
                            else -> "Copy to ${picked.size} ${if (picked.size == 1) "person" else "people"}"
                        },
                        color = if (canConfirm) cs.onPrimary else cs.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.primary else Color.Transparent, label = "mode_bg")
    Box(modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) cs.onPrimary else cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PersonRow(
    name: String,
    member: FamilyMember?,
    selected: Boolean,
    percent: Int,
    totalKcal: Float,
    steps: Boolean,
    onToggle: () -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.surfaceContainerHigh else cs.surfaceContainer, label = "person_bg")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg)
            .then(if (member != null) Modifier.clickable(onClick = onToggle) else Modifier).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (member != null) Avatar(member, 38)
        else Box(Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Text("Me", color = cs.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                if (selected) "${(totalKcal * percent / 100f).roundToInt()} kcal" else "Tap to include",
                color = cs.onSurfaceVariant, fontSize = 12.sp
            )
        }
        if (selected && steps) {
            StepButton(Icons.Default.Remove, "Less for $name", onMinus)
            Text("$percent%", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(56.dp).padding(horizontal = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            StepButton(Icons.Default.Add, "More for $name", onPlus)
        } else if (selected) {
            Icon(Icons.Default.Check, null, tint = cs.primary)
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(cs.surfaceContainerHighest).clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = cs.onSurface, modifier = Modifier.size(18.dp)) }
}
