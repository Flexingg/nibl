package com.bytelog.app.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.bytelog.app.data.repository.HomeTile
import com.bytelog.app.data.repository.HomeTiles
import com.bytelog.app.data.repository.TileSize
import com.bytelog.app.ui.theme.FluidMotion
import com.bytelog.app.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch

/**
 * Editing the home screen, like arranging quick settings tiles: the tiles wiggle; long-press one and drag to move it,
 * tap one to change its size (full, half, small), × takes it off, and the tray below puts it back. Every change is
 * passed to [onChange] as it happens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeTilesEditor(
    saved: HomeTiles,
    faceOf: (HomeTile) -> TileFace,
    onChange: (HomeTiles) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    var tiles by remember { mutableStateOf(saved) }
    val grid = rememberLazyGridState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun update(next: HomeTiles) {
        if (next == tiles) return
        tiles = next
        onChange(next)
    }

    // Dragging: the tile follows the finger; once its middle is over another tile, it takes that tile's place
    var dragging by remember { mutableStateOf<HomeTile?>(null) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }

    fun info(tile: HomeTile): LazyGridItemInfo? = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == tile.key }

    fun reorderUnderFinger() {
        val tile = dragging ?: return
        val me = info(tile) ?: return
        val centre = dragStart + dragDelta + Offset(me.size.width / 2f, me.size.height / 2f)
        val target = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key != tile.key && item.key is String && HomeTile.fromKey(item.key as String) != null &&
                centre.x >= item.offset.x && centre.x <= item.offset.x + item.size.width &&
                centre.y >= item.offset.y && centre.y <= item.offset.y + item.size.height
        } ?: return
        val targetTile = HomeTile.fromKey(target.key as String) ?: return
        // Keep the grid where it is when the first tile on screen moves (it would otherwise scroll after it)
        val first = grid.firstVisibleItemIndex
        val firstOffset = grid.firstVisibleItemScrollOffset
        update(tiles.moveTo(tile, targetTile))
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (me.index == first || target.index == first) scope.launch { grid.scrollToItem(first, firstOffset) }
    }

    // Near the top or bottom while dragging, the grid scrolls by itself
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        val edge = with(density) { 72.dp.toPx() }
        val step = with(density) { 10.dp.toPx() }
        while (dragging != null) {
            withFrameNanos { }
            val me = dragging?.let { info(it) } ?: continue
            val top = dragStart.y + dragDelta.y
            val bottom = top + me.size.height
            val viewport = grid.layoutInfo.viewportEndOffset
            val by = when {
                bottom > viewport - edge -> step
                top < edge -> -step
                else -> 0f
            }
            if (by != 0f && grid.scrollBy(by) != 0f) reorderUnderFinger()
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Edit home", color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Hold and drag to move · tap to resize", color = cs.onSurfaceVariant, fontSize = 12.5.sp)
            }
            if (!tiles.isDefault) {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    update(HomeTiles.DEFAULT)
                }) { Text("Reset") }
            }
            Spacer(Modifier.width(4.dp))
            Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onDone() }) { Text("Done") }
        }

        val visible = tiles.visible
        val heights = rowHeights(visible.map { it.size })
        val hidden = tiles.hiddenTiles
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = grid,
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 120.dp)
        ) {
            items(visible, key = { it.tile.key }, span = { GridItemSpan(it.size.span) }) { placed ->
                val tile = placed.tile
                val index = visible.indexOf(placed)
                val isDragging = dragging == tile
                val lift by animateFloatAsState(if (isDragging) 1f else 0f, FluidMotion.morph(), label = "lift")
                val wiggle = if (reduceMotion) 0f else {
                    val t = rememberInfiniteTransition(label = "wiggle")
                    val v by t.animateFloat(
                        -1f, 1f,
                        infiniteRepeatable(tween(240 + (index % 3) * 40, easing = LinearEasing), RepeatMode.Reverse),
                        label = "wiggle_${tile.key}"
                    )
                    v
                }
                val face = faceOf(tile)
                Box(
                    Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .then(if (isDragging) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = FluidMotion.placement()))
                        .graphicsLayer {
                            if (isDragging) {
                                val now = info(tile)
                                if (now != null) {
                                    translationX = dragStart.x + dragDelta.x - now.offset.x
                                    translationY = dragStart.y + dragDelta.y - now.offset.y
                                }
                            }
                            // Wider tiles wiggle less, so every tile's corners move about as far
                            rotationZ = if (isDragging) 0f else wiggle * 1.2f / placed.size.span.coerceAtLeast(1).let { kotlin.math.sqrt(it.toFloat()) }
                            val s = 1f + 0.05f * lift; scaleX = s; scaleY = s
                        }
                        .then(if (lift > 0.01f) Modifier.shadow((12 * lift).dp, RoundedCornerShape(TILE_RADIUS)) else Modifier)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${tile.title}, ${placed.size.label}"
                            role = Role.Button
                            if (tile.sizes.size > 1) onClick(label = "Change size") { update(tiles.resize(tile, tile.nextSize(placed.size))); true }
                            customActions = listOfNotNull(
                                if (index > 0) CustomAccessibilityAction("Move ${tile.title} earlier") { update(tiles.moveVisible(index, index - 1)); true } else null,
                                if (index < visible.lastIndex) CustomAccessibilityAction("Move ${tile.title} later") { update(tiles.moveVisible(index, index + 1)); true } else null,
                                if (tile.removable) CustomAccessibilityAction("Remove ${tile.title}") { update(tiles.remove(tile)); true } else null
                            )
                        }
                        .pointerInput(tile) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val long = awaitLongPressOrCancellation(down.id)
                                if (long == null) {
                                    // A tap (not a scroll): the next size
                                    val up = currentEvent.changes.firstOrNull { it.id == down.id }
                                    if (up != null && !up.pressed && !up.isConsumed && tile.sizes.size > 1) {
                                        up.consume()
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        update(tiles.resize(tile, tile.nextSize(tiles.sizeOf(tile))))
                                    }
                                    return@awaitEachGesture
                                }
                                val start = info(tile) ?: return@awaitEachGesture
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                dragStart = Offset(start.offset.x.toFloat(), start.offset.y.toFloat())
                                dragDelta = Offset.Zero
                                dragging = tile
                                try {
                                    drag(long.id) { change ->
                                        dragDelta += change.positionChange()
                                        change.consume()
                                        reorderUnderFinger()
                                    }
                                } finally {
                                    dragging = null
                                }
                            }
                        }
                ) {
                    SummaryTile(face, placed.size, enabled = false, height = heights.getOrElse(index) { TILE_HEIGHT })
                    if (tile.removable) {
                        Box(
                            Modifier
                                .align(Alignment.TopStart)
                                .offset((-4).dp, (-4).dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(cs.inverseSurface)
                                .clickable(onClickLabel = "Remove ${tile.title}") {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    update(tiles.remove(tile))
                                }
                                .semantics { contentDescription = "Remove ${tile.title}" },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Close, contentDescription = null, tint = cs.inverseOnSurface, modifier = Modifier.size(14.dp)) }
                    }
                    if (tile.sizes.size > 1) {
                        Text(
                            sizeTag(placed.size), color = cs.onSecondaryContainer, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                .clip(RoundedCornerShape(50)).background(cs.secondaryContainer).padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.Lock, contentDescription = null, tint = cs.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(14.dp)
                        )
                    }
                }
            }

            if (hidden.isNotEmpty()) {
                item(key = "add_tray", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = FluidMotion.placement())
                            .padding(top = 14.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(TILE_RADIUS))
                            .border(1.dp, cs.outlineVariant, RoundedCornerShape(TILE_RADIUS))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("ADD TILES", color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            hidden.forEach { placed ->
                                val face = faceOf(placed.tile)
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(cs.surfaceContainerHigh)
                                        .clickable(onClickLabel = "Add ${placed.tile.title}") {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            update(tiles.add(placed.tile))
                                        }
                                        .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    TileBadge(face.copy(progress = null), 26.dp)
                                    Text(placed.tile.title, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Icon(Icons.Default.Add, contentDescription = null, tint = cs.primary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Full", "Half" or "Small" on a tile being edited. */
private fun sizeTag(size: TileSize) = when (size) {
    TileSize.FULL -> "Full"
    TileSize.HALF -> "Half"
    TileSize.SMALL -> "Small"
}
