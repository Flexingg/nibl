package com.bytelog.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
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
import com.bytelog.app.ui.theme.LocalReduceMotion
import com.bytelog.app.ui.theme.MacroProtein
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Motion for the Diary: the three header cards fold into two narrow strips as the list scrolls, and spring back
 * at the top. Springs are tuned to the approved render (damping 0.58, stiffness ~420).
 */

private const val HEADER_DAMPING = 0.58f
private const val HEADER_STIFFNESS = 420f

/** Springy settle used by bars, rings and counters. */
fun <T> bouncySpec(damping: Float = 0.6f, stiffness: Float = Spring.StiffnessLow) = spring<T>(dampingRatio = damping, stiffness = stiffness)

/** A bar or ring value that springs to its target (snaps when motion is reduced). */
@Composable
fun animateBouncy(target: Float, label: String = "bouncy"): Float {
    val reduce = LocalReduceMotion.current
    val animated by animateFloatAsState(target, if (reduce) spring(stiffness = Spring.StiffnessHigh) else bouncySpec(), label = label)
    return if (reduce) target else animated
}

/** Squashes slightly while pressed and springs back. Observes touches without consuming them. */
fun Modifier.pressBounce(scaleDown: Float = 0.965f): Modifier = composed {
    val reduce = LocalReduceMotion.current
    var pressed by remember { mutableStateOf(false) }
    // Living Flow feedback: compress quickly on press, rebound with one small overshoot on release
    val scale by animateFloatAsState(
        if (pressed && !reduce) scaleDown else 1f,
        if (pressed) com.bytelog.app.ui.theme.FluidMotion.press() else com.bytelog.app.ui.theme.FluidMotion.release(),
        label = "press"
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
}

/** List rows pop up a little the first time they appear (once per key), overshooting like the header does. */
@Composable
fun Modifier.popIn(key: Any, seen: MutableSet<Any>, delayMillis: Long = 0L): Modifier {
    val reduce = LocalReduceMotion.current
    val progress = remember(key) { Animatable(if (reduce || key in seen) 1f else 0f) }
    LaunchedEffect(key) {
        if (progress.value < 1f) {
            seen += key
            if (delayMillis > 0) kotlinx.coroutines.delay(delayMillis)
            progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f))
        }
    }
    return graphicsLayer {
        val p = progress.value
        translationY = (1f - p) * 18.dp.toPx()
        val s = 0.96f + 0.04f * p
        scaleX = s; scaleY = s
    }
}

/**
 * Rows that scroll up under the header fade and shrink away instead of sliding beneath it. The fade only starts
 * once [FADE_START] of the row has gone, so a row stays readable while most of it is still on screen.
 */
fun Modifier.hideWhenScrolledAway(state: LazyListState, key: Any): Modifier = graphicsLayer {
    val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
    if (info != null && info.offset < 0 && info.size > 0) {
        val p = scrolledAwayFade(-info.offset.toFloat() / info.size)
        alpha = 1f - p
        val s = 1f - 0.08f * p
        scaleX = s; scaleY = s
        transformOrigin = TransformOrigin(0.5f, 1f)
    }
}

/**
 * Drives the header fold from scrolling. Scrolling down folds the header before the list moves; scrolling up only
 * unfolds it once the list is back at the top. When the finger lifts halfway, it settles on the nearer state.
 */
@Stable
class HeaderCollapseState(private val rangePx: Float) {
    private var offset by mutableFloatStateOf(0f)

    /** 0 = full cards, 1 = narrow strips. */
    val target: Float get() = (offset / rangePx).coerceIn(0f, 1f)

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy >= 0f || offset >= rangePx) return Offset.Zero
            val next = (offset - dy).coerceAtMost(rangePx)
            val used = next - offset
            offset = next
            return Offset(0f, -used)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy <= 0f || offset <= 0f) return Offset.Zero
            val next = (offset - dy).coerceAtLeast(0f)
            val used = offset - next
            offset = next
            return Offset(0f, used)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            settle()
            return Velocity.Zero
        }
    }

    fun settle() {
        offset = if (offset / rangePx > 0.45f) rangePx else 0f
    }
}

@Composable
fun rememberHeaderCollapseState(rangeDp: Float = 260f): HeaderCollapseState {
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(density) { HeaderCollapseState(with(density) { rangeDp.dp.toPx() }) }
}

/** Fold fraction that follows the scroll through a loose spring, so the cards lag and overshoot slightly. */
@Composable
fun rememberHeaderFraction(state: HeaderCollapseState): () -> Float {
    val reduce = LocalReduceMotion.current
    val spring by animateFloatAsState(state.target, spring(dampingRatio = HEADER_DAMPING, stiffness = HEADER_STIFFNESS), label = "header_fold")
    return { if (reduce) state.target else spring }
}

internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

// ---------- Narrow versions ----------

/** Calories left with the day's bar, and small protein, carb and fat rings. */
@Composable
fun MacroStrip(summary: DailySummary, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxSize().padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.width(78.dp)) {
            Text("${summary.remainingCalories.roundToInt()}", color = ByteCream, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("KCAL LEFT", color = ByteCreamMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
        val bar = animateBouncy(summary.calorieProgress, "strip_cal")
        Box(
            Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(ByteSurfaceVariantDark)
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(bar.coerceIn(0f, 1f)).clip(RoundedCornerShape(3.dp)).background(MacroCalories))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MacroRing("P", summary.proteinProgress, MacroProtein)
            MacroRing("C", summary.carbsProgress, MacroCarbs)
            MacroRing("F", summary.fatProgress, MacroFat)
        }
    }
}

@Composable
private fun MacroRing(letter: String, progress: Float, color: Color) {
    val sweep = animateBouncy(progress, "ring_$letter")
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        val cTrack = ByteSurfaceVariantDark
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.2.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(cTrack, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * sweep.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(letter, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Water so far against the goal, with a one-tap button for the default container. */
@Composable
fun WaterStrip(currentOz: Float, targetOz: Float, defaultOz: Float, onLogDefault: () -> Unit, modifier: Modifier = Modifier) {
    val progress = if (targetOz > 0f) (currentOz / targetOz).coerceIn(0f, 1f) else 0f
    val bar = animateBouncy(progress, "strip_water")
    Row(
        modifier = modifier.fillMaxSize().padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SplashingDrop(currentOz, size = 26.dp, iconSize = 14.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${currentOz.roundToInt()}", color = ByteCream, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("/${targetOz.roundToInt()} oz", color = ByteCreamMuted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(ByteSurfaceVariantDark)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(bar).clip(RoundedCornerShape(2.dp)).background(MacroProtein))
            }
        }
        Box(
            Modifier
                .size(30.dp)
                .pressBounce(0.82f)
                .clip(CircleShape)
                .background(MacroProtein.copy(alpha = 0.16f))
                .clickable(onClick = onLogDefault),
            contentAlignment = Alignment.Center
        ) {
            Text("+${defaultOz.roundToInt()}", color = MacroProtein, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Weight with the change since the last weigh-in. */
@Composable
fun WeightStrip(weight: Float?, unit: String, delta: Float?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxSize().padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(MacroCarbs.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MonitorWeight, contentDescription = null, tint = MacroCarbs, modifier = Modifier.size(14.dp))
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Text(
                if (weight != null) String.format(java.util.Locale.US, "%.1f", weight) else "—",
                color = ByteCream, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
            )
            Text(" $unit", color = ByteCreamMuted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (delta != null) {
            val good = delta < 0
            val color = if (good) MacroProtein else com.bytelog.app.ui.theme.ByteTomato
            val arrow = if (good) "↓" else "↑"
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.13f)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                Text("$arrow${String.format(java.util.Locale.US, "%.1f", abs(delta))}", color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** The water drop. It jumps and tilts whenever the amount changes, so logging a drink feels like a splash. */
@Composable
fun SplashingDrop(currentOz: Float, size: androidx.compose.ui.unit.Dp, iconSize: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val scale = remember { Animatable(1f) }
    val tilt = remember { Animatable(0f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(currentOz) {
        if (first) { first = false; return@LaunchedEffect }
        if (reduce) return@LaunchedEffect
        scale.snapTo(1.35f); tilt.snapTo(-8f)
        kotlinx.coroutines.coroutineScope {
            launch { scale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 350f)) }
            launch { tilt.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 350f)) }
        }
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value; rotationZ = tilt.value }
            .clip(CircleShape)
            .background(MacroProtein.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.WaterDrop, contentDescription = "Hydration", tint = MacroProtein, modifier = Modifier.size(iconSize))
    }
}

/**
 * A home list section: glides to its new place when the sections are reordered or one above it comes or goes,
 * and fades away under the header like every other row.
 */
fun Modifier.homeItem(scope: androidx.compose.foundation.lazy.LazyItemScope, state: LazyListState, key: Any): Modifier =
    with(scope) { animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = com.bytelog.app.ui.theme.FluidMotion.placement()) }
        .hideWhenScrolledAway(state, key)

/** How much of a row must be gone before it starts to fade, and how much when it has fully faded. */
internal const val FADE_START = 0.55f
internal const val FADE_END = 0.95f

/** 0 (fully shown) to 1 (faded) for a row with [goneFraction] of its height scrolled away. */
internal fun scrolledAwayFade(goneFraction: Float): Float =
    ((goneFraction - FADE_START) / (FADE_END - FADE_START)).coerceIn(0f, 1f)

