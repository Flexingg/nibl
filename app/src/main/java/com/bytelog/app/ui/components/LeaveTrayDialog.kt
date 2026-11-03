package com.bytelog.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Asked when leaving the add screens with food still in the tray: log it, drop it, or carry on adding. */
@Composable
fun LeaveTrayDialog(count: Int, verb: String, mealName: String, onLog: () -> Unit, onDiscard: () -> Unit, onKeepAdding: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepAdding,
        title = { Text(if (count == 1) "1 food in your tray" else "$count foods in your tray") },
        text = { Text("Log ${if (count == 1) "it" else "them"} to $mealName, or leave without? Tap outside to keep adding.") },
        confirmButton = { TextButton(onClick = onLog) { Text("$verb to $mealName") } },
        dismissButton = { TextButton(onClick = onDiscard) { Text("Discard") } }
    )
}
