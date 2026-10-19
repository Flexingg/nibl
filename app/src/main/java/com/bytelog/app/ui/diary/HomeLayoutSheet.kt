package com.bytelog.app.ui.diary

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.bytelog.app.data.repository.HomeLayout
import com.bytelog.app.data.repository.HomeSection
import com.bytelog.app.ui.theme.FluidMotion
import com.bytelog.app.ui.theme.FluidRadius
import com.bytelog.app.ui.theme.GlassLevel
import com.bytelog.app.ui.theme.LocalReduceMotion
import com.bytelog.app.ui.theme.glass

/**
 * "Customize home": drag a section by its handle to move it, switch it off to hide it. Every change is passed to
 * [onChange] straight away, so the home list behind the sheet rearranges as you go.
 */
@Composable
fun HomeLayoutEditor(saved: HomeLayout, onChange: (HomeLayout) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val reduceMotion = LocalReduceMotion.current
    val listState = rememberLazyListState()
    // Edited here and saved as it changes; the stored copy catches up a moment later
    var layout by remember { mutableStateOf(saved) }
    var dragging by remember { mutableStateOf<String?>(null) }

    fun update(next: HomeLayout) {
        layout = next
        onChange(next)
    }

    fun move(from: Int, to: Int) {
        update(layout.move(from, to))
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // Row heights are read when a drag starts; from then on the rows' places are worked out from the order, so a
    // fast drag can pass several rows between frames. The dragged row stays under the finger and swaps with a
    // neighbour once its middle passes the neighbour's middle.
    val gap = with(LocalDensity.current) { 8.dp.toPx() }
    var heights by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var startTop by remember { mutableFloatStateOf(0f) }
    var dragTotal by remember { mutableFloatStateOf(0f) }

    fun heightOf(key: String) = (heights[key] ?: heights.values.maxOrNull() ?: 0).toFloat()
    fun topOf(index: Int) = layout.order.take(index).sumOf { (heightOf(it.key) + gap).toDouble() }.toFloat()
    fun indexOf(key: String) = layout.order.indexOfFirst { it.key == key }

    fun startDrag(key: String) {
        heights = listState.layoutInfo.visibleItemsInfo.associate { it.key.toString() to it.size }
        dragging = key; dragTotal = 0f
        startTop = topOf(indexOf(key))
    }

    fun drag(dy: Float) {
        val key = dragging ?: return
        dragTotal += dy
        while (true) {
            val i = indexOf(key)
            val mid = startTop + dragTotal + heightOf(key) / 2f
            when {
                i < layout.order.lastIndex && mid > topOf(i + 1) + heightOf(layout.order[i + 1].key) / 2f -> move(i, i + 1)
                i > 0 && mid < topOf(i - 1) + heightOf(layout.order[i - 1].key) / 2f -> move(i, i - 1)
                else -> break
            }
        }
    }

    /** How far the dragged row is drawn from its place in the list. */
    fun dragOffset(key: String) = startTop + dragTotal - topOf(indexOf(key))

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Customize home", color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Drag to reorder. Switch off what you don't need.", color = cs.onSurfaceVariant, fontSize = 13.sp)
            }
            if (!layout.isDefault) {
                Text(
                    "Reset",
                    color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { update(HomeLayout.DEFAULT) }.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            itemsIndexed(layout.order, key = { _, s -> s.key }) { index, section ->
                val isDragging = dragging == section.key
                val lift by animateFloatAsState(if (isDragging && !reduceMotion) 1f else 0f, FluidMotion.morph(), label = "lift")
                val shown = section !in layout.hidden
                Row(
                    Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .then(if (isDragging) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = com.bytelog.app.ui.theme.FluidMotion.placement()))
                        .graphicsLayer {
                            translationY = if (isDragging) dragOffset(section.key) else 0f
                            val s = 1f + 0.03f * lift; scaleX = s; scaleY = s
                        }
                        .glass(if (isDragging) GlassLevel.Thick else GlassLevel.Thin, RoundedCornerShape(FluidRadius.md), elevation = (10 * lift).dp)
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics {
                            customActions = listOfNotNull(
                                if (index > 0) CustomAccessibilityAction("Move ${section.title} up") { move(index, index - 1); true } else null,
                                if (index < layout.order.lastIndex) CustomAccessibilityAction("Move ${section.title} down") { move(index, index + 1); true } else null
                            )
                        }
                        .padding(start = 4.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Drag ${section.title}" }
                            .pointerInput(section.key) {
                                detectDragGestures(
                                    onDragStart = {
                                        startDrag(section.key)
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragEnd = { dragging = null },
                                    onDragCancel = { dragging = null }
                                ) { change, amount -> change.consume(); drag(amount.y) }
                            },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.DragHandle, contentDescription = null, tint = cs.onSurfaceVariant) }
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(
                            section.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = if (shown) cs.onSurface else cs.onSurfaceVariant
                        )
                        Text(section.description, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (section.hideable) {
                        Switch(checked = shown, onCheckedChange = { update(layout.toggle(section, it)) })
                    } else {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Lock, contentDescription = "Always shown", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
