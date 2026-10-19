package com.bytelog.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.ByteOnTomato
import java.io.ByteArrayOutputStream

@Composable
fun GenerateFoodAiDialog(
    initialName: String,
    isGenerating: Boolean,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onGenerate: (name: String, description: String, imageBase64: String?, imageMimeType: String) -> Unit
) {
    val context = LocalContext.current
    var foodName by remember { mutableStateOf(initialName.trim()) }
    var description by remember { mutableStateOf("") }
    var attachedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Camera Launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            attachedBitmap = bitmap
        }
    }

    // Gallery Picker Launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    attachedBitmap = bmp
                }
            } catch (_: Exception) {}
        }
    }

    fun convertBitmapToBase64(bitmap: Bitmap): String {
        return com.bytelog.app.util.ImageUtils.convertBitmapToBase64(bitmap)
    }

    AlertDialog(
        onDismissRequest = { if (!isGenerating) onDismiss() },
        containerColor = ByteSurfaceDark,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(ByteTomato.copy(alpha = 0.2f))
                        .border(1.dp, ByteTomato, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = ByteTomato,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Generate with AI",
                        color = ByteCream,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Sparky AI creates & calculates on server",
                        color = ByteCreamMuted,
                        fontSize = 11.sp
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Food Name Field
                OutlinedTextField(
                    value = foodName,
                    onValueChange = { foodName = it },
                    label = { Text("Food Name", color = ByteCreamMuted) },
                    placeholder = { Text("e.g. Avocado Toast with Eggs", color = ByteCreamMuted) },
                    singleLine = true,
                    enabled = !isGenerating,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ByteTomato,
                        unfocusedBorderColor = ByteSurfaceVariantDark,
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        cursorColor = ByteTomato
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Optional Description / Details Field
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("More Details / Ingredients (Optional)", color = ByteCreamMuted) },
                    placeholder = { Text("e.g. 2 slices sourdough, 2 fried eggs, butter, homemade, ~350g", color = ByteCreamMuted) },
                    maxLines = 3,
                    enabled = !isGenerating,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ByteTomato,
                        unfocusedBorderColor = ByteSurfaceVariantDark,
                        focusedTextColor = ByteCream,
                        unfocusedTextColor = ByteCream,
                        cursorColor = ByteTomato
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Photo Option Section
                Text(
                    text = "Photo (Optional)",
                    color = ByteCreamMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                if (attachedBitmap == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { cameraLauncher.launch(null) },
                            enabled = !isGenerating,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ByteCream)
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Camera", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            enabled = !isGenerating,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ByteCream)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Gallery", fontSize = 12.sp)
                        }
                    }
                    Text(
                        text = "💡 Tip: Or leave empty and just send the name to generate instantly!",
                        color = ByteCreamMuted,
                        fontSize = 11.sp
                    )
                } else {
                    // Attached Photo Preview
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(ByteSurfaceVariantDark.copy(alpha = 0.5f))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                bitmap = attachedBitmap!!.asImageBitmap(),
                                contentDescription = "Attached food photo",
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Photo Attached",
                                    color = ByteCream,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Will be analyzed by Sparky AI",
                                    color = ByteCreamMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        IconButton(
                            onClick = { attachedBitmap = null },
                            enabled = !isGenerating
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove photo",
                                tint = ByteCreamMuted
                            )
                        }
                    }
                }

                // Error Message if any
                AnimatedVisibility(visible = errorMessage != null) {
                    errorMessage?.let { err ->
                        Text(
                            text = "⚠️ $err",
                            color = ByteTomato,
                            fontSize = 12.sp
                        )
                    }
                }

                // Loading State Indicator
                AnimatedVisibility(visible = isGenerating) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        com.bytelog.app.ui.diary.LoadingSpinner(
                            modifier = Modifier.size(16.dp),
                            color = ByteTomato,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Sparky AI is calculating nutrition & saving food…",
                            color = ByteCreamMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val base64 = attachedBitmap?.let { convertBitmapToBase64(it) }
                    onGenerate(foodName.trim(), description.trim(), base64, "image/jpeg")
                },
                enabled = foodName.isNotBlank() && !isGenerating,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ByteTomato,
                    contentColor = ByteOnTomato
                ),
                shape = RoundedCornerShape(50)
            ) {
                if (isGenerating) {
                    com.bytelog.app.ui.diary.LoadingSpinner(
                        modifier = Modifier.size(16.dp),
                        color = ByteOnTomato,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generating…", fontSize = 13.sp)
                } else {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Generate with AI", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isGenerating
            ) {
                Text("Cancel", color = ByteCreamMuted)
            }
        }
    )
}
