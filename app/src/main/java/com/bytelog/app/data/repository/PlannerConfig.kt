package com.bytelog.app.data.repository

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** How the meal planner shows the plan. Stored as "d=7;s=today;m=1;g=1". */
data class PlannerConfig(
    /** Days shown at a time. */
    val days: Int = 7,
    /** Start on today, or on the Monday of this week (so past days of the week show too). */
    val startOnMonday: Boolean = false,
    /** Protein, carbs and fat under each day. */
    val showMacros: Boolean = true,
    /** Each day's calories against the daily goal. */
    val showGoal: Boolean = true,
    /** What each food and day costs, from saved prices. */
    val showCosts: Boolean = true
) {
    fun encode(): String = "d=$days;s=${if (startOnMonday) "mon" else "today"};m=${if (showMacros) 1 else 0};g=${if (showGoal) 1 else 0};c=${if (showCosts) 1 else 0}"

    /** The first day shown, [page] periods from now. */
    fun start(today: LocalDate, page: Int = 0): LocalDate {
        val base = if (startOnMonday) today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else today
        return base.plusDays(page.toLong() * days)
    }

    companion object {
        val DAY_CHOICES = listOf(3, 7, 14)

        fun parse(raw: String?): PlannerConfig {
            if (raw.isNullOrBlank()) return PlannerConfig()
            val m = raw.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            return PlannerConfig(
                days = m["d"]?.toIntOrNull()?.takeIf { it in 1..31 } ?: 7,
                startOnMonday = m["s"] == "mon",
                showMacros = m["m"] != "0",
                showGoal = m["g"] != "0",
                showCosts = m["c"] != "0"
            )
        }
    }
}
