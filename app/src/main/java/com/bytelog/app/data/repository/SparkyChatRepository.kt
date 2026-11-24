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
    private val sparkyAiBackend: com.bytelog.app.research.SparkyAiBackend? = null,
    /** Where meal plans from the assistant are added. */
    private val planRepository: PlanRepository? = null,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val clock: () -> java.time.LocalTime = { java.time.LocalTime.now() }
) {
    private val _messages = MutableStateFlow<List<ChatMessageOutput>>(emptyList())
    val messages: StateFlow<List<ChatMessageOutput>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** What the web research assistant is doing right now ("Searching the web: …"). */
    private val _researchStatus = MutableStateFlow<String?>(null)
    val researchStatus: StateFlow<String?> = _researchStatus.asStateFlow()

    /** The web assistant's answer as it's being written (null when nothing is streaming). */
    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

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
                    val fromUser = entry.message_type == "user"
                    withPlan(ChatMessageOutput(
                        id = entry.id,
                        role = if (fromUser) "user" else "assistant",
                        // The meal plan note Nibl adds to a request is stored with it on the server
                        content = if (fromUser) MealPlan.stripNote(entry.content) else entry.content,
                        createdAt = System.currentTimeMillis(),
                        askUserPrompt = askPrompt
                    ))
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

        // Prepare context for backend chat. A meal plan request carries a note asking Sparky's AI to attach the plan in
        // a form Nibl can save, since Sparky can't save day-by-day plans itself.
        val asksForPlan = planRepository != null && MealPlan.wantsPlan(trimmed)
        // A change to the plan: Sparky's AI is shown what's planned (it can't see the plan) and asked for the changes
        val planned = if (planRepository != null && PlanEdits.wantsEdit(trimmed)) runCatching { planRepository?.upcoming(today()) }.getOrNull().orEmpty() else emptyList()
        val editNote = if (planned.isNotEmpty()) PlanEdits.formatNote(planned, today()) else ""
        val payloadMessages = currentList.filter { it.id != "welcome" }.map {
            ChatMessageInput(
                role = it.role,
                content = if (it.id == userMessage.id) it.content + (if (asksForPlan) MealPlan.formatNote(today()) else "") + editNote else it.content,
                imageBase64 = it.imageBase64
            )
        }

        // Tools Sparky may use: the user's selection in Settings (default: all, read and write).
        val categories = settingsRepository?.sparkyToolCategories?.first()?.let { com.bytelog.app.data.remote.SparkyTools.normalize(it) }
        val result = sparkyApiClient.sendChatMessage(payloadMessages, toolCategories = categories)
        _isLoading.value = false

        if (result.isSuccess) {
            val response = result.getOrNull()?.let { withPlan(it) }
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
            sparkyAi = sparkyAiBackend ?: com.bytelog.app.research.ChatSparkyAiBackend(sparkyApiClient),
            planReader = planRepository?.let { repo -> { repo.upcoming(today()) } },
            userContext = { researchContext() },
            onText = { _streamingText.value = it.ifEmpty { null } }
        )
        val history = researchHistory(priorMessages)

        _researchStatus.value = "Thinking…"
        val result = try {
            agent.run(history, question)
        } finally {
            _researchStatus.value = null
            _streamingText.value = null
            _pendingAction.value = null
            pendingDecision = null
            _isLoading.value = false
        }
        result.fold(
            onSuccess = { r ->
                val body = if (r.notice != null && r.text != r.notice) r.text + "\n\n_${r.notice}_" else r.text
                addAssistant(body, viaWeb = true, sources = r.sources, tools = r.sparkyToolsRun + r.delegatedTools.map { "sparky_ai:$it" }, plan = r.mealPlan,
                    actionNote = r.actionNote)
                r.planEdit?.let { edit ->
                    val last = _messages.value.last()
                    val withCard = withEdit(last, edit)
                    _messages.value = _messages.value.dropLast(1) + withCard
                }
                if (r.wroteData) {
                    // Refresh every day the changes touched, not just today
                    val days = r.touchedDates.ifEmpty { setOf(today()) }.map { it.format(DateTimeFormatter.ISO_LOCAL_DATE) }
                    appScope.launch(Dispatchers.IO) {
                        days.forEach { day -> try { foodRepository.syncDateWithServer(day) } catch (_: Exception) {} }
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
        tools: List<String> = emptyList(),
        plan: MealPlanDraft? = null,
        actionNote: String? = null
    ) {
        _messages.value = _messages.value + ChatMessageOutput(
            role = "assistant",
            content = content,
            executedTools = tools,
            webSources = sources,
            viaWeb = viaWeb,
            mealPlan = plan,
            actionNote = actionNote
        )
    }

    /**
     * The visible chat as history for the assistant. Its own earlier answers carry a note of what it did with the
     * user's data (tools and ids), so follow-ups like "make that 2 servings" can find the entry it logged.
     */
    private fun researchHistory(prior: List<ChatMessageOutput>): List<com.bytelog.app.research.ChatTurn> =
        prior.filter { it.id != "welcome" && it.content.isNotBlank() }.takeLast(HISTORY_MESSAGES).map {
            val note = it.actionNote?.takeIf { n -> it.role != "user" && n.isNotBlank() }
            com.bytelog.app.research.ChatTurn(
                fromUser = it.role == "user",
                text = if (note == null) it.content else it.content + "\n\n[Note for the assistant, not shown to the user. What I did with the user's data in this answer:\n" + note + "]"
            )
        }

    /**
     * What the assistant should know about the user right now: their local time and the meal it suggests, their
     * daily goals and what's left of them today. Read from the phone, so it costs no lookup.
     */
    private suspend fun researchContext(): String? {
        val now = clock()
        val day = today()
        return buildString {
            append("- Local time: ").append(now.format(DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)))
                .append(". The meal that fits now: ").append(mealAt(now)).append(".\n")
            val settings = settingsRepository
            if (settings != null) {
                val kcal = settings.targetCalories.first(); val p = settings.targetProtein.first()
                val c = settings.targetCarbs.first(); val f = settings.targetFat.first()
                append("- Daily goals: ${kcal.roundToInt()} kcal, protein ${p.roundToInt()} g, carbs ${c.roundToInt()} g, fat ${f.roundToInt()} g.\n")
                val entries = runCatching { foodRepository.getEntriesForDate(day.toString()).first() }.getOrNull()
                    ?.filter { it.syncStatus != com.bytelog.app.data.local.model.FoodEntry.SYNC_STATUS_PENDING_DELETE }
                if (entries != null) {
                    val eK = entries.sumOf { it.calories.toDouble() }; val eP = entries.sumOf { it.protein.toDouble() }
                    val eC = entries.sumOf { it.carbs.toDouble() }; val eF = entries.sumOf { it.fat.toDouble() }
                    if (entries.isEmpty()) append("- Nothing logged yet today.\n")
                    else {
                        val meals = entries.groupBy { it.mealName }.entries.joinToString(", ") { (m, list) -> "$m (${list.size})" }
                        append("- Logged today: ${eK.roundToInt()} kcal, protein ${eP.roundToInt()} g, carbs ${eC.roundToInt()} g, fat ${eF.roundToInt()} g. Meals: $meals.\n")
                    }
                    append("- Left today: ${(kcal - eK).roundToInt()} kcal, protein ${(p - eP).roundToInt()} g, carbs ${(c - eC).roundToInt()} g, fat ${(f - eF).roundToInt()} g.\n")
                }
            }
        }.trim().ifEmpty { null }
    }

    private fun Double.roundToInt() = kotlin.math.round(this).toInt()
    private fun Float.roundToInt() = kotlin.math.round(this).toInt()

    /** An answer with its meal plan block (if any) read into a plan and taken out of the text. */
    /** An answer with its meal plan and plan changes (if any) read into cards and taken out of the text. */
    private suspend fun withPlan(m: ChatMessageOutput): ChatMessageOutput {
        if (m.role != "assistant" || planRepository == null) return m
        var out = m
        MealPlan.parse(out.content, today())?.let { plan -> out = out.copy(content = MealPlan.stripBlock(out.content), mealPlan = plan) }
        PlanEdits.parse(out.content, today())?.let { edit -> out = withEdit(out.copy(content = PlanEdits.stripBlock(out.content)), edit) }
        return out
    }

    private suspend fun withEdit(m: ChatMessageOutput, edit: PlanEditDraft): ChatMessageOutput {
        val planned = runCatching { planRepository?.upcoming(today()) }.getOrNull().orEmpty()
        return m.copy(planEdit = edit, planEditLines = PlanEdits.describe(edit, planned))
    }

    /** What applying each message's plan changes did, for its undo. */
    private val appliedEdits = HashMap<String, AppliedPlanEdit>()

    /** Applies the plan changes in message [messageId]. Returns how many foods changed, or null if there were none. */
    suspend fun applyPlanEdit(messageId: String): Int? {
        val repo = planRepository ?: return null
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return null
        val edit = msg.planEdit ?: return null
        if (msg.planEditApplied) return 0
        val applied = repo.applyEdit(edit, today())
        appliedEdits[messageId] = applied
        _messages.value = _messages.value.map { if (it.id == messageId) it.copy(planEditApplied = true) else it }
        return applied.count
    }

    /** Undoes [applyPlanEdit] for the message. */
    suspend fun undoPlanEdit(messageId: String) {
        val repo = planRepository ?: return
        val applied = appliedEdits.remove(messageId) ?: return
        repo.undoEdit(applied)
        _messages.value = _messages.value.map { if (it.id == messageId) it.copy(planEditApplied = false) else it }
    }

    /** Adds the meal plan in message [messageId] to the Planned list. Returns a summary, or null if there's none. */
    suspend fun addPlan(messageId: String): String? {
        val repo = planRepository ?: return null
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return null
        val plan = msg.mealPlan ?: return null
        if (msg.planAdded) return MealPlan.summary(plan)
        repo.addPlan(plan)
        _messages.value = _messages.value.map { if (it.id == messageId) it.copy(planAdded = true) else it }
        return MealPlan.summary(plan)
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

    companion object {
        /** How many recent messages the web assistant sees as history. */
        const val HISTORY_MESSAGES = 16

        /** The meal that usually fits [time]. */
        fun mealAt(time: java.time.LocalTime): String = when {
            time.isBefore(java.time.LocalTime.of(4, 0)) -> "Snacks"
            time.isBefore(java.time.LocalTime.of(10, 30)) -> "Breakfast"
            time.isBefore(java.time.LocalTime.of(14, 30)) -> "Lunch"
            time.isBefore(java.time.LocalTime.of(17, 0)) -> "Snacks"
            time.isBefore(java.time.LocalTime.of(21, 0)) -> "Dinner"
            else -> "Snacks"
        }
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
