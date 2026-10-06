package com.bytelog.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.analytics.AdaptiveTdeeCalculation
import com.bytelog.app.data.analytics.DayChartPoint
import com.bytelog.app.ui.theme.ByteCardDark
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroProtein
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun WeightVsCaloriesCard(
    chartPoints: List<DayChartPoint>,
    calculation: AdaptiveTdeeCalculation?,
    selectedDaysRange: Int,
    weightUnit: String,
    onSelectDaysRange: (Int) -> Unit,
    onOpenGoalAdjuster: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var activeScrubIndex by remember { mutableIntStateOf(-1) }

    val validPoints = chartPoints.ifEmpty { emptyList() }
    val displayPoint = if (activeScrubIndex in validPoints.indices) {
        validPoints[activeScrubIndex]
    } else {
        validPoints.lastOrNull()
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = ByteCardDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row: Title & Range Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(ByteTomato.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ShowChart,
                            contentDescription = "Weight and Calories",
                            tint = ByteTomato,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = "Weight vs Calories",
                            color = ByteCream,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (calculation != null && !calculation.isFallback)
                                "Expenditure: ~${calculation.tdee.roundToInt()} kcal • ${calculation.confidence}"
                            else "Metabolic Trend (Calibrating)",
                            color = ByteTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                // Range Selector Pills (7D, 14D, 30D, 90D)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(ByteSurfaceVariantDark)
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf(7, 14, 30).forEach { days ->
                        val isSelected = selectedDaysRange == days
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) ByteTomato else Color.Transparent)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectDaysRange(days)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${days}D",
                                color = if (isSelected) ByteOnTomato else ByteTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Interactive Scrubber readout pill
            if (displayPoint != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ByteSurfaceVariantDark.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayPoint.displayDate + if (displayPoint.isToday) " (Today)" else "",
                        color = ByteCream,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "${displayPoint.calories.roundToInt()} kcal",
                            color = ByteTomato,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        val weightStr = displayPoint.actualWeight?.let { String.format(Locale.US, "%.1f %s", it, weightUnit) }
                            ?: displayPoint.smoothedWeight?.let { String.format(Locale.US, "~%.1f %s", it, weightUnit) }
                            ?: "—"
                        Text(
                            text = weightStr,
                            color = MacroCarbs,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Canvas Chart
            if (validPoints.size >= 2) {
                val maxCals = (validPoints.maxOfOrNull { it.calories } ?: 2500f).coerceAtLeast(2000f)
                val weightsWithValues = validPoints.mapNotNull { it.smoothedWeight ?: it.actualWeight }
                val minWeight = (weightsWithValues.minOrNull() ?: 150f) - 1.5f
                val maxWeight = (weightsWithValues.maxOrNull() ?: 180f) + 1.5f
                val weightSpan = (maxWeight - minWeight).coerceAtLeast(2.0f)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .pointerInput(validPoints) {
                            detectTapGestures(
                                onPress = { offset ->
                                    val count = validPoints.size
                                    if (count > 0) {
                                        val idx = ((offset.x / size.width) * count).toInt().coerceIn(0, count - 1)
                                        if (idx != activeScrubIndex) {
                                            activeScrubIndex = idx
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                }
                            )
                        }
                        .pointerInput(validPoints) {
                            detectDragGestures(
                                onDragEnd = {
                                    activeScrubIndex = -1
                                },
                                onDragCancel = {
                                    activeScrubIndex = -1
                                },
                                onDrag = { change, _ ->
                                    val count = validPoints.size
                                    if (count > 0) {
                                        val idx = ((change.position.x / size.width) * count).toInt().coerceIn(0, count - 1)
                                        if (idx != activeScrubIndex) {
                                            activeScrubIndex = idx
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                }
                            )
                        }
                ) {
                    val cCream = ByteCream
                    val cTomato = ByteTomato
                    val cCarbs = MacroCarbs
                    val cCard = ByteCardDark
                    Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                        val canvasWidth = size.width
                        val canvasHeight = size.height
                        val count = validPoints.size
                        val slotWidth = canvasWidth / count
                        val barWidth = (slotWidth * 0.55f).coerceIn(4f, 22f)

                        // 1. Target Calorie reference line (dashed)
                        val targetCal = validPoints.firstOrNull()?.targetCalories ?: 2000f
                        val targetY = canvasHeight - ((targetCal / maxCals) * (canvasHeight * 0.85f))
                        drawLine(
                            color = cCream.copy(alpha = 0.15f),
                            start = Offset(0f, targetY),
                            end = Offset(canvasWidth, targetY),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        )

                        // 2. Draw Calorie Bars
                        for (i in 0 until count) {
                            val pt = validPoints[i]
                            val barCenterX = slotWidth * i + (slotWidth / 2f)
                            val barHeight = ((pt.calories / maxCals) * (canvasHeight * 0.85f)).coerceAtLeast(4f)
                            val topY = canvasHeight - barHeight

                            val barColor = if (i == activeScrubIndex) cTomato else cTomato.copy(alpha = 0.45f)
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(barCenterX - (barWidth / 2f), topY),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                            )
                        }

                        // 3. Draw Weight Trend Line (Smooth curve across smoothed points)
                        val weightPath = Path()
                        val weightPoints = mutableListOf<Offset>()

                        for (i in 0 until count) {
                            val pt = validPoints[i]
                            val w = pt.smoothedWeight ?: pt.actualWeight
                            if (w != null) {
                                val x = slotWidth * i + (slotWidth / 2f)
                                val normalizedWeight = ((w - minWeight) / weightSpan).coerceIn(0f, 1f)
                                // Top 15% to bottom 15% of chart
                                val y = (canvasHeight * 0.15f) + ((1f - normalizedWeight) * (canvasHeight * 0.65f))
                                weightPoints.add(Offset(x, y))
                            }
                        }

                        if (weightPoints.isNotEmpty()) {
                            weightPath.moveTo(weightPoints.first().x, weightPoints.first().y)
                            for (j in 1 until weightPoints.size) {
                                val p0 = weightPoints[j - 1]
                                val p1 = weightPoints[j]
                                val midX = (p0.x + p1.x) / 2f
                                weightPath.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                            }
                            drawPath(
                                path = weightPath,
                                color = cCarbs,
                                style = Stroke(width = 4f, cap = StrokeCap.Round)
                            )
                        }

                        // 4. Draw actual weigh-in dots
                        for (i in 0 until count) {
                            val pt = validPoints[i]
                            if (pt.actualWeight != null) {
                                val x = slotWidth * i + (slotWidth / 2f)
                                val normalizedWeight = ((pt.actualWeight - minWeight) / weightSpan).coerceIn(0f, 1f)
                                val y = (canvasHeight * 0.15f) + ((1f - normalizedWeight) * (canvasHeight * 0.65f))

                                drawCircle(
                                    color = cCard,
                                    radius = 6f,
                                    center = Offset(x, y)
                                )
                                drawCircle(
                                    color = cCarbs,
                                    radius = 4f,
                                    center = Offset(x, y)
                                )
                            }
                        }

                        // 5. Scrubber vertical line if scrubbed
                        if (activeScrubIndex in 0 until count) {
                            val scrubX = slotWidth * activeScrubIndex + (slotWidth / 2f)
                            drawLine(
                                color = cCream.copy(alpha = 0.5f),
                                start = Offset(scrubX, 0f),
                                end = Offset(scrubX, canvasHeight),
                                strokeWidth = 2f
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Log food and weight to see your trend graph",
                        color = ByteTextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Footer / Goal Adjuster Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Legend
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(ByteTomato))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Calories", color = ByteTextSecondary, fontSize = 11.sp)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MacroCarbs))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Weight Trend", color = ByteTextSecondary, fontSize = 11.sp)
                    }
                }

                // Adjust Goal Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(ByteTomato.copy(alpha = 0.15f))
                        .border(1.dp, ByteTomato.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenGoalAdjuster()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = ByteTomato,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Adjust Goal",
                            color = ByteTomato,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
