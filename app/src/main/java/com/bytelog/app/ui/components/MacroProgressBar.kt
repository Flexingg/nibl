package com.bytelog.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.DailySummary
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.MacroCalories
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import kotlin.math.roundToInt

@Composable
fun DailyMacroHeader(
    summary: DailySummary,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ByteSurfaceDark)
            .padding(16.dp)
    ) {
        // Calories Top Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "CALORIES REMAINING",
                    color = ByteCreamMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "${summary.remainingCalories.roundToInt()} kcal",
                    color = ByteCream,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${summary.totalCalories.roundToInt()} / ${summary.targetCalories.roundToInt()}",
                    color = ByteCreamMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Calorie Progress Bar
        val animatedCalProgress = animateBouncy(summary.calorieProgress, "cal_progress").coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 16.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(ByteSurfaceVariantDark)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedCalProgress)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MacroCalories)
            )
        }

        // Macros Breakdown Row (Protein, Carbs, Fat)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MacroMiniPill(
                label = "PROTEIN",
                current = summary.totalProtein,
                target = summary.targetProtein,
                color = MacroProtein,
                modifier = Modifier.weight(1f)
            )
            MacroMiniPill(
                label = "CARBS",
                current = summary.totalCarbs,
                target = summary.targetCarbs,
                color = MacroCarbs,
                modifier = Modifier.weight(1f)
            )
            MacroMiniPill(
                label = "FAT",
                current = summary.totalFat,
                target = summary.targetFat,
                color = MacroFat,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun MacroMiniPill(
    label: String,
    current: Float,
    target: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val progress = if (target > 0f) (current / target).coerceIn(0f, 1f) else 0f
    val animatedProgress = animateBouncy(progress, "macro_progress").coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ByteSurfaceVariantDark)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "${(target - current).coerceAtLeast(0f).roundToInt()}g left",
                color = ByteCreamMuted,
                fontSize = 10.sp
            )
        }
        Text(
            text = "${current.roundToInt()} / ${target.roundToInt()}g",
            color = ByteCream,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
        )
        // Progress Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ByteSurfaceDark)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}
