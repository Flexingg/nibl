package com.bytelog.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.bytelog.app.data.repository.TileGrid
import com.bytelog.app.ui.components.pressBounce
import com.bytelog.app.ui.theme.LocalFluid
import kotlin.math.max
import kotlin.math.roundToInt

/** One of the tiles at the top of the home screen, which fold into slim strips as the list scrolls. */
@Immutable
class FoldTileSpec(
    val key: String,
    /** Width in quarters, unfolded. */
    val span: Int,
    /** Draws its own card when unfolded (the home cards do); the strip's card fades in as it folds. */
    val ownCard: Boolean,
    /** Height of the folded strip. */
    val stripHeight: Dp = 54.dp,
    /** A long press on it starts editing the home screen; null where long press already means something. */
    val onLongPress: (() -> Unit)? = null,
    val expanded: @Composable () -> Unit,
    /** The strip, given its folded width in quarters. */
    val compact: @Composable (span: Int) -> Unit
)

/** The tiles' folded widths, in quarters, for [spans] unfolded. */
fun foldedSpans(spans: List<Int>): List<Int> = TileGrid.foldedSpans(spans)

/** How tall a tile is unfolded, passed up from the tile to the header so the header can lay out its rows. */
private val ExpandedHeight = HorizontalAlignmentLine(::max)

private const val HERO_ID = "hero"
private const val HERO_DP = 132
private const val HERO_FOLDED_DP = 44

/**
 * The top of the home screen: up to three tiles in their grid places, folding into slim strips (full and half
 * width) as [fraction] goes from 0 to 1. [hero] is the calorie shape, which flies from the left of tile [heroIndex]
 * into its strip; it's only drawn when that tile is a full card.
 */
@Composable
fun DashboardHeader(
    fraction: () -> Float,
    tiles: List<FoldTileSpec>,
    modifier: Modifier = Modifier,
    heroIndex: Int = -1,
    hero: (@Composable () -> Unit)? = null
) {
    val spans = remember(tiles.map { it.span }) { tiles.map { it.span } }
    val folded = remember(spans) { foldedSpans(spans) }
    Layout(
        modifier = modifier,
        content = {
            tiles.forEachIndexed { i, t ->
                // Straight in, not in a Box: the tile takes its width from the minimum it is given
                FoldTile(fraction, t, folded[i], Modifier.layoutId(i))
            }
            if (hero != null && heroIndex in tiles.indices) Box(Modifier.layoutId(HERO_ID).zIndex(1f)) { hero() }
        }
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val gapE = TILE_GAP.roundToPx(); val gapC = 8.dp.roundToPx()
        val f = fraction().coerceIn(-0.07f, 1.07f)
        val unitE = (w - gapE * 3) / 4f
        val unitC = (w - gapC * 3) / 4f
        fun widthE(s: Int) = (unitE * s + gapE * (s - 1)).roundToInt()
        fun widthC(s: Int) = (unitC * s + gapC * (s - 1)).roundToInt()
        val placeE = TileGrid.place(spans)
        val placeC = TileGrid.place(folded)
        val strips = tiles.map { it.stripHeight.roundToPx() }

        val placeables = tiles.indices.map { i ->
            val m = measurables.first { it.layoutId == i }
            val ew = widthE(spans[i])
            val cw = lerp(ew.toFloat(), widthC(folded[i]).toFloat(), f).roundToInt().coerceAtLeast(0)
            // The tile is as wide as it is at this point of the fold; it lays out its full card at the unfolded width.
            m.measure(Constraints(minWidth = cw, maxWidth = max(cw, ew)))
        }
        val tall = placeables.map { p -> p[ExpandedHeight].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: p.height }

        fun rowTops(rowOf: List<Int>, heights: List<Int>, gap: Int): Pair<List<Int>, Int> {
            val rows = (rowOf.maxOrNull() ?: -1) + 1
            val rowH = IntArray(rows)
            rowOf.forEachIndexed { i, r -> rowH[r] = max(rowH[r], heights[i]) }
            val tops = IntArray(rows)
            var y = 0
            for (r in 0 until rows) { tops[r] = y; y += rowH[r] + gap }
            return tops.toList() to (y - gap).coerceAtLeast(0)
        }
        val (topsE, totalE) = rowTops(placeE.map { it.first }, tall, gapE)
        val (topsC, totalC) = rowTops(placeC.map { it.first }, strips, gapC)
        val height = lerp(totalE.toFloat(), totalC.toFloat(), f).roundToInt().coerceAtLeast(0)

        val xs = tiles.indices.map { i -> lerp(placeE[i].second * (unitE + gapE), placeC[i].second * (unitC + gapC), f) }
        val ys = tiles.indices.map { i -> lerp(topsE[placeE[i].first].toFloat(), topsC[placeC[i].first].toFloat(), f) }

        val heroM = measurables.firstOrNull { it.layoutId == HERO_ID }
        val heroPx = HERO_DP.dp.roundToPx()
        val heroP = heroM?.measure(Constraints.fixed(heroPx, heroPx))

        layout(w, height) {
            placeables.forEachIndexed { i, p -> p.place(xs[i].roundToInt(), ys[i].roundToInt()) }
            if (heroP != null) {
                val i = heroIndex
                val small = HERO_FOLDED_DP.dp.toPx()
                val t = f.coerceIn(0f, 1f)
                val scale = lerp(1f, small / heroPx, t)
                val ex = placeE[i].second * (unitE + gapE) + 14.dp.toPx()
                val ey = topsE[placeE[i].first] + (tall[i] - heroPx) / 2f
                val cx = placeC[i].second * (unitC + gapC) + 10.dp.toPx()
                val cy = topsC[placeC[i].first] + (strips[i] - small) / 2f
                heroP.placeWithLayer(0, 0, zIndex = 1f) {
                    translationX = lerp(ex, cx, f); translationY = lerp(ey, cy, f)
                    scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }
}

/**
 * A tile that folds: its full version fades and shrinks out as its strip fades in, while the card around them
 * narrows and shortens. Reports its unfolded height to the header through [ExpandedHeight].
 */
@Composable
private fun FoldTile(fraction: () -> Float, spec: FoldTileSpec, foldedSpan: Int, modifier: Modifier) {
    val edge = LocalFluid.current.edge
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val policy = remember(fraction, spec.stripHeight) { FoldTilePolicy(fraction, spec.stripHeight) }
    fun radius(f: Float) = lerp(TILE_RADIUS.value, 22f, f.coerceIn(0f, 1f)).dp
    // The card behind the strip; a tile with its own card shows that one until it starts to fold
    fun cardAlpha(f: Float) = if (spec.ownCard) ((f - 0.25f) / 0.3f).coerceIn(0f, 1f) else 1f
    Layout(
        modifier = modifier
            .then(if (spec.onLongPress != null) Modifier.longPressToEdit(true, spec.onLongPress) else Modifier)
            .pressBounce()
            .graphicsLayer {
                shape = RoundedCornerShape(radius(fraction()))
                clip = true
            }
            .drawWithContent {
                val f = fraction()
                val a = cardAlpha(f)
                val r = radius(f).toPx()
                if (a > 0f) drawRoundRect(surface.copy(alpha = surface.alpha * a), cornerRadius = CornerRadius(r))
                drawContent()
                if (a > 0f) {
                    val sw = 1.dp.toPx()
                    drawRoundRect(
                        edge, androidx.compose.ui.geometry.Offset(sw / 2, sw / 2),
                        androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw), CornerRadius(r - sw / 2), alpha = a, style = Stroke(sw)
                    )
                }
            },
        content = {
            Box(
                Modifier.graphicsLayer {
                    val f = fraction().coerceIn(0f, 1f)
                    alpha = ((0.52f - f) / 0.18f).coerceIn(0f, 1f)
                    val s = lerp(1f, 0.94f, f); scaleX = s; scaleY = s
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            ) { spec.expanded() }
            Box(
                Modifier.graphicsLayer {
                    val f = fraction().coerceIn(0f, 1f)
                    alpha = ((f - 0.48f) / 0.18f).coerceIn(0f, 1f)
                    val s = lerp(1.08f, 1f, f); scaleX = s; scaleY = s
                }
            ) { spec.compact(foldedSpan) }
        },
        measurePolicy = policy
    )
}

private class FoldTilePolicy(private val fraction: () -> Float, private val strip: Dp) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val f = fraction().coerceIn(-0.07f, 1.07f)
        val w = constraints.minWidth
        val fullW = constraints.maxWidth
        val stripH = strip.roundToPx()
        // Always measured, for its height; only placed (so only drawn and touchable) while it shows
        val e = measurables[0].measure(Constraints(minWidth = fullW, maxWidth = fullW, minHeight = 0, maxHeight = Constraints.Infinity))
        val h = lerp(e.height.toFloat(), stripH.toFloat(), f).roundToInt().coerceAtLeast(0)
        val c = if (f > 0.45f) measurables[1].measure(Constraints.fixed(w, stripH)) else null
        return layout(w, h, mapOf(ExpandedHeight to e.height)) {
            if (f < 0.55f) e.place(0, 0)
            c?.place(0, ((h - stripH) / 2).coerceAtLeast(0))
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
