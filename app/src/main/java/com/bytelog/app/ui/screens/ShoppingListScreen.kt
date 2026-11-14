package com.bytelog.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.repository.ShoppingAmount
import com.bytelog.app.data.repository.ShoppingItem
import com.bytelog.app.data.repository.ShoppingList
import com.bytelog.app.data.repository.SparkyPrice
import com.bytelog.app.ui.components.PriceDialog
import com.bytelog.app.ui.components.ScreenHeader
import com.bytelog.app.ui.components.rememberScrollFraction
import com.bytelog.app.ui.theme.fluidBackdrop
import com.bytelog.app.ui.viewmodel.ShoppingListViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What to buy for the food planned over the next few days: one line per food with the amount needed, grouped by
 * store, with what it should cost where you've saved a price. Tick things off as they go in the cart.
 */
@Composable
fun ShoppingListScreen(viewModel: ShoppingListViewModel, onBack: () -> Unit, onScanReceipt: () -> Unit = {}) {
    val s by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<ShoppingItem?>(null) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.messageShown() } }

    editing?.let { item ->
        PriceDialog(
            title = item.name,
            initial = item.price?.toSparky(),
            units = item.units,
            onSave = { viewModel.savePrice(item, it); editing = null },
            onDismiss = { editing = null }
        )
    }

    Scaffold(
        modifier = Modifier.fluidBackdrop(),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ScreenHeader(
                title = "Shopping list", subtitle = rangeLabel(s.from, s.to), onBack = onBack, fraction = rememberScrollFraction(list),
                actions = {
                    IconButton(onClick = onScanReceipt) { Icon(Icons.Default.ReceiptLong, contentDescription = "Prices from a receipt", tint = cs.onSurface) }
                    if (s.list.items.isNotEmpty()) IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, viewModel.shareText())
                        context.startActivity(Intent.createChooser(send, "Share shopping list").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Icon(Icons.Default.Share, contentDescription = "Share the list", tint = cs.onSurface) }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = list, modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "range") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 7, 14).forEach { n -> RangeChip("$n days", s.days == n) { viewModel.setDays(n) } }
                }
            }
            if (!s.loading && s.list.items.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = cs.primary, modifier = Modifier.size(32.dp))
                        Text("Nothing planned for these days", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Plan food for the days ahead from the home screen, or ask the chat for a meal plan, and it shows up here.",
                            color = cs.onSurfaceVariant, fontSize = 13.sp
                        )
                    }
                }
            }
            if (s.list.items.isNotEmpty()) {
                item(key = "summary") { Summary(s) { viewModel.clearChecks() } }
            }
            s.list.byStore.forEach { (store, items) ->
                item(key = "store:${store ?: ""}") {
                    Row(Modifier.padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storefront, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(store ?: "No store set", color = cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        val cost = items.sumOf { it.cost ?: 0.0 }
                        if (cost > 0) Text("  ·  ${ShoppingList.money(cost)}", color = cs.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
                items.forEach { i ->
                    item(key = i.key) { ItemRow(i, i.key in s.checked, onToggle = { viewModel.toggle(i) }, onPrice = { editing = i }) }
                }
            }
        }
    }
}

private fun rangeLabel(from: String, to: String): String = runCatching {
    val f = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    "${LocalDate.parse(from).format(f)} – ${LocalDate.parse(to).format(f)}"
}.getOrDefault("")

@Composable
private fun RangeChip(label: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) cs.onPrimary else cs.onSurface,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) cs.primary else cs.surfaceContainerHigh)
            .clickable(role = Role.Tab, onClickLabel = "Show $label") { onClick() }
            .heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
private fun Summary(s: ShoppingListViewModel.State, onClearTicks: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${s.left} of ${s.list.items.size} to get", color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                val total = s.list.total
                Text(
                    when {
                        s.list.priced == 0 -> "Tap an item to add its store and price"
                        s.list.priced < s.list.items.size -> "About ${ShoppingList.money(total)} · ${s.list.items.size - s.list.priced} without a price"
                        else -> "About ${ShoppingList.money(total)}"
                    },
                    color = cs.onSurfaceVariant, fontSize = 13.sp
                )
            }
            if (s.checked.isNotEmpty()) {
                Text(
                    "Clear ticks", color = cs.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClearTicks).padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ItemRow(i: ShoppingItem, checked: Boolean, onToggle: () -> Unit, onPrice: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainer)
            .clickable(onClickLabel = "Store and price", onClick = onPrice)
            .padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Checkbox, onClick = onToggle)
                .semantics { contentDescription = "Got ${i.name}"; stateDescription = if (checked) "Ticked" else "Not ticked" },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .background(if (checked) cs.primary else Color.Transparent)
                    .border(2.dp, if (checked) cs.primary else cs.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) { if (checked) Icon(Icons.Default.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(16.dp)) }
        }
        Column(Modifier.weight(1f).alpha(if (checked) 0.55f else 1f)) {
            Text(
                i.name + (i.brand?.let { " · $it" } ?: ""),
                color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (checked) TextDecoration.LineThrough else null
            )
            val days = i.days.size
            Text(
                "${i.amountLabel} · ${if (days == 1) "1 day" else "$days days"}",
                color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val p = i.price
            when {
                i.buyLabel != null ->
                    Text(i.buyLabel!!, color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                p?.price != null ->
                    Text("${ShoppingList.money(p.price.toDouble())}${p.quantity?.let { q -> " for ${ShoppingAmount.trim(q.toDouble())} ${p.unit.orEmpty()}" } ?: ""} · amounts don't convert", color = cs.onSurfaceVariant, fontSize = 12.sp)
                else -> Text("Add price", color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        i.cost?.let { Text(ShoppingList.money(it), color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
    }
}
