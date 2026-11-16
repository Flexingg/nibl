package com.bytelog.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.bytelog.app.ui.components.MultiAddTray
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.viewmodel.FastSearchViewModel
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun BarcodeScannerScreen(
    viewModel: FastSearchViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    val stagedEntries by viewModel.stagedEntries.collectAsState()
    val activeStagedId by viewModel.activeStagedId.collectAsState()
    var scannedNotification by remember { mutableStateOf<String?>(null) }
    val scanGate = remember { com.bytelog.app.ui.logging.ScanGate() }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember { BarcodeScanning.getClient() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            barcodeScanner.close()
        }
    }

    // What often goes with the food just scanned, one tap each
    val together by viewModel.together.collectAsState()
    val lastPortions by viewModel.lastPortions.collectAsState()
    val trayFoodIds = remember(stagedEntries) { stagedEntries.mapNotNull { it.foodId }.toSet() }
    val togetherPicks = remember(together, lastPortions, trayFoodIds) {
        together?.foods.orEmpty().filter { it.id !in trayFoodIds }.take(6).map { com.bytelog.app.ui.components.TogetherPick(it, viewModel.portionFor(it)) }
    }
    LaunchedEffect(together) { together?.foods?.let { viewModel.prefetchPortions(it) } }

    // Leaving with food in the tray asks first, rather than dropping it
    var confirmLeave by remember { mutableStateOf(false) }
    val leave: () -> Unit = { if (stagedEntries.isNotEmpty()) confirmLeave = true else onNavigateBack() }
    androidx.activity.compose.BackHandler(enabled = stagedEntries.isNotEmpty()) { confirmLeave = true }
    if (confirmLeave) {
        com.bytelog.app.ui.components.LeaveTrayDialog(
            count = stagedEntries.size, verb = if (viewModel.isPlanning) "Plan" else "Log", mealName = viewModel.targetMealName,
            onLog = { confirmLeave = false; viewModel.commitStagedTray { onNavigateBack() } },
            onDiscard = { confirmLeave = false; onNavigateBack() },
            onKeepAdding = { confirmLeave = false }
        )
    }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            MultiAddTray(
                stagedEntries = stagedEntries,
                targetMealName = viewModel.targetMealName,
                onLogAll = {
                    viewModel.commitStagedTray {
                        onNavigateBack()
                    }
                },
                onRemoveEntry = { viewModel.removeStagedEntry(it) },
                onEditEntry = { viewModel.selectStagedEntry(it.id) },
                activeEntryId = activeStagedId,
                foodFor = viewModel::foodFor,
                unitsFor = viewModel::unitOptionsFor,
                verb = if (viewModel.isPlanning) "Plan" else "Log",
                suggestions = if (togetherPicks.isNotEmpty()) ({
                    com.bytelog.app.ui.components.TogetherStrip(
                        label = together?.label.orEmpty(), picks = togetherPicks,
                        onAdd = { viewModel.tapFood(it) }
                    )
                }) else null,
                onPortionChange = viewModel::updateStagedPortion,
                onAdd = { viewModel.closeStagedEditor() }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (hasCameraPermission) {
                // CameraX Preview
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                val mediaImage = imageProxy.image
                                if (mediaImage != null) {
                                    val inputImage = InputImage.fromMediaImage(
                                        mediaImage,
                                        imageProxy.imageInfo.rotationDegrees
                                    )

                                    barcodeScanner.process(inputImage)
                                        .addOnSuccessListener { barcodes ->
                                            val firstBarcode = barcodes.firstOrNull()?.rawValue
                                            if (!firstBarcode.isNullOrBlank()) {
                                                // One scan per code held in view; again only after it leaves and returns
                                                if (scanGate.accept(firstBarcode, System.currentTimeMillis())) {
                                                    scope.launch {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        val food = viewModel.handleScannedBarcode(firstBarcode)
                                                        scannedNotification = if (food != null) {
                                                            "Added ${food.name}"
                                                        } else {
                                                            "Product not found: $firstBarcode"
                                                        }
                                                        delay(2000)
                                                        scannedNotification = null
                                                    }
                                                }
                                            }
                                        }
                                        .addOnCompleteListener {
                                            imageProxy.close()
                                        }
                                } else {
                                    imageProxy.close()
                                }
                            }

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    (ctx as androidx.lifecycle.LifecycleOwner),
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ByteInkDark),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Camera permission required for continuous scanning.",
                        color = ByteCream,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Top Header: Close button and Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = leave,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = ByteCream
                    )
                }

                Text(
                    text = "Burst Scanner",
                    color = ByteCream,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )

                Spacer(modifier = Modifier.size(40.dp))
            }

            // Target Viewfinder Reticle
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .align(Alignment.Center)
                    .border(2.dp, ByteTomato, RoundedCornerShape(24.dp))
            )

            // Scanned Notification Toast
            AnimatedVisibility(
                visible = scannedNotification != null,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
            ) {
                scannedNotification?.let { msg ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(ByteSurfaceDark.copy(alpha = 0.95f))
                            .border(1.dp, ByteTomato, RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = msg,
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
