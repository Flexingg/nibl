package com.bytelog.app.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.reports.MicronutrientCatalog
import com.bytelog.app.data.reports.ReportCustomization
import com.bytelog.app.data.reports.ReportRange
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.FluidRadius
import com.bytelog.app.ui.theme.GlassLevel
import com.bytelog.app.ui.theme.glass

/**
 * Bottom-sheet content for customizing Nibl's Reports screen:
 * default range, section visibility & ordering, and pinned micronutrients.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomizeReportsSheet(
    saved: ReportCustomization,
    onChange: (ReportCustomization) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var config by remember(saved) { mutableStateOf(saved) }

    fun update(next: ReportCustomization) {
        config = next
        onChange(next)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Customize reports", color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Reorder cards, hide what you don't need, and pin micronutrients.", color = cs.onSurfaceVariant, fontSize = 13.sp)
            }
            if (!config.isDefault) {
                Text(
                    "Reset",
                    color = cs.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { update(ReportCustomization.DEFAULT) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // Default range picker
        Text("Default range", color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FluidRadius.md))
                .background(ByteSurfaceVariantDark)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            ReportRange.entries.forEach { range ->
                val selected = config.defaultRange == range
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(FluidRadius.sm))
                        .background(if (selected) ByteTomato else Color.Transparent)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            update(config.copy(defaultRange = range))
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = range.label,
                        color = if (selected) ByteOnTomato else ByteTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
        ) {
            item(key = "sections_header") {
                Text("Report sections", color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            itemsIndexed(config.order, key = { _, s -> s.name }) { index, section ->
                val shown = section !in config.hidden
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(GlassLevel.Thin, RoundedCornerShape(FluidRadius.md))
                        .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                update(config.move(index, index - 1))
                            },
                            enabled = index > 0,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "Move ${section.title} up",
                                tint = if (index > 0) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                update(config.move(index, index + 1))
                            },
                            enabled = index < config.order.lastIndex,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Move ${section.title} down",
                                tint = if (index < config.order.lastIndex) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            text = section.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (shown) cs.onSurface else cs.onSurfaceVariant
                        )
                        Text(
                            text = section.description,
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Switch(
                        checked = shown,
                        onCheckedChange = { on -> update(config.toggleSection(section, on)) }
                    )
                }
            }

            item(key = "micros_header") {
                Spacer(Modifier.height(6.dp))
                Text("Pinned micronutrients", color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            item(key = "micros_chips") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MicronutrientCatalog.ALL.forEach { def ->
                        val selected = def.key in config.pinnedMicronutrients
                        FilterChip(
                            selected = selected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                update(config.toggleMicronutrient(def.key))
                            },
                            label = { Text(def.label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ByteTomato.copy(alpha = 0.2f),
                                selectedLabelColor = ByteTomato
                            )
                        )
                    }
                }
            }
        }
    }
}
