package com.bytelog.app.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.reports.CompareMetric
import com.bytelog.app.data.reports.CompareResult
import com.bytelog.app.data.reports.ReportConfigStore
import com.bytelog.app.data.reports.ReportCustomization
import com.bytelog.app.data.reports.ReportRange
import com.bytelog.app.data.reports.ReportsRepository
import com.bytelog.app.data.reports.ReportsSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReportsUiState(
    val range: ReportRange = ReportRange.D14,
    val customization: ReportCustomization = ReportCustomization.DEFAULT,
    val snapshot: ReportsSnapshot? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val compareMetricA: CompareMetric = CompareMetric.CALORIES,
    val compareMetricB: CompareMetric = CompareMetric.WEIGHT,
    val compareResult: CompareResult? = null,
    val isCompareLoading: Boolean = false,
    // Exercise Progress
    val exercises: List<com.bytelog.app.data.reports.LoggedExercise> = emptyList(),
    val selectedExercise: com.bytelog.app.data.reports.LoggedExercise? = null,
    val progressGrouping: com.bytelog.app.data.reports.ProgressGrouping = com.bytelog.app.data.reports.ProgressGrouping.WEEK,
    val exerciseProgress: com.bytelog.app.data.reports.ExerciseProgressReport? = null,
    val isProgressLoading: Boolean = false,
    val progressError: String? = null,
    val weightUnit: String = "kg"
)

class ReportsViewModel(
    private val repository: ReportsRepository,
    private val configStore: ReportConfigStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var compareJob: Job? = null
    private var progressJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = configStore.customization.first()
            _uiState.update { it.copy(customization = saved, range = saved.defaultRange) }
            loadForRange(saved.defaultRange)
            loadExercises()

            configStore.customization.collect { updated ->
                _uiState.update { it.copy(customization = updated) }
            }
        }
    }

    fun selectRange(range: ReportRange) {
        if (_uiState.value.range == range && _uiState.value.snapshot != null) return
        _uiState.update { it.copy(range = range) }
        loadForRange(range)
        loadProgress()
    }

    fun refresh() {
        loadForRange(_uiState.value.range)
        loadExercises()
    }

    fun selectExercise(exercise: com.bytelog.app.data.reports.LoggedExercise) {
        _uiState.update { it.copy(selectedExercise = exercise) }
        loadProgress()
    }

    fun selectProgressGrouping(grouping: com.bytelog.app.data.reports.ProgressGrouping) {
        _uiState.update { it.copy(progressGrouping = grouping) }
        loadProgress()
    }

    private fun loadExercises() {
        viewModelScope.launch {
            val unit = runCatching { repository.weightUnit() }.getOrDefault("kg")
            repository.loggedExercises()
                .onSuccess { list ->
                    _uiState.update { st ->
                        st.copy(exercises = list, weightUnit = unit,
                            selectedExercise = st.selectedExercise?.takeIf { s -> list.any { it.id == s.id } } ?: list.firstOrNull())
                    }
                    loadProgress()
                }
                .onFailure { e -> _uiState.update { it.copy(weightUnit = unit, progressError = "Couldn't load your exercises: ${e.message}") } }
        }
    }

    private fun loadProgress() {
        val st = _uiState.value
        val exercise = st.selectedExercise ?: run { _uiState.update { it.copy(exerciseProgress = null) }; return }
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            _uiState.update { it.copy(isProgressLoading = true, progressError = null) }
            repository.loadExerciseProgress(exercise, st.range, st.progressGrouping)
                .onSuccess { r -> _uiState.update { it.copy(exerciseProgress = r, isProgressLoading = false) } }
                .onFailure { e -> _uiState.update { it.copy(isProgressLoading = false, progressError = "Couldn't load ${exercise.name}: ${e.message}") } }
        }
    }

    fun updateCustomization(config: ReportCustomization) {
        _uiState.update { it.copy(customization = config) }
        viewModelScope.launch {
            configStore.save(config)
        }
    }

    fun selectCompareMetrics(metricA: CompareMetric, metricB: CompareMetric) {
        _uiState.update { it.copy(compareMetricA = metricA, compareMetricB = metricB) }
        loadCompare(metricA, metricB, _uiState.value.range, _uiState.value.snapshot)
    }

    private fun loadForRange(range: ReportRange) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { repository.loadReport(range) }
                .onSuccess { snap ->
                    _uiState.update { it.copy(snapshot = snap, isLoading = false, error = null) }
                    val st = _uiState.value
                    loadCompare(st.compareMetricA, st.compareMetricB, range, snap)
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = err.message ?: err.javaClass.simpleName
                        )
                    }
                }
        }
    }

    private fun loadCompare(
        metricA: CompareMetric,
        metricB: CompareMetric,
        range: ReportRange,
        snapshot: ReportsSnapshot?
    ) {
        compareJob?.cancel()
        compareJob = viewModelScope.launch {
            _uiState.update { it.copy(isCompareLoading = true) }
            val result = runCatching {
                repository.loadCompare(metricA, metricB, range, snapshot)
            }.getOrNull()
            _uiState.update { it.copy(compareResult = result, isCompareLoading = false) }
        }
    }

    class Factory(
        private val repository: ReportsRepository,
        private val configStore: ReportConfigStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReportsViewModel(repository, configStore) as T
        }
    }
}
