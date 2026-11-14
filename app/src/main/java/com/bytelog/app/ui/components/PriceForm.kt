package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.repository.ShoppingAmount
import com.bytelog.app.data.repository.SparkyPrice

/** What's typed into the price form. Numbers are text until saved; blank means "not set". */
data class PriceDraft(val store: String = "", val price: String = "", val quantity: String = "", val unit: String = "") {
    private fun num(s: String) = s.trim().replace(',', '.').removePrefix("$").toFloatOrNull()

    /** Why it can't be saved, or null. */
    val problem: String? get() = when {
        price.isNotBlank() && (num(price) == null || num(price)!! < 0f) -> "Enter the price as a number"
        quantity.isNotBlank() && (num(quantity) == null || num(quantity)!! <= 0f) -> "Enter how much the price buys"
        price.isNotBlank() && quantity.isNotBlank() && unit.isBlank() -> "Add a unit (oz, lb, each…)"
        else -> null
    }

    fun toPrice(): SparkyPrice = SparkyPrice(
        store = store.trim().ifBlank { null }, price = num(price),
        quantity = num(quantity), unit = unit.trim().ifBlank { null }
    )

    companion object {
        fun from(p: SparkyPrice?) = PriceDraft(
            store = p?.store.orEmpty(),
            price = p?.price?.let { String.format(java.util.Locale.US, "%.2f", it) }.orEmpty(),
            quantity = p?.quantity?.let { ShoppingAmount.trim(it.toDouble()) }.orEmpty(),
            unit = p?.unit.orEmpty()
        )
    }
}

private val commonUnits = listOf("each", "oz", "lb", "g", "kg", "fl oz", "l", "gallon", "dozen", "serving")

/**
 * Where a food is bought and what it costs: store, price, and the amount that price buys ("4.99 for 32 oz"). All
 * optional.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PriceForm(draft: PriceDraft, onChange: (PriceDraft) -> Unit, modifier: Modifier = Modifier, hint: String? = null) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        hint?.let { Text(it, color = cs.onSurfaceVariant, fontSize = 13.sp) }
        OutlinedTextField(
            value = draft.store, onValueChange = { onChange(draft.copy(store = it)) },
            label = { Text("Store") }, placeholder = { Text("e.g. Costco") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.price, onValueChange = { onChange(draft.copy(price = it)) },
                label = { Text("Price") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = draft.quantity, onValueChange = { onChange(draft.copy(quantity = it)) },
                label = { Text("For") }, placeholder = { Text("32") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier.weight(0.8f)
            )
            OutlinedTextField(
                value = draft.unit, onValueChange = { onChange(draft.copy(unit = it)) },
                label = { Text("Unit") }, placeholder = { Text("oz") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.weight(0.9f)
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            commonUnits.forEach { u ->
                val on = draft.unit.trim().equals(u, ignoreCase = true)
                Text(
                    u, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (on) cs.onPrimary else cs.primary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) cs.primary else cs.primary.copy(alpha = 0.1f))
                        .clickable(role = Role.Button) { onChange(draft.copy(unit = u)) }
                        .heightIn(min = 32.dp).padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }
        draft.problem?.let { Text(it, color = cs.error, fontSize = 12.sp) }
    }
}

/** [PriceForm] in a dialog, with save and clear. */
@Composable
fun PriceDialog(
    title: String,
    initial: SparkyPrice?,
    onSave: (SparkyPrice) -> Unit,
    onDismiss: () -> Unit,
    hint: String? = "Saved on the food in Sparky when it's one of yours. Leave anything blank you don't know."
) {
    var draft by remember { mutableStateOf(PriceDraft.from(initial)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { PriceForm(draft, { draft = it }, hint = hint) },
        confirmButton = { TextButton(onClick = { onSave(draft.toPrice()) }, enabled = draft.problem == null) { Text("Save") } },
        dismissButton = {
            Row {
                if (initial != null && !initial.isEmpty) TextButton(onClick = { onSave(SparkyPrice()) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
