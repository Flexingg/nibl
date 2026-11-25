package com.bytelog.app.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.repository.TileGrid
import com.bytelog.app.data.repository.TileSize
import com.bytelog.app.ui.components.pressBounce
import kotlinx.coroutines.withTimeoutOrNull

/** Height of a row of small tiles; on a phone a small tile is about square. */
val TILE_HEIGHT = 88.dp
/** Height of a row with a half tile in it: room for a chart or a short list under the number. */
val TILE_TALL = 150.dp
/** Space between tiles, across and down. */
val TILE_GAP = 10.dp
/** Corner radius of a home card. */
val TILE_RADIUS = 24.dp

/** How tall a row of tiles is: taller when it holds a half tile, which has a chart or list. */
fun rowHeight(sizes: List<TileSize>): Dp = if (sizes.any { it == TileSize.HALF }) TILE_TALL else TILE_HEIGHT

/** Each tile's row height, for tiles packed in order. */
fun rowHeights(sizes: List<TileSize>): List<Dp> {
    val rows = TileGrid.place(sizes.map { it.span }).map { it.first }
    return sizes.indices.map { i -> rowHeight(sizes.filterIndexed { j, _ -> rows[j] == rows[i] }) }
}

/** A quick action on a tile (water's "+16 oz"). */
@Immutable
data class TileAction(val label: String, val description: String, val onClick: () -> Unit)

/** What a tile shows when it isn't a full card: an icon in its colour, a value and a line of detail. */
@Immutable
data class TileFace(
    val title: String,
    val icon: ImageVector,
    val accent: Color,
    val container: Color,
    /** The number that matters ("1,240", "48 oz"); null for shortcuts, which show their title. */
    val value: String? = null,
    val detail: String? = null,
    /** 0..1 drawn as a ring round the icon. */
    val progress: Float? = null,
    val action: TileAction? = null,
    /** A picture of the data, on half and full tiles. */
    val chart: TileChart? = null,
    /** The tile is a glass filled this far (0..1) with gently moving water: the water tile. */
    val fill: Float? = null,
    /** More to read ("Rice", "2 portions"), on half (two) and full (four) tiles. */
    val lines: List<Pair<String, String>> = emptyList(),
    val onClick: () -> Unit = {}
) {
    val spoken: String get() = (listOfNotNull(title, value, detail) + lines.map { "${it.first} ${it.second}" }).joinToString(", ")
}

/** The home card look: rounded, on the surface container. */
@Composable
fun Modifier.tileCard(radius: Dp = TILE_RADIUS): Modifier =
    this.clip(RoundedCornerShape(radius)).background(MaterialTheme.colorScheme.surfaceContainer)

/** The icon in a soft disc, with a progress ring round it when there is one. */
@Composable
fun TileBadge(face: TileFace, size: Dp, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val p = face.progress
        if (p != null) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 3.dp.toPx(); val inset = sw / 2
                val arc = androidx.compose.ui.geometry.Size(this.size.width - sw, this.size.height - sw)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(sw))
                drawArc(face.accent, -90f, 360f * p.coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        val inner = if (p != null) size - 10.dp else size
        Box(Modifier.size(inner).clip(CircleShape).background(face.container), contentAlignment = Alignment.Center) {
            Icon(face.icon, contentDescription = null, tint = face.accent, modifier = Modifier.size(inner * 0.52f))
        }
    }
}

@Composable
private fun ActionButton(action: TileAction, accent: Color, container: Color, size: Dp) {
    com.bytelog.app.ui.diary.MorphButton(action.onClick, Modifier.size(size), container, description = action.description) {
        Text(action.label, color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * A tile that isn't a full card. The bigger it is, the more it shows:
 * - small: the icon (with its progress ring) and the number; in a tall row, the detail and title too
 * - half: title, number and detail, and a chart or two lines of what's behind the number
 * - full: all of that, a bigger chart and up to four lines
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SummaryTile(
    face: TileFace,
    size: TileSize,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    /** The row's height, so tiles side by side line up; defaults to what [size] needs. */
    height: Dp = if (size == TileSize.HALF) TILE_TALL else TILE_HEIGHT
) {
    val cs = MaterialTheme.colorScheme
    val radius = if (size == TileSize.SMALL) 20.dp else TILE_RADIUS
    Box(
        modifier
            .then(if (size == TileSize.FULL) Modifier.heightIn(min = height) else Modifier.height(height))
            .fillMaxWidth()
            .then(if (enabled) Modifier.pressBounce() else Modifier)
            .tileCard(radius)
            .then(face.fill?.let { Modifier.waterFill(it, face.accent, face.container) } ?: Modifier)
            .then(if (enabled) Modifier.combinedClickable(onClick = face.onClick, onLongClick = onLongClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = face.spoken }
    ) {
        when (size) {
            TileSize.SMALL -> SmallTile(face, tall = height > TILE_HEIGHT)
            TileSize.HALF -> Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TileBadge(face, 32.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        face.title.uppercase(), color = cs.onSurfaceVariant, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    if (enabled) face.action?.let { ActionButton(it, face.accent, face.container, 34.dp) }
                }
                Spacer(Modifier.height(6.dp))
                ValueLine(face, valueSize = 20)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomStart) {
                    TileMore(face, maxLines = 2, large = false)
                }
            }
            TileSize.FULL -> Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TileBadge(face, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(face.title.uppercase(), color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, maxLines = 1)
                        ValueLine(face, valueSize = 22)
                    }
                    if (enabled) face.action?.let { Spacer(Modifier.width(8.dp)); ActionButton(it, face.accent, face.container, 40.dp) }
                }
                face.chart?.let {
                    val m = when (it) {
                        is TileChart.Photos -> Modifier.height(110.dp)
                        is TileChart.Macros, is TileChart.Meter -> Modifier
                        else -> Modifier.height(64.dp)
                    }
                    TileChartView(it, face.accent, Modifier.fillMaxWidth().then(m), large = true)
                }
                if (face.lines.isNotEmpty()) TileLines(face.lines, 4)
            }
        }
    }
}

/** A small tile: the icon and number, centred; a tall one adds the detail and the title. */
@Composable
private fun SmallTile(face: TileFace, tall: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (tall) {
            Text(face.title.uppercase(), color = cs.onSurfaceVariant, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
        }
        TileBadge(face, if (tall) 48.dp else 40.dp)
        Spacer(Modifier.height(5.dp))
        Text(
            face.value ?: face.title, color = cs.onSurface, fontSize = if (tall) 14.sp else 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        val second = if (face.value != null) face.detail else null
        if (second != null) {
            Text(second, color = cs.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** Under the number on a half tile: the chart if there is one, else a couple of lines. */
@Composable
private fun TileMore(face: TileFace, maxLines: Int, large: Boolean) {
    val chart = face.chart
    when {
        chart != null -> TileChartView(chart, face.accent, Modifier.fillMaxSize(), large)
        face.lines.isNotEmpty() -> TileLines(face.lines, maxLines)
    }
}

@Composable
private fun TileLines(lines: List<Pair<String, String>>, max: Int) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.take(max).forEach { (label, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(cs.onSurfaceVariant.copy(alpha = 0.6f)))
                Spacer(Modifier.width(6.dp))
                Text(label, color = cs.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (value.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(value, color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ValueLine(face: TileFace, valueSize: Int) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            face.value ?: face.detail ?: face.title, color = cs.onSurface, fontSize = valueSize.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            // The number keeps its room; the detail beside it gives way
            modifier = if (face.value != null && face.detail != null) Modifier else Modifier.weight(1f, fill = false)
        )
        if (face.value != null && face.detail != null) {
            Text(
                "  " + face.detail, color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(bottom = 2.dp)
            )
        }
    }
}

/** A tile folded into a slim strip at the top of the home screen. [span] is its folded width in quarters. */
@Composable
fun TileStrip(face: TileFace, span: Int) {
    val cs = MaterialTheme.colorScheme
    if (span <= 1) {
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            TileBadge(face, 32.dp)
            face.value?.let {
                Spacer(Modifier.width(4.dp))
                Text(it, color = cs.onSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
        return
    }
    Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TileBadge(face, 36.dp)
        Column(Modifier.weight(1f)) {
            Text(face.value ?: face.title, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (face.value != null) face.detail ?: face.title else face.detail ?: "").ifEmpty { face.title },
                color = cs.onSurfaceVariant, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        face.action?.let { ActionButton(it, face.accent, face.container, 38.dp) }
    }
}

/** Tiles side by side on the four-column grid, so a half tile lines up with two small ones above or below it. */
@Composable
fun TileRow(spans: List<Int>, modifier: Modifier = Modifier, gap: Dp = TILE_GAP, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val w = constraints.maxWidth
        val g = gap.roundToPx()
        val unit = (w - g * (TileGrid.COLUMNS - 1)) / TileGrid.COLUMNS.toFloat()
        val columns = TileGrid.place(spans).map { it.second }
        val placeables = measurables.mapIndexed { i, m ->
            val span = spans.getOrElse(i) { 1 }
            val cw = (unit * span + g * (span - 1)).toInt().coerceAtLeast(0)
            m.measure(Constraints(minWidth = cw, maxWidth = cw, minHeight = 0, maxHeight = constraints.maxHeight))
        }
        val h = placeables.maxOfOrNull { it.height } ?: 0
        layout(w, h) {
            placeables.forEachIndexed { i, p -> p.place((columns.getOrElse(i) { 0 } * (unit + g)).toInt(), 0) }
        }
    }
}

/**
 * A long press anywhere on a card, even over its buttons, calls [onLongPress] (to start editing the home screen); a
 * tap, scroll or swipe goes through to the card as usual. Watches touches before the card does, so its own buttons
 * still work, and swallows the rest of a long press so nothing underneath is tapped as well.
 */
@Composable
fun Modifier.longPressToEdit(enabled: Boolean, onLongPress: () -> Unit): Modifier {
    if (!enabled) return this
    // The latest callback, without restarting the touch handling each time the screen recomposes
    val current by androidx.compose.runtime.rememberUpdatedState(onLongPress)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed || c.isConsumed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                true
            }
            if (ended == null) {
                current()
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    ev.changes.forEach { it.consume() }
                    if (ev.changes.none { it.pressed }) break
                }
            }
        }
    }
}

/** Grows in from a little smaller, as a small tile opens out to full width. */
@Composable
fun growIn(): Modifier {
    val reduce = com.bytelog.app.ui.theme.LocalReduceMotion.current
    val a = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (reduce) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        a.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 380f))
    }
    return Modifier.graphicsLayer {
        val s = 0.82f + 0.18f * a.value
        scaleX = s; scaleY = s
        alpha = 0.4f + 0.6f * a.value.coerceIn(0f, 1f)
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
    }
}

/** "Show less" under a tile that was opened out from small. */
@Composable
fun ShowLessButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh)
                .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ExpandLess, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Show less", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
