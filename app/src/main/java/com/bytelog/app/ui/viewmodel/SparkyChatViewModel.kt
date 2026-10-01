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
        "Give me a high protein snack under 200 calories"
    )

    fun sendMessage(content: String) {
        viewModelScope.launch {
            chatRepository.sendMessage(content)
        }
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
