package com.bytelog.app.ui.theme

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

/*
 * Nibl's "Living Flow" design tokens: calm near-opaque content surfaces, floating frosted-glass chrome with a
 * thin luminous edge, one rounded-corner family and spring motion that compresses, stretches and rebounds.
 * Values are tuned estimates; keep them here so every screen shares them.
 */

/** One corner family. Nested corners: inner = outer − padding. */
object FluidRadius {
    val xs = 8.dp      // tags, small inputs
    val sm = 12.dp     // chips, thumbnails
    val md = 16.dp     // list rows, text fields, small cards
    val lg = 24.dp     // cards, grouped lists
    val xl = 32.dp     // dialogs, sheets, large panels
}

/** Spring motion. */
object FluidMotion {
    /** Pressing down: quick, no wobble. */
    fun <T> press(): AnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 800f)
    /** Letting go: one visible overshoot, settles in about 400 ms. */
    fun <T> release(): AnimationSpec<T> = spring(dampingRatio = 0.55f, stiffness = 450f)
    /** Container changes: menus, sheets, a pill growing into a page. */
    fun <T> morph(): AnimationSpec<T> = spring(dampingRatio = 0.82f, stiffness = 380f)
    /** Fades, colour and glow. */
    fun <T> gentle(): AnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 200f)
}

enum class GlassLevel { Thin, Regular, Thick }

/** Glass fills, edges and glow for the current light or dark mode. */
@Immutable
data class FluidColors(
    val dark: Boolean,
    val glassThin: Color,
    val glassRegular: Color,
    val glassThick: Color,
    /** Opaque stand-ins when transparency is reduced or blur isn't available. */
    val solidThin: Color,
    val solidThick: Color,
    val edgeTop: Color,
    val edgeBottom: Color,
    val shadow: Color,
    val touchGlow: Color,
    val scrim: Color
) {
    fun glass(level: GlassLevel) = when (level) { GlassLevel.Thin -> glassThin; GlassLevel.Regular -> glassRegular; GlassLevel.Thick -> glassThick }
    fun solid(level: GlassLevel) = if (level == GlassLevel.Thick) solidThick else solidThin
    val edge: Brush get() = Brush.verticalGradient(listOf(edgeTop, edgeBottom))
}

val LightFluid = FluidColors(
    dark = false,
    glassThin = Color(0x8CFFFFFF), glassRegular = Color(0xADFFFFFF), glassThick = Color(0xCCFAFAFC),
    solidThin = Color(0xFFFFFFFF), solidThick = Color(0xFFF7F7F9),
    edgeTop = Color(0xBFFFFFFF), edgeBottom = Color(0x33FFFFFF),
    shadow = Color(0x14000000), touchGlow = Color(0x24FFFFFF), scrim = Color(0x2E000000)
)

val DarkFluid = FluidColors(
    dark = true,
    glassThin = Color(0x80282830), glassRegular = Color(0x9E242428), glassThick = Color(0xC21E1E22),
    solidThin = Color(0xFF1C1C1E), solidThick = Color(0xFF2A2A2D),
    edgeTop = Color(0x38FFFFFF), edgeBottom = Color(0x0DFFFFFF),
    shadow = Color(0x66000000), touchGlow = Color(0x1AFFFFFF), scrim = Color(0x66000000)
)

val LocalFluid = staticCompositionLocalOf { LightFluid }

/** Solid surfaces instead of glass (accessibility setting, or a device without blur). */
val LocalReduceTransparency = compositionLocalOf { false }

/** The content that floating glass on this screen blurs; null where nothing is set up to blur. */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/** Robolectric draws in software, which the blur's GPU layers can't; there the glass falls back to a fill. */
private val isRobolectric: Boolean = Build.FINGERPRINT == "robolectric"

private val blurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isRobolectric

/**
 * A frosted-glass surface: blurs [hazeState]'s content behind it where the device can (Android 12+), else a
 * translucent fill; a thin luminous edge and a soft shadow. With reduced transparency it is opaque.
 * [progressive] fades the blur out towards the top (for bars sitting at the bottom of the screen), or towards the
 * bottom when negative.
 */
fun Modifier.glass(
    level: GlassLevel = GlassLevel.Regular,
    shape: Shape = RoundedCornerShape(FluidRadius.lg),
    hazeState: HazeState? = null,
    elevation: Dp = 0.dp,
    edge: Boolean = true,
    progressive: Int = 0
): Modifier = composed {
    val f = LocalFluid.current
    val reduce = LocalReduceTransparency.current
    val state = hazeState ?: LocalHazeState.current
    val blur = state != null && !reduce && blurSupported
    var m = this
    if (elevation > 0.dp) m = m.shadow(elevation, shape, clip = false, ambientColor = f.shadow, spotColor = f.shadow)
    m = m.clip(shape)
    m = when {
        reduce -> m.background(f.solid(level))
        blur -> m.hazeEffect(
            state,
            HazeStyle(
                backgroundColor = f.solid(level),
                tint = HazeTint(f.glass(level)),
                blurRadius = when (level) { GlassLevel.Thin -> 24.dp; GlassLevel.Regular -> 32.dp; GlassLevel.Thick -> 44.dp },
                noiseFactor = 0f
            )
        ) {
            if (progressive > 0) this.progressive = HazeProgressive.verticalGradient(startIntensity = 0f, endIntensity = 1f)
            else if (progressive < 0) this.progressive = HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
        }
        // No blur here (older Android): solid, so content under a floating bar never shows through its text
        else -> m.background(f.solid(level))
    }
    if (edge) m = m.border(if (reduce) 1.dp else 1.dp, if (reduce) Brush.verticalGradient(listOf(f.edgeBottom, f.edgeBottom)) else f.edge, shape)
    m
}

/** A calm content card: opaque surface, the card radius, a faint edge in dark mode. */
fun Modifier.fluidCard(shape: Shape = RoundedCornerShape(FluidRadius.lg)): Modifier = composed {
    val f = LocalFluid.current
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    this.clip(shape).background(cs.surfaceContainer).border(1.dp, f.edge, shape)
}

/**
 * Light that follows the finger: a soft radial highlight under the touch point while pressed, fading out after
 * release. Put it inside the element's clip.
 */
fun Modifier.touchGlow(color: Color? = null): Modifier = composed {
    val f = LocalFluid.current
    val reduceMotion = LocalReduceMotion.current
    val glow = color ?: f.touchGlow
    val alpha = remember { Animatable(0f) }
    val pos = remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                pos.value = down.position
                scope.launch { alpha.snapTo(1f) }
                do {
                    val event = awaitPointerEvent()
                    event.changes.firstOrNull()?.let { pos.value = it.position }
                } while (event.changes.any { it.pressed })
                scope.launch { alpha.animateTo(0f, tween(if (reduceMotion) 0 else 250)) }
            }
        }
        .drawWithContent {
            drawContent()
            val a = alpha.value
            if (a > 0f) {
                val r = minOf(size.width, size.height) * 1.2f
                drawCircle(
                    Brush.radialGradient(listOf(glow.copy(alpha = glow.alpha * a), Color.Transparent), pos.value, r),
                    radius = r, center = pos.value
                )
            }
        }
}

/** Marks content that floating glass on the same screen blurs. */
fun Modifier.blurSource(state: HazeState): Modifier = if (blurSupported) this.then(Modifier.hazeSource(state)) else this

/**
 * The ambient backdrop behind every page: a soft gradient with large blooms of light tinted from the accent,
 * so the translucent cards and floating glass have colour to frost. Plain with reduced transparency.
 */
fun Modifier.fluidBackdrop(): Modifier = composed {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val f = LocalFluid.current
    val x = LocalByteExtraColors.current
    val reduce = LocalReduceTransparency.current
    if (reduce) return@composed this.background(if (f.dark) Color.Black else Color(0xFFF2F3F5))
    val top = if (f.dark) Color(0xFF0B0A0C) else Color(0xFFFFF3EE)
    val bottom = if (f.dark) Color(0xFF07080B) else Color(0xFFEDF0F7)
    val a = if (f.dark) 0.30f else 0.34f
    val blooms = listOf(cs.primary.copy(alpha = a), x.water.copy(alpha = a * 0.75f), x.fat.copy(alpha = a * 0.6f))
    this.drawBehind {
        drawRect(Brush.verticalGradient(listOf(top, bottom)))
        val w = size.width; val h = size.height
        fun bloom(c: Color, cx: Float, cy: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
        bloom(blooms[0], w * 0.05f, h * 0.12f, w * 0.95f)
        bloom(blooms[1], w * 1.0f, h * 0.48f, w * 0.85f)
        bloom(blooms[2], w * 0.2f, h * 0.92f, w * 0.9f)
    }
}
