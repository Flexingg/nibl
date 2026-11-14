package com.bytelog.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.repository.PriceRepository
import com.bytelog.app.data.repository.ShoppingList
import com.bytelog.app.data.repository.ValueMetric
import com.bytelog.app.ui.components.ScreenHeader
import com.bytelog.app.ui.components.ValueBar
import com.bytelog.app.ui.components.ValueMetricChips
import com.bytelog.app.ui.components.rememberScrollFraction
import com.bytelog.app.ui.logging.Portion
import com.bytelog.app.ui.theme.fluidBackdrop
import com.bytelog.app.ui.viewmodel.BestValueViewModel
import kotlin.math.roundToInt

/** Bang for your buck: your foods with prices, ranked by how much of a nutrient a dollar buys. */
@Composable
fun BestValueScreen(viewModel: BestValueViewModel, onBack: () -> Unit, onOpenFood: (String) -> Unit, onScanReceipt: () -> Unit) {
    val s by viewModel.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    Scaffold(
        modifier = Modifier.fluidBackdrop(),
        containerColor = Color.Transparent,
        topBar = { ScreenHeader(title = "Best value", subtitle = "${s.metric.label} per dollar", onBack = onBack, fraction = rememberScrollFraction(list)) }
    ) { padding ->
        LazyColumn(
            state = list, modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "metric") {
                ValueMetricChips(s.metric, { it?.let(viewModel::setMetric) }, metrics = ValueMetric.values().toList())
            }
            if (!s.loading && s.ranked.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Savings, contentDescription = null, tint = cs.tertiary, modifier = Modifier.size(32.dp))
                        Text("No prices yet", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Add prices on the shopping list or in a food, or scan a receipt, and your foods rank here by what a dollar buys.", color = cs.onSurfaceVariant, fontSize = 13.sp)
                        Text(
                            "Scan a receipt", color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onScanReceipt).padding(vertical = 8.dp)
                        )
                    }
                }
            }
            itemsIndexed(s.ranked, key = { _, v -> v.price.key }) { i, v ->
                val per = v.perDollar(s.metric)
                val id = v.food.id.takeIf { PriceRepository.isSparkyId(it) } ?: v.price.foodId?.takeIf { PriceRepository.isSparkyId(it) }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainer)
                        .clickable(enabled = id != null, role = Role.Button, onClickLabel = "Open ${v.food.name}") { id?.let(onOpenFood) }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(if (i < 3) cs.tertiary else cs.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) { Text("${i + 1}", color = if (i < 3) cs.onTertiary else cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.food.name + (v.food.brand?.let { " · $it" } ?: ""), color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    v.price.store,
                                    "${ShoppingList.money(v.costPerServing)} per ${Portion(v.food.servingSize, v.food.servingUnit).label}"
                                ).joinToString(" · "),
                                color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                if (s.metric == ValueMetric.CALORIES) "${per.roundToInt()}" else if (per >= 10) "${per.roundToInt()} g" else "%.1f g".format(per),
                                color = cs.tertiary, fontSize = 18.sp, fontWeight = FontWeight.Bold
                            )
                            Text(if (s.metric == ValueMetric.CALORIES) "kcal per $" else "per $", color = cs.onSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                    ValueBar(if (s.best > 0f) per / s.best else 0f)
                }
            }
            if (s.unpriced > 0 && s.ranked.isNotEmpty()) {
                item(key = "note") {
                    Text(
                        "${s.unpriced} price${if (s.unpriced == 1) " is" else "s are"} on foods whose nutrition isn't on this phone, so they can't be ranked.",
                        color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }
    }
}
