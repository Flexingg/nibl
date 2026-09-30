package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import java.util.UUID

@Composable
fun VoiceLogDialog(
    mealTypeId: String,
    mealName: String,
    date: String,
    onDismiss: () -> Unit,
    onParsedEntries: (List<FoodEntry>) -> Unit
) {
    var rawText by remember { mutableStateOf("") }

    fun parseIngredients(input: String): List<FoodEntry> {
        val lines = input.split(",", "\n", ";").map { it.trim() }.filter { it.isNotEmpty() }
        val regex = Regex("""^(\d+(?:\.\d+)?)\s*([a-zA-Z]+)?\s+(?:of\s+)?(.+)$""")

        return lines.map { line ->
            val match = regex.find(line)
            val qty = match?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
            val rawUnit = match?.groupValues?.get(2)?.lowercase() ?: "serving"
            val rawName = match?.groupValues?.get(3) ?: line

            val unit = when (rawUnit) {
                "g", "gram", "grams" -> "g"
                "oz", "ounce", "ounces" -> "oz"
                "ml", "milliliter", "milliliters" -> "ml"
                "cup", "cups" -> "cup"
                "slice", "slices" -> "slice"
                "egg", "eggs" -> "egg"
                "scoop", "scoops" -> "scoop"
                else -> rawUnit
            }

            // Approximate estimate for quick staging
            val estimatedCalories = when {
                rawName.contains("egg", ignoreCase = true) -> qty * 72f
                rawName.contains("oat", ignoreCase = true) -> if (unit == "g") qty * 3.75f else qty * 150f
                rawName.contains("yogurt", ignoreCase = true) -> if (unit == "g") qty * 0.6f else qty * 100f
                rawName.contains("chicken", ignoreCase = true) -> if (unit == "g") qty * 1.65f else qty * 200f
                rawName.contains("rice", ignoreCase = true) -> if (unit == "g") qty * 1.3f else qty * 195f
                rawName.contains("whey", ignoreCase = true) || rawName.contains("protein", ignoreCase = true) -> qty * 120f
                rawName.contains("banana", ignoreCase = true) -> qty * 105f
                rawName.contains("bread", ignoreCase = true) -> qty * 80f
                rawName.contains("butter", ignoreCase = true) -> qty * 100f
                else -> qty * 100f
            }

            FoodEntry(
                id = UUID.randomUUID().toString(),
                foodName = rawName.replaceFirstChar { it.uppercase() },
                mealTypeId = mealTypeId,
                mealName = mealName,
                quantity = qty,
                unit = unit,
                calories = estimatedCalories,
                protein = (estimatedCalories * 0.25f / 4f),
                carbs = (estimatedCalories * 0.50f / 4f),
                fat = (estimatedCalories * 0.25f / 9f),
                entryDate = date,
                syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT
            )
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ByteSurfaceDark),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Natural Language & Dictation",
                    color = ByteCream,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Text(
                    text = "Type or dictate items separated by commas:\nExample: 3 eggs, 40g oats, 1 scoop whey, 1 banana",
                    color = ByteCreamMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    placeholder = { Text("3 eggs, 2 slices toast, 15g butter…", color = ByteCreamMuted) },
                    maxLines = 4,
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        focusedBorderColor = ByteTomato,
                        unfocusedBorderColor = ByteSurfaceVariantDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

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
                            if (rawText.isNotBlank()) {
                                val entries = parseIngredients(rawText)
                                onParsedEntries(entries)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ByteTomato),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Stage in Tray", color = ByteCream, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
