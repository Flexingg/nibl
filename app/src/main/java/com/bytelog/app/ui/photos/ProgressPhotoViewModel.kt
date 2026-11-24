package com.bytelog.app.ui.photos

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.photos.PhotoPose
import com.bytelog.app.data.photos.ProgressPhotoFormatters
import com.bytelog.app.data.photos.ProgressPhotoItem
import com.bytelog.app.data.photos.ProgressPhotoRepository
import com.bytelog.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class PhotoComparisonMetrics(
    val daysElapsed: Long,
    val beforeWeightKg: Float?,
    val afterWeightKg: Float?,
    val weightDeltaKg: Float?
)

class ProgressPhotoViewModel(
    private val repository: ProgressPhotoRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    val photos: StateFlow<List<ProgressPhotoItem>> = repository.photos

    val weightUnit: StateFlow<String> = settings.weightUnit
        .stateIn(viewModelScope, SharingStarted.Eagerly, "lbs")

    private val _selectedPoseFilter = MutableStateFlow<PhotoPose?>(null)
    val selectedPoseFilter: StateFlow<PhotoPose?> = _selectedPoseFilter.asStateFlow()

    val filteredPhotos: StateFlow<List<ProgressPhotoItem>> = combine(
        photos,
        _selectedPoseFilter
    ) { all, filter ->
        if (filter == null) all else all.filter { it.pose == filter }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _deletingPhotoId = MutableStateFlow<String?>(null)
    val deletingPhotoId: StateFlow<String?> = _deletingPhotoId.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        refresh()
    }

    fun setPoseFilter(pose: PhotoPose?) {
        _selectedPoseFilter.value = pose
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            val result = repository.refresh()
            _isRefreshing.value = false
            if (result.isFailure && photos.value.isEmpty()) {
                _statusMessage.value = result.exceptionOrNull()?.message ?: "Could not sync progress photos"
            }
        }
    }

    suspend fun loadBitmap(item: ProgressPhotoItem, maxDim: Int = 1280): Bitmap? {
        return repository.loadBitmap(item, maxDim)
    }

    suspend fun getReferencePhotoForOverlay(
        pose: PhotoPose,
        targetDate: String = LocalDate.now().toString()
    ): ProgressPhotoItem? {
        return repository.getReferencePhotoForOverlay(pose, targetDate)
    }

    suspend fun copyUriToTempFile(uri: Uri): File? {
        return repository.copyUriToTempFile(uri)
    }

    fun uploadCapturedPhoto(
        date: String,
        pose: PhotoPose,
        file: File,
        onComplete: (Result<ProgressPhotoItem>) -> Unit = {}
    ) {
        if (_isUploading.value) return
        viewModelScope.launch {
            _isUploading.value = true
            val result = repository.saveAndUploadPhoto(date, pose, file)
            _isUploading.value = false
            if (result.isSuccess) {
                _statusMessage.value = "${pose.label} photo saved"
            } else {
                _statusMessage.value = "Saved locally — upload failed: ${result.exceptionOrNull()?.message ?: "offline"}"
            }
            onComplete(result)
        }
    }

    fun deletePhoto(
        item: ProgressPhotoItem,
        onComplete: (Boolean) -> Unit = {}
    ) {
        if (_deletingPhotoId.value != null) return
        viewModelScope.launch {
            _deletingPhotoId.value = item.id
            val result = repository.deletePhoto(item)
            _deletingPhotoId.value = null
            if (result.isSuccess) {
                _statusMessage.value = "Photo deleted"
                onComplete(true)
            } else {
                _statusMessage.value = result.exceptionOrNull()?.message ?: "Failed to delete photo"
                onComplete(false)
            }
        }
    }

    // ---- Side-by-Side & Time-Lapse helpers ----

    /** Returns photos for [pose] sorted chronologically oldest -> newest. */
    fun photosForPoseChronological(
        pose: PhotoPose,
        allPhotos: List<ProgressPhotoItem> = photos.value
    ): List<ProgressPhotoItem> {
        return allPhotos
            .filter { it.pose == pose }
            .sortedBy { it.date }
    }

    fun computeComparison(
        before: ProgressPhotoItem?,
        after: ProgressPhotoItem?
    ): PhotoComparisonMetrics? {
        if (before == null || after == null) return null
        val days = runCatching {
            val d1 = LocalDate.parse(before.date)
            val d2 = LocalDate.parse(after.date)
            ChronoUnit.DAYS.between(d1, d2)
        }.getOrDefault(0L)
        val deltaKg = if (before.weightKg != null && after.weightKg != null) {
            after.weightKg - before.weightKg
        } else null
        return PhotoComparisonMetrics(
            daysElapsed = days,
            beforeWeightKg = before.weightKg,
            afterWeightKg = after.weightKg,
            weightDeltaKg = deltaKg
        )
    }

    fun formatWeight(weightKg: Float?, unit: String = weightUnit.value): String? {
        return ProgressPhotoFormatters.formatWeight(weightKg, unit)
    }

    fun formatWeightDelta(deltaKg: Float?, unit: String = weightUnit.value): String? {
        return ProgressPhotoFormatters.formatWeightDelta(deltaKg, unit)
    }

    fun formatDisplayDate(isoDate: String): String {
        return runCatching {
            val parsed = LocalDate.parse(isoDate)
            val today = LocalDate.now()
            val fmt = if (parsed.year == today.year) SHORT_DATE_FMT else FULL_DATE_FMT
            parsed.format(fmt)
        }.getOrDefault(isoDate)
    }

    fun formatOverlayDateBadge(
        isoDate: String,
        targetDate: String = LocalDate.now().toString()
    ): String {
        return runCatching {
            val photoDate = LocalDate.parse(isoDate)
            val refDate = LocalDate.parse(targetDate)
            val diff = ChronoUnit.DAYS.between(photoDate, refDate)
            when (diff) {
                1L -> "Yesterday"
                0L -> "Today"
                else -> photoDate.format(SHORT_DATE_FMT)
            }
        }.getOrDefault(isoDate)
    }

    companion object {
        private val SHORT_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        private val FULL_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

        fun factory(
            repository: ProgressPhotoRepository,
            settings: SettingsRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ProgressPhotoViewModel(repository, settings) as T
            }
        }
    }
}
