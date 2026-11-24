package com.bytelog.app.ui.caffeine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.caffeine.CaffeinePreset
import com.bytelog.app.data.caffeine.CaffeineRepository
import com.bytelog.app.data.caffeine.CaffeineSettings
import com.bytelog.app.data.caffeine.CaffeineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class CaffeineViewModel(
    private val repository: CaffeineRepository
) : ViewModel() {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private val _selectedDate = MutableStateFlow(LocalDate.now().toString())
    val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

    val status: StateFlow<CaffeineStatus?> = repository.status
    val settings: StateFlow<CaffeineSettings> = repository.settings
    val isLoading: StateFlow<Boolean> = repository.isLoading

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        load(_selectedDate.value)
    }

    fun load(date: String = _selectedDate.value) {
        val cleanDate = date.substringBefore('T').trim().ifBlank { LocalDate.now().toString() }
        _selectedDate.value = cleanDate
        viewModelScope.launch {
            repository.refresh(cleanDate)
        }
    }

    fun logPreset(
        preset: CaffeinePreset,
        timeHHmm: String = currentTimeHHmm(),
        mealTypeId: String = ""
    ) {
        logCustom(
            name = preset.name,
            mg = preset.mg,
            timeHHmm = timeHHmm,
            mealTypeId = mealTypeId
        )
    }

    fun logCustom(
        name: String,
        mg: Float,
        timeHHmm: String = currentTimeHHmm(),
        mealTypeId: String = ""
    ) {
        if (mg <= 0f) {
            _message.value = "Enter a caffeine amount greater than 0 mg"
            return
        }
        viewModelScope.launch {
            val result = repository.logCaffeine(
                name = name.ifBlank { "Caffeine" },
                mg = mg,
                timeHHmm = timeHHmm,
                date = _selectedDate.value,
                mealTypeId = mealTypeId
            )
            _message.value = if (result.isSuccess) {
                "Logged ${mg.toInt()} mg (${name.ifBlank { "Caffeine" }})"
            } else {
                "Saved ${mg.toInt()} mg locally (offline)"
            }
        }
    }

    fun updateSettings(halfLifeHours: Float, targetBedtime: String) {
        viewModelScope.launch {
            val result = repository.updateSettings(halfLifeHours, targetBedtime)
            _message.value = if (result.isSuccess) {
                "Caffeine settings updated"
            } else {
                "Settings saved locally (offline)"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun currentTimeHHmm(): String = LocalTime.now().format(timeFormatter)

    companion object {
        fun factory(repository: CaffeineRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CaffeineViewModel(repository) as T
                }
            }
    }
}
