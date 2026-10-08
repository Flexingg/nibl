package com.bytelog.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.remote.SparkyFood
import com.bytelog.app.data.remote.SparkyNutrients
import com.bytelog.app.data.repository.FoodRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/** The editor's fields as typed. Numbers stay text until saved so a half-typed "1." isn't fought. */
data class FoodForm(
    val name: String = "",
    val brand: String = "",
    val barcode: String = "",
    val servingSize: String = "",
    val servingUnit: String = "g",
    val calories: String = "",
    val protein: String = "",
    val carbs: String = "",
    val fat: String = "",
    val fiber: String = "",
    val sugars: String = "",
    val saturatedFat: String = "",
    val sodium: String = "",
    val cholesterol: String = "",
    val potassium: String = ""
) {
    private fun f(s: String) = s.replace(',', '.').trim().toFloatOrNull()

    /** What's wrong with the form, or null when it can be saved. */
    val problem: String?
        get() = when {
            name.isBlank() -> "Give the food a name"
            (f(servingSize) ?: 0f) <= 0f -> "Serving size must be more than 0"
            servingUnit.isBlank() -> "Pick a serving unit"
            f(calories) == null -> "Enter the calories"
            listOf(calories, protein, carbs, fat, fiber, sugars, saturatedFat, sodium, cholesterol, potassium)
                .any { it.isNotBlank() && (f(it) == null || f(it)!! < 0f) } -> "Numbers can't be negative or blank-ish"
            else -> null
        }

    /** Calories implied by the macros (4/4/9), to flag a likely typo. */
    val macroCalories: Float get() = 4f * (f(protein) ?: 0f) + 4f * (f(carbs) ?: 0f) + 9f * (f(fat) ?: 0f)

    /** True when the stated calories and the macros disagree by more than 20% (and 25 kcal). */
    val macrosDisagree: Boolean
        get() {
            val c = f(calories) ?: return false
            val m = macroCalories
            return m > 0f && abs(c - m) > 25f && abs(c - m) > 0.2f * maxOf(c, m)
        }

    fun nutrients() = SparkyNutrients(
        servingSize = f(servingSize) ?: 100f,
        servingUnit = servingUnit.trim(),
        calories = f(calories) ?: 0f,
        protein = f(protein) ?: 0f,
        carbs = f(carbs) ?: 0f,
        fat = f(fat) ?: 0f,
        fiber = f(fiber), sugars = f(sugars), saturatedFat = f(saturatedFat),
        sodium = f(sodium), cholesterol = f(cholesterol), potassium = f(potassium)
    )

    companion object {
        fun num(v: Float?): String {
            if (v == null) return ""
            val r = Math.round(v * 100f) / 100f
            return if (r == r.toLong().toFloat()) r.toLong().toString() else String.format(Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')
        }

        fun from(food: SparkyFood) = FoodForm(
            name = food.name, brand = food.brand.orEmpty(), barcode = food.barcode.orEmpty(),
            servingSize = num(food.nutrients.servingSize), servingUnit = food.nutrients.servingUnit,
            calories = num(food.nutrients.calories), protein = num(food.nutrients.protein),
            carbs = num(food.nutrients.carbs), fat = num(food.nutrients.fat),
            fiber = num(food.nutrients.fiber), sugars = num(food.nutrients.sugars),
            saturatedFat = num(food.nutrients.saturatedFat), sodium = num(food.nutrients.sodium),
            cholesterol = num(food.nutrients.cholesterol), potassium = num(food.nutrients.potassium)
        )

        fun from(food: FoodItem) = FoodForm(
            name = food.name, brand = food.brand.orEmpty(), barcode = food.barcode.orEmpty(),
            servingSize = num(food.servingSize), servingUnit = food.servingUnit,
            calories = num(food.calories), protein = num(food.protein), carbs = num(food.carbs), fat = num(food.fat),
            fiber = num(food.fiber?.takeIf { it > 0f })
        )
    }
}

data class FoodEditorState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val food: FoodRepository.EditableFood? = null,
    val form: FoodForm = FoodForm(),
    val original: FoodForm = FoodForm(),
    val updatePastEntries: Boolean = false,
    val saving: Boolean = false,
    val saveError: String? = null,
    /** Set once saved or deleted: the food as stored now (null after a delete). */
    val finished: Boolean = false,
    val savedFood: FoodItem? = null
) {
    val owned get() = food?.owned == true
    val dirty get() = form != original
    /** Editing someone else's food saves a copy, so it's worth saving even unchanged. */
    val canSave get() = !loading && !saving && food != null && form.problem == null && (dirty || !owned)
}

/** Loads a food from Sparky, edits it and saves it back (or as the user's own copy). */
class FoodEditorViewModel(
    private val foodRepository: FoodRepository,
    private val foodId: String
) : ViewModel() {
    private val _state = MutableStateFlow(FoodEditorState())
    val state: StateFlow<FoodEditorState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            foodRepository.loadFoodForEditing(foodId)
                .onSuccess { f ->
                    val form = f.sparky?.let { FoodForm.from(it) } ?: f.local?.let { FoodForm.from(it) } ?: FoodForm()
                    _state.update { it.copy(loading = false, food = f, form = form, original = form) }
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, loadError = e.message ?: "Couldn't reach Sparky") }
                }
        }
    }

    fun edit(change: (FoodForm) -> FoodForm) = _state.update { it.copy(form = change(it.form), saveError = null) }

    fun setUpdatePastEntries(on: Boolean) = _state.update { it.copy(updatePastEntries = on) }

    fun save() {
        val s = _state.value
        val food = s.food ?: return
        if (!s.canSave) return
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val f = s.form
            foodRepository.saveFoodEdit(
                original = food,
                name = f.name.trim(),
                brand = f.brand.trim().ifBlank { null },
                barcode = f.barcode.trim().ifBlank { null },
                nutrients = f.nutrients(),
                updatePastEntries = s.owned && s.updatePastEntries
            ).onSuccess { saved ->
                _state.update { it.copy(saving = false, finished = true, savedFood = saved) }
            }.onFailure { e ->
                _state.update { it.copy(saving = false, saveError = e.message ?: "Couldn't save to Sparky") }
            }
        }
    }

    fun delete(hideOnly: Boolean) {
        val id = _state.value.food?.sparky?.id ?: return
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            foodRepository.deleteFood(id, hideOnly)
                .onSuccess { _state.update { it.copy(saving = false, finished = true, savedFood = null) } }
                .onFailure { e -> _state.update { it.copy(saving = false, saveError = e.message ?: "Couldn't delete") } }
        }
    }
}

/** The user's own foods on Sparky, searchable. */
class MyFoodsViewModel(private val foodRepository: FoodRepository) : ViewModel() {
    data class State(val loading: Boolean = true, val error: String? = null, val foods: List<SparkyFood> = emptyList(), val query: String = "")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: kotlinx.coroutines.Job? = null

    init { refresh() }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        refresh(debounceMs = 250)
    }

    fun refresh(debounceMs: Long = 0) {
        job?.cancel()
        job = viewModelScope.launch {
            if (debounceMs > 0) kotlinx.coroutines.delay(debounceMs)
            _state.update { it.copy(loading = true, error = null) }
            foodRepository.myFoods(_state.value.query)
                .onSuccess { list -> _state.update { it.copy(loading = false, foods = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Couldn't reach Sparky") } }
        }
    }
}
