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
data class PriceDraft(
    val store: String = "",
    val price: String = "",
    val quantity: String = "",
    val unit: String = "",
    /** What it comes in when that isn't one of the food's units ("tub"); [quantity] [unit] is one of them. */
    val packageName: String = ""
) {
    private fun num(s: String) = s.trim().replace(',', '.').removePrefix("$").toFloatOrNull()

    /**
     * Why it can't be saved, or null. The amount has to be in one of [units] (the food's own), so the price always
     * converts to what's planned; something else ("tub") is a package of a known amount.
     */
    fun problem(units: List<String>): String? = when {
        price.isNotBlank() && (num(price) == null || num(price)!! < 0f) -> "Enter the price as a number"
        quantity.isNotBlank() && (num(quantity) == null || num(quantity)!! <= 0f) -> "Enter how much the price buys"
        price.isNotBlank() && quantity.isBlank() -> "How much does that price buy?"
        quantity.isNotBlank() && unit.isBlank() -> "Pick a unit"
        unit.isNotBlank() && units.isNotEmpty() && !inUnits(unit, units) ->
            "\"$unit\" isn't one of this food's units. Pick one, or name the package and say how much it holds."
        packageName.isNotBlank() && quantity.isBlank() -> "How much is in one $packageName?"
        else -> null
    }

    fun toPrice(): SparkyPrice = SparkyPrice(
        store = store.trim().ifBlank { null }, price = num(price),
        quantity = num(quantity), unit = unit.trim().ifBlank { null }, packageName = packageName.trim().ifBlank { null }
    )

    companion object {
        fun inUnits(unit: String, units: List<String>): Boolean {
            fun key(u: String) = com.bytelog.app.recipes.UnitConverter.canonical(u) ?: com.bytelog.app.data.repository.ShoppingList.normalUnit(u)
            return units.any { key(it) == key(unit) }
        }

        fun from(p: SparkyPrice?) = PriceDraft(
            store = p?.store.orEmpty(),
            price = p?.price?.let { String.format(java.util.Locale.US, "%.2f", it) }.orEmpty(),
            quantity = p?.quantity?.let { ShoppingAmount.trim(it.toDouble()) }.orEmpty(),
            unit = p?.unit.orEmpty(),
            packageName = p?.packageName.orEmpty()
        )
    }
}

/**
 * Where a food is bought and what it costs: store, price, and the amount that price buys, in one of the food's own
 * [units] ("4.99 for 32 oz"). A package that isn't one of its units ("tub") is named and given as an amount of one
 * that is, so it always converts. All optional.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PriceForm(draft: PriceDraft, onChange: (PriceDraft) -> Unit, units: List<String>, modifier: Modifier = Modifier, hint: String? = null) {
    val cs = MaterialTheme.colorScheme
    var asPackage by remember { mutableStateOf(draft.packageName.isNotBlank()) }
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
                label = { Text(if (asPackage && draft.packageName.isNotBlank()) "One ${draft.packageName} holds" else "For") },
                placeholder = { Text("32") }, singleLine = true,
                suffix = { if (draft.unit.isNotBlank()) Text(draft.unit) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1.3f)
            )
        }
        Text("Unit (this food's own)", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            units.forEach { u ->
                val on = draft.unit.isNotBlank() && PriceDraft.inUnits(draft.unit, listOf(u))
                UnitChip(u, on) { onChange(draft.copy(unit = u)) }
            }
            UnitChip(if (asPackage) "Package ✓" else "+ Package (tub, bag…)", asPackage) {
                asPackage = !asPackage
                if (!asPackage) onChange(draft.copy(packageName = ""))
            }
        }
        if (asPackage) {
            OutlinedTextField(
                value = draft.packageName, onValueChange = { onChange(draft.copy(packageName = it)) },
                label = { Text("Package name") }, placeholder = { Text("tub") }, singleLine = true,
                supportingText = { Text("Then say how much one holds, in one of the units above, so it converts.") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
        }
        draft.problem(units)?.let { Text(it, color = cs.error, fontSize = 12.sp) }
    }
}

@Composable
private fun UnitChip(label: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = if (on) cs.onPrimary else cs.primary,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) cs.primary else cs.primary.copy(alpha = 0.1f))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 9.dp)
    )
}

/** [PriceForm] in a dialog, with save and clear. */
@Composable
fun PriceDialog(
    title: String,
    initial: SparkyPrice?,
    units: List<String>,
    onSave: (SparkyPrice) -> Unit,
    onDismiss: () -> Unit,
    hint: String? = "Saved on the food in Sparky when it's one of yours. Leave anything blank you don't know."
) {
    var draft by remember { mutableStateOf(PriceDraft.from(initial)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { PriceForm(draft, { draft = it }, units, hint = hint) },
        confirmButton = { TextButton(onClick = { onSave(draft.toPrice()) }, enabled = draft.problem(units) == null) { Text("Save") } },
        dismissButton = {
            Row {
                if (initial != null && !initial.isEmpty) TextButton(onClick = { onSave(SparkyPrice()) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
