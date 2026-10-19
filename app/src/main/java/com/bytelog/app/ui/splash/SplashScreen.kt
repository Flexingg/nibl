package com.bytelog.app.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.bytelog.app.ui.theme.BrandTomato
import com.bytelog.app.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay

/** Seconds the splash runs before handing over (the pill has filled the screen by then). */
private const val DURATION = 4.0f

/** The design's easing between keyframes: fast out, long gentle settle. */
private val FLOW = CubicBezierEasing(0.2f, 0.9f, 0.1f, 1f)

/** A value over time from (seconds, value) keyframes, eased between them; held before the first and after the last. */
private fun kf(t: Float, vararg k: Pair<Float, Float>): Float {
    if (t <= k.first().first) return k.first().second
    for (i in 1 until k.size) {
        val (t1, v1) = k[i]
        if (t <= t1) {
            val (t0, v0) = k[i - 1]
            val p = if (t1 == t0) 1f else FLOW.transform((t - t0) / (t1 - t0))
            return v0 + (v1 - v0) * p
        }
    }
    return k.last().second
}

private class Palette(
    val bgTop: Color, val bgBottom: Color, val blooms: List<Color>,
    val glass: Color, val solid: Color, val edgeTop: Color, val edgeBottom: Color, val sheen: Color,
    val glow: Color, val ripple: Color, val text: Color, val text2: Color, val sweep: Color
)

private val LightPalette = Palette(
    Color(0xFFFFF6F1), Color(0xFFFFE6DB),
    listOf(Color(0x6BF2542D), Color(0x80FFAA6E), Color(0x4DFF7896)),
    Color(0x8CFFFFFF), Color(0xFFF2F3F5), Color(0xBFFFFFFF), Color(0x33FFFFFF), Color(0x8CFFFFFF),
    Color(0x59F2542D), Color(0xF2FFFFFF), Color(0xE624100A), Color(0x9424100A), Color(0xB3FFFFFF)
)

private val DarkPalette = Palette(
    Color(0xFF120B09), Color(0xFF0A0706),
    listOf(Color(0x6BF2542D), Color(0x4DFF9A5C), Color(0x47D6345A)),
    Color(0x85281E1C), Color(0xFF000000), Color(0x29FFFFFF), Color(0x0DFFFFFF), Color(0x1AFFFFFF),
    Color(0x73FF7A50), Color(0xD9FFAA82), Color(0xEBFFFFFF), Color(0x9EFFFFFF), Color(0x38FFFFFF)
)

/**
 * Nibl's Living Flow splash. A frosted-glass droplet blooms over drifting light; the cookie inside is nibbled
 * three times (each bite squishes the glass and sends out a ring of light); the disc flows into a floating pill
 * that lights up "nibl" and drops a glowing dot on the i; then the pill grows into the app's background, so
 * the first screen appears from it rather than after a cut. Reduced motion shows the finished pill briefly.
 */
@Composable
fun AnimatedSplashScreen(onSplashFinished: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkPalette else LightPalette
    val time = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (reduceMotion) {
            time.snapTo(2.9f)
            delay(700)
            time.snapTo(DURATION)
        } else {
            time.animateTo(DURATION, tween((DURATION * 1000).toInt(), easing = LinearEasing))
        }
        onSplashFinished()
    }

    val measurer = rememberTextMeasurer()
    val word = TextStyle(color = c.text, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
    val tagStyle = TextStyle(color = c.text2, fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)

    Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Nibl" }) {
        val t = time.value
        val dp = density
        val cx = size.width / 2f
        val cy = size.height * 0.46f

        // ----- backdrop: warm gradient and drifting blooms of light -----
        drawRect(Brush.verticalGradient(listOf(c.bgTop, c.bgBottom)))
        val drift = (if (reduceMotion) 0f else t) / 14f
        bloom(c.blooms[0], Offset(80f * dp + 60f * dp * drift, 270f * dp + 90f * dp * drift), 210f * dp)
        bloom(c.blooms[1], Offset(size.width - 70f * dp - 70f * dp * drift, size.height * 0.55f - 60f * dp * drift), 230f * dp)
        bloom(c.blooms[2], Offset(200f * dp + 40f * dp * drift, size.height - 180f * dp - 80f * dp * drift), 200f * dp)

        // ----- the disc → pill → screen -----
        val w = kf(t, 0f to 168f, 1.5f to 168f, 1.8f to 300f, 1.92f to 284f, 2.02f to 290f, 2.12f to 288f, 3.55f to 288f, 4.0f to size.width / dp + 8f) * dp
        val h = kf(t, 0f to 168f, 1.5f to 168f, 1.8f to 90f, 1.92f to 99f, 2.02f to 95f, 2.12f to 96f, 3.55f to 96f, 4.0f to size.height / dp + 8f) * dp
        val radius = kf(t, 0f to 84f, 1.5f to 84f, 1.8f to 45f, 1.92f to 49.5f, 2.02f to 47.5f, 2.12f to 48f, 3.55f to 48f, 4.0f to 0f) * dp
        val dy = kf(t, 3.55f to 0f, 4.0f to size.height / 2f - cy)
        val pop = kf(t, 0f to 0.15f, 0.32f to 1.07f, 0.44f to 0.97f, 0.5f to 1f)
        // a squish for each bite, then a rebound
        fun squish(at: Float) = kf(t, at to 0f, at + 0.05f to 1f, at + 0.15f to -0.3f, at + 0.2f to 0f)
        val sq = squish(0.70f) + squish(0.95f) + squish(1.20f) * 1.2f
        val sx = pop * (1f + 0.05f * sq); val sy = pop * (1f - 0.05f * sq)
        val discAlpha = kf(t, 0f to 0f, 0.18f to 1f)
        val solid = kf(t, 3.55f to 0f, 4.0f to 1f)
        val glowPulse = kf(t, 2.25f to 0f, 2.5f to 1f, 2.9f to 0f)

        val rect = Rect(Offset(cx - w / 2f, cy + dy - h / 2f), Size(w, h))
        withTransform({ scale(sx, sy, Offset(cx, cy + dy)) }) {
            val shape = RoundRect(rect, CornerRadius(radius))
            val path = Path().apply { addRoundRect(shape) }
            // soft accent glow around the pill as it lights up, and a gentle shadow
            if (glowPulse > 0f) {
                drawRoundRect(c.glow.copy(alpha = c.glow.alpha * glowPulse), rect.topLeft - Offset(10f * dp, 10f * dp),
                    Size(w + 20f * dp, h + 20f * dp), CornerRadius(radius + 10f * dp))
            }
            val fill = androidx.compose.ui.graphics.lerp(c.glass, c.solid, solid)
            drawPath(path, fill.copy(alpha = fill.alpha * discAlpha))
            clipPath(path) {
                drawRect(Brush.verticalGradient(listOf(c.sheen.copy(alpha = c.sheen.alpha * discAlpha * (1f - solid)), Color.Transparent), rect.top, rect.top + h * 0.45f))
                val content = discAlpha * kf(t, 3.5f to 1f, 3.65f to 0f)
                if (content > 0f) drawPillContent(t, cx, cy + dy, dp, c, content, measurer, word)
            }
            if (solid < 1f) {
                drawPath(path, Brush.verticalGradient(listOf(c.edgeTop, c.edgeBottom), rect.top, rect.bottom),
                    alpha = discAlpha * (1f - solid), style = Stroke(1f * dp))
            }
        }

        // ----- tagline -----
        val tag = kf(t, 2.45f to 0f, 2.75f to 1f, 3.5f to 1f, 3.65f to 0f)
        if (tag > 0f) {
            val layout = measurer.measure("bite-sized food logging", tagStyle)
            drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy + 70f * dp + (1f - tag) * 10f * dp), alpha = tag)
        }
    }
}

private fun DrawScope.bloom(color: Color, center: Offset, radius: Float) {
    drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center, radius), radius, center)
}

/** The cookie, its bites and their rings of light, "nibl" with its glowing i-dot, and the light sweep. */
private fun DrawScope.drawPillContent(
    t: Float, cx: Float, cy: Float, dp: Float, c: Palette, alpha: Float,
    measurer: androidx.compose.ui.text.TextMeasurer, word: TextStyle
) {
    // Cookie: 112 dp across at first, sliding left and shrinking into the pill
    val shift = kf(t, 1.5f to 0f, 1.92f to -100f, 2.02f to -95f, 2.12f to -96f) * dp
    val scale = kf(t, 1.5f to 1f, 1.92f to 0.48f, 2.02f to 0.51f, 2.12f to 0.5f)
    val unit = 1.12f * dp * scale
    val ox = cx + shift; val oy = cy
    fun at(x: Float, y: Float) = Offset(ox + (x - 50f) * unit, oy + (y - 50f) * unit)

    val cookie = Path().apply { addOval(Rect(at(50f, 50f), 42f * unit)) }
    val bites = Path()
    listOf(Triple(80f, 19f, 0.70f), Triple(92f, 38f, 0.95f), Triple(95f, 60f, 1.20f)).forEach { (x, y, start) ->
        val b = kf(t, start to 0f, start + 0.1f to 1f)
        if (b > 0f) bites.addOval(Rect(at(x, y), 13f * unit * b))
    }
    val bitten = Path().apply { op(cookie, bites, PathOperation.Difference) }
    drawPath(bitten, Brush.linearGradient(listOf(Color(0xFFFF8A5B), Color(0xFFE8481F)), at(8f, 8f), at(92f, 92f)), alpha = alpha)
    clipPath(bitten) {
        listOf(
            Triple(34f, 36f, 5f), Triple(52f, 26f, 4f), Triple(28f, 60f, 4.5f), Triple(54f, 54f, 5.2f),
            Triple(72f, 72f, 4f), Triple(46f, 76f, 4.6f), Triple(74f, 46f, 3.6f)
        ).forEach { (x, y, r) ->
            drawOval(Color(0xFFFFF4E6), at(x - r, y - r * 0.85f), Size(r * 2 * unit, r * 1.7f * unit), alpha = alpha)
        }
    }
    // A ring of light where each bite was taken
    listOf(Triple(80f, 19f, 0.70f), Triple(92f, 38f, 0.95f), Triple(95f, 60f, 1.20f)).forEach { (x, y, start) ->
        val p = kf(t, start to 0f, start + 0.32f to 1f)
        val a = kf(t, start to 0f, start + 0.04f to 0.9f, start + 0.32f to 0f)
        if (a > 0f) {
            val r = 20f * dp * (0.3f + 1.9f * p)
            drawCircle(c.ripple.copy(alpha = c.ripple.alpha * a * alpha), r, at(x, y), style = Stroke(2f * dp))
        }
    }

    // "nibl", revealed left to right, with a dotless ı so its dot can drop in
    val reveal = kf(t, 1.86f to 0f, 2.15f to 1f)
    if (reveal > 0f) {
        val left = cx - 40f * dp + (1f - reveal) * 10f * dp
        val n = measurer.measure("n", word); val i = measurer.measure("ı", word); val bl = measurer.measure("bl", word)
        val gap = -1f * dp
        val top = cy - n.size.height / 2f
        clipRect(left = left - 2f * dp, right = left + (n.size.width + i.size.width + bl.size.width) * reveal + 2f * dp) {
            drawText(n, topLeft = Offset(left, top), alpha = alpha)
            drawText(i, topLeft = Offset(left + n.size.width + gap, top), alpha = alpha)
            drawText(bl, topLeft = Offset(left + n.size.width + i.size.width + gap * 2, top), alpha = alpha)
        }
        // the i-dot drops, squashes and settles, glowing tomato
        val fall = kf(t, 2.25f to -64f, 2.4f to 0f, 2.5f to -6f, 2.58f to 0f) * dp
        val dotA = kf(t, 2.25f to 0f, 2.28f to 1f)
        if (dotA > 0f) {
            val squash = kf(t, 2.38f to 1f, 2.4f to 0.65f, 2.5f to 1.1f, 2.58f to 1f)
            val fontPx = 46f * dp * fontScale
            val r = fontPx * 0.105f
            val dc = Offset(left + n.size.width + gap + i.size.width / 2f, top + i.firstBaseline - fontPx * 0.53f - fontPx * 0.15f + fall)
            drawCircle(Brush.radialGradient(listOf(BrandTomato.copy(alpha = 0.55f * dotA), Color.Transparent), dc, r * 2.6f), r * 2.6f, dc)
            withTransform({ scale(2f - squash, squash, dc) }) { drawCircle(BrandTomato.copy(alpha = dotA * alpha), r, dc) }
        }
    }

    // A band of light passes over the pill once the word is in
    val sweep = kf(t, 2.2f to 0f, 2.6f to 1f)
    if (sweep > 0f && sweep < 1f) {
        val x = cx - 220f * dp + 430f * dp * sweep
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, c.sweep, Color.Transparent), x - 35f * dp, x + 35f * dp),
            Offset(x - 35f * dp, cy - 80f * dp), Size(70f * dp, 160f * dp), alpha = alpha
        )
    }
}
