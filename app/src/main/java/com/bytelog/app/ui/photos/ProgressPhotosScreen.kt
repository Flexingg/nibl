package com.bytelog.app.ui.photos

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bytelog.app.data.photos.PhotoPose
import com.bytelog.app.data.photos.ProgressPhotoItem
import com.bytelog.app.ui.components.ScreenHeader
import com.bytelog.app.ui.theme.FluidRadius
import com.bytelog.app.ui.theme.LocalByteExtraColors
import com.bytelog.app.ui.theme.fluidBackdrop
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class ProgressPhotosTab(val label: String) {
    GALLERY("Gallery"),
    COMPARE("Compare"),
    TIMELAPSE("Time-Lapse")
}

private data class SpeedOption(val label: String, val delayMs: Long, val fadeMs: Int)

private val TIMELAPSE_SPEEDS = listOf(
    SpeedOption("0.5x", 2000L, 450),
    SpeedOption("1x", 1000L, 300),
    SpeedOption("2x", 500L, 180),
    SpeedOption("4x", 250L, 100)
)

/**
 * 3-tab Material 3 Progress Photos screen:
 * 1. **Gallery** — pose filter chips, chronological date-grouped grid, fullscreen viewer + delete, camera FAB.
 * 2. **Compare** — side-by-side & interactive split-slider overlay with days elapsed + weight delta.
 * 3. **Time-Lapse** — chronological crossfade player with play/pause, speed, loop, scrubber, and live stats overlay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressPhotosScreen(
    viewModel: ProgressPhotoViewModel,
    onNavigateBack: () -> Unit,
    onOpenCamera: (PhotoPose) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val allPhotos by viewModel.photos.collectAsState()
    val filteredPhotos by viewModel.filteredPhotos.collectAsState()
    val selectedPoseFilter by viewModel.selectedPoseFilter.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val deletingPhotoId by viewModel.deletingPhotoId.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    var activeTab by remember { mutableStateOf(ProgressPhotosTab.GALLERY) }
    var viewerPhoto by remember { mutableStateOf<ProgressPhotoItem?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .fluidBackdrop(),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ScreenHeader(
                    title = "Progress Photos",
                    onBack = onNavigateBack,
                    subtitle = if (allPhotos.isEmpty()) {
                        "Track your visual progress"
                    } else {
                        "${allPhotos.size} ${if (allPhotos.size == 1) "photo" else "photos"}"
                    },
                    actions = {
                        IconButton(onClick = { viewModel.refresh() }, enabled = !isRefreshing) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh gallery")
                            }
                        }
                    }
                )

                PrimaryTabRow(
                    selectedTabIndex = activeTab.ordinal,
                    containerColor = Color.Transparent
                ) {
                    ProgressPhotosTab.entries.forEach { tab ->
                        val icon = when (tab) {
                            ProgressPhotosTab.GALLERY -> Icons.Default.PhotoLibrary
                            ProgressPhotosTab.COMPARE -> Icons.Default.Compare
                            ProgressPhotosTab.TIMELAPSE -> Icons.Default.PlayCircle
                        }
                        Tab(
                            selected = activeTab == tab,
                            onClick = { activeTab = tab },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (activeTab == ProgressPhotosTab.GALLERY) {
                ExtendedFloatingActionButton(
                    onClick = { onOpenCamera(selectedPoseFilter ?: PhotoPose.FRONT) },
                    icon = { Icon(Icons.Default.AddAPhoto, contentDescription = null) },
                    text = { Text("Take Today's Photo", fontWeight = FontWeight.SemiBold) },
                    containerColor = cs.primary,
                    contentColor = cs.onPrimary
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (activeTab) {
                ProgressPhotosTab.GALLERY -> {
                    GalleryTabContent(
                        photos = filteredPhotos,
                        selectedPoseFilter = selectedPoseFilter,
                        weightUnit = weightUnit,
                        viewModel = viewModel,
                        onSelectFilter = { viewModel.setPoseFilter(it) },
                        onPhotoClick = { viewerPhoto = it },
                        onTakePhoto = { onOpenCamera(selectedPoseFilter ?: PhotoPose.FRONT) }
                    )
                }
                ProgressPhotosTab.COMPARE -> {
                    CompareTabContent(
                        allPhotos = allPhotos,
                        weightUnit = weightUnit,
                        viewModel = viewModel,
                        onTakePhoto = onOpenCamera
                    )
                }
                ProgressPhotosTab.TIMELAPSE -> {
                    TimeLapseTabContent(
                        allPhotos = allPhotos,
                        weightUnit = weightUnit,
                        viewModel = viewModel,
                        onTakePhoto = onOpenCamera
                    )
                }
            }
        }
    }

    // Fullscreen photo viewer dialog
    viewerPhoto?.let { photo ->
        FullscreenPhotoDialog(
            photo = photo,
            weightUnit = weightUnit,
            isDeleting = deletingPhotoId == photo.id,
            viewModel = viewModel,
            onDismiss = { viewerPhoto = null },
            onDeleteConfirmed = {
                viewModel.deletePhoto(photo) { deleted ->
                    if (deleted) viewerPhoto = null
                }
            }
        )
    }
}

// ============================================================================
// TAB 1: GALLERY
// ============================================================================

@Composable
private fun GalleryTabContent(
    photos: List<ProgressPhotoItem>,
    selectedPoseFilter: PhotoPose?,
    weightUnit: String,
    viewModel: ProgressPhotoViewModel,
    onSelectFilter: (PhotoPose?) -> Unit,
    onPhotoClick: (ProgressPhotoItem) -> Unit,
    onTakePhoto: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val grouped = remember(photos) {
        photos.groupBy { it.date }.entries.sortedByDescending { it.key }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Pose filter chips (All / Front / Side / Back)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedPoseFilter == null,
                    onClick = { onSelectFilter(null) },
                    label = { Text("All") }
                )
            }
            items(PhotoPose.entries) { pose ->
                FilterChip(
                    selected = selectedPoseFilter == pose,
                    onClick = { onSelectFilter(if (selectedPoseFilter == pose) null else pose) },
                    label = { Text(pose.label) }
                )
            }
        }

        if (grouped.isEmpty()) {
            EmptyPhotosCard(
                title = if (selectedPoseFilter == null) "No progress photos yet" else "No ${selectedPoseFilter.label} photos yet",
                subtitle = "Capture your first check-in photo. Tomorrow, the camera will show it as a ghost overlay so you can match your exact framing.",
                ctaLabel = "Take Today's Photo",
                onCtaClick = onTakePhoto
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                grouped.forEach { (date, datePhotos) ->
                    item(key = "header_$date") {
                        val dateWeight = datePhotos.firstNotNullOfOrNull { it.weightKg }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = viewModel.formatDisplayDate(date),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = cs.onSurface
                            )
                            viewModel.formatWeight(dateWeight, weightUnit)?.let { w ->
                                Surface(
                                    shape = RoundedCornerShape(FluidRadius.sm),
                                    color = cs.surfaceContainerHigh
                                ) {
                                    Text(
                                        text = w,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = cs.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    val rows = datePhotos.chunked(3)
                    items(
                        items = rows,
                        key = { chunk -> "row_${date}_${chunk.joinToString("_") { it.id }}" }
                    ) { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowItems.forEach { item ->
                                GalleryPhotoTile(
                                    item = item,
                                    weightUnit = weightUnit,
                                    viewModel = viewModel,
                                    onClick = { onPhotoClick(item) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(3 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryPhotoTile(
    item: ProgressPhotoItem,
    weightUnit: String,
    viewModel: ProgressPhotoViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val bitmap by rememberProgressPhotoBitmap(item, viewModel, maxDim = 640)

    Box(
        modifier = modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(FluidRadius.md))
            .background(cs.surfaceContainerHigh)
            .clickable(onClick = onClick)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "${item.pose.label} photo on ${item.date}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(22.dp)
                    .align(Alignment.Center),
                strokeWidth = 2.dp
            )
        }

        // Bottom gradient scrim
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)))
                )
        )

        // Pose badge (top-left)
        Surface(
            shape = RoundedCornerShape(FluidRadius.xs),
            color = Color.Black.copy(alpha = 0.58f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
        ) {
            Text(
                text = item.pose.label,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // Unsynced indicator (top-right)
        if (!item.isSynced) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = "Local only (unsynced)",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        // Weight label (bottom-left)
        viewModel.formatWeight(item.weightKg, weightUnit)?.let { w ->
            Text(
                text = w,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

// ============================================================================
// TAB 2: COMPARE (SIDE-BY-SIDE + SPLIT SLIDER)
// ============================================================================

@Composable
private fun CompareTabContent(
    allPhotos: List<ProgressPhotoItem>,
    weightUnit: String,
    viewModel: ProgressPhotoViewModel,
    onTakePhoto: (PhotoPose) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val extraColors = LocalByteExtraColors.current

    var selectedPose by remember { mutableStateOf(PhotoPose.FRONT) }
    var useSplitSlider by remember { mutableStateOf(false) }
    var splitFraction by remember { mutableFloatStateOf(0.5f) }

    val posePhotos = remember(allPhotos, selectedPose) {
        viewModel.photosForPoseChronological(selectedPose, allPhotos)
    }

    var beforeId by remember(selectedPose, posePhotos) {
        mutableStateOf(posePhotos.firstOrNull()?.id)
    }
    var afterId by remember(selectedPose, posePhotos) {
        mutableStateOf(posePhotos.lastOrNull()?.id)
    }

    // Ensure selections stay valid when photo list refreshes
    val beforePhoto = remember(posePhotos, beforeId) {
        posePhotos.firstOrNull { it.id == beforeId } ?: posePhotos.firstOrNull()
    }
    val afterPhoto = remember(posePhotos, afterId) {
        posePhotos.firstOrNull { it.id == afterId } ?: posePhotos.lastOrNull()
    }
    val metrics = remember(beforePhoto, afterPhoto) {
        viewModel.computeComparison(beforePhoto, afterPhoto)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Pose selector + view mode toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoPose.entries.forEach { pose ->
                    FilterChip(
                        selected = selectedPose == pose,
                        onClick = { selectedPose = pose },
                        label = { Text(pose.label) }
                    )
                }
            }

            if (posePhotos.size >= 2) {
                FilledIconToggleButton(
                    checked = useSplitSlider,
                    onCheckedChange = { useSplitSlider = it }
                ) {
                    Icon(
                        imageVector = if (useSplitSlider) Icons.Default.ViewColumn else Icons.Default.Compare,
                        contentDescription = if (useSplitSlider) "Switch to Side-by-Side" else "Switch to Split Slider"
                    )
                }
            }
        }

        if (posePhotos.size < 2) {
            EmptyPhotosCard(
                title = "Need at least 2 ${selectedPose.label} photos",
                subtitle = if (posePhotos.isEmpty()) {
                    "Take your first ${selectedPose.label} photo today to start tracking changes."
                } else {
                    "You have 1 ${selectedPose.label} photo (${viewModel.formatDisplayDate(posePhotos.first().date)}). Capture another day's photo to compare side-by-side."
                },
                ctaLabel = "Take ${selectedPose.label} Photo",
                onCtaClick = { onTakePhoto(selectedPose) }
            )
            return@Column
        }

        // Before / After date dropdown pickers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DatePickerDropdownChip(
                label = "Before",
                selected = beforePhoto,
                options = posePhotos,
                weightUnit = weightUnit,
                viewModel = viewModel,
                onSelected = { beforeId = it.id },
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = {
                    val tmp = beforeId
                    beforeId = afterId
                    afterId = tmp
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.SwapHoriz, contentDescription = "Swap before and after")
            }

            DatePickerDropdownChip(
                label = "After",
                selected = afterPhoto,
                options = posePhotos,
                weightUnit = weightUnit,
                viewModel = viewModel,
                onSelected = { afterId = it.id },
                modifier = Modifier.weight(1f)
            )
        }

        // Summary delta banner
        metrics?.let { m ->
            val daysAbs = abs(m.daysElapsed)
            val daysLabel = "$daysAbs ${if (daysAbs == 1L) "day" else "days"}"
            val deltaText = viewModel.formatWeightDelta(m.weightDeltaKg, weightUnit)
            val summaryHeadline = if (deltaText != null) {
                "$deltaText over $daysLabel"
            } else {
                "$daysLabel elapsed"
            }

            Surface(
                shape = RoundedCornerShape(FluidRadius.lg),
                color = cs.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = summaryHeadline,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (m.weightDeltaKg != null && m.weightDeltaKg < -0.05f) {
                                extraColors.success
                            } else {
                                cs.onSurface
                            }
                        )
                        val bWeight = viewModel.formatWeight(m.beforeWeightKg, weightUnit)
                        val aWeight = viewModel.formatWeight(m.afterWeightKg, weightUnit)
                        if (bWeight != null || aWeight != null) {
                            Text(
                                text = "${bWeight ?: "—"}  →  ${aWeight ?: "—"}",
                                fontSize = 13.sp,
                                color = cs.onSurfaceVariant
                            )
                        }
                    }

                    TextButton(onClick = { useSplitSlider = !useSplitSlider }) {
                        Text(if (useSplitSlider) "Side-by-Side" else "Split Slider")
                    }
                }
            }
        }

        // Photo comparison viewport
        if (beforePhoto != null && afterPhoto != null) {
            if (!useSplitSlider) {
                // Side-by-Side cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ComparePhotoCard(
                        tag = "BEFORE",
                        item = beforePhoto,
                        weightUnit = weightUnit,
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f)
                    )
                    ComparePhotoCard(
                        tag = "AFTER",
                        item = afterPhoto,
                        weightUnit = weightUnit,
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                // Interactive Split Slider Overlay
                SplitSliderComparisonCard(
                    beforePhoto = beforePhoto,
                    afterPhoto = afterPhoto,
                    splitFraction = splitFraction,
                    onSplitChange = { splitFraction = it },
                    weightUnit = weightUnit,
                    viewModel = viewModel
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DatePickerDropdownChip(
    label: String,
    selected: ProgressPhotoItem?,
    options: List<ProgressPhotoItem>,
    weightUnit: String,
    viewModel: ProgressPhotoViewModel,
    onSelected: (ProgressPhotoItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(FluidRadius.md),
            color = cs.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        color = cs.onSurfaceVariant
                    )
                    Text(
                        text = selected?.let { viewModel.formatDisplayDate(it.date) } ?: "Select",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Choose $label date",
                    tint = cs.onSurfaceVariant
                )
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                val weightText = viewModel.formatWeight(option.weightKg, weightUnit)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(viewModel.formatDisplayDate(option.date), fontWeight = FontWeight.Medium)
                            if (weightText != null) {
                                Text(weightText, fontSize = 12.sp, color = cs.onSurfaceVariant)
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun ComparePhotoCard(
    tag: String,
    item: ProgressPhotoItem,
    weightUnit: String,
    viewModel: ProgressPhotoViewModel,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val bitmap by rememberProgressPhotoBitmap(item, viewModel, maxDim = 1080)

    Box(
        modifier = modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(FluidRadius.lg))
            .background(cs.surfaceContainerHigh)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "$tag photo on ${item.date}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(26.dp)
                    .align(Alignment.Center),
                strokeWidth = 2.5.dp
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))
                )
        )

        Surface(
            shape = RoundedCornerShape(FluidRadius.xs),
            color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        ) {
            Text(
                text = tag,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
        ) {
            Text(
                text = viewModel.formatDisplayDate(item.date),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            viewModel.formatWeight(item.weightKg, weightUnit)?.let { w ->
                Text(
                    text = w,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SplitSliderComparisonCard(
    beforePhoto: ProgressPhotoItem,
    afterPhoto: ProgressPhotoItem,
    splitFraction: Float,
    onSplitChange: (Float) -> Unit,
    weightUnit: String,
    viewModel: ProgressPhotoViewModel
) {
    val cs = MaterialTheme.colorScheme
    val beforeBmp by rememberProgressPhotoBitmap(beforePhoto, viewModel, maxDim = 1280)
    val afterBmp by rememberProgressPhotoBitmap(afterPhoto, viewModel, maxDim = 1280)
    var boxWidthPx by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(FluidRadius.lg))
                .background(cs.surfaceContainerHigh)
                .onSizeChanged { boxWidthPx = it.width.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        val next = (splitFraction + dragAmount / boxWidthPx.toFloat()).coerceIn(0.02f, 0.98f)
                        onSplitChange(next)
                    }
                }
        ) {
            // Base layer: After photo
            if (afterBmp != null) {
                Image(
                    bitmap = afterBmp!!.asImageBitmap(),
                    contentDescription = "After photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Clipped layer: Before photo (left portion)
            if (beforeBmp != null) {
                Image(
                    bitmap = beforeBmp!!.asImageBitmap(),
                    contentDescription = "Before photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            clipRect(right = size.width * splitFraction) {
                                this@drawWithContent.drawContent()
                            }
                        }
                )
            }

            if (beforeBmp == null || afterBmp == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            // Vertical divider line + handle
            val handleX = (boxWidthPx * splitFraction).roundToInt()
            Box(
                modifier = Modifier
                    .offset { IntOffset(handleX - with(density) { 1.dp.roundToPx() }, 0) }
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.92f))
            )

            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(handleX - with(density) { 18.dp.roundToPx() }, 0) }
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(cs.primary)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = "Drag split divider",
                    tint = cs.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Corner badges
            Surface(
                shape = RoundedCornerShape(FluidRadius.xs),
                color = Color.Black.copy(alpha = 0.62f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = "Before • ${viewModel.formatDisplayDate(beforePhoto.date)}",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Surface(
                shape = RoundedCornerShape(FluidRadius.xs),
                color = Color.Black.copy(alpha = 0.62f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
            ) {
                Text(
                    text = "After • ${viewModel.formatDisplayDate(afterPhoto.date)}",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Fine-tune scrub slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = viewModel.formatWeight(beforePhoto.weightKg, weightUnit) ?: "Before",
                fontSize = 12.sp,
                color = cs.onSurfaceVariant
            )
            Slider(
                value = splitFraction,
                onValueChange = onSplitChange,
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = viewModel.formatWeight(afterPhoto.weightKg, weightUnit) ?: "After",
                fontSize = 12.sp,
                color = cs.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// TAB 3: TIME-LAPSE PLAYER
// ============================================================================

@Composable
private fun TimeLapseTabContent(
    allPhotos: List<ProgressPhotoItem>,
    weightUnit: String,
    viewModel: ProgressPhotoViewModel,
    onTakePhoto: (PhotoPose) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val extraColors = LocalByteExtraColors.current

    var selectedPose by remember { mutableStateOf(PhotoPose.FRONT) }
    var isPlaying by remember { mutableStateOf(false) }
    var speedIndex by remember { mutableIntStateOf(1) } // default 1x
    var loopEnabled by remember { mutableStateOf(true) }
    var currentIndex by remember { mutableIntStateOf(0) }

    val frames = remember(allPhotos, selectedPose) {
        viewModel.photosForPoseChronological(selectedPose, allPhotos)
    }

    // Reset playback safely when pose or frame count changes
    LaunchedEffect(selectedPose, frames.size) {
        isPlaying = false
        currentIndex = 0
    }

    val safeIndex = if (frames.isEmpty()) 0 else currentIndex.coerceIn(0, frames.lastIndex)
    val currentFrame = frames.getOrNull(safeIndex)
    val firstFrame = frames.firstOrNull()
    val speed = TIMELAPSE_SPEEDS[speedIndex]

    // Playback loop
    LaunchedEffect(isPlaying, safeIndex, frames.size, speedIndex, loopEnabled) {
        if (!isPlaying || frames.size < 2) return@LaunchedEffect
        delay(speed.delayMs)
        if (safeIndex < frames.lastIndex) {
            currentIndex = safeIndex + 1
        } else if (loopEnabled) {
            currentIndex = 0
        } else {
            isPlaying = false
        }
    }

    // Preload next couple of frames into memory cache for stutter-free playback
    LaunchedEffect(safeIndex, frames) {
        if (frames.isEmpty()) return@LaunchedEffect
        val preloadIndices = listOf(safeIndex + 1, safeIndex + 2).mapNotNull { idx ->
            when {
                idx < frames.size -> idx
                loopEnabled && frames.size > 1 -> idx % frames.size
                else -> null
            }
        }.distinct()
        preloadIndices.forEach { idx ->
            viewModel.loadBitmap(frames[idx], maxDim = 1080)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Pose selector chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhotoPose.entries.forEach { pose ->
                FilterChip(
                    selected = selectedPose == pose,
                    onClick = { selectedPose = pose },
                    label = { Text(pose.label) }
                )
            }
        }

        if (frames.size < 2) {
            EmptyPhotosCard(
                title = "Need at least 2 ${selectedPose.label} photos",
                subtitle = "Time-lapse plays through your ${selectedPose.label} photos chronologically so you can watch your transformation unfold.",
                ctaLabel = "Take ${selectedPose.label} Photo",
                onCtaClick = { onTakePhoto(selectedPose) }
            )
            return@Column
        }

        // Crossfade viewport with live date + weight + delta overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(FluidRadius.lg))
                .background(cs.surfaceContainerHigh)
                .clickable { isPlaying = !isPlaying }
        ) {
            Crossfade(
                targetState = currentFrame,
                animationSpec = tween(durationMillis = speed.fadeMs),
                label = "timelapse_frame"
            ) { frame ->
                if (frame != null) {
                    val bmp by rememberProgressPhotoBitmap(frame, viewModel, maxDim = 1080)
                    if (bmp != null) {
                        Image(
                            bitmap = bmp!!.asImageBitmap(),
                            contentDescription = "Time-lapse frame ${frame.date}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            // Bottom gradient scrim
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)))
                    )
            )

            // Frame counter pill (top-right)
            Surface(
                shape = RoundedCornerShape(FluidRadius.sm),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Text(
                    text = "${safeIndex + 1} / ${frames.size}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            // Live date + weight + delta overlay (bottom)
            currentFrame?.let { frame ->
                val metrics = viewModel.computeComparison(firstFrame, frame)
                val weightText = viewModel.formatWeight(frame.weightKg, weightUnit)
                val deltaText = if (safeIndex > 0) viewModel.formatWeightDelta(metrics?.weightDeltaKg, weightUnit) else null

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = viewModel.formatDisplayDate(frame.date),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        val days = metrics?.daysElapsed ?: 0L
                        Text(
                            text = if (safeIndex == 0) "Day 1" else "Day ${days + 1}",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                    }

                    if (weightText != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = weightText,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (deltaText != null) {
                                Text(
                                    text = deltaText,
                                    color = if ((metrics?.weightDeltaKg ?: 0f) < -0.05f) extraColors.success else Color.White.copy(alpha = 0.85f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Frame scrubber slider
        Slider(
            value = safeIndex.toFloat(),
            onValueChange = { value ->
                isPlaying = false
                currentIndex = value.roundToInt().coerceIn(0, frames.lastIndex)
            },
            valueRange = 0f..frames.lastIndex.toFloat(),
            steps = (frames.size - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )

        // Controls: Play/Pause | Speed selector | Loop toggle
        Surface(
            shape = RoundedCornerShape(FluidRadius.lg),
            color = cs.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (!isPlaying && safeIndex >= frames.lastIndex) {
                                currentIndex = 0
                            }
                            isPlaying = !isPlaying
                        },
                        shape = RoundedCornerShape(FluidRadius.md)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play"
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isPlaying) "Pause" else "Play", fontWeight = FontWeight.SemiBold)
                    }

                    FilledIconToggleButton(
                        checked = loopEnabled,
                        onCheckedChange = { loopEnabled = it }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Repeat,
                            contentDescription = if (loopEnabled) "Loop on" else "Loop off"
                        )
                    }
                }

                // Speed selector row (0.5x / 1x / 2x / 4x)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Speed",
                        fontSize = 13.sp,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    TIMELAPSE_SPEEDS.forEachIndexed { idx, opt ->
                        FilterChip(
                            selected = speedIndex == idx,
                            onClick = { speedIndex = idx },
                            label = { Text(opt.label) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ============================================================================
// FULLSCREEN PHOTO VIEWER + DELETE DIALOG
// ============================================================================

@Composable
private fun FullscreenPhotoDialog(
    photo: ProgressPhotoItem,
    weightUnit: String,
    isDeleting: Boolean,
    viewModel: ProgressPhotoViewModel,
    onDismiss: () -> Unit,
    onDeleteConfirmed: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val bitmap by rememberProgressPhotoBitmap(photo, viewModel, maxDim = 1600)
    var confirmDelete by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "${photo.pose.label} photo on ${photo.date}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }

            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent))
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss, enabled = !isDeleting) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${photo.pose.label} • ${viewModel.formatDisplayDate(photo.date)}",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    viewModel.formatWeight(photo.weightKg, weightUnit)?.let { w ->
                        Text(
                            text = w,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                    }
                }

                IconButton(onClick = { confirmDelete = true }, enabled = !isDeleting) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Delete, contentDescription = "Delete photo", tint = Color.White)
                    }
                }
            }

            if (!photo.isSynced) {
                Surface(
                    shape = RoundedCornerShape(FluidRadius.md),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Text("Saved locally (not yet synced)", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete progress photo?") },
            text = {
                Text("This will permanently remove your ${photo.pose.label} photo from ${viewModel.formatDisplayDate(photo.date)}.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        onDeleteConfirmed()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ============================================================================
// SHARED HELPERS
// ============================================================================

@Composable
private fun rememberProgressPhotoBitmap(
    item: ProgressPhotoItem,
    viewModel: ProgressPhotoViewModel,
    maxDim: Int = 1080
): androidx.compose.runtime.State<Bitmap?> {
    val state = remember(item.id, item.localPath, maxDim) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id, item.localPath, maxDim) {
        state.value = viewModel.loadBitmap(item, maxDim)
    }
    return state
}

@Composable
private fun EmptyPhotosCard(
    title: String,
    subtitle: String,
    ctaLabel: String,
    onCtaClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(FluidRadius.lg),
            color = cs.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(44.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = cs.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(onClick = onCtaClick) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(ctaLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
