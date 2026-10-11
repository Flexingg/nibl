package com.bytelog.app.ui.diary

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.bytelog.app.data.local.model.DailySummary
import com.bytelog.app.ui.components.FoldingCard
import com.bytelog.app.ui.components.pressBounce
import com.bytelog.app.ui.theme.LocalByteExtraColors
import com.bytelog.app.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Expressive spring tokens
private const val FAST_DAMPING = 0.6f
private const val FAST_STIFFNESS = 800f

private fun <T> fastSpatial() = spring<T>(dampingRatio = FAST_DAMPING, stiffness = FAST_STIFFNESS)
private fun <T> spatial() = spring<T>(dampingRatio = 0.8f, stiffness = 380f)

// ======================================================================
// Header: calories (with a shape that flies between the card and the strip), water, weight
// ======================================================================

private const val HERO_DP = 156

/**
 * The Diary header. Like the previous folding header, but the calorie shape is its own layer that shrinks and
 * flies from the big card into the narrow strip while the cards fold.
 */
@Composable
fun ExpressiveDiaryHeader(
    fraction: () -> Float,
    hero: @Composable () -> Unit,
    heroExpanded: @Composable () -> Unit,
    heroCompact: @Composable () -> Unit,
    waterExpanded: @Composable () -> Unit,
    waterCompact: @Composable () -> Unit,
    weightExpanded: @Composable () -> Unit,
    weightCompact: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Layout(
        modifier = modifier,
        content = {
            FoldingCard(fraction, 32, 22, Modifier.layoutId(0), heroExpanded, heroCompact)
            FoldingCard(fraction, 28, 27, Modifier.layoutId(1), waterExpanded, waterCompact)
            FoldingCard(fraction, 28, 27, Modifier.layoutId(2), weightExpanded, weightCompact)
            Box(Modifier.layoutId(3).zIndex(1f)) { hero() }
        }
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val gapE = 10.dp.roundToPx(); val gapC = 8.dp.roundToPx()
        val heroC = 64.dp.roundToPx(); val rowC = 54.dp.roundToPx()
        val heroPx = HERO_DP.dp.roundToPx()
        val byId = measurables.associateBy { it.layoutId as Int }
        val e = (0..2).map { byId.getValue(it).maxIntrinsicHeight(w) }
        val f = fraction().coerceIn(-0.07f, 1.07f)
        val half = (w - gapC) / 2
        fun fixed(i: Int, cw: Float, ch: Float) = byId.getValue(i).measure(Constraints.fixed(cw.roundToInt().coerceAtLeast(0), ch.roundToInt().coerceAtLeast(0)))
        val heroCard = fixed(0, w.toFloat(), lerpF(e[0].toFloat(), heroC.toFloat(), f))
        val water = fixed(1, lerpF(w.toFloat(), half.toFloat(), f), lerpF(e[1].toFloat(), rowC.toFloat(), f))
        val weight = fixed(2, lerpF(w.toFloat(), half.toFloat(), f), lerpF(e[2].toFloat(), rowC.toFloat(), f))
        val heroShape = byId.getValue(3).measure(Constraints.fixed(heroPx, heroPx))

        val expandedTotal = e[0] + gapE + e[1] + gapE + e[2]
        val collapsedTotal = heroC + gapC + rowC
        val height = lerpF(expandedTotal.toFloat(), collapsedTotal.toFloat(), f).roundToInt().coerceAtLeast(0)
        val rowTop = heroC + gapC
        // Shape: centred vertically on the left of the big card, then 44 dp inside the strip.
        val small = 44.dp.toPx()
        val scale = lerpF(1f, small / heroPx, clamp01(f))
        val hx = lerpF(16.dp.toPx(), 10.dp.toPx(), f)
        val hy = lerpF((e[0] - heroPx) / 2f, (heroC - small) / 2f, f)
        layout(w, height) {
            heroCard.place(0, 0)
            water.place(0, lerpF((e[0] + gapE).toFloat(), rowTop.toFloat(), f).roundToInt())
            weight.place(lerpF(0f, (half + gapC).toFloat(), f).roundToInt(), lerpF((e[0] + gapE + e[1] + gapE).toFloat(), rowTop.toFloat(), f).roundToInt())
            // Sub-pixel translation in the layer: rounding the position to whole pixels while the scale changes
            // smoothly made the shape stutter as it moved.
            heroShape.placeWithLayer(0, 0, zIndex = 1f) {
                translationX = hx; translationY = hy
                scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

/** Calories eaten so far (counting up to the goal) inside a morphing shape, with a wavy ring for the day's progress. */
@Composable
fun CalorieHero(eaten: Int, goal: Int, progress: Float, shape: BlobShape, fraction: () -> Float, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val morph = rememberMorphState(shape, spinDegrees = 60f)
    val phase = rememberWavePhase()
    val animatedProgress by animateFloatAsState(progress, spring(dampingRatio = 0.8f, stiffness = 200f), label = "hero_progress")
    val kick = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val reduce = LocalReduceMotion.current
    Box(
        Modifier
            .size(HERO_DP.dp)
            .graphicsLayer { scaleX = kick.value; scaleY = kick.value }
            .clip(CircleShape)
            .clickable {
                if (!reduce) scope.launch { kick.snapTo(0.88f); kick.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f)) }
                onClick()
            }
            .semantics { contentDescription = "$eaten of $goal calories eaten" },
        contentAlignment = Alignment.Center
    ) {
        val primary = cs.primary; val track = cs.surfaceContainerHighest; val blob = cs.primaryContainer
        Canvas(Modifier.fillMaxSize()) {
            val f = clamp01(fraction())
            val ringAlpha = clamp01(1f - f * 1.8f)
            val r = size.minDimension / 2
            if (ringAlpha > 0f) {
                drawWavyRing(animatedProgress, phase.value, primary.copy(alpha = ringAlpha), track.copy(alpha = ringAlpha),
                    ringRadius = r * 0.9f, stroke = 7.dp.toPx() * 156f / HERO_DP, amplitude = 2.6.dp.toPx())
            }
            // Folded, the shape becomes a glass filled to how far the day is towards the calorie goal.
            val glass = clamp01((f - 0.3f) / 0.45f)
            drawFilledBlob(
                morph.current(), morph.rotation.value, r * lerpF(0.68f, 0.86f, f),
                level = animatedProgress * glass + (1f - glass), phase = phase.value,
                fillColor = androidx.compose.ui.graphics.lerp(blob, primary, glass),
                emptyColor = androidx.compose.ui.graphics.lerp(blob, track, glass)
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { alpha = clamp01(1f - clamp01(fraction()) * 2.6f) }
        ) {
            val shown = rememberCountUp(eaten)
            Text(String.format(Locale.getDefault(), "%,d", shown), color = cs.onPrimaryContainer, fontSize = 36.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(
                String.format(Locale.getDefault(), "OF %,d KCAL", goal),
                color = cs.onPrimaryContainer.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, maxLines = 1
            )
        }
    }
}

/** [target] as shown on screen, rolling up (or down) to each new value instead of jumping. */
@Composable
fun rememberCountUp(target: Int): Int {
    val reduce = LocalReduceMotion.current
    val anim = remember { Animatable(target.toFloat()) }
    LaunchedEffect(target, reduce) {
        if (reduce) anim.snapTo(target.toFloat())
        else anim.animateTo(target.toFloat(), tween(durationMillis = 700, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    return anim.value.roundToInt()
}

/** Full calorie card: space for the shape on the left, macros with wavy bars on the right. */
@Composable
fun HeroMacros(summary: DailySummary) {
    val x = LocalByteExtraColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = (HERO_DP + 32).dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width((HERO_DP + 14).dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MacroRow("PROTEIN", summary.totalProtein, summary.targetProtein, x.protein)
            MacroRow("CARBS", summary.totalCarbs, summary.targetCarbs, x.carbs)
            MacroRow("FAT", summary.totalFat, summary.targetFat, x.fat)
        }
    }
}

@Composable
private fun MacroRow(label: String, value: Float, target: Float, color: Color) {
    val cs = MaterialTheme.colorScheme
    val p by animateFloatAsState(if (target > 0) value / target else 0f, spring(dampingRatio = 0.8f, stiffness = 200f), label = "macro_$label")
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, color = cs.onSurfaceVariant, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
            Text("/ ${target.roundToInt()}", color = cs.onSurfaceVariant, fontSize = 11.sp)
        }
        Text("${value.roundToInt()} g", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        WavyLinearProgress(p, color, Modifier.fillMaxWidth(), amplitude = 2.2.dp, wavelength = 16.dp)
    }
}

/** Narrow calorie strip (the flying shape sits in its first 64 dp). */
@Composable
fun HeroStrip(summary: DailySummary) {
    val cs = MaterialTheme.colorScheme
    val x = LocalByteExtraColors.current
    Row(Modifier.fillMaxSize().padding(start = 64.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(String.format(Locale.getDefault(), "%,d", rememberCountUp(summary.totalCalories.roundToInt())), color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1)
                Text(
                    String.format(Locale.getDefault(), " / %,d", summary.targetCalories.roundToInt()),
                    color = cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            Text("KCAL EATEN", color = cs.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallRing("P", summary.proteinProgress, x.protein)
            SmallRing("C", summary.carbsProgress, x.carbs)
            SmallRing("F", summary.fatProgress, x.fat)
        }
    }
}

@Composable
private fun SmallRing(letter: String, progress: Float, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val p by animateFloatAsState(progress, spring(dampingRatio = 0.8f, stiffness = 200f), label = "ring_$letter")
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 3.4.dp.toPx(); val inset = sw / 2
            val arc = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(sw))
            drawArc(color, -90f, 360f * clamp01(p), false, Offset(inset, inset), arc, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text(letter, color = color, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
    }
}

// ----- water -----

@Composable
private fun WaterBadge(oz: Float, size: androidx.compose.ui.unit.Dp) {
    val x = LocalByteExtraColors.current
    var shape by remember { mutableStateOf(BlobShape.CIRCLE) }
    var last by remember { mutableFloatStateOf(oz) }
    LaunchedEffect(oz) {
        if (oz != last) {
            shape = if (oz > last) BlobShape.SUNNY12 else BlobShape.CLOVER4
            last = oz
            kotlinx.coroutines.delay(420)
            shape = BlobShape.CIRCLE
        }
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        MorphingBlob(shape, x.waterContainer, Modifier.fillMaxSize(), spinDegrees = 30f, fill = 0.95f)
        Icon(Icons.Default.WaterDrop, contentDescription = null, tint = x.water, modifier = Modifier.size(size * 0.45f))
    }
}

/** Full water card with a connected button group. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WaterExpanded(
    currentOz: Float, targetOz: Float, defaultOz: Float, containerName: String,
    onLogDefault: () -> Unit, onLogDouble: () -> Unit, onCustom: () -> Unit, onUndo: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val x = LocalByteExtraColors.current
    val haptic = LocalHapticFeedback.current
    val progress = if (targetOz > 0) currentOz / targetOz else 0f
    val p by animateFloatAsState(progress, spatial(), label = "water_bar")
    Column(Modifier.fillMaxWidth().clickable(onClick = onCustom).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WaterBadge(currentOz, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("HYDRATION", color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Text("${currentOz.roundToInt()} / ${targetOz.roundToInt()} oz", color = cs.onSurface, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text("${(clamp01(progress) * 100).roundToInt()}%", color = x.water, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        }
        WavyLinearProgress(p, x.water, Modifier.fillMaxWidth(), wavelength = 20.dp)
        ConnectedButtonGroup(
            listOf(
                GroupButton(
                    label = "+${defaultOz.roundToInt()} oz", detail = containerName, icon = Icons.Default.LocalDrink, weight = 1.45f,
                    container = x.waterContainer, content = x.onWaterContainer,
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onLogDefault() },
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLogDouble() }
                ),
                GroupButton(label = "Custom", weight = 1f, container = cs.surfaceContainerHighest, content = cs.onSurface, onClick = onCustom),
                GroupButton(icon = Icons.Default.Remove, description = "Undo last drink", weight = 0.55f, container = cs.surfaceContainerHighest, content = cs.onSurface,
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onUndo() })
            )
        )
    }
}

/** Narrow water pill. */
@Composable
fun WaterPill(currentOz: Float, targetOz: Float, defaultOz: Float, onLogDefault: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val x = LocalByteExtraColors.current
    val p by animateFloatAsState(if (targetOz > 0) currentOz / targetOz else 0f, spatial(), label = "water_pill")
    Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WaterBadge(currentOz, 34.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${currentOz.roundToInt()}", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1)
                Text("/${targetOz.roundToInt()} oz", color = cs.onSurfaceVariant, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            WavyLinearProgress(p, x.water, Modifier.fillMaxWidth(), height = 8.dp, amplitude = 1.6.dp, wavelength = 12.dp, stroke = 3.dp)
        }
        MorphButton(onLogDefault, Modifier.size(38.dp), x.waterContainer, description = "Log ${defaultOz.roundToInt()} oz") {
            Text("+${defaultOz.roundToInt()}", color = x.onWaterContainer, fontSize = 11.5.sp, fontWeight = FontWeight.Black)
        }
    }
}

// ----- weight -----

@Composable
private fun WeightBadge(size: androidx.compose.ui.unit.Dp, shape: BlobShape) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        MorphingBlob(shape, cs.tertiaryContainer, Modifier.fillMaxSize(), spinDegrees = 90f, fill = 0.95f)
        Icon(Icons.Default.MonitorWeight, contentDescription = null, tint = cs.onTertiaryContainer, modifier = Modifier.size(size * 0.45f))
    }
}

/** Full weight card: today's weight, change and a sparkline of recent weigh-ins. */
@Composable
fun WeightExpanded(weight: Float?, unit: String, delta: Float?, recent: List<Float>, shape: BlobShape, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        WeightBadge(40.dp, shape)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("BODYWEIGHT", color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            if (weight != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(String.format(Locale.US, "%.1f", weight), color = cs.onSurface, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text(" $unit", color = cs.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
                }
            } else {
                Text("Tap to log today", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            if (delta != null) DeltaChip(delta)
        }
        Spacer(Modifier.weight(1f))
        if (recent.size >= 2) Sparkline(recent, Modifier.width(96.dp).height(44.dp))
    }
}

@Composable
private fun DeltaChip(delta: Float) {
    val x = LocalByteExtraColors.current
    val color = if (delta < 0) x.protein else MaterialTheme.colorScheme.primary
    Text(
        (if (delta < 0) "↓" else "↑") + String.format(Locale.US, "%.1f", abs(delta)),
        color = color, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun Sparkline(points: List<Float>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        val lo = points.min(); val hi = points.max(); val span = (hi - lo).takeIf { it > 0.01f } ?: 1f
        val pad = 6.dp.toPx()
        val stepX = (size.width - pad * 2) / (points.size - 1)
        val pts = points.indices.map { i -> Offset(pad + i * stepX, size.height - pad - (points[i] - lo) / span * (size.height - pad * 2)) }
        // Smooth curve through the points (Catmull-Rom as cubic Béziers), so the line reads soft and round.
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 0 until pts.lastIndex) {
                val p0 = pts[maxOf(0, i - 1)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[minOf(pts.lastIndex, i + 2)]
                cubicTo(
                    p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                    p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                    p2.x, p2.y
                )
            }
        }
        val area = androidx.compose.ui.graphics.Path().apply {
            addPath(path); lineTo(pts.last().x, size.height); lineTo(pts.first().x, size.height); close()
        }
        drawPath(area, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f))))
        drawPath(path, color, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(color, 5.dp.toPx(), pts.last())
        drawCircle(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f), 2.dp.toPx(), pts.last())
    }
}

/** Narrow weight pill. */
@Composable
fun WeightPill(weight: Float?, unit: String, delta: Float?, shape: BlobShape) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WeightBadge(34.dp, shape)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Text(weight?.let { String.format(Locale.US, "%.1f", it) } ?: "—", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(" $unit", color = cs.onSurfaceVariant, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (delta != null) DeltaChip(delta)
    }
}

// ======================================================================
// Buttons
// ======================================================================

data class GroupButton(
    val label: String? = null,
    val detail: String? = null,
    val icon: ImageVector? = null,
    val description: String? = null,
    val weight: Float = 1f,
    val container: Color,
    val content: Color,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null
)

/**
 * Connected button group: outer corners round, inner corners tight. The pressed button grows and its corners
 * round off while its neighbours make room, on the fast spatial spring.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectedButtonGroup(buttons: List<GroupButton>, modifier: Modifier = Modifier) {
    var pressedIndex by remember { mutableIntStateOf(-1) }
    val reduce = LocalReduceMotion.current
    Row(modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        buttons.forEachIndexed { i, b ->
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            LaunchedEffect(pressed) { pressedIndex = if (pressed) i else if (pressedIndex == i) -1 else pressedIndex }
            val grow = when {
                pressedIndex == i -> 1.3f
                pressedIndex >= 0 -> 0.85f
                else -> 1f
            }
            val weight by animateFloatAsState(b.weight * grow, if (reduce) spring(stiffness = 10_000f) else fastSpatial(), label = "group_w")
            val outer = 23.dp; val inner = 8.dp
            val start by animateDpAsState(if (i == 0 || pressed) outer else inner, fastSpatial(), label = "group_s")
            val end by animateDpAsState(if (i == buttons.lastIndex || pressed) outer else inner, fastSpatial(), label = "group_e")
            Row(
                Modifier
                    .weight(weight)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end))
                    .background(b.container)
                    .combinedClickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = b.onClick, onLongClick = b.onLongClick)
                    .semantics { if (b.description != null) contentDescription = b.description },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (b.icon != null) Icon(b.icon, contentDescription = null, tint = b.content, modifier = Modifier.size(17.dp))
                if (b.icon != null && b.label != null) Spacer(Modifier.width(6.dp))
                if (b.label != null) Text(b.label, color = b.content, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (b.detail != null) {
                    Spacer(Modifier.width(5.dp))
                    Text(b.detail, color = b.content.copy(alpha = 0.75f), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Round button whose corners tighten while pressed. */
@Composable
fun MorphButton(onClick: () -> Unit, modifier: Modifier, container: Color, description: String? = null, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 12.dp else 100.dp, fastSpatial(), label = "morph_btn")
    Box(
        modifier
            .pressBounce(0.92f)
            .clip(RoundedCornerShape(corner))
            .background(container)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick)
            .semantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center
    ) { content() }
}

// ======================================================================
// Top bar, toolbar, FAB menu
// ======================================================================

/** Large flexible top bar: a big day title that shrinks as the header folds. */
@Composable
fun ExpressiveTopBar(
    fraction: () -> Float,
    date: String,
    title: String,
    subtitle: String,
    syncing: Boolean,
    onSync: () -> Unit,
    onChat: () -> Unit,
    onSettings: () -> Unit,
    onFamily: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    Layout(
        content = {
            Row(Modifier.layoutId("actions"), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onSync, modifier = Modifier.pressBounce(0.85f)) {
                    if (syncing) MorphingLoadingIndicator(indicatorSize = 30.dp)
                    else Icon(Icons.Default.Sync, contentDescription = "Sync with Sparky", tint = cs.onSurfaceVariant)
                }
                if (onFamily != null) {
                    IconButton(onClick = onFamily, modifier = Modifier.pressBounce(0.85f)) {
                        Icon(Icons.Default.Groups, contentDescription = "Family", tint = cs.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onChat, modifier = Modifier.pressBounce(0.85f)) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "Sparky AI Coach", tint = cs.primary)
                }
                IconButton(onClick = onSettings, modifier = Modifier.pressBounce(0.85f)) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = cs.onSurfaceVariant)
                }
            }
            Column(Modifier.layoutId("titles")) {
                AnimatedContent(
                    targetState = date to title,
                    transitionSpec = {
                        if (reduce) EnterTransition.None togetherWith ExitTransition.None
                        else {
                            val dir = if (targetState.first > initialState.first) 1 else -1
                            val s = spring(dampingRatio = 0.8f, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold)
                            (slideInVertically(s) { dir * it / 2 } + fadeIn()) togetherWith (slideOutVertically(s) { -dir * it / 2 } + fadeOut()) using SizeTransform(clip = false)
                        }
                    },
                    label = "day_title"
                ) { (_, t) ->
                    Text(
                        t, color = cs.onSurface, fontWeight = FontWeight.Black, maxLines = 1,
                        fontSize = lerpF(40f, 22f, clamp01(fraction())).sp,
                        letterSpacing = (-0.5).sp
                    )
                }
                Text(subtitle, color = cs.onSurfaceVariant, fontSize = 14.sp, maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = clamp01(1f - clamp01(fraction()) * 2.2f) })
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { measurables, constraints ->
        val f = clamp01(fraction())
        val h = lerpF(112.dp.toPx(), 60.dp.toPx(), f).roundToInt()
        val actions = measurables.first { it.layoutId == "actions" }.measure(Constraints())
        val titles = measurables.first { it.layoutId == "titles" }.measure(Constraints(maxWidth = (constraints.maxWidth - actions.width - 8.dp.roundToPx()).coerceAtLeast(0)))
        layout(constraints.maxWidth, h) {
            actions.place(constraints.maxWidth - actions.width, 4.dp.roundToPx())
            val titleTop = lerpF(h - titles.height - 4.dp.toPx(), (h - titles.height) / 2f + 4.dp.toPx(), f)
            titles.place(4.dp.roundToPx(), titleTop.roundToInt())
        }
    }
}

/** Hides on scroll down, returns on scroll up, like an expressive floating toolbar. */
@Stable
class ToolbarScrollState {
    var hidden by mutableStateOf(false)
        private set
    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (consumed.y < -2f) hidden = true else if (consumed.y > 2f || available.y > 2f) hidden = false
            return Offset.Zero
        }
    }
    fun show() { hidden = false }
}

/** Floating toolbar: previous day, the day (tap for today), next day, recent meals. */
@Composable
fun DayToolbar(
    hidden: Boolean,
    dayLabel: String,
    onPrev: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
    onRecent: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    val offset by animateFloatAsState(if (hidden) 1f else 0f, if (reduce) spring(stiffness = 10_000f) else fastSpatial(), label = "toolbar")
    Row(
        modifier
            .graphicsLayer { translationY = offset * 110.dp.toPx(); alpha = 1f - offset * 0.3f }
            .height(64.dp)
            .shadow(6.dp, RoundedCornerShape(32.dp))
            .clip(RoundedCornerShape(32.dp))
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ToolbarIcon(Icons.Default.ChevronLeft, "Previous Day", onPrev)
        MorphButton(onToday, Modifier.height(48.dp).width(104.dp), cs.secondaryContainer, description = "Go to today") {
            Text(dayLabel, color = cs.onSecondaryContainer, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp))
        }
        ToolbarIcon(Icons.Default.ChevronRight, "Next Day", onNext)
        Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(28.dp).background(cs.outlineVariant))
        ToolbarIcon(Icons.Default.History, "Copy Recent Meals", onRecent)
    }
}

@Composable
private fun ToolbarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    MorphButton(onClick, Modifier.size(48.dp), Color.Transparent, description = description) {
        Icon(icon, contentDescription = null, tint = cs.onSurface)
    }
}

data class FabMenuItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** FAB that opens a menu of actions springing out of it; the FAB turns into a round close button. */
@Composable
fun DiaryFabMenu(expanded: Boolean, onExpandedChange: (Boolean) -> Unit, items: List<FabMenuItem>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    val spec = if (reduce) spring<Float>(stiffness = 10_000f) else spatial()
    Box(modifier.fillMaxSize()) {
        val scrim by animateFloatAsState(if (expanded) 1f else 0f, spring(stiffness = 1600f), label = "scrim")
        if (scrim > 0.01f) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = scrim }.background(cs.scrim.copy(alpha = 0.45f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onExpandedChange(false) }
            )
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 96.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEachIndexed { i, item ->
                val order = items.lastIndex - i     // nearest to the FAB first
                val p = remember { Animatable(0f) }
                LaunchedEffect(expanded) {
                    if (expanded) { kotlinx.coroutines.delay(order * 40L); p.animateTo(1f, spec) } else p.animateTo(0f, spring(stiffness = 1600f))
                }
                if (p.value > 0.01f || expanded) {
                    MorphButton(
                        onClick = { onExpandedChange(false); item.onClick() },
                        modifier = Modifier
                            .graphicsLayer {
                                val v = p.value
                                alpha = clamp01(v * 1.6f)
                                translationY = (1f - v) * 24.dp.toPx()
                                val s = lerpF(0.6f, 1f, v); scaleX = s; scaleY = s
                                transformOrigin = TransformOrigin(1f, 0.5f)
                            }
                            .height(56.dp),
                        container = cs.primaryContainer
                    ) {
                        Row(Modifier.padding(start = 18.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(item.icon, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(item.label, color = cs.onPrimaryContainer, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        val open by animateFloatAsState(if (expanded) 1f else 0f, spec, label = "fab")
        val corner = lerpF(20f, 28f, open).dp
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = lerpF(16f, 20f, open).dp, bottom = lerpF(18f, 22f, open).dp)
                .size(lerpF(64f, 56f, open).dp)
                .pressBounce(0.9f)
                .shadow(6.dp, RoundedCornerShape(corner))
                .clip(RoundedCornerShape(corner))
                .background(if (open > 0.5f) cs.primary else cs.primaryContainer)
                .clickable { onExpandedChange(!expanded) }
                .semantics { contentDescription = if (expanded) "Close menu" else "Add food" },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add, contentDescription = null,
                tint = if (open > 0.5f) cs.onPrimary else cs.onPrimaryContainer,
                modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = open * 135f }
            )
        }
    }
}

/** Default menu entries for the Diary. */
fun diaryMenuItems(onSearch: () -> Unit, onScan: () -> Unit, onImport: () -> Unit, onAsk: () -> Unit) = listOf(
    FabMenuItem("Ask Sparky", Icons.Default.AutoAwesome, onAsk),
    FabMenuItem("Import recipe", Icons.AutoMirrored.Filled.MenuBook, onImport),
    FabMenuItem("Scan barcode", Icons.Default.QrCodeScanner, onScan),
    FabMenuItem("Search food", Icons.Default.Search, onSearch)
)

// ======================================================================
// Swiping between days
// ======================================================================

/**
 * Horizontal swipe to change day. It never fights other gestures:
 *  - it starts only after a clearly horizontal move, so vertical scrolling wins otherwise;
 *  - it backs off if anything underneath (swipe-to-delete rows, horizontal lists, the chart scrubber) consumed
 *    the move first, since children see touches before this parent;
 *  - it ignores touches that start in the system gesture areas at the screen edges, which belong to back.
 */
@Stable
class DaySwipeState {
    val offset = Animatable(0f)
    var width by mutableFloatStateOf(1f)
    /** -1 previous day, 1 next day, 0 none: where the current drag would go. */
    val pending: Int get() = when {
        offset.value <= -width * COMMIT -> 1
        offset.value >= width * COMMIT -> -1
        else -> 0
    }
    companion object { const val COMMIT = 0.28f }
}

@Composable
fun Modifier.daySwipe(state: DaySwipeState, enabled: Boolean, onCommit: (direction: Int) -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val insets = WindowInsets.systemGestures
    val edgeInsetPx = maxOf(insets.getLeft(density, dir), insets.getRight(density, dir)).toFloat()
    val commit by androidx.compose.runtime.rememberUpdatedState(onCommit)
    return this.pointerInput(enabled, edgeInsetPx) {
        if (!enabled) return@pointerInput
        state.width = size.width.toFloat()
        val slop = viewConfiguration.touchSlop * 1.5f
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val edge = maxOf(24.dp.toPx(), edgeInsetPx)
            if (down.position.x < edge || down.position.x > size.width - edge) return@awaitEachGesture
            var dx = 0f; var dy = 0f
            var dragging = false
            val tracker = VelocityTracker()
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (!dragging) {
                    if (change.isConsumed) return@awaitEachGesture        // a child took it
                    val d = change.positionChange()
                    dx += d.x; dy += d.y
                    if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture   // vertical scroll
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) dragging = true else continue
                }
                if (change.isConsumed) break
                val d = change.positionChange().x
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                scope.launch { state.offset.snapTo(state.offset.value + d * 0.85f) }
            }
            if (dragging) {
                val v = tracker.calculateVelocity().x
                val dir = when {
                    state.offset.value < -state.width * DaySwipeState.COMMIT || (v < -1200f && state.offset.value < 0) -> 1
                    state.offset.value > state.width * DaySwipeState.COMMIT || (v > 1200f && state.offset.value > 0) -> -1
                    else -> 0
                }
                scope.launch {
                    if (dir != 0) { commit(dir); state.offset.snapTo(0f) }
                    else state.offset.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 800f))
                }
            }
        }
    }
}

/** Hint at the edge showing which day a swipe would open. */
@Composable
fun DaySwipeHint(state: DaySwipeState, previousLabel: String, nextLabel: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val v = state.offset.value
    if (abs(v) < 4f) return
    val toNext = v < 0
    val progress = clamp01(abs(v) / (state.width * DaySwipeState.COMMIT))
    Box(modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(if (toNext) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 12.dp)
                .graphicsLayer { alpha = progress; val s = lerpF(0.8f, if (progress >= 1f) 1.06f else 1f, progress); scaleX = s; scaleY = s }
                .clip(RoundedCornerShape(24.dp))
                .background(if (progress >= 1f) cs.primaryContainer else cs.surfaceContainerHighest)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!toNext) Icon(Icons.Default.ChevronLeft, null, tint = cs.onSurface)
            Text(if (toNext) nextLabel else previousLabel, color = if (progress >= 1f) cs.onPrimaryContainer else cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (toNext) Icon(Icons.Default.ChevronRight, null, tint = cs.onSurface)
        }
    }
}
