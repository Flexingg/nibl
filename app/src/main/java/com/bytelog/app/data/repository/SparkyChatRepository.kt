package com.bytelog.app.data.repository

import com.bytelog.app.data.remote.ChatMessageInput
import com.bytelog.app.data.remote.ChatMessageOutput
import com.bytelog.app.data.remote.SparkyApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

class SparkyChatRepository(
    private val sparkyApiClient: SparkyApiClient,
    private val foodRepository: FoodRepository,
    private val weightRepository: WeightRepository,
    private val appScope: CoroutineScope
) {
    private val _messages = MutableStateFlow<List<ChatMessageOutput>>(emptyList())
    val messages: StateFlow<List<ChatMessageOutput>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val initialWelcomeMessage = ChatMessageOutput(
        id = "welcome",
        role = "assistant",
        content = "👋 Hi! I'm Sparky, your AI fitness & nutrition coach.\n\nAsk me to log your food (\"I had 2 eggs and toast for breakfast\"), log your weight (\"Log my weight as 175 lbs\"), calculate your macros, or give you personalized nutrition recommendations.\n\nAll intelligence & tool operations execute directly on your Sparky backend!",
        createdAt = System.currentTimeMillis()
    )

    init {
        appScope.launch(Dispatchers.IO) {
            loadHistory()
        }
    }

    suspend fun loadHistory() {
        val result = sparkyApiClient.fetchChatHistory()
        if (result.isSuccess) {
            val serverHistory = result.getOrNull().orEmpty()
            if (serverHistory.isNotEmpty()) {
                val mapped = serverHistory.map { entry ->
                    ChatMessageOutput(
                        id = entry.id,
                        role = if (entry.message_type == "user") "user" else "assistant",
                        content = entry.content,
                        createdAt = System.currentTimeMillis()
                    )
                }
                _messages.value = mapped
            } else {
                _messages.value = listOf(initialWelcomeMessage)
            }
        } else {
            if (_messages.value.isEmpty()) {
                _messages.value = listOf(initialWelcomeMessage)
            }
        }
    }

    suspend fun sendMessage(userText: String, imageBase64: String? = null) {
        val trimmed = userText.trim()
        if (trimmed.isBlank() && imageBase64.isNullOrBlank()) return
        if (_isLoading.value) return

        val displayContent = if (trimmed.isBlank() && !imageBase64.isNullOrBlank()) {
            "Analyze attached image"
        } else {
            trimmed
        }

        val userMessage = ChatMessageOutput(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = displayContent,
            imageBase64 = imageBase64,
            createdAt = System.currentTimeMillis()
        )

        // Optimistically add user message
        val currentList = _messages.value.toMutableList()
        currentList.add(userMessage)
        _messages.value = currentList
        _isLoading.value = true
        _error.value = null

        // Prepare context for backend chat
        val payloadMessages = currentList.filter { it.id != "welcome" }.map {
            ChatMessageInput(
                role = it.role,
                content = it.content,
                imageBase64 = it.imageBase64
            )
        }

        val result = sparkyApiClient.sendChatMessage(payloadMessages)
        _isLoading.value = false

        if (result.isSuccess) {
            val response = result.getOrNull()
            if (response != null) {
                val updated = _messages.value.toMutableList()
                updated.add(response)
                _messages.value = updated

                // If tools were executed by Sparky server, trigger background sync
                val executedTools = response.executedTools
                val action = response.action.orEmpty()
                val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

                if (executedTools.any { it.contains("food") } || action == "food_added") {
                    appScope.launch(Dispatchers.IO) {
                        try {
                            foodRepository.syncDateWithServer(today)
                        } catch (_: Exception) {}
                    }
                }

                if (executedTools.any { it.contains("checkin") || it.contains("weight") } || action == "measurement_added") {
                    appScope.launch(Dispatchers.IO) {
                        try {
                            weightRepository.syncWeightHistory()
                        } catch (_: Exception) {}
                    }
                }
            }
        } else {
            val ex = result.exceptionOrNull()
            val errorMsg = ex?.message ?: "Failed to connect to Sparky AI service."
            _error.value = errorMsg

            val errorBotMsg = ChatMessageOutput(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                content = "⚠️ Unable to get a response from Sparky AI ($errorMsg). Please check your backend connection in Settings.",
                createdAt = System.currentTimeMillis()
            )
            val updated = _messages.value.toMutableList()
            updated.add(errorBotMsg)
            _messages.value = updated
        }
    }

    suspend fun editAndResend(targetMessageId: String, newText: String, newImageBase64: String? = null) {
        val current = _messages.value.toMutableList()
        val index = current.indexOfFirst { it.id == targetMessageId }
        if (index != -1) {
            // Drop target message and all subsequent messages to branch/retry conversation
            val trimmedList = current.subList(0, index)
            _messages.value = trimmedList.toList()
        }
        sendMessage(newText, newImageBase64)
    }

    suspend fun clearHistory(): Boolean {
        _isLoading.value = true
        val result = sparkyApiClient.clearChatHistory()
        _isLoading.value = false
        _messages.value = listOf(initialWelcomeMessage)
        _error.value = null
        return result.isSuccess
    }
}
