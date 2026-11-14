package com.bytelog.app.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.receipts.MatchCandidate
import com.bytelog.app.data.receipts.OcrLine
import com.bytelog.app.data.receipts.Receipt
import com.bytelog.app.data.receipts.ReceiptItem
import com.bytelog.app.data.receipts.ReceiptMatcher
import com.bytelog.app.data.receipts.ReceiptParser
import com.bytelog.app.data.receipts.ReceiptPricing
import com.bytelog.app.data.receipts.ReceiptReader
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.PlanRepository
import com.bytelog.app.data.repository.PriceRepository
import com.bytelog.app.data.repository.ShoppingList
import com.bytelog.app.ui.components.PriceDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Prices from a receipt: read the photo, match each line to a food on the shopping list or in your foods, let you
 * check each one, and save the store and price on the foods you keep.
 */
class ReceiptViewModel(
    private val reader: ReceiptReader,
    private val prices: PriceRepository,
    private val plans: PlanRepository,
    private val foods: FoodRepository,
    private val today: () -> LocalDate = { LocalDate.now() }
) : ViewModel() {

    /** One receipt line under review. */
    data class Line(
        val id: Int,
        val item: ReceiptItem,
        val match: MatchCandidate?,
        val alternatives: List<MatchCandidate>,
        val draft: PriceDraft,
        val include: Boolean,
        /** How sure the automatic match is (1 when you picked or scanned it). */
        val score: Float = 1f,
        /** You said the automatic match is right. */
        val confirmed: Boolean = false,
        /** What's happening for this line ("Looking up the barcode…"). */
        val busy: String? = null,
        /** Something to tell you about this line (a barcode that wasn't found). */
        val note: String? = null
    ) {
        val units: List<String> get() = match?.let { unitsOf(it) }.orEmpty()
        /** An automatic match that isn't a clear one: asked about before saving. */
        val unsure: Boolean get() = match != null && score < SURE && !confirmed
        val problem: String? get() = when {
            !include -> null
            match == null -> "Which food is this?"
            unsure -> "Is this ${match.name}?"
            draft.quantity.isBlank() -> "How much does ${com.bytelog.app.data.repository.ShoppingList.money((draft.toPrice().price ?: item.unitPrice).toDouble())} buy?"
            else -> draft.problem(units)
        }
    }

    sealed interface Phase {
        data object Pick : Phase
        data object Reading : Phase
        data object Review : Phase
        data object Saving : Phase
        data class Done(val saved: Int, val onSparky: Int, val notes: List<String>) : Phase
    }

    data class State(
        val phase: Phase = Phase.Pick,
        val store: String = "",
        val date: LocalDate? = null,
        val total: Float? = null,
        val lines: List<Line> = emptyList(),
        val candidates: List<MatchCandidate> = emptyList(),
        val error: String? = null
    ) {
        val ready: List<Line> get() = lines.filter { it.include && it.problem == null }
        val needsAttention: Int get() = lines.count { it.include && it.problem != null }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun read(photo: Uri) {
        _state.update { it.copy(phase = Phase.Reading, error = null) }
        viewModelScope.launch {
            reader.read(photo).fold(
                onSuccess = { review(it) },
                onFailure = { e -> _state.update { it.copy(phase = Phase.Pick, error = "Couldn't read that photo: ${e.message ?: "unknown error"}") } }
            )
        }
    }

    /** Reviews text already read (from a photo, or in tests). */
    suspend fun review(lines: List<OcrLine>) {
        val receipt = ReceiptParser.parse(lines)
        if (receipt.items.isEmpty()) {
            _state.update { it.copy(phase = Phase.Pick, error = "No prices found on that receipt. Try a flatter, closer photo in good light.") }
            return
        }
        val candidates = candidates()
        _state.update { it.copy(phase = Phase.Review, store = receipt.store.orEmpty(), date = receipt.date, total = receipt.total, candidates = candidates, lines = linesFor(receipt, candidates)) }
    }

    /** Foods a line could be: what's planned over the next two weeks first, then your recent and favourite foods. */
    private suspend fun candidates(): List<MatchCandidate> {
        val t = today()
        val planned = runCatching { plans.between(t.toString(), t.plusDays(13).toString()).first() }.getOrDefault(emptyList())
        val plannedFoods = runCatching { foods.foodsByIds(planned.mapNotNull { it.foodId }.distinct()) }.getOrDefault(emptyList()).associateBy { it.id }
        val out = LinkedHashMap<String, MatchCandidate>()
        for (p in planned) {
            val key = ShoppingList.keyOf(p.foodId, p.foodName, p.brandName)
            if (key in out) continue
            val food = p.foodId?.let { plannedFoods[it] } ?: FoodItem(id = p.foodId ?: key, name = p.foodName, brand = p.brandName, servingSize = 1f, servingUnit = p.unit)
            out[key] = MatchCandidate(key, p.foodId, p.foodName, p.brandName, onList = true, food = food)
        }
        val library = runCatching { foods.getRecentFoods(150).first() + foods.getFavoriteFoods().first() }.getOrDefault(emptyList())
        for (f in library) {
            val key = ShoppingList.keyOf(f.id, f.name, f.brand)
            if (key !in out && out.values.none { com.bytelog.app.data.repository.FoodSearch.identity(it.name, it.brand) == com.bytelog.app.data.repository.FoodSearch.identity(f.name, f.brand) }) {
                out[key] = MatchCandidate(key, f.id, f.name, f.brand, onList = false, food = f)
            }
        }
        return out.values.toList()
    }

    private fun linesFor(receipt: Receipt, candidates: List<MatchCandidate>): List<Line> = receipt.items.mapIndexed { i, item ->
        val matches = ReceiptMatcher.matches(item.name, candidates)
        val best = matches.firstOrNull()?.candidate
        Line(i, item, best, matches.map { it.candidate }, draftFor(item, best, receipt.store), include = best != null, score = matches.firstOrNull()?.score ?: 0f)
    }

    private fun draftFor(item: ReceiptItem, match: MatchCandidate?, store: String?): PriceDraft {
        if (match == null) return PriceDraft(store = store.orEmpty(), price = "%.2f".format(java.util.Locale.US, item.unitPrice))
        return PriceDraft.from(ReceiptPricing.suggest(item, unitsOf(match), store))
    }

    // ----- Review -----

    fun setStore(store: String) = _state.update { s -> s.copy(store = store) }

    fun include(id: Int, on: Boolean) = updateLine(id) { it.copy(include = on) }

    fun choose(id: Int, candidate: MatchCandidate?) = updateLine(id) { l ->
        l.copy(match = candidate, include = candidate != null, draft = draftFor(l.item, candidate, _state.value.store).copy(store = _state.value.store), score = 1f, confirmed = true, note = null, busy = null)
    }

    /** The automatic match is right. */
    fun confirm(id: Int) = updateLine(id) { it.copy(confirmed = true) }

    /** The id of the next line that needs you, after [after] (wrapping round), or null when none do. */
    fun nextToCheck(after: Int? = null): Int? {
        val need = _state.value.lines.filter { it.include && it.problem != null }.map { it.id }
        if (need.isEmpty()) return null
        return need.firstOrNull { after == null || it > after } ?: need.first()
    }

    /** The line is this product: looked up by its barcode (your foods first, then Sparky and Open Food Facts). */
    fun scanBarcode(id: Int, code: String) {
        updateLine(id) { it.copy(busy = "Looking up the barcode…", note = null) }
        viewModelScope.launch {
            val food = runCatching { foods.lookupBarcode(code) }.getOrNull()
            if (food == null) { updateLine(id) { it.copy(busy = null, note = "No product found for barcode $code. Try its nutrition label.") }; return@launch }
            runCatching { foods.rememberFood(food) }
            choose(id, candidateFor(food))
        }
    }

    /**
     * The line is a new food read from its nutrition label (Sparky's AI reads it), saved to your foods, named from
     * the label or else the receipt.
     */
    fun scanLabel(id: Int, photo: android.graphics.Bitmap) {
        val line = _state.value.lines.firstOrNull { it.id == id } ?: return
        updateLine(id) { it.copy(busy = "Reading the label…", note = null) }
        viewModelScope.launch {
            val b64 = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.bytelog.app.util.ImageUtils.convertBitmapToBase64(photo, maxDim = 1600, quality = 90)
            }
            val label = foods.scanNutritionLabel(b64).getOrElse { e ->
                updateLine(id) { it.copy(busy = null, note = "Couldn't read that label: ${e.message ?: "try again"}") }; return@launch
            }
            val name = label.name?.takeIf { it.isNotBlank() } ?: titleCase(line.item.name)
            val created = foods.createCustomFood(
                name = name, brand = label.brand, servingSize = label.servingSize ?: 100f, servingUnit = label.servingUnit ?: "g",
                calories = label.calories ?: 0f, protein = label.protein ?: 0f, carbs = label.carbs ?: 0f, fat = label.fat ?: 0f, fiber = label.fiber
            ).getOrElse { e -> updateLine(id) { it.copy(busy = null, note = "Read the label but couldn't save the food: ${e.message}") }; return@launch }
            choose(id, candidateFor(created))
        }
    }

    private fun candidateFor(food: FoodItem): MatchCandidate {
        val key = ShoppingList.keyOf(food.id, food.name, food.brand)
        val c = _state.value.candidates.firstOrNull { it.key == key } ?: MatchCandidate(key, food.id, food.name, food.brand, onList = false, food = food)
        _state.update { s -> if (s.candidates.any { it.key == key }) s else s.copy(candidates = s.candidates + c) }
        return c
    }

    private fun titleCase(s: String) = s.lowercase().split(' ').filter { it.isNotBlank() }.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }

    fun editDraft(id: Int, draft: PriceDraft) = updateLine(id) { it.copy(draft = draft) }

    /** Foods to pick from for a line, by name, matches for it first. */
    fun search(id: Int, query: String): List<MatchCandidate> {
        val s = _state.value
        val line = s.lines.firstOrNull { it.id == id } ?: return emptyList()
        if (query.isBlank()) return (line.alternatives + s.candidates.filter { it.onList }).distinctBy { it.key }.take(12)
        val tokens = com.bytelog.app.data.repository.FoodSearch.tokens(query)
        return s.candidates.filter { com.bytelog.app.data.repository.FoodSearch.match(it.name, it.brand, tokens) != null }.take(20)
    }

    private fun updateLine(id: Int, change: (Line) -> Line) =
        _state.update { s -> s.copy(lines = s.lines.map { if (it.id == id) change(it) else it }) }

    fun save() {
        val s = _state.value
        val ready = s.ready
        if (ready.isEmpty()) return
        _state.update { it.copy(phase = Phase.Saving) }
        viewModelScope.launch {
            var onSparky = 0
            val notes = mutableListOf<String>()
            for (l in ready) {
                val m = l.match ?: continue
                val saved = prices.save(
                    m.foodId?.takeIf { PriceRepository.isSparkyId(it) }, m.name, m.brand,
                    l.draft.copy(store = s.store.ifBlank { l.draft.store }).toPrice()
                )
                if (saved.onSparky) onSparky++ else saved.problem?.let { notes += "${m.name}: $it" }
            }
            _state.update { it.copy(phase = Phase.Done(ready.size, onSparky, notes)) }
        }
    }

    fun restart() { _state.value = State() }

    companion object {
        /** Matches scoring below this are asked about. */
        const val SURE = 0.75f

        fun unitsOf(c: MatchCandidate): List<String> =
            c.food?.let { ShoppingList.priceUnits(it) } ?: listOf("serving")
    }
}
