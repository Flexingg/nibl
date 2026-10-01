package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.ui.theme.ByteCardDark
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroProtein
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopyMealDialog(
    currentMealName: String,
    currentDate: String,
    mealTypes: List<MealType>,
    onDismiss: () -> Unit,
    onCopyFromYesterday: (mealType: String) -> Unit,
    onCopyCustom: (sourceDate: String, sourceMeal: String, targetDate: String, targetMeal: String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val yesterday = remember {
        try {
            LocalDate.parse(currentDate).minusDays(1).toString()
        } catch (_: Exception) {
            LocalDate.now().minusDays(1).toString()
        }
    }

    var isCustomMode by remember { mutableStateOf(false) }
    var sourceDateText by remember { mutableStateOf(yesterday) }
    var selectedSourceMeal by remember { mutableStateOf(currentMealName) }
    var mealMenuExpanded by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = ByteCardDark,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = MacroProtein,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Copy $currentMealName",
                            color = ByteCream,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = ByteTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (!isCustomMode) {
                    // Quick Action: Copy from Yesterday
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCopyFromYesterday(currentMealName)
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MacroProtein)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = ByteCardDark,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Copy Yesterday's $currentMealName",
                            color = ByteCardDark,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Secondary: Custom Date / Meal
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ByteSurfaceVariantDark.copy(alpha = 0.6f))
                            .clickable { isCustomMode = true }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Copy from another date or meal...",
                            color = ByteCream,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    // Custom copy mode
                    Text(
                        text = "Source Date (YYYY-MM-DD):",
                        color = ByteTextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = sourceDateText,
                        onValueChange = { sourceDateText = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ByteCream,
                            unfocusedTextColor = ByteCream,
                            focusedBorderColor = MacroProtein,
                            unfocusedBorderColor = ByteSurfaceVariantDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Source Meal:",
                        color = ByteTextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    ExposedDropdownMenuBox(
                        expanded = mealMenuExpanded,
                        onExpandedChange = { mealMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedSourceMeal,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mealMenuExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = ByteCream,
                                unfocusedTextColor = ByteCream,
                                focusedBorderColor = MacroProtein,
                                unfocusedBorderColor = ByteSurfaceVariantDark
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )

                        ExposedDropdownMenu(
                            expanded = mealMenuExpanded,
                            onDismissRequest = { mealMenuExpanded = false },
                            modifier = Modifier.background(ByteCardDark)
                        ) {
                            mealTypes.forEach { meal ->
                                DropdownMenuItem(
                                    text = { Text(meal.name, color = ByteCream) },
                                    onClick = {
                                        selectedSourceMeal = meal.name
                                        mealMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCopyCustom(sourceDateText.trim(), selectedSourceMeal, currentDate, currentMealName)
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ByteTomato)
                    ) {
                        Text(
                            text = "Copy Into $currentMealName",
                            color = ByteCream,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
