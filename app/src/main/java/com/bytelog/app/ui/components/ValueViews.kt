package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.repository.FoodValue
import com.bytelog.app.data.repository.ValueMetric

/** "24 g protein · 410 kcal per $1" with a piggy bank, small, under a food. */
@Composable
fun ValueLine(value: FoodValue, metric: ValueMetric = ValueMetric.PROTEIN, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Savings, contentDescription = null, tint = cs.tertiary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(value.summary(metric), color = cs.tertiary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A thin bar of how [fraction] of the best this is. */
@Composable
fun ValueBar(fraction: Float, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerHighest)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0.02f, 1f)).clip(RoundedCornerShape(50)).background(cs.tertiary))
    }
}

/** Chips to pick what "value" means: a nutrient per dollar (or [noneLabel], e.g. "By store"). */
@Composable
fun ValueMetricChips(
    selected: ValueMetric?,
    onPick: (ValueMetric?) -> Unit,
    modifier: Modifier = Modifier,
    noneLabel: String? = null,
    metrics: List<ValueMetric> = listOf(ValueMetric.PROTEIN, ValueMetric.CALORIES, ValueMetric.FIBER)
) {
    val cs = MaterialTheme.colorScheme
    @Composable
    fun chip(label: String, on: Boolean, onClick: () -> Unit) = Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) cs.onTertiary else cs.onSurface,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) cs.tertiary else cs.surfaceContainerHigh)
            .semantics { this.selected = on }
            .clickable(role = Role.Tab, onClick = onClick).heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 9.dp)
    )
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        noneLabel?.let { chip(it, selected == null) { onPick(null) } }
        metrics.forEach { m -> chip("${m.label} per $", selected == m) { onPick(m) } }
    }
}
