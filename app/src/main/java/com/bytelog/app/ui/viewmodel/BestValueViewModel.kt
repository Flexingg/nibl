package com.bytelog.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.repository.FoodRepository
import com.bytelog.app.data.repository.FoodValue
import com.bytelog.app.data.repository.PriceRepository
import com.bytelog.app.data.repository.ValueMetric
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** Your foods with prices, ranked by what a dollar buys of a nutrient. */
@OptIn(ExperimentalCoroutinesApi::class)
class BestValueViewModel(prices: PriceRepository, private val foods: FoodRepository) : ViewModel() {
    data class State(val metric: ValueMetric = ValueMetric.PROTEIN, val ranked: List<FoodValue> = emptyList(), val unpriced: Int = 0, val loading: Boolean = true) {
        val best: Float get() = ranked.firstOrNull()?.perDollar(metric) ?: 0f
    }

    private val metric = MutableStateFlow(ValueMetric.PROTEIN)
    fun setMetric(m: ValueMetric) { metric.value = m }

    /** Each saved price with its food (by id, else by name and brand), when the food's nutrition is on the phone. */
    private val values = prices.prices.mapLatest { list ->
        val byId = runCatching { foods.foodsByIds(list.mapNotNull { it.foodId }.distinct()) }.getOrDefault(emptyList()).associateBy { it.id }
        list.mapNotNull { p ->
            val food: FoodItem? = p.foodId?.let { byId[it] }
                ?: runCatching { foods.savedFoodId(p.foodName, p.brand)?.let { id -> foods.foodsByIds(listOf(id)).firstOrNull() } }.getOrNull()
            food?.let { FoodValue.of(it, p) }
        } to list.size
    }

    val state: StateFlow<State> = combine(metric, values) { m, (vals, total) ->
        State(m, vals.filter { m.amount(it.food) > 0f }.sortedByDescending { it.perDollar(m) }, unpriced = total - vals.size, loading = false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State())
}
