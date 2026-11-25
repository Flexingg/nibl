package com.bytelog.app.data.family

import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.remote.FamilyMember
import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.remote.UserMacroGoals
import com.bytelog.app.data.repository.FoodRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID
import com.bytelog.app.data.remote.fetchFamilyMembers

/** One day of a family member's diary with their goals. */
data class MemberDay(
    val member: FamilyMember,
    val date: String,
    val entries: List<FoodEntry>,
    val goals: UserMacroGoals?
) {
    val calories get() = entries.sumOf { it.calories.toDouble() }.toFloat()
    val protein get() = entries.sumOf { it.protein.toDouble() }.toFloat()
    val carbs get() = entries.sumOf { it.carbs.toDouble() }.toFloat()
    val fat get() = entries.sumOf { it.fat.toDouble() }.toFloat()
}

/** Who gets what: [member] null is the signed-in user. */
data class Share(val member: FamilyMember?, val percent: Int)

/** What happened for one person when a meal was shared. */
data class ShareOutcome(val member: FamilyMember?, val percent: Int, val sent: Int, val error: String?) {
    val ok get() = error == null
}

enum class ShareMode { SPLIT, COPY }

/** Family access on Sparky: who the user can see, their diaries, and sending meals into their diaries. */
class FamilyRepository(
    private val api: SparkyApiClient,
    private val foodRepository: FoodRepository
) {
    suspend fun members(): Result<List<FamilyMember>> = api.fetchFamilyMembers()

    /** A member's day: their entries and the goals Sparky has for them. */
    suspend fun memberDay(member: FamilyMember, date: String): Result<MemberDay> = coroutineScope {
        val entries = async { api.fetchEntriesForDate(date, member.userId) }
        val goals = async { api.fetchGoalsForDate(date, actingAs = member.userId) }
        entries.await().map { MemberDay(member, date, it, goals.await().getOrNull()) }
    }

    /** Calories for the [days] days ending on [endDate], oldest first (null where a day couldn't be loaded). */
    suspend fun memberCalories(member: FamilyMember, endDate: String, days: Int = 7): List<Pair<String, Float?>> = coroutineScope {
        val end = LocalDate.parse(endDate)
        (days - 1 downTo 0).map { back ->
            val d = end.minusDays(back.toLong()).toString()
            async { d to api.fetchEntriesForDate(d, member.userId).getOrNull()?.sumOf { it.calories.toDouble() }?.toFloat() }
        }.map { it.await() }
    }

    /**
     * Sends [entries] (one meal of the signed-in user) to people. Each person with a share gets every item scaled to
     * their percent in their own diary, on the same day and meal. In SPLIT mode the user's own items shrink to their
     * share (anything that couldn't be sent stays with the user); in COPY mode nothing of the user's changes.
     */
    suspend fun shareMeal(entries: List<FoodEntry>, shares: List<Share>, mode: ShareMode): List<ShareOutcome> = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext emptyList()
        val others = shares.filter { it.member != null && it.percent > 0 }
        val results = mutableListOf<ShareOutcome>()

        // Link each item to a Sparky food once (not once per person), so nothing is created twice
        val links = entries.associate { it.id to foodRepository.sparkyLinkFor(it) }
        val mealName = entries.first().mealName

        for (share in others) {
            val member = share.member!!
            val theirMealType = api.fetchMealTypes(actingAs = member.userId).getOrNull()
                ?.firstOrNull { it.name.trim().equals(mealName.trim(), ignoreCase = true) }?.id
            var sent = 0
            var error: String? = null
            for (entry in entries) {
                val link = links[entry.id]?.getOrNull()
                if (link == null) { error = links[entry.id]?.exceptionOrNull()?.message ?: "Couldn't prepare ${entry.foodName}"; break }
                val scaled = FamilySplit.scale(entry, share.percent).copy(
                    id = UUID.randomUUID().toString(),
                    foodId = link.first,
                    mealTypeId = theirMealType ?: "",         // blank: Sparky looks the meal up by its name
                    syncStatus = FoodEntry.SYNC_STATUS_SYNCED
                )
                val res = api.createFoodEntry(scaled, link.first, link.second, forUserId = member.userId)
                if (res.isFailure) { error = res.exceptionOrNull()?.message; break }
                sent++
            }
            results += ShareOutcome(member, share.percent, sent, error)
        }

        if (mode == ShareMode.SPLIT) {
            val mine = shares.firstOrNull { it.member == null }?.percent ?: 0
            // What couldn't be sent stays with the user
            val failed = results.filter { !it.ok }.sumOf { it.percent }
            applyMyShare(entries, (mine + failed).coerceIn(0, 100))
        }
        results
    }

    private suspend fun applyMyShare(entries: List<FoodEntry>, percent: Int) {
        if (percent >= 100) return
        if (percent <= 0) { foodRepository.deleteEntries(entries); return }
        entries.forEach { foodRepository.updateEntry(FamilySplit.scale(it, percent)) }
    }

    /** Copies a family member's meal into the signed-in user's own diary (as new entries the app then uploads). */
    suspend fun copyToMe(entries: List<FoodEntry>, date: String, mealTypeId: String, mealName: String, percent: Int = 100) {
        val copies = entries.map {
            FamilySplit.scale(it, percent).copy(
                id = UUID.randomUUID().toString(), foodId = null,
                mealTypeId = mealTypeId, mealName = mealName, entryDate = date,
                syncStatus = FoodEntry.SYNC_STATUS_PENDING_INSERT
            )
        }
        foodRepository.commitMultiAdd(copies)
    }
}
