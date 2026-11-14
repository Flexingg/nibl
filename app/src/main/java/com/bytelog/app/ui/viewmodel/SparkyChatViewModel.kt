package com.bytelog.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.remote.ChatMessageOutput
import com.bytelog.app.data.repository.SparkyChatRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SparkyChatViewModel(
    private val chatRepository: SparkyChatRepository
) : ViewModel() {

    val messages: StateFlow<List<ChatMessageOutput>> = chatRepository.messages
    val isLoading: StateFlow<Boolean> = chatRepository.isLoading
    val error: StateFlow<String?> = chatRepository.error

    val suggestedPrompts = listOf(
        "Log 2 eggs and toast for breakfast",
        "What are my remaining calories and protein today?",
        "Log my weight as 175.5 lbs",
        "Give me a high protein snack under 200 calories",
        "Make me a meal plan for the next 3 days",
        "Swap tomorrow's dinner in my plan for something cheaper with more protein"
    )

    val researchStatus: StateFlow<String?> = chatRepository.researchStatus
    val pendingAction: StateFlow<com.bytelog.app.research.PendingAction?> = chatRepository.pendingAction
    val researchConfig = chatRepository.researchConfig

    fun resolvePendingAction(approved: Boolean) = chatRepository.resolvePendingAction(approved)

    fun sendMessage(content: String, imageBase64: String? = null, webResearch: Boolean = false) {
        viewModelScope.launch {
            chatRepository.sendMessage(content, imageBase64, webResearch)
        }
    }

    fun editAndResend(targetMessageId: String, newContent: String, newImageBase64: String? = null, webResearch: Boolean = false) {
        viewModelScope.launch {
            chatRepository.editAndResend(targetMessageId, newContent, newImageBase64, webResearch)
        }
    }

    /** Adds a message's meal plan to the Planned list; [done] gets a summary of what was added. */
    fun addPlan(messageId: String, done: (String?) -> Unit = {}) {
        viewModelScope.launch {
            done(runCatching { chatRepository.addPlan(messageId) }.getOrNull())
        }
    }

    /** Applies a message's plan changes; [done] gets how many foods changed. */
    fun applyPlanEdit(messageId: String, done: (Int?) -> Unit = {}) {
        viewModelScope.launch { done(runCatching { chatRepository.applyPlanEdit(messageId) }.getOrNull()) }
    }

    fun undoPlanEdit(messageId: String) {
        viewModelScope.launch { runCatching { chatRepository.undoPlanEdit(messageId) } }
    }

    fun clearHistory() {
        viewModelScope.launch {
            chatRepository.clearHistory()
        }
    }

    fun reload() {
        viewModelScope.launch {
            chatRepository.loadHistory()
        }
    }
}
