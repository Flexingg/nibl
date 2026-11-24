package com.bytelog.app.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bytelog.app.data.remote.SparkyEntryPhotos
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import kotlinx.coroutines.launch
import java.io.File

/**
 * Horizontal photo attachment strip for a food entry (used in EditFoodEntrySheet).
 * Supports camera capture (via FileProvider authority `${packageName}.photos`), gallery picking,
 * JPEG compression (<= 1600px, 85%), fullscreen preview, and removal.
 */
@Composable
fun FoodEntryPhotosSection(
    entryId: String,
    initialImages: List<String>,
    entryPhotos: SparkyEntryPhotos,
    onImagesChanged: (List<String>) -> Unit,
    isMealEntry: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    var images by remember(entryId, initialImages) { mutableStateOf(initialImages) }
    var isUploading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun handlePickedUri(uri: Uri) {
        scope.launch {
            isUploading = true
            errorMessage = null
            val compressed = entryPhotos.compressUriToJpegFile(context, uri, maxDim = 1600, quality = 85)
            if (compressed == null) {
                errorMessage = "Couldn't read that photo"
                isUploading = false
                return@launch
            }
            val result = entryPhotos.uploadEntryPhotos(
                entryId = entryId,
                keepPaths = images,
                newFiles = listOf(compressed),
                isMealEntry = isMealEntry
            )
            runCatching { compressed.delete() }
            result.fold(
                onSuccess = { updated ->
                    images = updated
                    entryPhotos.setCachedPhotos(context, entryId, updated)
                    onImagesChanged(updated)
                },
                onFailure = { err ->
                    errorMessage = err.message ?: "Photo upload failed"
                }
            )
            isUploading = false
        }
    }

    fun removePhoto(pathToRemove: String) {
        scope.launch {
            isUploading = true
            errorMessage = null
            val remaining = images.filterNot { it == pathToRemove }
            val result: Result<List<String>> = if (remaining.isEmpty()) {
                entryPhotos.clearEntryPhotos(entryId = entryId, isMealEntry = isMealEntry).map { emptyList() }
            } else {
                entryPhotos.uploadEntryPhotos(
                    entryId = entryId,
                    keepPaths = remaining,
                    newFiles = emptyList(),
                    isMealEntry = isMealEntry
                )
            }
            result.fold(
                onSuccess = { updated ->
                    images = updated
                    entryPhotos.setCachedPhotos(context, entryId, updated)
                    onImagesChanged(updated)
                    if (previewPath == pathToRemove) previewPath = null
                },
                onFailure = { err ->
                    errorMessage = err.message ?: "Couldn't remove photo"
                }
            )
            isUploading = false
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) handlePickedUri(uri)
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) handlePickedUri(uri)
    }

    fun launchCamera() {
        val uri = createCameraPhotoUri(context)
        pendingCameraUri = uri
        runCatching { cameraLauncher.launch(uri) }.onFailure {
            galleryLauncher.launch("image/*")
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            errorMessage = "Camera permission is needed to take a photo. You can pick from your gallery instead."
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PHOTOS",
                color = ByteCreamMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            if (isUploading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = ByteTomato
                    )
                    Text(
                        text = "Syncing…",
                        color = ByteCreamMuted,
                        fontSize = 12.sp
                    )
                }
            } else if (images.isNotEmpty()) {
                Text(
                    text = "${images.size} attached",
                    color = ByteCreamMuted,
                    fontSize = 12.sp
                )
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(images, key = { it }) { path ->
                Box(modifier = Modifier.size(76.dp)) {
                    EntryPhotoThumbnail(
                        relativePath = path,
                        entryPhotos = entryPhotos,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { previewPath = path }
                    )
                    // Remove (X) badge
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.68f))
                            .clickable(enabled = !isUploading) { removePhoto(path) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove photo",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // "+" Add photo tile
            item(key = "__add_photo__") {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ByteSurfaceVariantDark)
                        .border(1.dp, ByteTomato.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .clickable(enabled = !isUploading) { showSourceDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = ByteTomato
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddAPhoto,
                                contentDescription = "Add photo",
                                tint = ByteTomato,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "Add",
                                color = ByteCreamMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        if (errorMessage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.errorContainer.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = errorMessage.orEmpty(),
                    color = cs.onErrorContainer,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { errorMessage = null },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss error",
                        tint = cs.onErrorContainer,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }

    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            containerColor = ByteSurfaceDark,
            title = {
                Text(
                    text = "Add meal photo",
                    color = ByteCream,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(ByteSurfaceVariantDark)
                            .clickable {
                                showSourceDialog = false
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    launchCamera()
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = ByteTomato,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Take photo",
                            color = ByteCream,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(ByteSurfaceVariantDark)
                            .clickable {
                                showSourceDialog = false
                                galleryLauncher.launch("image/*")
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = ByteTomato,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Choose from gallery",
                            color = ByteCream,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSourceDialog = false }) {
                    Text("Cancel", color = ByteCreamMuted)
                }
            }
        )
    }

    previewPath?.let { path ->
        FullscreenPhotoDialog(
            relativePath = path,
            entryPhotos = entryPhotos,
            onDelete = { removePhoto(path) },
            onDismiss = { previewPath = null }
        )
    }
}

/**
 * Compact thumbnail composable for Diary meal rows and photo strips.
 */
@Composable
fun EntryPhotoThumbnail(
    relativePath: String,
    entryPhotos: SparkyEntryPhotos,
    modifier: Modifier = Modifier,
    maxDim: Int = 256
) {
    val context = LocalContext.current
    var bitmap by remember(relativePath, maxDim) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(relativePath, maxDim) {
        bitmap = entryPhotos.fetchEntryPhotoBitmap(context, relativePath, maxDim = maxDim)
    }

    Box(
        modifier = modifier.background(ByteSurfaceVariantDark),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Food entry photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = ByteCreamMuted.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun FullscreenPhotoDialog(
    relativePath: String,
    entryPhotos: SparkyEntryPhotos,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(relativePath) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(relativePath) { mutableStateOf(true) }

    LaunchedEffect(relativePath) {
        loading = true
        bitmap = entryPhotos.fetchEntryPhotoBitmap(context, relativePath, maxDim = 1600)
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(onClick = onDismiss)
        ) {
            val bmp = bitmap
            when {
                loading -> CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
                bmp != null && !bmp.isRecycled -> Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Full photo preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )
                else -> Text(
                    text = "Unable to load photo",
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete photo",
                        tint = Color(0xFFFF8A80),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close preview",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Creates a FileProvider URI inside `cacheDir/meal_photos/` matching `res/xml/photo_paths.xml`
 * and `AndroidManifest.xml` authority `${applicationId}.photos`.
 */
private fun createCameraPhotoUri(context: Context): Uri {
    val dir = File(context.cacheDir, "meal_photos").apply { mkdirs() }
    val file = File(dir, "entry_photo_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
}
