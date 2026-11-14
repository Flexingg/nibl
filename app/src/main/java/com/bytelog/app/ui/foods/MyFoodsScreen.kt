package com.bytelog.app.ui.foods

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.bytelog.app.ui.theme.fluidBackdrop
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.ui.components.ScreenHeader
import com.bytelog.app.ui.components.popIn
import com.bytelog.app.ui.components.pressBounce
import com.bytelog.app.ui.components.rememberScrollFraction
import com.bytelog.app.ui.diary.LoadingSpinner
import com.bytelog.app.ui.viewmodel.FoodForm
import com.bytelog.app.ui.viewmodel.MyFoodsViewModel
import kotlin.math.roundToInt

/** The user's own foods on Sparky. Tap one to edit it. */
@Composable
fun MyFoodsScreen(viewModel: MyFoodsViewModel, onBack: () -> Unit, onOpenFood: (String) -> Unit, onBestValue: () -> Unit = {}) {
    val s by viewModel.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    val popped = remember { mutableSetOf<Any>() }

    Scaffold(
        modifier = Modifier.fluidBackdrop(),
        containerColor = Color.Transparent,
        topBar = {
            ScreenHeader(
                title = "My foods", subtitle = "Your Sparky library", onBack = onBack, fraction = rememberScrollFraction(list),
                actions = { androidx.compose.material3.IconButton(onClick = onBestValue) { Icon(Icons.Default.Savings, contentDescription = "Best value foods", tint = cs.onSurface) } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(52.dp)
                    .clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, tint = cs.primary)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (s.query.isEmpty()) Text("Search your foods…", color = cs.onSurfaceVariant, fontSize = 16.sp)
                    BasicTextField(
                        value = s.query, onValueChange = viewModel::setQuery, singleLine = true,
                        textStyle = TextStyle(color = cs.onSurface, fontSize = 16.sp), cursorBrush = SolidColor(cs.primary),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search your foods" }
                    )
                }
                if (s.loading) LoadingSpinner(Modifier.size(18.dp), color = cs.primary, strokeWidth = 2.dp)
            }

            when {
                s.error != null && s.foods.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Text("Couldn't reach Sparky", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(s.error.orEmpty(), color = cs.onSurfaceVariant, fontSize = 13.sp)
                    TextButton(onClick = { viewModel.refresh() }) { Text("Try again") }
                }
                !s.loading && s.foods.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (s.query.isBlank()) "Foods you create or save show up here." else "No foods of yours match \"${s.query}\".",
                        color = cs.onSurfaceVariant, fontSize = 15.sp
                    )
                }
                else -> LazyColumn(state = list, contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    itemsIndexed(s.foods, key = { _, f -> f.id }) { i, food ->
                        val outer = 24.dp; val inner = 6.dp
                        val shape = RoundedCornerShape(
                            topStart = if (i == 0) outer else inner, topEnd = if (i == 0) outer else inner,
                            bottomStart = if (i == s.foods.lastIndex) outer else inner, bottomEnd = if (i == s.foods.lastIndex) outer else inner
                        )
                        Row(
                            Modifier.popIn(food.id, popped, (i.coerceAtMost(8) * 25).toLong()).fillMaxWidth().pressBounce(0.98f)
                                .clip(shape).background(cs.surfaceContainer).clickable { onOpenFood(food.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(food.name, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(food.brand, "${FoodForm.num(food.nutrients.servingSize)} ${food.nutrients.servingUnit}").joinToString(" • ") +
                                        "  ·  P ${food.nutrients.protein.roundToInt()} C ${food.nutrients.carbs.roundToInt()} F ${food.nutrients.fat.roundToInt()}",
                                    color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text("${food.nutrients.calories.roundToInt()} kcal", color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Icon(Icons.Default.ChevronRight, null, tint = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
