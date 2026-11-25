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
import com.bytelog.app.ui.theme.GlassLevel
import com.bytelog.app.ui.theme.glass
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
    /** How far the page has scrolled (0 = top, 1 = folded). Null for a header that stays as it is. */
    fraction: (() -> Float)? = null,
    statusBarPadding: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)) {
        Layout(
            content = {
                Box(Modifier.layoutId("back")) {
                    MorphButton(onBack, Modifier.size(40.dp).glass(GlassLevel.Thin, androidx.compose.foundation.shape.CircleShape), androidx.compose.ui.graphics.Color.Transparent, description = "Back") {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = cs.onSurface)
                    }
                }
                Row(Modifier.layoutId("actions"), verticalAlignment = Alignment.CenterVertically, content = actions)
                Row(Modifier.layoutId("title"), verticalAlignment = Alignment.CenterVertically) {
                    if (leading != null) { leading(); Box(Modifier.padding(start = 10.dp)) }
                    Column {
                        Text(
                            title, color = cs.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = (28f + (19f - 28f) * (fraction?.invoke() ?: 1f).coerceIn(0f, 1f)).sp, letterSpacing = (-0.3).sp
                        )
                        // On a page that scrolls, the subtitle fades out as the header folds and is gone once it's a
                        // slim bar; a header that doesn't fold keeps it
                        val f = fraction?.invoke()?.coerceIn(0f, 1f) ?: 0f
                        if (subtitle != null && f < 0.95f) {
                            Text(
                                subtitle, color = cs.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.graphicsLayer { alpha = (1f - f * 1.6f).coerceIn(0f, 1f) }
                            )
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { measurables, constraints ->
            val f = (fraction?.invoke() ?: 1f).coerceIn(0f, 1f)
            val pad = 12.dp.roundToPx()
            val back = measurables.first { it.layoutId == "back" }.measure(Constraints())
            val actions = measurables.first { it.layoutId == "actions" }.measure(Constraints())
            val compactH = 56.dp.roundToPx()
            // Expanded: title on its own line under the buttons. Compact: title between them.
            val titleStartExpanded = 18.dp.roundToPx()
            val titleStartCompact = pad + back.width + 12.dp.roundToPx()
            val titleStart = (titleStartExpanded + (titleStartCompact - titleStartExpanded) * f).roundToInt()
            val titleMax = (constraints.maxWidth - titleStart - pad - (actions.width * f).roundToInt()).coerceAtLeast(0)
            val titleP = measurables.first { it.layoutId == "title" }.measure(Constraints(maxWidth = titleMax))
            val expandedH = compactH + titleP.height + 4.dp.roundToPx()
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
fun rememberScrollFraction(state: ScrollState, rangeDp: Float = 72f): () -> Float {
    val range = with(LocalDensity.current) { rangeDp.dp.toPx() }
    return remember(state, range) { { (state.value / range).coerceIn(0f, 1f) } }
}

/** 0 at the top of a lazy list, 1 once it has scrolled [rangeDp]. */
@Composable
fun rememberScrollFraction(state: LazyListState, rangeDp: Float = 72f): () -> Float {
    val range = with(LocalDensity.current) { rangeDp.dp.toPx() }
    return remember(state, range) {
        { if (state.firstVisibleItemIndex > 0) 1f else (state.firstVisibleItemScrollOffset / range).coerceIn(0f, 1f) }
    }
}
