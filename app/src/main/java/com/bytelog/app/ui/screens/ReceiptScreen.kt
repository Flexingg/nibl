package com.bytelog.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import kotlinx.coroutines.launch
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Which line the barcode scanner or label photo is for, and the line last brought into view
    var barcodeFor by remember { mutableStateOf<Int?>(null) }
    var labelFor by remember { mutableStateOf<Int?>(null) }
    var focused by remember { mutableStateOf<Int?>(null) }
    val labelCapture = com.bytelog.app.ui.logging.rememberMealPhotoCapture(
        onPhoto = { bmp -> labelFor?.let { viewModel.scanLabel(it, bmp) }; labelFor = null },
        onError = { captureError = it }
    )
    fun goToNext() {
        val id = viewModel.nextToCheck(focused) ?: return
        focused = id
        val index = s.lines.indexOfFirst { it.id == id }
        if (index >= 0) scope.launch { list.animateScrollToItem(index + 1) }
    }
    val actions = LineActions(
        onScanBarcode = { barcodeFor = it },
        onScanLabel = { labelFor = it; labelCapture.takePhoto() }
    )

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
                    if (s.needsAttention > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${s.needsAttention} line${if (s.needsAttention == 1) " needs" else "s need"} you before ${if (s.needsAttention == 1) "it" else "they"} can be saved",
                            color = cs.error, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                        )
                        Text(
                            "Next to check", color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { goToNext() }.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
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
                items(s.lines, key = { it.id }) { line -> LineCard(line, viewModel, actions, highlighted = focused == line.id) }
            }
        }
    }
    barcodeFor?.let { id ->
        com.bytelog.app.ui.components.BarcodeScanOverlay(
            title = "Scan the barcode of ${s.lines.firstOrNull { it.id == id }?.item?.name ?: "the product"}",
            onCode = { code -> barcodeFor = null; viewModel.scanBarcode(id, code) },
            onClose = { barcodeFor = null }
        )
    }
}

/** What a line can ask for: the product's barcode, or a photo of its nutrition label. */
private class LineActions(val onScanBarcode: (Int) -> Unit, val onScanLabel: (Int) -> Unit)

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
private fun LineCard(line: ReceiptViewModel.Line, viewModel: ReceiptViewModel, actions: LineActions, highlighted: Boolean) {
    val cs = MaterialTheme.colorScheme
    val search by viewModel.search.collectAsState()
    val picking = search?.lineId == line.id
    var creating by remember { mutableStateOf<ReceiptViewModel.NewFood?>(null) }
    fun togglePicker() { creating = null; if (picking) viewModel.closeSearch() else viewModel.startSearch(line.id) }
    fun startCreating() { viewModel.closeSearch(); creating = viewModel.newFoodFor(line.id) }
    val needsYou = line.include && line.problem != null
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainer)
            .then(if (needsYou || highlighted) Modifier.border(2.dp, if (needsYou) cs.error.copy(alpha = 0.7f) else cs.primary, RoundedCornerShape(20.dp)) else Modifier)
            .animateContentSize().padding(14.dp),
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
        line.busy?.let { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingSpinner(Modifier.size(16.dp), color = cs.primary, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(b, color = cs.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        line.note?.let { Text(it, color = cs.error, fontSize = 12.sp) }
        if (line.busy == null && (line.include || line.match == null)) {
            when {
                // Nothing matched: ask, with every way to say what it is
                creating != null -> {}
                line.match == null && !picking -> {
                    Prompt("Which food is this?")
                    ActionRow {
                        Action("Find a food", primary = true) { togglePicker() }
                        Action("Create a food") { startCreating() }
                        Action("Scan barcode") { actions.onScanBarcode(line.id) }
                        Action("Scan label") { actions.onScanLabel(line.id) }
                        if (line.include) Action("Not food") { viewModel.include(line.id, false) }
                    }
                }
                // A match that isn't a clear one: ask before using it
                line.unsure && !picking -> {
                    MatchRow(line) { togglePicker() }
                    Prompt("Is this ${line.match!!.name}?")
                    ActionRow {
                        Action("Yes", primary = true) { viewModel.confirm(line.id) }
                        Action("Change") { togglePicker() }
                        Action("Scan barcode") { actions.onScanBarcode(line.id) }
                    }
                }
                else -> MatchRow(line, picking) { togglePicker() }
            }
            if (picking) search?.let { st ->
                FoodPicker(line, st, viewModel, onCreate = { startCreating() })
                ActionRow {
                    Action("Scan barcode") { viewModel.closeSearch(); actions.onScanBarcode(line.id) }
                    Action("Scan label") { viewModel.closeSearch(); actions.onScanLabel(line.id) }
                }
            }
            creating?.let { draft ->
                CreateFood(
                    draft, onChange = { creating = it },
                    onSave = { viewModel.createFood(line.id, draft); creating = null },
                    onAi = { viewModel.createWithAi(line.id, draft.name); creating = null },
                    onLabel = { creating = null; actions.onScanLabel(line.id) },
                    onCancel = { creating = null }
                )
            }
        }
        if (line.include && line.match != null && !line.unsure && !picking && creating == null && line.busy == null) {
            line.problem?.let { Prompt(it) }
            PriceForm(line.draft, { viewModel.editDraft(line.id, it) }, line.units, showStore = false)
        }
    }
}

@Composable
private fun Prompt(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(text, color = cs.error, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun MatchRow(line: ReceiptViewModel.Line, picking: Boolean = false, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val m = line.match ?: return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh)
            .clickable(role = Role.Button, onClickLabel = "Change the food", onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.name + (m.brand?.let { " · $it" } ?: ""), color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(if (m.onList) "On your shopping list" else "From your foods", color = cs.onSurfaceVariant, fontSize = 12.sp)
        }
        Text(if (picking) "Done" else "Change", color = cs.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Action(label: String, primary: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (primary) cs.onPrimary else cs.primary,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (primary) cs.primary else cs.primary.copy(alpha = 0.1f))
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
private fun FoodPicker(line: ReceiptViewModel.Line, st: ReceiptViewModel.FoodSearchState, viewModel: ReceiptViewModel, onCreate: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = st.query, onValueChange = { viewModel.setQuery(line.id, it) },
            label = { Text("Search foods") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (st.searchingOnline) LoadingSpinner(Modifier.size(18.dp), color = cs.primary, strokeWidth = 2.dp) }
        )
        if (st.mine.isNotEmpty()) SectionLabel("Your foods")
        st.mine.forEach { c -> PickRow(c) { viewModel.pick(line.id, c) } }
        if (st.online.isNotEmpty()) SectionLabel("Sparky & Open Food Facts")
        st.online.forEach { c -> PickRow(c) { viewModel.pick(line.id, c) } }
        if (st.mine.isEmpty() && st.online.isEmpty() && !st.searchingOnline) {
            Text(if (st.query.isBlank()) "Type to search your foods and online." else "Nothing matches \"${st.query}\".", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(4.dp))
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.primary.copy(alpha = 0.08f))
                .clickable(role = Role.Button, onClick = onCreate).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(androidx.compose.material.icons.Icons.Default.Add, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Create \"${st.query.ifBlank { line.item.name }}\" as a new food", color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            "Not a food: skip this line", color = cs.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { viewModel.closeSearch(); viewModel.choose(line.id, null); viewModel.include(line.id, false) }.padding(10.dp)
        )
    }
}

@Composable
private fun SectionLabel(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 6.dp))

@Composable
private fun PickRow(c: MatchCandidate, onPick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onPick)
            .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.name + (c.brand?.let { " · $it" } ?: ""), color = cs.onSurface, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            c.food?.takeIf { it.calories > 0f }?.let { f ->
                Text("${com.bytelog.app.ui.logging.Portion(f.servingSize, f.servingUnit).label} · ${f.calories.toInt()} kcal · P ${f.protein.toInt()} g", color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
            }
        }
        if (c.onList) Text("on list", color = cs.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A new food for a receipt line: typed in, made by Sparky's AI from its name, or read from its label. */
@Composable
private fun CreateFood(
    f: ReceiptViewModel.NewFood,
    onChange: (ReceiptViewModel.NewFood) -> Unit,
    onSave: () -> Unit,
    onAi: () -> Unit,
    onLabel: () -> Unit,
    onCancel: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    @Composable
    fun field(label: String, value: String, modifier: Modifier, number: Boolean = false, set: (String) -> Unit) = OutlinedTextField(
        value = value, onValueChange = set, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (number) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text,
            capitalization = if (number) androidx.compose.ui.text.input.KeyboardCapitalization.None else androidx.compose.ui.text.input.KeyboardCapitalization.Words
        )
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("New food", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        field("Name", f.name, Modifier.fillMaxWidth()) { onChange(f.copy(name = it)) }
        field("Brand (optional)", f.brand, Modifier.fillMaxWidth()) { onChange(f.copy(brand = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            field("Serving", f.servingSize, Modifier.weight(1f), number = true) { onChange(f.copy(servingSize = it)) }
            field("Unit", f.servingUnit, Modifier.weight(1f)) { onChange(f.copy(servingUnit = it)) }
            field("Calories", f.calories, Modifier.weight(1.1f), number = true) { onChange(f.copy(calories = it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            field("Protein g", f.protein, Modifier.weight(1f), number = true) { onChange(f.copy(protein = it)) }
            field("Carbs g", f.carbs, Modifier.weight(1f), number = true) { onChange(f.copy(carbs = it)) }
            field("Fat g", f.fat, Modifier.weight(1f), number = true) { onChange(f.copy(fat = it)) }
        }
        f.problem?.takeIf { f.calories.isNotBlank() || f.name.isBlank() }?.let { Text(it, color = cs.error, fontSize = 12.sp) }
        ActionRow {
            if (f.problem == null) Action("Save food", primary = true, onClick = onSave)
            if (f.name.isNotBlank()) Action("Fill in with Sparky AI", primary = f.problem != null, onClick = onAi)
            Action("Scan its label", onClick = onLabel)
            Action("Cancel", onClick = onCancel)
        }
        Text("Saved to your foods in Sparky. Sparky's AI estimates the nutrition from the name if you'd rather not type it.", color = cs.onSurfaceVariant, fontSize = 11.sp)
    }
}
