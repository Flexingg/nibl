package com.bytelog.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bytelog.app.data.receipts.MatchCandidate
import com.bytelog.app.data.repository.ShoppingList
import com.bytelog.app.ui.components.PriceForm
import com.bytelog.app.ui.components.ScreenHeader
import com.bytelog.app.ui.components.rememberScrollFraction
import com.bytelog.app.ui.diary.LoadingSpinner
import com.bytelog.app.ui.theme.fluidBackdrop
import com.bytelog.app.ui.viewmodel.ReceiptViewModel
import com.bytelog.app.ui.viewmodel.ReceiptViewModel.Phase
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Takes a photo of a receipt (asking for the camera first if needed) or picks one, and hands back where it is. */
class ReceiptCapture internal constructor(val takePhoto: () -> Unit, val pickFromGallery: () -> Unit)

@Composable
fun rememberReceiptCapture(onPhoto: (Uri) -> Unit, onError: (String) -> Unit): ReceiptCapture {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onPhoto)
    val latestError = rememberUpdatedState(onError)
    var pending by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> pending?.takeIf { ok }?.let { latest.value(it) } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { latest.value(it) } }
    fun launchCamera() {
        val uri = receiptUri(context)
        pending = uri
        runCatching { camera.launch(uri) }.onFailure { gallery.launch("image/*") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else latestError.value("Camera permission is needed to take a photo. You can pick one from your gallery instead.")
    }
    return remember {
        ReceiptCapture(
            takePhoto = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                else permission.launch(Manifest.permission.CAMERA)
            },
            pickFromGallery = { gallery.launch("image/*") }
        )
    }
}

private fun receiptUri(context: Context): Uri {
    val dir = File(context.cacheDir, "meal_photos").apply { mkdirs() }
    return FileProvider.getUriForFile(context, "${context.packageName}.photos", File(dir, "receipt.jpg"))
}

/**
 * Prices from a receipt: snap it, check what each line was matched to and how much its price buys, and save. The
 * text is read on the phone; nothing is sent until prices are saved to your foods.
 */
@Composable
fun ReceiptScreen(viewModel: ReceiptViewModel, onBack: () -> Unit, onShoppingList: () -> Unit) {
    val s by viewModel.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    var captureError by remember { mutableStateOf<String?>(null) }
    val capture = rememberReceiptCapture(onPhoto = { captureError = null; viewModel.read(it) }, onError = { captureError = it })

    Scaffold(
        modifier = Modifier.fluidBackdrop(),
        containerColor = Color.Transparent,
        topBar = {
            ScreenHeader(
                title = "Receipt prices",
                subtitle = when (s.phase) {
                    Phase.Review -> listOfNotNull(s.store.ifBlank { null }, s.date?.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))).joinToString(" · ").ifBlank { "Check the matches" }
                    else -> "Match a receipt to your foods"
                },
                onBack = onBack, fraction = rememberScrollFraction(list)
            )
        },
        bottomBar = {
            if (s.phase == Phase.Review) {
                val n = s.ready.size
                Column(
                    Modifier.fillMaxWidth().background(cs.surface.copy(alpha = 0.96f)).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (s.needsAttention > 0) Text("${s.needsAttention} line${if (s.needsAttention == 1) " needs" else "s need"} a look before it can be saved", color = cs.onSurfaceVariant, fontSize = 12.sp)
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(if (n > 0) cs.primary else cs.surfaceContainerHigh)
                            .clickable(enabled = n > 0, role = Role.Button) { viewModel.save() }.heightIn(min = 52.dp).padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(if (n == 0) "Nothing to save yet" else "Save $n price${if (n == 1) "" else "s"}", color = if (n > 0) cs.onPrimary else cs.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    ) { padding ->
        when (val phase = s.phase) {
            Phase.Pick -> PickPhoto(Modifier.padding(padding), s.error ?: captureError, capture)
            Phase.Reading -> Busy(Modifier.padding(padding), "Reading the receipt…")
            Phase.Saving -> Busy(Modifier.padding(padding), "Saving prices…")
            is Phase.Done -> Done(Modifier.padding(padding), phase, onShoppingList, viewModel::restart)
            Phase.Review -> LazyColumn(
                state = list, modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "store") {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(value = s.store, onValueChange = viewModel::setStore, label = { Text("Store") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${s.lines.size} line${if (s.lines.size == 1) "" else "s"} read" + (s.total?.let { " · total ${ShoppingList.money(it.toDouble())}" } ?: "") +
                                ". Check each match and how much its price buys; switch off anything that isn't food.",
                            color = cs.onSurfaceVariant, fontSize = 12.sp
                        )
                    }
                }
                items(s.lines, key = { it.id }) { line -> LineCard(line, viewModel) }
            }
        }
    }
}

@Composable
private fun PickPhoto(modifier: Modifier, error: String?, capture: ReceiptCapture) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = cs.primary, modifier = Modifier.size(36.dp))
            Text("Prices from a receipt", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Take a photo of a grocery receipt, flat and in good light. Each line is matched to your shopping list and foods, " +
                    "you check the matches, and the store and prices are saved on those foods. The receipt is read on your phone.",
                color = cs.onSurfaceVariant, fontSize = 14.sp
            )
        }
        BigButton(Icons.Default.CameraAlt, "Take a photo", primary = true, onClick = capture.takePhoto)
        BigButton(Icons.Default.PhotoLibrary, "Choose a photo", primary = false, onClick = capture.pickFromGallery)
        error?.let { Text(it, color = cs.error, fontSize = 13.sp) }
    }
}

@Composable
private fun BigButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, primary: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(if (primary) cs.primary else cs.secondaryContainer)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (primary) cs.onPrimary else cs.onSecondaryContainer)
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (primary) cs.onPrimary else cs.onSecondaryContainer, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Busy(modifier: Modifier, label: String) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LoadingSpinner(Modifier.size(32.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
        Spacer(Modifier.size(12.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

@Composable
private fun Done(modifier: Modifier, d: Phase.Done, onShoppingList: () -> Unit, onAnother: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = cs.primary, modifier = Modifier.size(36.dp))
            Text("Saved ${d.saved} price${if (d.saved == 1) "" else "s"}", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                if (d.onSparky == d.saved) "All saved on your foods in Sparky." else "${d.onSparky} saved in Sparky; the rest on this phone.",
                color = cs.onSurfaceVariant, fontSize = 14.sp
            )
            d.notes.take(6).forEach { Text("• $it", color = cs.onSurfaceVariant, fontSize = 12.sp) }
        }
        BigButton(Icons.Default.ArrowForward, "Open the shopping list", primary = true, onClick = onShoppingList)
        BigButton(Icons.Default.ReceiptLong, "Scan another receipt", primary = false, onClick = onAnother)
    }
}

@Composable
private fun LineCard(line: ReceiptViewModel.Line, viewModel: ReceiptViewModel) {
    val cs = MaterialTheme.colorScheme
    var picking by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainer).animateContentSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).alpha(if (line.include) 1f else 0.55f)) {
                Text(line.item.raw, color = cs.onSurfaceVariant, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val extra = listOfNotNull(
                    line.item.count.takeIf { it > 1 }?.let { "$it × ${ShoppingList.money(line.item.unitPrice.toDouble())}" },
                    line.item.weight?.let { w -> "${w.quantity} ${w.unit} @ ${ShoppingList.money((line.item.pricePerWeightUnit ?: 0f).toDouble())}/${w.unit}" }
                )
                Text(
                    ShoppingList.money(line.item.price.toDouble()) + if (extra.isNotEmpty()) "  ·  " + extra.joinToString(" · ") else "",
                    color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Switch(
                checked = line.include, onCheckedChange = { viewModel.include(line.id, it) },
                modifier = Modifier.semantics { contentDescription = "Save a price from ${line.item.name}" }
            )
        }
        if (line.include || line.match == null) {
            // What it was matched to; tap to change
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh)
                    .clickable(role = Role.Button, onClickLabel = "Change the food") { picking = !picking }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        line.match?.let { m -> m.name + (m.brand?.let { " · $it" } ?: "") } ?: "Which food is this?",
                        color = if (line.match == null) cs.primary else cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when { line.match == null -> "Tap to pick one, or switch the line off"; line.match.onList -> "On your shopping list"; else -> "From your foods" },
                        color = cs.onSurfaceVariant, fontSize = 12.sp
                    )
                }
                Text(if (picking) "Done" else "Change", color = cs.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (picking) FoodPicker(line, viewModel) { picking = false }
        }
        if (line.include && line.match != null && !picking) {
            PriceForm(
                line.draft, { viewModel.editDraft(line.id, it) }, line.units, showStore = false
            )
        }
        line.problem?.takeIf { line.include && !picking && line.match == null }?.let { Text(it, color = cs.error, fontSize = 12.sp) }
    }
}

@Composable
private fun FoodPicker(line: ReceiptViewModel.Line, viewModel: ReceiptViewModel, onPicked: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val options = remember(query, line.id) { viewModel.search(line.id, query) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search your foods") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        options.forEach { c: MatchCandidate ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { viewModel.choose(line.id, c); onPicked() }
                    .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(c.name + (c.brand?.let { " · $it" } ?: ""), color = cs.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.onList) Text("on list", color = cs.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (options.isEmpty()) Text("No foods of yours match.", color = cs.onSurfaceVariant, fontSize = 13.sp)
        Text(
            "Not a food: skip this line", color = cs.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { viewModel.choose(line.id, null); viewModel.include(line.id, false); onPicked() }.padding(10.dp)
        )
    }
}
