package com.bytelog.app.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteTomato
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AnimatedSplashScreen(
    onSplashFinished: () -> Unit
) {
    // Animation drivers based on Splash.dc.html
    val cookieScale = remember { Animatable(0f) }
    val bite1Scale = remember { Animatable(0f) }
    val bite2Scale = remember { Animatable(0f) }
    val bite3Scale = remember { Animatable(0f) }

    val line1Alpha = remember { Animatable(0f) }
    val line1OffsetX = remember { Animatable(34f) }

    val line2Alpha = remember { Animatable(0f) }
    val line2OffsetX = remember { Animatable(44f) }

    val line3Alpha = remember { Animatable(0f) }
    val line3OffsetX = remember { Animatable(34f) }

    val brandAlpha = remember { Animatable(0f) }
    val brandOffsetY = remember { Animatable(16f) }
    val overallAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        // 1. Pop cookie circle with slight overshoot spring
        launch {
            cookieScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
            )
        }

        // 2. Sequential bite animations
        delay(420)
        launch {
            bite1Scale.animateTo(1f, animationSpec = tween(durationMillis = 140))
        }
        delay(120)
        launch {
            bite2Scale.animateTo(1f, animationSpec = tween(durationMillis = 140))
        }
        delay(120)
        launch {
            bite3Scale.animateTo(1f, animationSpec = tween(durationMillis = 140))
        }

        // 3. Trailing speed lines shoot out to the left
        delay(100)
        launch {
            line2Alpha.animateTo(1f, tween(180))
            line2OffsetX.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
        }
        launch {
            line1Alpha.animateTo(0.7f, tween(200))
            line1OffsetX.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
        }
        launch {
            line3Alpha.animateTo(0.7f, tween(200))
            line3OffsetX.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
        }

        // 4. Brand text slides up
        delay(150)
        launch {
            brandAlpha.animateTo(1f, tween(300))
            brandOffsetY.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
        }

        // Hold display and fade out smoothly into main app
        delay(1100)
        overallAlpha.animateTo(0f, tween(250, easing = LinearEasing))
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ByteTomato)
            .graphicsLayer { alpha = overallAlpha.value },
        contentAlignment = Alignment.Center
    ) {
        // Main Logo Canvas
        Canvas(
            modifier = Modifier.size(200.dp)
        ) {
            val canvasW = size.width
            val scale = canvasW / 312f

            fun tx(x: Float) = (x - 100f) * scale
            fun ty(y: Float) = (y - 100f) * scale
            fun tr(r: Float) = r * scale

            val cX = tx(272f)
            val cY = ty(256f)
            val cR = tr(110f) * cookieScale.value

            if (cR > 0f) {
                val cookiePath = Path().apply {
                    addOval(Rect(cX - cR, cY - cR, cX + cR, cY + cR))
                }

                val bitesPath = Path()
                // Bite 1
                if (bite1Scale.value > 0f) {
                    val b1X = tx(331f)
                    val b1Y = ty(154f)
                    val b1R = tr(30f) * bite1Scale.value
                    bitesPath.addOval(Rect(b1X - b1R, b1Y - b1R, b1X + b1R, b1Y + b1R))
                }
                // Bite 2
                if (bite2Scale.value > 0f) {
                    val b2X = tx(369f)
                    val b2Y = ty(188f)
                    val b2R = tr(30f) * bite2Scale.value
                    bitesPath.addOval(Rect(b2X - b2R, b2Y - b2R, b2X + b2R, b2Y + b2R))
                }
                // Bite 3
                if (bite3Scale.value > 0f) {
                    val b3X = tx(388f)
                    val b3Y = ty(236f)
                    val b3R = tr(30f) * bite3Scale.value
                    bitesPath.addOval(Rect(b3X - b3R, b3Y - b3R, b3X + b3R, b3Y + b3R))
                }

                val finalCookiePath = Path().apply {
                    op(cookiePath, bitesPath, PathOperation.Difference)
                }

                drawPath(path = finalCookiePath, color = ByteCream)
            }

            // Speed lines on left
            val sw = tr(18f)

            // Line 1 (top)
            if (line1Alpha.value > 0f) {
                val x1 = tx(126f) + line1OffsetX.value * scale
                val x2 = tx(146f) + line1OffsetX.value * scale
                val y = ty(214f)
                drawLine(
                    color = ByteCream.copy(alpha = line1Alpha.value),
                    start = Offset(x1, y),
                    end = Offset(x2, y),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
            }

            // Line 2 (center)
            if (line2Alpha.value > 0f) {
                val x1 = tx(112f) + line2OffsetX.value * scale
                val x2 = tx(146f) + line2OffsetX.value * scale
                val y = ty(256f)
                drawLine(
                    color = ByteCream.copy(alpha = line2Alpha.value),
                    start = Offset(x1, y),
                    end = Offset(x2, y),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
            }

            // Line 3 (bottom)
            if (line3Alpha.value > 0f) {
                val x1 = tx(126f) + line3OffsetX.value * scale
                val x2 = tx(146f) + line3OffsetX.value * scale
                val y = ty(298f)
                drawLine(
                    color = ByteCream.copy(alpha = line3Alpha.value),
                    start = Offset(x1, y),
                    end = Offset(x2, y),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
            }
        }

        // Brand typography anchored near bottom
        Text(
            text = "bytelog",
            color = ByteCream,
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-68).dp + brandOffsetY.value.dp)
                .graphicsLayer { alpha = brandAlpha.value }
        )
    }
}
