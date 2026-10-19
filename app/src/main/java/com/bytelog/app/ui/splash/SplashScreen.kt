package com.bytelog.app.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.bytelog.app.ui.theme.BrandCream
import com.bytelog.app.ui.theme.BrandTomato
import com.bytelog.app.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Where each bite is taken, as an angle on the cookie's edge (degrees, 0 = right, negative = up). */
private val BITE_ANGLES = floatArrayOf(-58f, -28f, 2f)

/** Chocolate chips, in cookie radii from its centre, with their size. */
private val CHIPS = listOf(
    Triple(-0.38f, -0.30f, 0.11f), Triple(0.02f, -0.50f, 0.09f), Triple(-0.52f, 0.22f, 0.10f),
    Triple(0.12f, 0.08f, 0.12f), Triple(0.46f, 0.42f, 0.09f), Triple(-0.10f, 0.56f, 0.11f), Triple(0.58f, -0.10f, 0.08f)
)

/**
 * Nibl's splash: a cookie pops in and gets nibbled three times (each bite squishes it and throws crumbs),
 * "nibl" rises letter by letter, and the last crumb arcs over to land as the dot of the i. Then a tagline,
 * and it fades into the app. With reduced motion it shows the finished picture briefly instead.
 */
@Composable
fun AnimatedSplashScreen(
    onSplashFinished: () -> Unit
) {
    val reduceMotion = LocalReduceMotion.current
    val cookie = remember { Animatable(0f) }
    val squish = remember { Animatable(1f) }
    val bites = remember { List(3) { Animatable(0f) } }
    val crumbs = remember { List(3) { Animatable(0f) } }
    val letters = remember { List(4) { Animatable(0f) } }
    val dotFlight = remember { Animatable(0f) }
    val dotSquash = remember { Animatable(1f) }
    val tagline = remember { Animatable(0f) }
    val overall = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        if (reduceMotion) {
            (listOf(cookie, dotFlight, tagline) + bites + letters).forEach { it.snapTo(1f) }
            delay(700)
            overall.animateTo(0f, tween(200))
            onSplashFinished()
            return@LaunchedEffect
        }
        // 1. The cookie pops in with a little overshoot
        launch { cookie.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f)) }
        delay(380)

        // 2. Three nibbles: the bite appears, the cookie squishes, crumbs fly
        for (i in 0 until 3) {
            launch { bites[i].animateTo(1f, tween(110, easing = FastOutSlowInEasing)) }
            launch {
                squish.animateTo(0.93f, tween(60))
                squish.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 900f))
            }
            launch { crumbs[i].animateTo(1f, tween(560, easing = LinearEasing)) }
            delay(170)
        }

        // 3. "nibl" rises letter by letter
        delay(40)
        letters.forEachIndexed { i, a ->
            launch {
                delay(i * 65L)
                a.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 520f))
            }
        }

        // 4. A crumb arcs over and lands as the dot of the i
        delay(260)
        dotFlight.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        launch {
            dotSquash.snapTo(0.62f)
            dotSquash.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 700f))
        }

        // 5. Tagline, a moment to read it, then into the app
        delay(80)
        tagline.animateTo(1f, tween(260))
        delay(650)
        overall.animateTo(0f, tween(240, easing = LinearEasing))
        onSplashFinished()
    }

    val measurer = rememberTextMeasurer()
    val wordStyle = TextStyle(color = BrandCream, fontSize = 68.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp)
    val tagStyle = TextStyle(color = BrandCream, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
    val chipColor = lerp(BrandTomato, Color(0xFF3B1408), 0.42f)

    Box(
        Modifier
            .fillMaxSize()
            .background(BrandTomato)
            .graphicsLayer { alpha = overall.value }
            .semantics { contentDescription = "Nibl" }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val u = size.minDimension / 400f          // layout unit: the design is drawn on a 400-wide grid
            val cx = size.width / 2f
            val cy = size.height * 0.40f
            val r = 92f * u

            // Soft glow behind the cookie
            drawCircle(
                Brush.radialGradient(listOf(BrandCream.copy(alpha = 0.18f), Color.Transparent), Offset(cx, cy), r * 2.3f),
                radius = r * 2.3f, center = Offset(cx, cy)
            )

            fun biteCenter(i: Int): Offset {
                val a = BITE_ANGLES[i] * PI.toFloat() / 180f
                return Offset(cx + cos(a) * r * 1.02f, cy + sin(a) * r * 1.02f)
            }
            val biteR = r * 0.33f

            // ----- the cookie, nibbled -----
            val s = cookie.value * squish.value
            if (s > 0f) {
                val disc = Path().apply { addOval(Rect(Offset(cx, cy), r)) }
                val bitten = Path().apply {
                    bites.forEachIndexed { i, b ->
                        if (b.value > 0f) addOval(Rect(biteCenter(i), biteR * b.value))
                    }
                }
                val shape = Path().apply { op(disc, bitten, PathOperation.Difference) }
                scale(s, Offset(cx, cy)) {
                    drawPath(shape, BrandCream)
                    clipPath(shape) {
                        CHIPS.forEach { (dx, dy, cr) ->
                            drawOval(chipColor, Offset(cx + dx * r - cr * r, cy + dy * r - cr * r * 0.85f),
                                androidx.compose.ui.geometry.Size(cr * r * 2f, cr * r * 1.7f))
                        }
                    }
                }
            }

            // ----- crumbs thrown by each bite -----
            crumbs.forEachIndexed { i, c ->
                val t = c.value
                if (t <= 0f || t >= 1f) return@forEachIndexed
                val origin = biteCenter(i)
                val out = BITE_ANGLES[i] * PI.toFloat() / 180f
                for (k in 0 until 4) {
                    val ang = out + (k - 1.5f) * 0.42f
                    val speed = (150f + k * 28f) * u
                    val x = origin.x + cos(ang) * speed * t
                    val y = origin.y + sin(ang) * speed * t + 260f * u * t * t
                    drawCircle(BrandCream.copy(alpha = (1f - t).coerceIn(0f, 1f)), radius = (4.5f - k * 0.6f) * u, center = Offset(x, y))
                }
            }

            // ----- "nibl": dotless ı, so the dot can be a crumb -----
            val glyphs = listOf("n", "ı", "b", "l").map { measurer.measure(it, wordStyle) }
            val gap = -3f * u
            val wordW = glyphs.sumOf { it.size.width.toDouble() }.toFloat() + gap * (glyphs.size - 1)
            val wordTop = cy + r + 70f * u
            var x = cx - wordW / 2f
            val starts = FloatArray(glyphs.size)
            glyphs.forEachIndexed { i, g ->
                starts[i] = x
                val p = letters[i].value
                if (p > 0f) {
                    drawText(g, topLeft = Offset(x, wordTop + (1f - p) * 26f * u), alpha = p.coerceIn(0f, 1f))
                }
                x += g.size.width + gap
            }

            // The dot of the i: flies from the last bite, lands with a squash
            val iGlyph = glyphs[1]
            val fontPx = 68.sp.toPx()
            val dotR = fontPx * 0.085f
            val target = Offset(
                starts[1] + iGlyph.size.width / 2f,
                wordTop + iGlyph.firstBaseline - fontPx * 0.53f - fontPx * 0.2f
            )
            val f = dotFlight.value
            if (f > 0f) {
                val from = biteCenter(2)
                val ctrl = Offset((from.x + target.x) / 2f + 40f * u, minOf(from.y, target.y) - 150f * u)
                val p = quad(from, ctrl, target, f)
                val sq = dotSquash.value
                withTransform({ scale(2f - sq, sq, p) }) { drawCircle(BrandCream, radius = dotR, center = p) }
            }

            // ----- tagline -----
            if (tagline.value > 0f) {
                val tag = measurer.measure("bite-sized food logging", tagStyle)
                drawText(
                    tag,
                    topLeft = Offset(cx - tag.size.width / 2f, wordTop + glyphs[0].size.height + 6f * u + (1f - tagline.value) * 10f * u),
                    alpha = tagline.value * 0.85f
                )
            }
        }
    }
}

private fun quad(a: Offset, c: Offset, b: Offset, t: Float): Offset {
    val m = 1f - t
    return Offset(m * m * a.x + 2 * m * t * c.x + t * t * b.x, m * m * a.y + 2 * m * t * c.y + t * t * b.y)
}
