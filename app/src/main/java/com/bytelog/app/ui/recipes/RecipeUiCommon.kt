package com.bytelog.app.ui.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.recipes.Macros
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

fun fmt0(v: Float): String = String.format(Locale.US, "%.0f", v)
fun fmt1(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

@Composable
fun MacroLine(m: Macros, modifier: Modifier = Modifier, size: Int = 12, showKcal: Boolean = true) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showKcal) Text("${fmt0(m.calories)} kcal", color = ByteTomato, fontSize = (size + 1).sp, fontWeight = FontWeight.Bold)
        Text("P ${fmt0(m.protein)}g", color = MacroProtein, fontSize = size.sp, fontWeight = FontWeight.SemiBold)
        Text("C ${fmt0(m.carbs)}g", color = MacroCarbs, fontSize = size.sp, fontWeight = FontWeight.SemiBold)
        Text("F ${fmt0(m.fat)}g", color = MacroFat, fontSize = size.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color else color.copy(alpha = 0.15f))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = if (filled) ByteInkDark else color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun byteFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = ByteCream,
    unfocusedTextColor = ByteCream,
    focusedBorderColor = ByteTomato,
    unfocusedBorderColor = ByteSurfaceVariantDark,
    cursorColor = ByteTomato,
    focusedLabelColor = ByteTomato,
    unfocusedLabelColor = ByteTextSecondary
)

/** Search dialog for choosing a food: local library first, then Sparky and OpenFoodFacts. */
@Composable
fun FoodPickerDialog(
    initialQuery: String,
    suggestions: List<FoodItem>,
    search: suspend (String) -> List<FoodItem>,
    onPick: (FoodItem) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf(suggestions) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    fun runSearch(q: String, debounce: Boolean) {
        job?.cancel()
        if (q.isBlank()) return
        job = scope.launch {
            if (debounce) delay(450)
            loading = true
            results = search(q)
            loading = false
        }
    }

    LaunchedEffect(Unit) { if (suggestions.isEmpty()) runSearch(initialQuery, debounce = false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ByteSurfaceDark,
        title = { Text("Choose food", color = ByteCream, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; runSearch(it, debounce = true) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = ByteTextSecondary) },
                    trailingIcon = { if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = ByteTomato, strokeWidth = 2.dp) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch(query, debounce = false) }),
                    colors = byteFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    if (results.isEmpty() && !loading) {
                        item {
                            Text(
                                if (query.isBlank()) "Type to search your foods, Sparky and OpenFoodFacts." else "No foods found. Try a shorter name.",
                                color = ByteTextSecondary, fontSize = 13.sp, modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    items(results, key = { it.id }) { food ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPick(food) }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            Text(food.name, color = ByteCream, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(food.brand, "${fmt1(food.servingSize)} ${food.servingUnit}", "${fmt0(food.calories)} kcal").joinToString(" · "),
                                color = ByteTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ByteTextSecondary) } }
    )
}
