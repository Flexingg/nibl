package com.bytelog.app.ui.diary

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.repository.FitOption
import com.bytelog.app.data.repository.WeeklyReview
import com.bytelog.app.ui.components.pressBounce
import com.bytelog.app.ui.theme.LocalByteExtraColors
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "What fits?": foods you eat that fit what's left today, at your usual amounts. One tap logs a suggestion
 * to [mealName].
 */
@Composable
fun WhatFitsCard(
    caloriesLeft: Int,
    proteinLeft: Int,
    options: List<FitOption>,
    mealName: String,
    onLog: (FitOption) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val x = LocalByteExtraColors.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Lightbulb, contentDescription = null, tint = cs.onTertiaryContainer, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("WHAT FITS?", color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Text(
                    buildString {
                        append("${"%,d".format(caloriesLeft)} kcal")
                        if (proteinLeft >= 10) append(" · $proteinLeft g protein")
                        append(" left")
                    },
                    color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            options.forEachIndexed { i, o ->
                val first = i == 0; val last = i == options.lastIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressBounce(0.98f)
                        .clip(RoundedCornerShape(topStart = if (first) 18.dp else 6.dp, topEnd = if (first) 18.dp else 6.dp, bottomStart = if (last) 18.dp else 6.dp, bottomEnd = if (last) 18.dp else 6.dp))
                        .background(cs.surfaceContainerHigh)
                        .clickable(onClickLabel = "Log to $mealName") { onLog(o) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(o.label, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${o.calories.roundToInt()} kcal · P ${o.protein.roundToInt()} g · C ${o.carbs.roundToInt()} · F ${o.fat.roundToInt()}",
                            color = cs.onSurfaceVariant, fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Text("Tap one to log it to $mealName", color = cs.onSurfaceVariant, fontSize = 11.5.sp)
    }
}

/** The last seven days: averages, days on target, a bar per day against the goal, the streak and weight. */
@Composable
fun WeeklyReviewCard(review: WeeklyReview, weightUnit: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val x = LocalByteExtraColors.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Insights, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("YOUR WEEK", color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Text(
                    "${review.start.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${review.start.dayOfMonth} – " +
                        "${review.end.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${review.end.dayOfMonth}",
                    color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(50)).clickable(onClick = onDismiss).semantics { contentDescription = "Close weekly review" },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Close, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
        }

        // A bar per day, the goal as a dashed line; on-target days in the primary colour
        val goal = review.calorieGoal
        val top = maxOf(goal * 1.25f, review.days.maxOf { it.calories }, 1f)
        val onTarget = cs.primary; val off = cs.tertiary; val empty = cs.surfaceContainerHighest; val line = cs.onSurfaceVariant
        Column {
            Canvas(Modifier.fillMaxWidth().height(84.dp).semantics { contentDescription = "Calories each day against the goal" }) {
                val slot = size.width / 7f
                val bw = slot * 0.56f
                review.days.forEachIndexed { i, d ->
                    val h = if (d.logged) (d.calories / top * size.height).coerceAtLeast(4f) else 6f
                    val color = when {
                        !d.logged -> empty
                        goal > 0f && abs(d.calories - goal) <= goal * WeeklyReview.ON_TARGET -> onTarget
                        else -> off
                    }
                    drawRoundRect(color, Offset(i * slot + (slot - bw) / 2f, size.height - h), Size(bw, h), CornerRadius(bw / 2.5f))
                }
                if (goal > 0f) {
                    val gy = size.height - goal / top * size.height
                    drawLine(line.copy(alpha = 0.6f), Offset(0f, gy), Offset(size.width, gy), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                review.days.forEach { d ->
                    Text(
                        d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("ON TARGET", "${review.daysOnTarget}/7", "days", Modifier.weight(1f), cs.secondaryContainer, cs.onSecondaryContainer)
            Stat("AVERAGE", "%,d".format(review.avgCalories), "of ${"%,d".format(goal.roundToInt())} kcal", Modifier.weight(1f), cs.surfaceContainerHigh, cs.onSurface)
            Stat("PROTEIN", "${review.avgProtein} g", "${review.proteinDays}/7 days hit", Modifier.weight(1f), x.waterContainer, x.onWaterContainer)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("STREAK", "${review.streak}", if (review.streak == 1) "day logged" else "days logged", Modifier.weight(1f), cs.tertiaryContainer, cs.onTertiaryContainer)
            val w = review.weightChange
            Stat(
                "WEIGHT", w?.let { (if (it > 0) "+" else "") + String.format(Locale.US, "%.1f", it) } ?: "—",
                if (w != null) "$weightUnit this week" else "weigh in twice", Modifier.weight(1f), cs.surfaceContainerHigh, cs.onSurface
            )
            Stat("MACROS", "${review.avgCarbs}·${review.avgFat}", "carbs·fat g", Modifier.weight(1f), cs.surfaceContainerHigh, cs.onSurface)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, detail: String, modifier: Modifier, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(bg).padding(horizontal = 10.dp, vertical = 10.dp)) {
        Text(label, color = fg.copy(alpha = 0.75f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Text(value, color = fg, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(detail, color = fg.copy(alpha = 0.75f), fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small "Fasting 14h 20m" pill. */
@Composable
fun FastingPill(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.clip(RoundedCornerShape(50)).background(cs.secondaryContainer).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Timer, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = cs.onSecondaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
