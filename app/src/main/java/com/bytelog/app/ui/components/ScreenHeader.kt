package com.bytelog.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.ui.diary.MorphButton
import kotlin.math.roundToInt

/**
 * Expressive page header: a large title that shrinks into a normal top bar as the page scrolls ([fraction]:
 * 0 = large, 1 = compact), a round tonal back button whose corners tighten when pressed, and actions on the right.
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    fraction: () -> Float = { 1f },
    statusBarPadding: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().background(cs.surface).then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)) {
        Layout(
            content = {
                Box(Modifier.layoutId("back")) {
                    MorphButton(onBack, Modifier.size(44.dp), cs.surfaceContainerHigh, description = "Back") {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = cs.onSurface)
                    }
                }
                Row(Modifier.layoutId("actions"), verticalAlignment = Alignment.CenterVertically, content = actions)
                Row(Modifier.layoutId("title"), verticalAlignment = Alignment.CenterVertically) {
                    if (leading != null) { leading(); Box(Modifier.padding(start = 10.dp)) }
                    Column {
                        Text(
                            title, color = cs.onSurface, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = (34f + (21f - 34f) * fraction().coerceIn(0f, 1f)).sp, letterSpacing = (-0.4).sp
                        )
                        if (subtitle != null) {
                            Text(subtitle, color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { measurables, constraints ->
            val f = fraction().coerceIn(0f, 1f)
            val pad = 12.dp.roundToPx()
            val back = measurables.first { it.layoutId == "back" }.measure(Constraints())
            val actions = measurables.first { it.layoutId == "actions" }.measure(Constraints())
            val compactH = 64.dp.roundToPx()
            // Expanded: title on its own line under the buttons. Compact: title between them.
            val titleStartExpanded = 20.dp.roundToPx()
            val titleStartCompact = pad + back.width + 12.dp.roundToPx()
            val titleStart = (titleStartExpanded + (titleStartCompact - titleStartExpanded) * f).roundToInt()
            val titleMax = (constraints.maxWidth - titleStart - pad - (actions.width * f).roundToInt()).coerceAtLeast(0)
            val titleP = measurables.first { it.layoutId == "title" }.measure(Constraints(maxWidth = titleMax))
            val expandedH = compactH + titleP.height + 8.dp.roundToPx()
            val h = (expandedH + (compactH - expandedH) * f).roundToInt()
            layout(constraints.maxWidth, h) {
                back.place(pad, (compactH - back.height) / 2)
                actions.place(constraints.maxWidth - pad - actions.width, (compactH - actions.height) / 2)
                val yExpanded = compactH
                val yCompact = (compactH - titleP.height) / 2
                titleP.place(titleStart, (yExpanded + (yCompact - yExpanded) * f).roundToInt())
            }
        }
    }
}

/** 0 at the top of a scrolling column, 1 once it has scrolled [rangeDp]. */
@Composable
fun rememberScrollFraction(state: ScrollState, rangeDp: Float = 96f): () -> Float {
    val range = with(LocalDensity.current) { rangeDp.dp.toPx() }
    return remember(state, range) { { (state.value / range).coerceIn(0f, 1f) } }
}

/** 0 at the top of a lazy list, 1 once it has scrolled [rangeDp]. */
@Composable
fun rememberScrollFraction(state: LazyListState, rangeDp: Float = 96f): () -> Float {
    val range = with(LocalDensity.current) { rangeDp.dp.toPx() }
    return remember(state, range) {
        { if (state.firstVisibleItemIndex > 0) 1f else (state.firstVisibleItemScrollOffset / range).coerceIn(0f, 1f) }
    }
}
