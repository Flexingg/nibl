package com.bytelog.app.ui.screens

import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import android.graphics.Bitmap
import com.bytelog.app.ui.theme.fluidBackdrop
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import com.bytelog.app.ui.components.pressBounce
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.bytelog.app.ui.theme.MacroProtein
import androidx.compose.material.icons.filled.Language
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.viewmodel.SparkyChatViewModel
import com.bytelog.app.util.ImageUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SparkyChatScreen(
    viewModel: SparkyChatViewModel,
    onNavigateBack: () -> Unit,
    onOpenShoppingList: () -> Unit = {},
    onOpenPlanner: () -> Unit = {}
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

    // Web research: the user's own model searches the web and can use their Sparky data.
    var webMode by rememberSaveable { mutableStateOf(false) }
    val researchStatus by viewModel.researchStatus.collectAsState()
    val pendingAction by viewModel.pendingAction.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val researchConfig by viewModel.researchConfig.collectAsState(initial = com.bytelog.app.research.ResearchConfig())

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
    // Keep the answer in view while it's being written
    LaunchedEffect(streamingText?.length?.div(120)) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (!streamingText.isNullOrEmpty() && last >= 0) listState.scrollToItem(last, scrollOffset = Int.MAX_VALUE / 2)
    }

    LaunchedEffect(messages.size, isLoading) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Approval for a change the web research assistant wants to make to the user's data
    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { viewModel.resolvePendingAction(false) },
            containerColor = ByteSurfaceDark,
            title = { Text(if (action.items.size > 1) "Allow these changes?" else "Allow this change?", color = ByteCream, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(action.summary, color = ByteCream, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (action.items.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        action.items.forEach { line ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text("•", color = ByteTomato, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text(line, color = ByteCream, fontSize = 13.sp)
                            }
                        }
                    }
                    if (action.argsPreview.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(action.argsPreview, color = ByteCreamMuted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(action.note ?: "The assistant wants to change your Sparky data.", color = ByteCreamMuted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolvePendingAction(true) }) {
                    Text(if (action.items.size > 1) "Allow all ${action.items.size}" else "Allow", color = ByteTomato, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { viewModel.resolvePendingAction(false) }) { Text("Don't allow", color = ByteCreamMuted) } }
        )
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
                            shape = RoundedCornerShape(50),
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
                            shape = RoundedCornerShape(50),
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
        modifier = Modifier.fluidBackdrop(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
        topBar = {
            com.bytelog.app.ui.components.ScreenHeader(
                title = "Sparky AI Coach",
                subtitle = "Your Sparky server's AI, plus web search",
                onBack = onNavigateBack,
                leading = {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        com.bytelog.app.ui.diary.MorphingBlob(
                            if (isLoading) com.bytelog.app.ui.diary.BlobShape.FLOWER8 else com.bytelog.app.ui.diary.BlobShape.COOKIE9,
                            MaterialTheme.colorScheme.primaryContainer, Modifier.fillMaxSize(), spinDegrees = 72f
                        )
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
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
                }
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
                                    .pressBounce(0.94f)
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        viewModel.sendMessage(prompt)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 9.dp)
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
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, ByteTomato, RoundedCornerShape(12.dp))
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

                // Web search toggle
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val canUseWeb = attachedBitmap == null || researchConfig.provider.readsImages
                    val active = webMode && canUseWeb
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (active) MacroProtein else ByteSurfaceDark)
                            .border(1.dp, if (active) MacroProtein else ByteSurfaceVariantDark, RoundedCornerShape(50))
                            .clickable(enabled = canUseWeb) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                webMode = !webMode
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = if (active) ByteSurfaceDark else ByteCreamMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (active) "Web search on · ${researchConfig.provider.label.substringBefore(' ')}" else "Web search",
                            color = if (active) ByteSurfaceDark else ByteCreamMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            !canUseWeb -> "${researchConfig.provider.shortName} can't read photos"
                            active && !researchConfig.isConfigured -> "Add an API key in Settings first"
                            active && attachedBitmap != null -> "Can read foods off this photo"
                            active -> "Uses your ${researchConfig.provider.label.substringBefore(' ')} key"
                            else -> ""
                        },
                        color = if (active && !researchConfig.isConfigured) ByteTomato else ByteCreamMuted,
                        fontSize = 11.sp
                    )
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
                            .size(46.dp)
                            .pressBounce(0.9f)
                            .clip(CircleShape)
                            .background(if (attachedBitmap != null) ByteTomato.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHigh)
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
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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

                    val sendSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val sendPressed by sendSource.collectIsPressedAsState()
                    val sendCorner by androidx.compose.animation.core.animateDpAsState(
                        if (sendPressed) 14.dp else 28.dp,
                        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 800f), label = "send_corner"
                    )
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .pressBounce(0.9f)
                            .clip(RoundedCornerShape(sendCorner))
                            .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(interactionSource = sendSource, indication = androidx.compose.material3.ripple(), enabled = canSend) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val textToSend = inputText
                                // The assistant may read a nutrition table off the photo, so it gets a sharper copy
                                val base64ToSend = attachedBitmap?.let {
                                    if (webMode && researchConfig.provider.readsImages) ImageUtils.convertBitmapToBase64(it, maxDim = 2048, quality = 90)
                                    else ImageUtils.convertBitmapToBase64(it)
                                }

                                inputText = ""
                                attachedBitmap = null

                                if (editingMessageId != null) {
                                    val targetId = editingMessageId!!
                                    editingMessageId = null
                                    viewModel.editAndResend(targetId, textToSend, base64ToSend, webMode)
                                } else {
                                    viewModel.sendMessage(textToSend, base64ToSend, webResearch = webMode)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) {
                            com.bytelog.app.ui.diary.LoadingSpinner(
                                modifier = Modifier.size(30.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary else ByteCreamMuted,
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
                    onShoppingList = onOpenShoppingList,
                    onOpenPlanner = onOpenPlanner,
                    onApplyEdit = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.applyPlanEdit(message.id) { n ->
                            scope.launch { snackbarHostState.showSnackbar(if (n == null) "Couldn't change the plan" else "Plan updated: $n food${if (n == 1) "" else "s"} changed") }
                        }
                    },
                    onUndoEdit = { viewModel.undoPlanEdit(message.id) },
                    onAddPlan = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.addPlan(message.id) { summary ->
                            scope.launch {
                                snackbarHostState.showSnackbar(if (summary != null) "Added to Planned: $summary" else "Couldn't add the plan")
                            }
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
                    SparkyThinkingIndicator(status = researchStatus, partialAnswer = streamingText)
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
    onEdit: (ChatMessageOutput) -> Unit,
    onAddPlan: () -> Unit = {},
    onShoppingList: () -> Unit = {},
    onApplyEdit: () -> Unit = {},
    onUndoEdit: () -> Unit = {},
    onOpenPlanner: (() -> Unit)? = null
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
                    .align(Alignment.Top),
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
            modifier = if (isUser) Modifier.fillMaxWidth(0.85f) else Modifier.weight(1f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = if (isUser) 22.dp else 6.dp,
                            topEnd = 22.dp,
                            bottomStart = 22.dp,
                            bottomEnd = if (isUser) 6.dp else 22.dp
                        )
                    )
                    .background(if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = if (isUser) 14.dp else 16.dp, vertical = if (isUser) 10.dp else 12.dp)
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
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (displayContent.isNotBlank() && isUser) {
                        Text(
                            text = displayContent,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 14.5.sp,
                            lineHeight = 21.sp
                        )
                    } else if (displayContent.isNotBlank()) {
                        com.bytelog.app.ui.components.MarkdownText(displayContent, color = ByteCream)
                    } else if (message.askUserPrompt?.question != null) {
                        Text(
                            text = message.askUserPrompt.question,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else ByteCream,
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
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(
                                            if (enabled) ByteInkDark.copy(alpha = 0.85f)
                                            else ByteInkDark.copy(alpha = 0.35f)
                                        )
                                        .border(
                                            1.dp,
                                            if (enabled) ByteTomato.copy(alpha = 0.7f)
                                            else ByteSurfaceVariantDark.copy(alpha = 0.4f),
                                            RoundedCornerShape(24.dp)
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

            // Copy (and Edit on the user's messages): under a question, and at the very end of an answer
            val actionRow: @Composable () -> Unit = {
                Row(
                    modifier = Modifier
                        .padding(top = 2.dp, start = if (isUser) 0.dp else 4.dp, end = if (isUser) 4.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Copy Action
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onCopy(if (isUser) message.content else com.bytelog.app.ui.components.Markdown.plain(displayContent)) }
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
                                .clip(RoundedCornerShape(12.dp))
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
            }
            if (isUser) actionRow()

            // Pages the web research assistant used
            if (!isUser && message.webSources.isNotEmpty()) {
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    message.webSources.take(8).forEach { src ->
                        val host = remember(src.url) { runCatching { java.net.URI(src.url).host?.removePrefix("www.") }.getOrNull() ?: src.title }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MacroProtein.copy(alpha = 0.12f))
                                .clickable { runCatching { uriHandler.openUri(src.url) } }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, tint = MacroProtein, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(host.take(32), color = MacroProtein, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // A meal plan, ready to add to the Planned list
            message.mealPlan?.let { plan ->
                if (!isUser) {
                    Spacer(modifier = Modifier.height(8.dp))
                    com.bytelog.app.ui.components.MealPlanCard(plan, added = message.planAdded, onAdd = onAddPlan, onShoppingList = onShoppingList)
                }
            }

            // Changes to the meal plan, ready to apply
            if (!isUser && message.planEdit != null && message.planEditLines.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                com.bytelog.app.ui.components.PlanEditCard(
                    message.planEditLines, applied = message.planEditApplied, onApply = onApplyEdit, onUndo = onUndoEdit, onOpenPlanner = onOpenPlanner
                )
            }

            // What the assistant did: one chip per kind of action ("Logged food ×4"), changes first
            val chips = remember(message.executedTools) { com.bytelog.app.ui.components.ChatToolChips.summarize(message.executedTools) }
            if (!isUser && chips.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { chip ->
                        val tint = if (chip.wrote) MacroCarbs else ByteCreamMuted
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(tint.copy(alpha = if (chip.wrote) 0.14f else 0.10f))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (chip.wrote) Icons.Default.Check else Icons.Default.Search,
                                contentDescription = null, tint = tint, modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                chip.label + if (chip.count > 1) " ×${chip.count}" else "",
                                color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false
                            )
                        }
                    }
                }
            }
            if (!isUser) {
                Spacer(modifier = Modifier.height(2.dp))
                actionRow()
            }
        }
    }
}

@Composable
fun SparkyThinkingIndicator(status: String? = null, partialAnswer: String? = null) {
    // The answer as it's written, with the spinner under it; the status line alone until text arrives
    if (!partialAnswer.isNullOrBlank()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(ByteTomato.copy(alpha = 0.2f)).border(1.dp, ByteTomato, CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = ByteTomato, modifier = Modifier.size(16.dp)) }
            Spacer(Modifier.width(8.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(24.dp))
                    .background(ByteSurfaceDark)
                    .border(1.dp, ByteSurfaceVariantDark, RoundedCornerShape(24.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                com.bytelog.app.ui.components.MarkdownText(partialAnswer, color = ByteCream)
                Spacer(Modifier.height(6.dp))
                com.bytelog.app.ui.diary.LoadingSpinner(modifier = Modifier.size(12.dp), color = ByteTomato, strokeWidth = 2.dp)
            }
        }
        return
    }
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
                .clip(RoundedCornerShape(24.dp))
                .background(ByteSurfaceDark)
                .border(1.dp, ByteSurfaceVariantDark, RoundedCornerShape(24.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.bytelog.app.ui.diary.LoadingSpinner(
                    modifier = Modifier.size(14.dp),
                    color = ByteTomato,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = status ?: "Sparky is thinking & executing tools on server…",
                    color = ByteCreamMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
