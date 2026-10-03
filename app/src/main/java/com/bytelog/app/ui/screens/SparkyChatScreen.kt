package com.bytelog.app.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.remote.ChatMessageOutput
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.viewmodel.SparkyChatViewModel
import com.bytelog.app.util.ImageUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SparkyChatScreen(
    viewModel: SparkyChatViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var attachedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var editingMessageId by remember { mutableStateOf<String?>(null) }

    var showClearConfirm by remember { mutableStateOf(false) }
    var showImageSourceDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            attachedBitmap = bitmap
        }
    }

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val bmp = ImageUtils.loadBitmapFromUri(context, uri)
            if (bmp != null) {
                attachedBitmap = bmp
            }
        }
    }

    // Auto-scroll to the newest message
    LaunchedEffect(messages.size, isLoading) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Image Source Picker Dialog
    if (showImageSourceDialog) {
        AlertDialog(
            onDismissRequest = { showImageSourceDialog = false },
            title = {
                Text("Attach Image for Sparky AI", color = ByteCream, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Sparky can analyze meal photos, food nutrition labels, or body check-in photos directly on the backend.",
                        color = ByteCreamMuted,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showImageSourceDialog = false
                                cameraLauncher.launch(null)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ByteCream)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Camera", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                showImageSourceDialog = false
                                galleryLauncher.launch("image/*")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ByteCream)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Gallery", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImageSourceDialog = false }) {
                    Text("Cancel", color = ByteCreamMuted)
                }
            },
            containerColor = ByteSurfaceDark
        )
    }

    // Clear History Dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Chat History", color = ByteCream) },
            text = { Text("Are you sure you want to clear your conversation history with Sparky AI on the server?", color = ByteCreamMuted) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        showClearConfirm = false
                    }
                ) {
                    Text("Clear All", color = ByteTomato, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", color = ByteCream)
                }
            },
            containerColor = ByteSurfaceDark
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = ByteInkDark,
        topBar = {
            TopAppBar(
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
                                contentDescription = "Sparky AI",
                                tint = ByteTomato,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Sparky AI Coach",
                                color = ByteCream,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Backend MCP & Fitness Agent",
                                color = ByteCreamMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = ByteCream
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear History",
                            tint = ByteCreamMuted
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ByteInkDark
                )
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ByteInkDark)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // Quick suggested prompts row (hidden while editing)
                if (editingMessageId == null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        viewModel.suggestedPrompts.forEach { prompt ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(ByteSurfaceDark)
                                    .border(1.dp, ByteSurfaceVariantDark, RoundedCornerShape(16.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        viewModel.sendMessage(prompt)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = prompt,
                                    color = ByteCream,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Editing banner indicator
                AnimatedVisibility(visible = editingMessageId != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ByteSurfaceDark)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = ByteTomato,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Editing previous message",
                                color = ByteTomato,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(
                            onClick = {
                                editingMessageId = null
                                inputText = ""
                                attachedBitmap = null
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel edit",
                                tint = ByteCreamMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Attached image thumbnail preview strip
                AnimatedVisibility(visible = attachedBitmap != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, ByteTomato, RoundedCornerShape(8.dp))
                        ) {
                            attachedBitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Attachment preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Photo attached",
                                color = ByteCream,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Sparky AI will inspect this image with vision",
                                color = ByteCreamMuted,
                                fontSize = 11.sp
                            )
                        }
                        IconButton(onClick = { attachedBitmap = null }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove photo",
                                tint = ByteCreamMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Chat text input box
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Image attachment icon button
                    IconButton(
                        onClick = { showImageSourceDialog = true },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (attachedBitmap != null) ByteTomato.copy(alpha = 0.2f) else ByteSurfaceDark)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "Attach Photo",
                            tint = if (attachedBitmap != null) ByteTomato else ByteCreamMuted,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                text = if (attachedBitmap != null) "Describe or ask about this photo…" else "Ask Sparky or log food/weight…",
                                color = ByteCreamMuted,
                                fontSize = 14.sp
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = ByteSurfaceDark,
                            unfocusedContainerColor = ByteSurfaceDark,
                            focusedTextColor = ByteCream,
                            unfocusedTextColor = ByteCream,
                            cursorColor = ByteTomato,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    val canSend = (inputText.isNotBlank() || attachedBitmap != null) && !isLoading

                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(if (canSend) ByteTomato else ByteSurfaceDark)
                            .clickable(enabled = canSend) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val textToSend = inputText
                                val base64ToSend = attachedBitmap?.let { ImageUtils.convertBitmapToBase64(it) }

                                inputText = ""
                                attachedBitmap = null

                                if (editingMessageId != null) {
                                    val targetId = editingMessageId!!
                                    editingMessageId = null
                                    viewModel.editAndResend(targetId, textToSend, base64ToSend)
                                } else {
                                    viewModel.sendMessage(textToSend, base64ToSend)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = ByteTomato,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (canSend) ByteCream else ByteCreamMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                val isLastAssistantMessage = !isLoading &&
                    message.role == "assistant" &&
                    message == messages.lastOrNull { it.role == "assistant" }

                ChatMessageBubble(
                    message = message,
                    isLastAssistantMessage = isLastAssistantMessage,
                    onOptionSelected = { option ->
                        viewModel.sendMessage(option)
                    },
                    onCopy = { text ->
                        clipboardManager.setText(AnnotatedString(text))
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch {
                            snackbarHostState.showSnackbar("Message copied to clipboard")
                        }
                    },
                    onEdit = { msg ->
                        editingMessageId = msg.id
                        inputText = msg.content
                        if (!msg.imageBase64.isNullOrBlank()) {
                            attachedBitmap = ImageUtils.decodeBase64ToBitmap(msg.imageBase64)
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                )
            }

            if (isLoading) {
                item {
                    SparkyThinkingIndicator()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatMessageBubble(
    message: ChatMessageOutput,
    isLastAssistantMessage: Boolean = false,
    onOptionSelected: (String) -> Unit = {},
    onCopy: (String) -> Unit,
    onEdit: (ChatMessageOutput) -> Unit
) {
    val isUser = message.role == "user"
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val attachedBitmap = remember(message.imageBase64) {
        message.imageBase64?.let { ImageUtils.decodeBase64ToBitmap(it) }
    }
    val displayContent = remember(message.content) {
        message.content.replace(Regex("""(?i)\s*\(options offered:\s*.+?\)"""), "").trim()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(ByteTomato.copy(alpha = 0.2f))
                    .border(1.dp, ByteTomato, CircleShape)
                    .align(Alignment.Bottom),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = ByteTomato,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.fillMaxWidth(0.85f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .background(if (isUser) ByteTomato else ByteSurfaceDark)
                    .border(
                        1.dp,
                        if (isUser) Color.Transparent else ByteSurfaceVariantDark,
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    // Display attached image if present in message
                    if (attachedBitmap != null) {
                        Image(
                            bitmap = attachedBitmap.asImageBitmap(),
                            contentDescription = "Attached food or workout photo",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (displayContent.isNotBlank()) {
                        Text(
                            text = displayContent,
                            color = ByteCream,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    } else if (message.askUserPrompt?.question != null) {
                        Text(
                            text = message.askUserPrompt.question,
                            color = ByteCream,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }

                    // Render quick-reply choices/options if present (from sparky_ask_user tool or assistant prompt)
                    val askPrompt = message.askUserPrompt
                    if (!isUser && askPrompt != null && askPrompt.options.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))

                        if (!askPrompt.question.isNullOrBlank() &&
                            !displayContent.contains(askPrompt.question, ignoreCase = true) &&
                            displayContent.isNotBlank()
                        ) {
                            Text(
                                text = askPrompt.question,
                                color = ByteCreamMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            askPrompt.options.forEach { option ->
                                val enabled = isLastAssistantMessage
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(
                                            if (enabled) ByteInkDark.copy(alpha = 0.85f)
                                            else ByteInkDark.copy(alpha = 0.35f)
                                        )
                                        .border(
                                            1.dp,
                                            if (enabled) ByteTomato.copy(alpha = 0.7f)
                                            else ByteSurfaceVariantDark.copy(alpha = 0.4f),
                                            RoundedCornerShape(20.dp)
                                        )
                                        .clickable(enabled = enabled) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onOptionSelected(option)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = option,
                                        color = if (enabled) ByteCream else ByteCreamMuted.copy(alpha = 0.5f),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Action row below message (Copy & Edit)
            Row(
                modifier = Modifier
                    .padding(top = 2.dp, start = if (isUser) 0.dp else 4.dp, end = if (isUser) 4.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Copy Action
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onCopy(message.content) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy message",
                        tint = ByteCreamMuted,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "Copy",
                        color = ByteCreamMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Edit Action (User messages only)
                if (isUser) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onEdit(message) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit message",
                            tint = ByteCreamMuted,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "Edit",
                            color = ByteCreamMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Executed tool execution badges (excluding sparky_ask_user which renders interactive chips)
            val visibleTools = message.executedTools.filter { it != "sparky_ask_user" }
            if (!isUser && (visibleTools.isNotEmpty() || !message.action.isNullOrBlank())) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    visibleTools.forEach { tool ->
                        val friendlyTool = when {
                            tool.contains("food") -> "Logged Food"
                            tool.contains("checkin") -> "Weight Updated"
                            tool.contains("exercise") -> "Exercise Logged"
                            else -> tool
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MacroCarbs.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "✓ $friendlyTool",
                                color = MacroCarbs,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SparkyThinkingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(ByteTomato.copy(alpha = 0.2f))
                .border(1.dp, ByteTomato, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = ByteTomato,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(ByteSurfaceDark)
                .border(1.dp, ByteSurfaceVariantDark, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = ByteTomato,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Sparky is thinking & executing tools on server…",
                    color = ByteCreamMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
