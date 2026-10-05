package com.bytelog.app.data.repository

import com.bytelog.app.data.remote.ChatMessageInput
import com.bytelog.app.data.remote.ChatMessageOutput
import com.bytelog.app.data.remote.SparkyApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    private val appScope: CoroutineScope,
    private val settingsRepository: SettingsRepository? = null,
    private val toolBackend: com.bytelog.app.research.SparkyToolBackend? = null,
    private val webFetcher: com.bytelog.app.research.WebFetcher = com.bytelog.app.research.WebFetcher(),
    private val providerHttp: com.bytelog.app.research.ProviderHttp = com.bytelog.app.research.ProviderHttp(),
    /** Replaces the AI providers' web address (tests). */
    private val providerBaseUrl: String? = null,
    /** Replaces the hand-off to Sparky's AI (tests). */
    private val sparkyAiBackend: com.bytelog.app.research.SparkyAiBackend? = null
) {
    private val _messages = MutableStateFlow<List<ChatMessageOutput>>(emptyList())
    val messages: StateFlow<List<ChatMessageOutput>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** What the web research assistant is doing right now ("Searching the web: …"). */
    private val _researchStatus = MutableStateFlow<String?>(null)
    val researchStatus: StateFlow<String?> = _researchStatus.asStateFlow()

    /** A change the web assistant wants to make; the chat shows it for approval. */
    private val _pendingAction = MutableStateFlow<com.bytelog.app.research.PendingAction?>(null)
    val pendingAction: StateFlow<com.bytelog.app.research.PendingAction?> = _pendingAction.asStateFlow()
    private var pendingDecision: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    /** The user's research settings (null when the repository has no settings). */
    val researchConfig: kotlinx.coroutines.flow.Flow<com.bytelog.app.research.ResearchConfig> =
        settingsRepository?.researchConfig ?: kotlinx.coroutines.flow.flowOf(com.bytelog.app.research.ResearchConfig())

    fun resolvePendingAction(approved: Boolean) {
        pendingDecision?.complete(approved)
    }

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
                    val askPrompt = if (entry.message_type != "user") {
                        SparkyApiClient.extractAskUserPromptFromText(entry.content)
                    } else null
                    ChatMessageOutput(
                        id = entry.id,
                        role = if (entry.message_type == "user") "user" else "assistant",
                        content = entry.content,
                        createdAt = System.currentTimeMillis(),
                        askUserPrompt = askPrompt
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

    suspend fun sendMessage(userText: String, imageBase64: String? = null, webResearch: Boolean = false) {
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

        if (webResearch && imageBase64.isNullOrBlank()) {
            runWebResearch(trimmed, priorMessages = currentList.dropLast(1))
            return
        }

        // Prepare context for backend chat
        val payloadMessages = currentList.filter { it.id != "welcome" }.map {
            ChatMessageInput(
                role = it.role,
                content = it.content,
                imageBase64 = it.imageBase64
            )
        }

        // Tools Sparky may use: the user's selection in Settings (default: all, read and write).
        val categories = settingsRepository?.sparkyToolCategories?.first()?.let { com.bytelog.app.data.remote.SparkyTools.normalize(it) }
        val result = sparkyApiClient.sendChatMessage(payloadMessages, toolCategories = categories)
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

                if (executedTools.any { t -> listOf("food", "meal", "diary", "entry", "water").any { t.contains(it, ignoreCase = true) } } || action == "food_added") {
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

    /**
     * Answers with the web research assistant: the user's chosen model (their own API key) searches the web and
     * can use their Sparky tools. Changes to their data need approval unless they turned that off.
     */
    private suspend fun runWebResearch(question: String, priorMessages: List<ChatMessageOutput>) {
        val settings = settingsRepository
        val config = settings?.researchConfig?.first() ?: com.bytelog.app.research.ResearchConfig()
        val provider = com.bytelog.app.research.ResearchFactory.provider(config, providerHttp, providerBaseUrl)
        if (provider == null) {
            _isLoading.value = false
            addAssistant("🌐 Web research needs an API key. Add one in Settings → Web research assistant, then try again.", viaWeb = true)
            return
        }
        val backend = toolBackend ?: com.bytelog.app.research.McpToolBackend(sparkyApiClient)
        val enabledTools = settings?.sparkyToolCategories?.first() ?: com.bytelog.app.data.remote.SparkyTools.allSlugs
        val agent = com.bytelog.app.research.ResearchAgent(
            provider = provider,
            sparky = backend,
            search = com.bytelog.app.research.ResearchFactory.searchBackend(config, providerHttp, providerBaseUrl),
            fetcher = webFetcher,
            enabledCategories = enabledTools,
            askBeforeWriting = config.askBeforeWriting,
            confirm = { action -> askUser(action) },
            onProgress = { _researchStatus.value = it },
            // Logging goes through Sparky's own AI, which the assistant can talk to as often as it needs.
            sparkyAi = sparkyAiBackend ?: com.bytelog.app.research.ChatSparkyAiBackend(sparkyApiClient)
        )
        val history = priorMessages.filter { it.id != "welcome" && it.content.isNotBlank() }.takeLast(12)
            .map { com.bytelog.app.research.ChatTurn(fromUser = it.role == "user", text = it.content) }

        _researchStatus.value = "Thinking…"
        val result = try {
            agent.run(history, question)
        } finally {
            _researchStatus.value = null
            _pendingAction.value = null
            pendingDecision = null
            _isLoading.value = false
        }
        result.fold(
            onSuccess = { r ->
                val body = if (r.notice != null && r.text != r.notice) r.text + "\n\n_${r.notice}_" else r.text
                addAssistant(body, viaWeb = true, sources = r.sources, tools = r.sparkyToolsRun + r.delegatedTools.map { "sparky_ai:$it" })
                if (r.wroteData) {
                    val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    appScope.launch(Dispatchers.IO) {
                        try { foodRepository.syncDateWithServer(today) } catch (_: Exception) {}
                        try { weightRepository.syncWeightHistory() } catch (_: Exception) {}
                    }
                }
            },
            onFailure = { e ->
                _error.value = e.message
                addAssistant("⚠️ ${e.message ?: "The web research assistant failed."}", viaWeb = true)
            }
        )
    }

    private suspend fun askUser(action: com.bytelog.app.research.PendingAction): Boolean {
        val decision = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pendingDecision = decision
        _pendingAction.value = action
        return try {
            decision.await()
        } finally {
            _pendingAction.value = null
            pendingDecision = null
        }
    }

    private fun addAssistant(
        content: String,
        viaWeb: Boolean = false,
        sources: List<com.bytelog.app.research.WebSource> = emptyList(),
        tools: List<String> = emptyList()
    ) {
        _messages.value = _messages.value + ChatMessageOutput(
            role = "assistant",
            content = content,
            executedTools = tools,
            webSources = sources,
            viaWeb = viaWeb
        )
    }

    suspend fun editAndResend(targetMessageId: String, newText: String, newImageBase64: String? = null, webResearch: Boolean = false) {
        val current = _messages.value.toMutableList()
        val index = current.indexOfFirst { it.id == targetMessageId }
        if (index != -1) {
            // Drop target message and all subsequent messages to branch/retry conversation
            val trimmedList = current.subList(0, index)
            _messages.value = trimmedList.toList()
        }
        sendMessage(newText, newImageBase64, webResearch)
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
