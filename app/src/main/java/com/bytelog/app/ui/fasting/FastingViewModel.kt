package com.bytelog.app.ui.fasting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.fasting.FastingLog
import com.bytelog.app.data.fasting.FastingPreferences
import com.bytelog.app.data.fasting.FastingProtocol
import com.bytelog.app.data.fasting.FastingRepository
import com.bytelog.app.data.fasting.FastingStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

class FastingViewModel(
    private val repository: FastingRepository
) : ViewModel() {

    val activeFast: StateFlow<FastingLog?> = repository.activeFast
    val history: StateFlow<List<FastingLog>> = repository.history
    val stats: StateFlow<FastingStats> = repository.stats
    val preferences: StateFlow<FastingPreferences> = repository.preferences
    val isLoading: StateFlow<Boolean> = repository.isLoading

    private val _selectedProtocol = MutableStateFlow(
        FastingProtocol.fromCode(repository.preferences.value.defaultProtocol)
    )
    val selectedProtocol: StateFlow<FastingProtocol> = _selectedProtocol.asStateFlow()

    private val _customTargetHours = MutableStateFlow(
        repository.preferences.value.targetFastingHours.coerceIn(4f, 72f)
    )
    val customTargetHours: StateFlow<Float> = _customTargetHours.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        viewModelScope.launch {
            repository.preferences.collect { prefs ->
                if (activeFast.value == null) {
                    _selectedProtocol.value = FastingProtocol.fromCode(prefs.defaultProtocol)
                    _customTargetHours.value = prefs.targetFastingHours.coerceIn(4f, 72f)
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refresh()
        }
    }

    fun selectProtocol(protocol: FastingProtocol) {
        _selectedProtocol.value = protocol
        if (protocol != FastingProtocol.CUSTOM) {
            _customTargetHours.value = protocol.fastHours
        }
    }

    fun setCustomTargetHours(hours: Float) {
        _customTargetHours.value = hours.coerceIn(4f, 72f)
        _selectedProtocol.value = FastingProtocol.CUSTOM
    }

    fun startFast(
        startTime: Instant = Instant.now(),
        protocol: FastingProtocol = _selectedProtocol.value,
        customHours: Float = _customTargetHours.value
    ) {
        val targetHours = if (protocol == FastingProtocol.CUSTOM) customHours.coerceAtLeast(1f) else protocol.fastHours
        val targetEnd = startTime.plusSeconds((targetHours * 3600f).toLong())
        viewModelScope.launch {
            repository.startFast(
                startTimeIso = startTime.toString(),
                targetEndTimeIso = targetEnd.toString(),
                fastingType = protocol.code
            ).onSuccess {
                _statusMessage.value = "Fast started (${protocol.label})"
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Could not start fast"
            }
        }
    }

    fun endActiveFast(endTime: Instant = Instant.now()) {
        val current = activeFast.value ?: return
        viewModelScope.launch {
            repository.endFast(
                id = current.id,
                endTimeIso = endTime.toString(),
                startTimeIso = current.startTime,
                targetEndTimeIso = current.targetEndTime,
                fastingType = current.fastingType
            ).onSuccess {
                _statusMessage.value = "Fast completed"
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Could not end fast"
            }
        }
    }

    fun adjustActiveStartTime(newStartTime: Instant) {
        val current = activeFast.value ?: return
        val targetMillis = current.targetDurationMillis()
        val newTargetEnd = newStartTime.plusMillis(targetMillis)
        viewModelScope.launch {
            repository.updateFast(
                id = current.id,
                startTimeIso = newStartTime.toString(),
                targetEndTimeIso = newTargetEnd.toString(),
                fastingType = current.fastingType
            ).onSuccess {
                _statusMessage.value = "Start time updated"
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Could not update start time"
            }
        }
    }

    /** Changes a past (or ongoing) fast's start and end; its target end moves with the start. */
    fun editFast(log: com.bytelog.app.data.fasting.FastingLog, start: Instant, end: Instant?) {
        viewModelScope.launch {
            repository.updateFast(
                id = log.id,
                startTimeIso = start.toString(),
                endTimeIso = end?.toString(),
                targetEndTimeIso = start.plusMillis(log.targetDurationMillis()).toString(),
                fastingType = log.fastingType
            ).onSuccess { _statusMessage.value = "Fast updated" }
                .onFailure { err -> _statusMessage.value = err.message ?: "Could not update the fast" }
        }
    }

    fun deleteFast(id: String) {
        viewModelScope.launch {
            repository.deleteFast(id)
                .onSuccess { _statusMessage.value = "Fast deleted" }
                .onFailure { err -> _statusMessage.value = err.message ?: "Could not delete fast" }
        }
    }

    fun updatePreferences(prefs: FastingPreferences) {
        viewModelScope.launch {
            repository.updatePreferences(prefs)
                .onSuccess { _statusMessage.value = "Preferences saved" }
                .onFailure { _statusMessage.value = "Saved locally (offline)" }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    class Factory(private val repository: FastingRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(FastingViewModel::class.java)) {
                return FastingViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
