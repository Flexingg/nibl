package com.bytelog.app.ui.photos

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bytelog.app.data.photos.PhotoPose
import com.bytelog.app.data.photos.ProgressPhotoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private val TIMER_OPTIONS = listOf(0, 3, 5, 10)

/**
 * Full-screen CameraX capture screen for Progress Photos with:
 * - Live viewfinder (`PreviewView` + `ImageCapture`)
 * - Yesterday's / most recent prior photo ghost overlay with toggle, date badge, and opacity slider (10%-80%)
 * - Pose selector chips (`Front` / `Side` / `Back`) that dynamically swap the ghost overlay
 * - Front / Rear camera flip
 * - Self-timer (`Off` / `3s` / `5s` / `10s`) with centered countdown overlay
 * - Gallery import alternative
 * - Post-capture quick preview with Retake / Use Photo
 */
@Composable
fun ProgressPhotoCameraScreen(
    viewModel: ProgressPhotoViewModel,
    initialPose: PhotoPose = PhotoPose.FRONT,
    targetDate: String = LocalDate.now().toString(),
    onNavigateBack: () -> Unit,
    onPhotoSaved: (ProgressPhotoItem?) -> Unit = { onNavigateBack() }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    val isUploading by viewModel.isUploading.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Camera & overlay state
    var selectedPose by remember { mutableStateOf(initialPose) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var timerSeconds by remember { mutableIntStateOf(0) }
    var countdownRemaining by remember { mutableIntStateOf(0) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }

    var showGhostOverlay by remember { mutableStateOf(true) }
    var ghostOpacity by remember { mutableFloatStateOf(0.35f) }
    var ghostItem by remember { mutableStateOf<ProgressPhotoItem?>(null) }
    var ghostBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoadingGhost by remember { mutableStateOf(false) }

    // Post-capture review state
    var capturedFile by remember { mutableStateOf<File?>(null) }
    var capturedPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    // CameraX handles
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            countdownJob?.cancel()
            runCatching { cameraProviderRef?.unbindAll() }
            cameraExecutor.shutdown()
        }
    }

    // Gallery import launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val temp = viewModel.copyUriToTempFile(uri)
                if (temp != null) {
                    capturedFile = temp
                } else {
                    cameraError = "Could not open selected photo"
                }
            }
        }
    }

    // Load ghost reference photo whenever pose changes
    LaunchedEffect(selectedPose, targetDate) {
        isLoadingGhost = true
        ghostBitmap = null
        val ref = viewModel.getReferencePhotoForOverlay(selectedPose, targetDate)
        ghostItem = ref
        ghostBitmap = if (ref != null) {
            viewModel.loadBitmap(ref, maxDim = 1280)
        } else {
            null
        }
        isLoadingGhost = false
    }

    // Decode preview bitmap whenever a photo is captured/imported
    LaunchedEffect(capturedFile) {
        val file = capturedFile
        if (file == null) {
            capturedPreviewBitmap = null
        } else {
            capturedPreviewBitmap = withContext(Dispatchers.IO) {
                decodePreviewBitmap(file, maxDim = 1600)
            }
        }
    }

    // Bind / rebind CameraX when live preview is visible
    LaunchedEffect(hasCameraPermission, lensFacing, capturedFile, previewViewRef) {
        val previewView = previewViewRef ?: return@LaunchedEffect
        if (!hasCameraPermission || capturedFile != null) return@LaunchedEffect

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProviderRef = provider

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                imageCaptureRef = capture
                cameraError = null
            } catch (e: Exception) {
                cameraError = e.message ?: "Unable to start camera"
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun takePictureNow() {
        val capture = imageCaptureRef ?: return
        if (isCapturing) return
        isCapturing = true
        cameraError = null

        val outFile = File(context.cacheDir, "progress_capture_${System.currentTimeMillis()}.jpg")
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = (lensFacing == CameraSelector.LENS_FACING_FRONT)
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outFile)
            .setMetadata(metadata)
            .build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    scope.launch(Dispatchers.Main) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isCapturing = false
                        capturedFile = outFile
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    scope.launch(Dispatchers.Main) {
                        isCapturing = false
                        cameraError = exception.message ?: "Capture failed"
                    }
                }
            }
        )
    }

    fun triggerShutter() {
        if (countdownRemaining > 0) {
            countdownJob?.cancel()
            countdownRemaining = 0
            return
        }
        if (timerSeconds <= 0) {
            takePictureNow()
        } else {
            countdownJob?.cancel()
            countdownJob = scope.launch {
                for (sec in timerSeconds downTo 1) {
                    countdownRemaining = sec
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    delay(1000L)
                }
                countdownRemaining = 0
                takePictureNow()
            }
        }
    }

    BackHandler(enabled = capturedFile != null || countdownRemaining > 0) {
        if (countdownRemaining > 0) {
            countdownJob?.cancel()
            countdownRemaining = 0
        } else if (capturedFile != null && !isUploading) {
            runCatching { capturedFile?.delete() }
            capturedFile = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (capturedFile != null) {
            // ---- Post-Capture Quick Preview ----
            PostCapturePreviewContent(
                bitmap = capturedPreviewBitmap,
                selectedPose = selectedPose,
                targetDate = viewModel.formatDisplayDate(targetDate),
                isUploading = isUploading,
                onSelectPose = { selectedPose = it },
                onRetake = {
                    if (!isUploading) {
                        runCatching { capturedFile?.delete() }
                        capturedFile = null
                    }
                },
                onUsePhoto = {
                    val file = capturedFile ?: return@PostCapturePreviewContent
                    viewModel.uploadCapturedPhoto(targetDate, selectedPose, file) { result ->
                        runCatching { file.delete() }
                        onPhotoSaved(result.getOrNull())
                    }
                }
            )
        } else {
            // ---- Live CameraX Viewfinder ----
            if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Yesterday's / Previous Photo Ghost Overlay
                val currentGhost = ghostBitmap
                if (showGhostOverlay && currentGhost != null) {
                    Image(
                        bitmap = currentGhost.asImageBitmap(),
                        contentDescription = "Previous ${selectedPose.label} photo ghost overlay",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(ghostOpacity)
                    )
                }
            } else {
                // Permission fallback with gallery import option
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Camera permission is needed to align and capture your progress photo.",
                        color = Color.White,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Camera Permission")
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Import from Gallery", color = Color.White)
                    }
                }
            }

            // Top gradient scrim
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                        )
                    )
            )

            // Bottom gradient scrim
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                        )
                    )
            )

            // ---- Top Camera HUD ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .align(Alignment.TopCenter),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Close button
                    IconButton(
                        onClick = {
                            countdownJob?.cancel()
                            onNavigateBack()
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close camera", tint = Color.White)
                    }

                    // Ghost Overlay Badge
                    val badgeText = when {
                        isLoadingGhost -> "Loading ghost..."
                        ghostItem != null && showGhostOverlay -> {
                            "Ghost: ${viewModel.formatOverlayDateBadge(ghostItem!!.date, targetDate)}"
                        }
                        ghostItem != null && !showGhostOverlay -> "Ghost Off"
                        else -> "No prior ${selectedPose.label}"
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (showGhostOverlay && ghostItem != null) {
                            cs.primary.copy(alpha = 0.85f)
                        } else {
                            Color.Black.copy(alpha = 0.58f)
                        },
                        modifier = Modifier.clickable(enabled = ghostItem != null) {
                            showGhostOverlay = !showGhostOverlay
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (showGhostOverlay && ghostItem != null) Icons.Default.Layers else Icons.Default.LayersClear,
                                contentDescription = "Toggle ghost overlay",
                                tint = if (showGhostOverlay && ghostItem != null) cs.onPrimary else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = badgeText,
                                color = if (showGhostOverlay && ghostItem != null) cs.onPrimary else Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Self-Timer toggle button
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (timerSeconds > 0) cs.primary.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier.clickable {
                            val nextIdx = (TIMER_OPTIONS.indexOf(timerSeconds) + 1) % TIMER_OPTIONS.size
                            timerSeconds = TIMER_OPTIONS[nextIdx]
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = "Self-timer",
                                tint = if (timerSeconds > 0) cs.onPrimary else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (timerSeconds == 0) "Off" else "${timerSeconds}s",
                                color = if (timerSeconds > 0) cs.onPrimary else Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Pose Selector Chips (Front / Side / Back)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Color.Black.copy(alpha = 0.55f)
                    ) {
                        Row(
                            modifier = Modifier.padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PhotoPose.entries.forEach { pose ->
                                val selected = pose == selectedPose
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(if (selected) cs.primary else Color.Transparent)
                                        .clickable { selectedPose = pose }
                                        .padding(horizontal = 16.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = pose.label,
                                        color = if (selected) cs.onPrimary else Color.White.copy(alpha = 0.85f),
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- Centered Countdown Overlay ----
            AnimatedVisibility(
                visible = countdownRemaining > 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .border(3.dp, cs.primary, CircleShape)
                        .clickable {
                            countdownJob?.cancel()
                            countdownRemaining = 0
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = countdownRemaining.toString(),
                            color = Color.White,
                            fontSize = 68.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tap to cancel",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Error banner if any
            cameraError?.let { err ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = cs.errorContainer.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp)
                ) {
                    Text(
                        text = err,
                        color = cs.onErrorContainer,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            // ---- Bottom Camera HUD (Opacity slider + Gallery / Shutter / Flip) ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Ghost opacity slider (10% - 80%)
                if (showGhostOverlay && ghostBitmap != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                        Slider(
                            value = ghostOpacity,
                            onValueChange = { ghostOpacity = it },
                            valueRange = 0.10f..0.80f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = cs.primary,
                                activeTrackColor = cs.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        Text(
                            text = "${(ghostOpacity * 100f).roundToInt()}%",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(34.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }

                // Controls row: Gallery Import | Shutter | Flip Camera
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Gallery import button
                    IconButton(
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "Import from gallery",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Shutter button
                    Box(
                        modifier = Modifier
                            .size(78.dp)
                            .clip(CircleShape)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(6.dp)
                            .clip(CircleShape)
                            .background(if (countdownRemaining > 0) cs.error else Color.White)
                            .clickable(enabled = hasCameraPermission && !isCapturing) {
                                triggerShutter()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isCapturing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = Color.Black,
                                strokeWidth = 3.dp
                            )
                        } else if (countdownRemaining > 0) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel timer",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    // Front / Rear camera flip button
                    IconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Switch camera",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PostCapturePreviewContent(
    bitmap: Bitmap?,
    selectedPose: PhotoPose,
    targetDate: String,
    isUploading: Boolean,
    onSelectPose: (PhotoPose) -> Unit,
    onRetake: () -> Unit,
    onUsePhoto: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Captured progress photo preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        // Top metadata & pose confirm bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))
                )
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Review Photo • $targetDate",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.55f)
            ) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PhotoPose.entries.forEach { pose ->
                        val selected = pose == selectedPose
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (selected) cs.primary else Color.Transparent)
                                .clickable(enabled = !isUploading) { onSelectPose(pose) }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = pose.label,
                                color = if (selected) cs.onPrimary else Color.White.copy(alpha = 0.85f),
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // Bottom Retake / Use Photo actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))
                )
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedButton(
                onClick = onRetake,
                enabled = !isUploading,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Retake", fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = onUsePhoto,
                enabled = !isUploading && bitmap != null,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = cs.onPrimary,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Saving...", fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Use Photo", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun decodePreviewBitmap(file: File, maxDim: Int): Bitmap? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val bmp = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bmp
        }
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true).also {
            if (it !== bmp) bmp.recycle()
        }
    }.getOrNull()
}
