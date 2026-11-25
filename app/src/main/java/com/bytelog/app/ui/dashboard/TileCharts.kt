package com.bytelog.app.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A small picture of a tile's data, drawn in the tile's colour. */
@Immutable
sealed interface TileChart {
    /** A bar a day (or a person); [goal] as a dashed line; [labels] under the bars; the [highlight] bar stands out (-2: all do). */
    data class Bars(val values: List<Float>, val goal: Float? = null, val labels: List<String> = emptyList(), val highlight: Int = values.lastIndex) : TileChart
    /** A smooth line through [points], ending in a dot. */
    data class Spark(val points: List<Float>) : TileChart
    /** How full, 0..1, with [caption] beside it. */
    data class Meter(val fraction: Float, val caption: String? = null) : TileChart
    /** Protein, carbs and fat: label, how far to goal (0..1), grams, colour. */
    data class Macros(val parts: List<MacroPart>) : TileChart
    /** Pictures, newest first, loaded when shown. */
    class Photos(val count: Int, val load: List<suspend () -> ImageBitmap?>) : TileChart
}

@Immutable
data class MacroPart(val label: String, val fraction: Float, val grams: Int, val color: Color)

/** Draws [chart], filling [modifier]'s space. */
@Composable
fun TileChartView(chart: TileChart, accent: Color, modifier: Modifier = Modifier, large: Boolean = false) {
    when (chart) {
        is TileChart.Bars -> BarsView(chart, accent, modifier)
        is TileChart.Spark -> SparkView(chart.points, accent, modifier)
        is TileChart.Meter -> MeterView(chart, accent, modifier)
        is TileChart.Macros -> MacrosView(chart, modifier, large)
        is TileChart.Photos -> PhotosView(chart, modifier, if (large) 4 else 2)
    }
}

@Composable
private fun BarsView(chart: TileChart.Bars, accent: Color, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val track = cs.surfaceContainerHighest.copy(alpha = 0.7f)
    val grow = rememberEntrance()
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val n = chart.values.size.coerceAtLeast(1)
            val top = maxOf(chart.values.maxOrNull() ?: 0f, chart.goal ?: 0f).takeIf { it > 0f } ?: 1f
            // Bars start a little below the lowest day, so days that differ look different (an empty day stays empty)
            val lowest = (chart.values.filter { it > 0f } + listOfNotNull(chart.goal)).minOrNull() ?: 0f
            val floor = (lowest * 0.55f).coerceAtMost(top * 0.9f)
            fun frac(v: Float) = if (v <= 0f) 0f else (0.12f + 0.88f * (v - floor) / (top - floor).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
            val gap = 4.dp.toPx()
            val bw = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            val r = CornerRadius(minOf(bw / 2, 4.dp.toPx()))
            chart.values.forEachIndexed { i, v ->
                val x = i * (bw + gap)
                drawRoundRect(track, Offset(x, 0f), Size(bw, size.height), r)
                val h = frac(v) * size.height * grow
                if (h > 0f) {
                    val c = if (i == chart.highlight || chart.highlight == -2) accent else accent.copy(alpha = 0.5f)
                    drawRoundRect(c, Offset(x, size.height - h), Size(bw, h), r)
                }
            }
            chart.goal?.takeIf { it > 0f }?.let { g ->
                val y = size.height - frac(g) * size.height
                drawLine(cs.onSurface.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            }
        }
        if (chart.labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                chart.labels.forEachIndexed { i, l ->
                    Text(
                        l, color = if (i == chart.highlight) cs.onSurface else cs.onSurfaceVariant, fontSize = 9.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SparkView(points: List<Float>, accent: Color, modifier: Modifier) {
    val reveal = rememberEntrance()
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val lo = points.min(); val hi = points.max(); val span = (hi - lo).takeIf { it > 0.01f } ?: 1f
        val pad = 4.dp.toPx()
        val stepX = (size.width - pad * 2) / (points.size - 1)
        val pts = points.indices.map { i -> Offset(pad + i * stepX, size.height - pad - (points[i] - lo) / span * (size.height - pad * 2)) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 0 until pts.lastIndex) {
                val p0 = pts[maxOf(0, i - 1)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[minOf(pts.lastIndex, i + 2)]
                cubicTo(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f, p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f, p2.x, p2.y)
            }
        }
        val area = Path().apply { addPath(path); lineTo(pts.last().x, size.height); lineTo(pts.first().x, size.height); close() }
        clipRectReveal(reveal) {
            drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.25f), accent.copy(alpha = 0f))))
            drawPath(path, accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawCircle(accent, 4.dp.toPx(), pts.last())
        drawCircle(Color.White.copy(alpha = 0.9f), 1.6.dp.toPx(), pts.last())
    }
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.clipRectReveal(fraction: Float, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    drawContext.canvas.save()
    drawContext.canvas.clipRect(0f, 0f, size.width * fraction.coerceIn(0f, 1f), size.height)
    block()
    drawContext.canvas.restore()
}

@Composable
private fun MeterView(chart: TileChart.Meter, accent: Color, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val p by animateFloatAsState(chart.fraction.coerceIn(0f, 1f), spring(dampingRatio = 0.8f, stiffness = 200f), label = "meter")
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        chart.caption?.let {
            Text(it, color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerHighest)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(RoundedCornerShape(50)).background(accent))
        }
    }
}

@Composable
private fun MacrosView(chart: TileChart.Macros, modifier: Modifier, large: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (large) 8.dp else 2.dp, Alignment.Bottom)) {
        chart.parts.forEach { part ->
            val p by animateFloatAsState(part.fraction.coerceIn(0f, 1f), spring(dampingRatio = 0.8f, stiffness = 200f), label = "macro_${part.label}")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(part.label, color = part.color, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(12.dp))
                Box(Modifier.weight(1f).height(if (large) 8.dp else 6.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerHighest)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(RoundedCornerShape(50)).background(part.color))
                }
                Text("${part.grams}g", color = cs.onSurfaceVariant, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End, modifier = Modifier.width(34.dp))
            }
        }
    }
}

@Composable
private fun PhotosView(chart: TileChart.Photos, modifier: Modifier, max: Int) {
    val cs = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chart.load.take(max).forEach { load ->
            val image by produceState<ImageBitmap?>(null, load) { value = runCatching { load() }.getOrNull() }
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    ?: Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = cs.onSurfaceVariant)
            }
        }
        // Room for more, so one photo doesn't stretch across the tile
        repeat((max - chart.load.size).coerceAtLeast(0)) { Box(Modifier.weight(1f)) }
    }
}

/** 0 to 1 once, as the chart first appears (straight to 1 with reduced motion). Read while drawing. */
@Composable
private fun rememberEntrance(): Float {
    val reduce = com.bytelog.app.ui.theme.LocalReduceMotion.current
    val a = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (reduce) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { a.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 140f)) }
    return a.value
}

/**
 * The tile as a glass: water rising from the bottom to [level] (0..1), its surface two waves rolling opposite ways over
 * a gentle bob, like the home screen water widget. Still with reduced motion. Drawn behind the tile's content.
 */
@Composable
fun Modifier.waterFill(level: Float, color: Color, deep: Color): Modifier {
    val reduce = com.bytelog.app.ui.theme.LocalReduceMotion.current
    val lv by animateFloatAsState(level.coerceIn(0f, 1f), spring(dampingRatio = 0.6f, stiffness = 60f), label = "water_level")
    val phase = if (reduce) androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) } else
        androidx.compose.animation.core.rememberInfiniteTransition(label = "water_wave").animateFloat(
            0f, (2 * Math.PI).toFloat(),
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(3200, easing = androidx.compose.animation.core.LinearEasing)),
            label = "water_phase"
        )
    fun lerp(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)
    val light = lerp(color, Color.White, 0.15f)
    return this.drawBehind {
        if (lv <= 0f) return@drawBehind
        val ph = phase.value
        val w = size.width; val h = size.height; val u = 1.dp.toPx()
        val tau = (2 * Math.PI).toFloat()
        val top = h - lv * h - (if (lv >= 1f) 8f * u else 0f) + 1.2f * u * kotlin.math.sin(ph)
        val path = Path().apply {
            moveTo(-6f, h + 6f)
            var x = -6f
            while (true) {
                val t = x / w
                lineTo(x, top + 3.2f * u * kotlin.math.sin(tau * 2f * t + ph) + 1.4f * u * kotlin.math.sin(tau * 3f * t - 2f * ph))
                if (x >= w + 6f) break
                x = (x + 3f * u).coerceAtMost(w + 6f)
            }
            lineTo(w + 6f, h + 6f); close()
        }
        // Soft enough that the text over it stays easy to read
        drawPath(path, Brush.verticalGradient(listOf(lerp(deep, color, 0.35f).copy(alpha = 0.85f), deep.copy(alpha = 0.9f)), startY = top, endY = h))
        drawPath(path, light.copy(alpha = 0.7f), style = Stroke(1.6f * u))
    }
}
