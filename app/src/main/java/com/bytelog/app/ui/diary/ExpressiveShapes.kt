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
        fun forDay(epochDay: Long): BlobShape = listOf(COOKIE9, CLOVER4, FLOWER8, PENTAGON, SUNNY12, COOKIE9, FLOWER8)[Math.floorMod(epochDay, 7L).toInt()]
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

/** Linear wavy progress: a moving wave up to the value, then a flat track with a stop dot. */
@Composable
fun WavyLinearProgress(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    height: Dp = 12.dp,
    amplitude: Dp = 2.4.dp,
    wavelength: Dp = 18.dp,
    stroke: Dp = 4.dp
) {
    val phase = rememberWavePhase()
    Canvas(modifier.height(height)) {
        val w = size.width; val y = size.height / 2
        val sw = stroke.toPx(); val amp = amplitude.toPx(); val wl = wavelength.toPx()
        val end = progress.coerceIn(0f, 1f) * w
        val gap = 5.dp.toPx()
        if (end + gap < w - sw) {
            drawLine(trackColor, Offset(end + gap, y), Offset(w - sw / 2, y), sw, StrokeCap.Round)
            drawCircle(color, sw / 2, Offset(w - sw / 2, y))
        }
        if (end > sw) {
            val p = Path()
            var x = sw / 2
            p.moveTo(x, y + amp * sin(x / wl * 2f * PI.toFloat() - phase.value))
            while (x < end) {
                x = min(end, x + 1.5f)
                p.lineTo(x, y + amp * sin(x / wl * 2f * PI.toFloat() - phase.value))
            }
            drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round))
        }
    }
}

/** Draws a circular wavy progress ring of radius [ringRadius] around the centre. */
fun DrawScope.drawWavyRing(progress: Float, phase: Float, color: Color, track: Color, ringRadius: Float, stroke: Float, amplitude: Float, waves: Int = 16) {
    val start = -PI.toFloat() / 2
    val sweep = 2 * PI.toFloat() * progress.coerceIn(0.001f, 1f)
    val p = Path()
    var a = start; var first = true
    while (a <= start + sweep) {
        val r = ringRadius + amplitude * sin(waves * (a - start) - phase)   // whole multiple of the phase, so the loop has no seam
        val x = center.x + cos(a) * r; val y = center.y + sin(a) * r
        if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
        a += 0.02f
    }
    drawPath(p, color, style = Stroke(stroke, cap = StrokeCap.Round))
    val gap = 0.12f
    val t0 = start + sweep + gap; val t1 = start + 2 * PI.toFloat() - gap
    if (t0 < t1) {
        drawArc(
            track, t0 * 180f / PI.toFloat(), (t1 - t0) * 180f / PI.toFloat(), false,
            Offset(center.x - ringRadius, center.y - ringRadius), androidx.compose.ui.geometry.Size(ringRadius * 2, ringRadius * 2),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}

/** Shape-shifting loading indicator: a shape that keeps morphing and turning inside a round container. */
@Composable
fun MorphingLoadingIndicator(modifier: Modifier = Modifier, indicatorSize: Dp = 32.dp) {
    val reduce = LocalReduceMotion.current
    val state = remember { MorphState(BlobShape.COOKIE9) }
    val spin = rememberInfiniteTransition(label = "loader").animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "loader_spin")
    LaunchedEffect(Unit) {
        val seq = listOf(BlobShape.CLOVER4, BlobShape.PENTAGON, BlobShape.FLOWER8, BlobShape.SUNNY12, BlobShape.COOKIE9)
        var i = 0
        while (true) { delay(550); state.morphTo(seq[i++ % seq.size], 0f, reduce) }
    }
    val container = MaterialTheme.colorScheme.secondaryContainer
    val active = MaterialTheme.colorScheme.primary
    Box(modifier.size(indicatorSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(container)
            drawBlob(state.current(), if (reduce) 0f else spin.value, active, size.minDimension * 0.29f)
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
