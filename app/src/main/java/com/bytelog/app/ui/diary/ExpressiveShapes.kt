package com.bytelog.app.ui.diary

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bytelog.app.ui.theme.LocalReduceMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Material 3 Expressive building blocks drawn by hand, so they work on the Material 3 version the app ships:
 * morphing shapes (cookie, clover, flower...), wavy progress and a shape-shifting loading indicator.
 * Motion uses the expressive spring tokens: spatial (damping 0.8, stiffness 380) and slow spatial (0.8, 200).
 */

private const val SAMPLES = 96

/** Shapes from the Material shape library, described as a radius around the centre. */
enum class BlobShape(private val radius: (Double) -> Double) {
    COOKIE9({ 1 + 0.06 * cos(9 * it) }),
    CLOVER4({ 1 + 0.14 * cos(4 * it) }),
    FLOWER8({ 1 + 0.09 * cos(8 * it) }),
    SUNNY12({ 1 + 0.045 * cos(12 * it) }),
    PENTAGON({ 1 + 0.07 * cos(5 * it) }),
    CIRCLE({ 1.0 }),
    SQUIRCLE({ val c = abs(cos(it)); val s = abs(sin(it)); 0.94 / (c.pow(4) + s.pow(4)).pow(0.25) });

    fun radii(): FloatArray = FloatArray(SAMPLES) { radius(it.toDouble() / SAMPLES * 2 * PI).toFloat() }

    companion object {
        /** A different shape for each day, so moving between days visibly changes the hero. */
        // Living Flow: one calm circle, every day
        @Suppress("UNUSED_PARAMETER")
        fun forDay(epochDay: Long): BlobShape = CIRCLE
    }
}

/** Morphs between shapes on a slow spatial spring and spins on the spatial spring. */
@Stable
class MorphState(initial: BlobShape) {
    private var from by mutableStateOf(initial.radii())
    private var to by mutableStateOf(initial.radii())
    val progress = Animatable(1f)
    val rotation = Animatable(0f)
    var shape = initial
        private set

    fun current(): FloatArray {
        val p = progress.value
        val a = from; val b = to
        return FloatArray(SAMPLES) { a[it] + (b[it] - a[it]) * p }
    }

    suspend fun morphTo(target: BlobShape, spinDegrees: Float, reduce: Boolean) {
        val now = current()
        shape = target
        from = now; to = target.radii()
        if (reduce) { progress.snapTo(1f); return }
        progress.snapTo(0f)
        coroutineScope {
            launch { progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 200f)) }
            launch { rotation.animateTo(rotation.value + spinDegrees, spring(dampingRatio = 0.8f, stiffness = 380f)) }
        }
    }
}

@Composable
fun rememberMorphState(shape: BlobShape, spinDegrees: Float = 60f): MorphState {
    val reduce = LocalReduceMotion.current
    val state = remember { MorphState(shape) }
    LaunchedEffect(shape) { if (shape != state.shape) state.morphTo(shape, spinDegrees, reduce) }
    return state
}

/** The outline of [radii] (from a [MorphState]) as a smooth closed path. */
fun blobPath(radii: FloatArray, rotationDegrees: Float, radius: Float, center: Offset): Path {
    val n = radii.size
    val rot = rotationDegrees / 180f * PI.toFloat()
    val xs = FloatArray(n); val ys = FloatArray(n)
    for (i in 0 until n) {
        val a = i.toFloat() / n * 2f * PI.toFloat() + rot
        xs[i] = center.x + cos(a) * radii[i] * radius
        ys[i] = center.y + sin(a) * radii[i] * radius
    }
    return Path().apply {
        moveTo(xs[0], ys[0])
        for (i in 0 until n) {
            val i0 = (i - 1 + n) % n; val i2 = (i + 1) % n; val i3 = (i + 2) % n
            cubicTo(
                xs[i] + (xs[i2] - xs[i0]) / 6f, ys[i] + (ys[i2] - ys[i0]) / 6f,
                xs[i2] - (xs[i3] - xs[i]) / 6f, ys[i2] - (ys[i3] - ys[i]) / 6f,
                xs[i2], ys[i2]
            )
        }
        close()
    }
}

/** Draws [radii] (from a [MorphState]) as a smooth closed shape. */
fun DrawScope.drawBlob(radii: FloatArray, rotationDegrees: Float, color: Color, radius: Float, center: Offset = this.center) {
    drawPath(blobPath(radii, rotationDegrees, radius, center), color)
}

/**
 * Fills a shape like a glass: [level] (0..1) of its height from the bottom, with a gently moving wavy surface.
 * The empty part is drawn in [emptyColor].
 */
fun DrawScope.drawFilledBlob(
    radii: FloatArray, rotationDegrees: Float, radius: Float, level: Float, phase: Float,
    fillColor: Color, emptyColor: Color, center: Offset = this.center
) {
    val outline = blobPath(radii, rotationDegrees, radius, center)
    drawPath(outline, emptyColor)
    val top = center.y - radius * 1.15f
    val bottom = center.y + radius * 1.15f
    val surface = bottom - (bottom - top) * level.coerceIn(0f, 1f)
    if (level <= 0f) return
    val amp = if (level >= 1f) 0f else radius * 0.07f
    val left = center.x - radius * 1.2f; val right = center.x + radius * 1.2f
    val liquid = Path().apply {
        moveTo(left, bottom)
        lineTo(left, surface)
        var x = left
        while (x < right) {
            x = min(right, x + 2f)
            lineTo(x, surface + amp * sin((x - left) / radius * 3.2f - phase))
        }
        lineTo(right, bottom)
        close()
    }
    clipPath(outline) { drawPath(liquid, fillColor) }
}

/** A shape that morphs to [shape] whenever it changes. */
@Composable
fun MorphingBlob(shape: BlobShape, color: Color, modifier: Modifier = Modifier, spinDegrees: Float = 60f, fill: Float = 0.9f) {
    val state = rememberMorphState(shape, spinDegrees)
    Canvas(modifier) { drawBlob(state.current(), state.rotation.value, color, min(size.width, size.height) / 2f * fill) }
}

/** Phase of the moving wave on wavy progress indicators; still when animations are off. */
@Composable
fun rememberWavePhase(): State<Float> {
    val reduce = LocalReduceMotion.current
    if (reduce) return remember { mutableStateOf(0f) }
    val t = rememberInfiniteTransition(label = "wave")
    return t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "wave_phase")
}

/**
 * Living Flow progress bar: a thick rounded track with a luminous fill and a soft glow at its leading edge
 * (the "comet"). Name kept from the earlier wavy bar so every caller picks up the new look.
 */
@Composable
fun WavyLinearProgress(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    height: Dp = 12.dp,
    @Suppress("UNUSED_PARAMETER") amplitude: Dp = 2.4.dp,
    @Suppress("UNUSED_PARAMETER") wavelength: Dp = 18.dp,
    stroke: Dp = 6.dp
) {
    Canvas(modifier.height(height)) {
        val w = size.width; val y = size.height / 2
        val sw = max(stroke.toPx(), 6.dp.toPx()).coerceAtMost(size.height)
        drawLine(trackColor, Offset(sw / 2, y), Offset(w - sw / 2, y), sw, StrokeCap.Round)
        val end = progress.coerceIn(0f, 1f) * (w - sw) + sw / 2
        if (progress > 0.001f) {
            drawLine(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(color.copy(alpha = 0.55f), color), startX = 0f, endX = end),
                Offset(sw / 2, y), Offset(end, y), sw, StrokeCap.Round
            )
            drawFlowGlow(color, Offset(end, y), sw * 1.6f)
        }
    }
}

/** A soft light at the head of a progress fill. */
fun DrawScope.drawFlowGlow(color: Color, at: Offset, radius: Float) {
    drawCircle(
        androidx.compose.ui.graphics.Brush.radialGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent), at, radius),
        radius, at
    )
    drawCircle(Color.White.copy(alpha = 0.55f), radius * 0.16f, at)
}

/** Living Flow progress ring: smooth arc with a luminous gradient and a glowing head. */
fun DrawScope.drawWavyRing(progress: Float, @Suppress("UNUSED_PARAMETER") phase: Float, color: Color, track: Color, ringRadius: Float, stroke: Float, @Suppress("UNUSED_PARAMETER") amplitude: Float, @Suppress("UNUSED_PARAMETER") waves: Int = 16) {
    val topLeft = Offset(center.x - ringRadius, center.y - ringRadius)
    val sz = androidx.compose.ui.geometry.Size(ringRadius * 2, ringRadius * 2)
    drawArc(track, 0f, 360f, false, topLeft, sz, style = Stroke(stroke, cap = StrokeCap.Round))
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0.001f) return
    val sweep = 360f * p
    // The gradient runs from the start of the arc (12 o'clock) to its head, brightest at the head
    rotate(-90f, center) {
        drawArc(
            androidx.compose.ui.graphics.Brush.sweepGradient(0f to color.copy(alpha = 0.5f), p to color, center = center),
            0f, sweep, false, topLeft, sz, style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
    val a = ((sweep - 90f) / 180f * PI).toFloat()
    drawFlowGlow(color, Offset(center.x + cos(a) * ringRadius, center.y + sin(a) * ringRadius), stroke * 1.8f)
}

/** Loading: a comet of light running round a soft ring (name kept for its callers). */
@Composable
fun MorphingLoadingIndicator(modifier: Modifier = Modifier, indicatorSize: Dp = 32.dp) {
    val reduce = LocalReduceMotion.current
    val spin = rememberInfiniteTransition(label = "loader").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "loader_spin")
    val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val active = MaterialTheme.colorScheme.primary
    Box(modifier.size(indicatorSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = size.minDimension * 0.11f
            val r = size.minDimension / 2f - sw
            val tl = Offset(center.x - r, center.y - r); val sz = androidx.compose.ui.geometry.Size(r * 2, r * 2)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(sw))
            rotate(if (reduce) 0f else spin.value, center) {
                drawArc(
                    androidx.compose.ui.graphics.Brush.sweepGradient(0f to Color.Transparent, 0.3f to active, center = center),
                    0f, 108f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round)
                )
                drawFlowGlow(active, Offset(center.x + cos(108f / 180f * PI.toFloat()) * r, center.y + sin(108f / 180f * PI.toFloat()) * r), sw * 1.8f)
            }
        }
    }
}

internal fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t
internal fun clamp01(v: Float) = max(0f, min(1f, v))

/**
 * Drop-in for an indeterminate CircularProgressIndicator: the expressive shape-shifting indicator, sized by
 * [modifier] (24 dp by default). [color] tints the moving shape; the container is a soft tint of it.
 */
@Composable
fun LoadingSpinner(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    @Suppress("UNUSED_PARAMETER") strokeWidth: Dp = 0.dp,
    @Suppress("UNUSED_PARAMETER") trackColor: Color = Color.Transparent
) {
    val reduce = LocalReduceMotion.current
    val state = remember { MorphState(BlobShape.COOKIE9) }
    val spin = rememberInfiniteTransition(label = "spinner").animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spinner_spin")
    LaunchedEffect(Unit) {
        val seq = listOf(BlobShape.CLOVER4, BlobShape.PENTAGON, BlobShape.FLOWER8, BlobShape.SUNNY12, BlobShape.COOKIE9)
        var i = 0
        while (true) { delay(550); state.morphTo(seq[i++ % seq.size], 0f, reduce) }
    }
    Canvas(modifier.defaultMinSize(24.dp, 24.dp)) {
        drawCircle(color.copy(alpha = 0.18f))
        drawBlob(state.current(), if (reduce) 0f else spin.value, color, size.minDimension * 0.3f)
    }
}
