package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import kotlin.math.roundToInt

@Composable
fun PortionEditorSheet(
    food: FoodItem,
    initialQuantity: Float,
    initialUnit: String,
    onConfirm: (quantity: Float, unit: String) -> Unit,
    onDismiss: () -> Unit
) {
    var quantityText by remember { mutableStateOf(if (initialQuantity % 1f == 0f) initialQuantity.toInt().toString() else initialQuantity.toString()) }
    var selectedUnit by remember { mutableStateOf(initialUnit) }

    val currentQty = quantityText.toFloatOrNull() ?: 0f
    val currentCalories = food.caloriesFor(currentQty, selectedUnit)
    val currentProtein = food.proteinFor(currentQty, selectedUnit)
    val currentCarbs = food.carbsFor(currentQty, selectedUnit)
    val currentFat = food.fatFor(currentQty, selectedUnit)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(ByteSurfaceDark)
            .padding(16.dp)
    ) {
        // Header Row: Food Name & Close
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = food.name,
                    color = ByteCream,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                food.brand?.let {
                    Text(
                        text = it,
                        color = ByteCreamMuted,
                        fontSize = 13.sp
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = ByteCream
                )
            }
        }

        // Live Calculated Calories & Macros
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ByteSurfaceVariantDark)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${currentCalories.roundToInt()} kcal",
                color = ByteTomato,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "P: ${currentProtein.roundToInt()}g",
                    color = MacroProtein,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "C: ${currentCarbs.roundToInt()}g",
                    color = MacroCarbs,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "F: ${currentFat.roundToInt()}g",
                    color = MacroFat,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Quantity Display & Unit Selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (quantityText.isEmpty()) "0" else quantityText,
                color = ByteCream,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(start = 8.dp)
            )

            // Unit Selection Pills
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("g", "oz", "serving", "ml").forEach { unit ->
                    val isSelected = selectedUnit == unit
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) ByteTomato else ByteSurfaceVariantDark)
                            .clickable { selectedUnit = unit }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = unit,
                            color = if (isSelected) ByteCream else ByteCreamMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Custom In-App Numpad
        CustomNumpad(
            currentValue = quantityText,
            onValueChange = { quantityText = it },
            onQuickAddGrams = { add ->
                val q = (quantityText.toFloatOrNull() ?: 0f) + add
                quantityText = if (q % 1f == 0f) q.toInt().toString() else q.toString()
            },
            onMultiply = { factor ->
                val q = (food.servingSize * factor)
                quantityText = if (q % 1f == 0f) q.toInt().toString() else q.toString()
            }
        )

        // Save / Set Button
        Button(
            onClick = {
                val finalQty = quantityText.toFloatOrNull() ?: food.servingSize
                onConfirm(finalQty, selectedUnit)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ByteTomato)
        ) {
            Text(
                text = "Apply Quantity",
                color = ByteOnTomato,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
