package com.bytelog.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytelog.app.data.family.FamilyRepository
import com.bytelog.app.data.family.MemberDay
import com.bytelog.app.data.family.Share
import com.bytelog.app.data.family.ShareMode
import com.bytelog.app.data.family.ShareOutcome
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.remote.FamilyMember
import com.bytelog.app.data.repository.FoodRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A member's calories today against their goal, for the family strip and cards. */
data class MemberToday(val calories: Float, val goal: Float?, val protein: Float = 0f, val carbs: Float = 0f, val fat: Float = 0f)

data class FamilyState(
    val loading: Boolean = true,
    val error: String? = null,
    val members: List<FamilyMember> = emptyList(),
    val today: Map<String, MemberToday> = emptyMap()
)

/** Everyone who shares their diary with the signed-in user, and today's numbers for each. Shared by all pages. */
class FamilyViewModel(private val repo: FamilyRepository) : ViewModel() {
    private val _state = MutableStateFlow(FamilyState())
    val state: StateFlow<FamilyState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repo.members()
                .onSuccess { members ->
                    _state.update { it.copy(loading = false, members = members) }
                    loadToday(members)
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Couldn't reach Sparky") } }
        }
    }

    private suspend fun loadToday(members: List<FamilyMember>) = coroutineScope {
        val today = LocalDate.now().toString()
        members.filter { it.canViewDiary }.map { m ->
            async { repo.memberDay(m, today).getOrNull()?.let { m.userId to MemberToday(it.calories, it.goals?.calories, it.protein, it.carbs, it.fat) } }
        }.mapNotNull { it.await() }.toMap().let { map -> _state.update { it.copy(today = map) } }
    }

    /** Shares one meal with family members; [onDone] gets what happened for each of them. */
    fun share(entries: List<FoodEntry>, shares: List<Share>, mode: ShareMode, onDone: (List<ShareOutcome>) -> Unit) {
        viewModelScope.launch {
            val outcomes = repo.shareMeal(entries, shares, mode)
            onDone(outcomes)
            refresh()
        }
    }
}

data class MemberState(
    val loading: Boolean = true,
    val error: String? = null,
    val member: FamilyMember? = null,
    val date: String = LocalDate.now().toString(),
    val day: MemberDay? = null,
    val week: List<Pair<String, Float?>> = emptyList()
)

/** One family member's diary, a day at a time. */
class MemberViewModel(
    private val repo: FamilyRepository,
    private val foodRepository: FoodRepository,
    private val userId: String
) : ViewModel() {
    private val _state = MutableStateFlow(MemberState())
    val state: StateFlow<MemberState> = _state.asStateFlow()

    init { load() }

    fun setDate(date: String) { _state.update { it.copy(date = date) }; load() }
    fun shiftDay(days: Long) = setDate(LocalDate.parse(_state.value.date).plusDays(days).toString())

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val member = _state.value.member ?: repo.members().getOrNull()?.firstOrNull { it.userId == userId }
            if (member == null) {
                _state.update { it.copy(loading = false, error = "This person no longer shares their diary with you") }
                return@launch
            }
            _state.update { it.copy(member = member) }
            val date = _state.value.date
            repo.memberDay(member, date)
                .onSuccess { day -> _state.update { it.copy(loading = false, day = day) } }
                .onFailure { e -> _state.update { it.copy(loading = false, day = null, error = e.message ?: "Couldn't load their diary") } }
            if (member.canViewDiary) {
                val week = repo.memberCalories(member, date)
                _state.update { if (it.date == date) it.copy(week = week) else it }
            }
        }
    }

    /** Copies one of their meals into the user's own diary today, onto the meal of the same name. */
    fun copyMealToMe(mealName: String, entries: List<FoodEntry>, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val types = foodRepository.getAllMealTypes().first()
            val type = types.firstOrNull { it.name.trim().equals(mealName.trim(), ignoreCase = true) } ?: types.firstOrNull()
            val name = type?.name ?: mealName
            repo.copyToMe(entries, LocalDate.now().toString(), type?.id ?: mealName.lowercase(), name)
            onDone("Copied ${entries.size} item${if (entries.size == 1) "" else "s"} to your $name today")
        }
    }
}
