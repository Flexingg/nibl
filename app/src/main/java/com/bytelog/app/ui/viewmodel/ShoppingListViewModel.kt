package com.bytelog.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.PlanRepository
import com.bytelog.app.data.repository.PriceRepository
import com.bytelog.app.data.repository.ShoppingItem
import com.bytelog.app.data.repository.ShoppingList
import com.bytelog.app.data.repository.ShoppingListResult
import com.bytelog.app.data.repository.SparkyPrice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The shopping list for the next few days' planned food, with prices and ticks. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingListViewModel(
    private val plans: PlanRepository,
    private val prices: PriceRepository,
    private val foods: FoodRepository,
    private val today: () -> LocalDate = { LocalDate.now() }
) : ViewModel() {
    data class State(
        val days: Int = 7,
        val from: String = "",
        val to: String = "",
        val list: ShoppingListResult = ShoppingListResult(emptyList()),
        val checked: Set<String> = emptySet(),
        /** Items swiped off the list (bought, or already in the cupboard). */
        val removed: List<ShoppingItem> = emptyList(),
        val loading: Boolean = true
    ) {
        val left: Int get() = list.items.count { it.key !in checked }
    }

    /** How the list is ordered: by store, or best value first by a nutrient per dollar. */
    private val _sort = MutableStateFlow<com.bytelog.app.data.repository.ValueMetric?>(null)
    val sort: StateFlow<com.bytelog.app.data.repository.ValueMetric?> = _sort.asStateFlow()
    fun setSort(m: com.bytelog.app.data.repository.ValueMetric?) { _sort.value = m }

    private val _days = MutableStateFlow(7)
    fun setDays(days: Int) { _days.value = days }

    private val planned = _days.flatMapLatest { n ->
        val start = today()
        plans.between(start.toString(), start.plusDays((n - 1).toLong()).toString())
    }

    /** The planned foods saved on the phone, so their servings and household measures convert. */
    private val servings = planned.mapLatest { list ->
        val ids = list.mapNotNull { it.foodId }.distinct()
        runCatching { foods.foodsByIds(ids) }.getOrDefault(emptyList()).associateBy { it.id }
    }

    val state: StateFlow<State> = combine(_days, planned, servings, prices.prices, prices.checks) { days, p, sv, pr, ch ->
        val byKey = pr.associateBy { it.key }
        val start = today()
        val all = ShoppingList.build(p, priceOf = { byKey[it] }, foodOf = { sv[it] })
        val (ticked, removed) = PriceRepository.split(ch)
        State(
            days = days, from = start.toString(), to = start.plusDays((days - 1).toLong()).toString(),
            list = ShoppingListResult(all.items.filter { it.key !in removed }),
            checked = ticked, removed = all.items.filter { it.key in removed }, loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun messageShown() { _message.value = null }

    private val asked = HashSet<String>()

    init {
        // Prices saved on Sparky (from another device) for planned foods this phone has none for
        viewModelScope.launch {
            planned.collect { list ->
                val refs = list.filter { it.foodId != null && asked.add(it.foodId) }
                    .map { PriceRepository.FoodRef(it.foodId!!, it.foodName, it.brandName) }
                if (refs.isNotEmpty()) runCatching { prices.refreshFromSparky(refs) }
            }
        }
    }

    fun toggle(item: ShoppingItem) {
        val on = item.key !in state.value.checked
        viewModelScope.launch { prices.check(item.key, on) }
    }

    /** Clears ticks and puts removed items back. */
    fun clearChecks() { viewModelScope.launch { prices.clearChecks() } }

    /** Takes an item off the list (bought or already have it), or puts it back. */
    fun remove(item: ShoppingItem, removed: Boolean = true) { viewModelScope.launch { prices.remove(item.key, removed) } }

    fun savePrice(item: ShoppingItem, price: SparkyPrice) {
        viewModelScope.launch {
            val saved = prices.save(item.foodId?.takeIf { PriceRepository.isSparkyId(it) }, item.name, item.brand, price)
            _message.value = saved.problem ?: if (saved.price == null) "Price cleared" else "Saved to ${item.name} in Sparky"
        }
    }

    fun shareText(): String {
        val s = state.value
        return ShoppingList.asText(s.list, s.checked, "Shopping list · next ${s.days} days")
    }

}
