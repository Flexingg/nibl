package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun QuickAddDialog(
    mealTypeId: String,
    mealName: String,
    date: String,
    onDismiss: () -> Unit,
    onConfirm: (FoodEntry) -> Unit
) {
    var name by remember { mutableStateOf("Quick Calorie Entry") }
    var caloriesText by remember { mutableStateOf("") }
    var proteinText by remember { mutableStateOf("") }
    var carbsText by remember { mutableStateOf("") }
    var fatText by remember { mutableStateOf("") }

    // Auto-balance calories if P, C, F are entered
    fun updateCalculatedCalories() {
        val p = proteinText.toFloatOrNull() ?: 0f
        val c = carbsText.toFloatOrNull() ?: 0f
        val f = fatText.toFloatOrNull() ?: 0f
        if (p > 0 || c > 0 || f > 0) {
            val autoCal = (p * 4f) + (c * 4f) + (f * 9f)
            caloriesText = autoCal.roundToInt().toString()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = ByteSurfaceDark),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Quick Macro Add",
                    color = ByteCream,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "Logging to $mealName",
                    color = ByteCreamMuted,
                    fontSize = 13.sp
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Description", color = ByteCreamMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = ByteTomato,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Calories Input
                OutlinedTextField(
                    value = caloriesText,
                    onValueChange = { caloriesText = it },
                    label = { Text("Calories (kcal)", color = ByteTomato) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = ByteTomato,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Macros row: P, C, F
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = proteinText,
                        onValueChange = {
                            proteinText = it
                            updateCalculatedCalories()
                        },
                        label = { Text("Prot (g)", color = MacroProtein, fontSize = 11.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ByteCream,
                            unfocusedTextColor = ByteCream,
                            focusedBorderColor = MacroProtein,
                            unfocusedBorderColor = ByteSurfaceVariantDark
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = carbsText,
                        onValueChange = {
                            carbsText = it
                            updateCalculatedCalories()
                        },
                        label = { Text("Carb (g)", color = MacroCarbs, fontSize = 11.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ByteCream,
                            unfocusedTextColor = ByteCream,
                            focusedBorderColor = MacroCarbs,
                            unfocusedBorderColor = ByteSurfaceVariantDark
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = fatText,
                        onValueChange = {
                            fatText = it
                            updateCalculatedCalories()
                        },
                        label = { Text("Fat (g)", color = MacroFat, fontSize = 11.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ByteCream,
                            unfocusedTextColor = ByteCream,
                            focusedBorderColor = MacroFat,
                            unfocusedBorderColor = ByteSurfaceVariantDark
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = ByteCreamMuted)
                    }

                    Button(
                        onClick = {
                            val cals = caloriesText.toFloatOrNull() ?: 0f
                            val p = proteinText.toFloatOrNull() ?: 0f
                            val c = carbsText.toFloatOrNull() ?: 0f
                            val f = fatText.toFloatOrNull() ?: 0f

                            val entry = FoodEntry(
                                id = UUID.randomUUID().toString(),
                                foodName = if (name.isBlank()) "Quick Add" else name,
                                mealTypeId = mealTypeId,
                                mealName = mealName,
                                quantity = 1f,
                                unit = "serving",
                                calories = cals,
                                protein = p,
                                carbs = c,
                                fat = f,
                                entryDate = date,
                                syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT
                            )
                            onConfirm(entry)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ByteTomato),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text("Add Entry", color = ByteOnTomato, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
