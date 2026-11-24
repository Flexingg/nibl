package com.bytelog.app.data.caffeine

import kotlinx.serialization.Serializable

@Serializable
data class CaffeineDose(
    val at: String,
    val mg: Float,
    val name: String,
    val isEstimated: Boolean = false
)

@Serializable
data class CaffeineStatus(
    val halfLifeHours: Float = 5f,
    val targetBedtime: String = "22:30",
    val bedtimeAt: String? = null,
    val doses: List<CaffeineDose> = emptyList(),
    val activeMgNow: Float = 0f,
    val atBedtimeMg: Float = 0f,
    val latestSafeDoseTime: String? = null,
    val cutoffState: String = "safe",
    val bedtimeHeadroomMg: Float = 100f,
    val thresholdMg: Float = 100f,
    val totalTodayMg: Float = 0f
)

@Serializable
data class CaffeineSettings(
    val halfLifeHours: Float = 5f,
    val targetBedtime: String = "22:30"
)

data class CaffeinePreset(
    val name: String,
    val mg: Float,
    val emoji: String
)

val DefaultCaffeinePresets: List<CaffeinePreset> = listOf(
    CaffeinePreset("Espresso", 63f, "\u2615"),
    CaffeinePreset("Drip Coffee", 95f, "\uD83E\uDED6"),
    CaffeinePreset("Cold Brew", 200f, "\uD83E\uDDCA"),
    CaffeinePreset("Latte", 75f, "\uD83E\uDD5B"),
    CaffeinePreset("Green Tea", 35f, "\uD83C\uDF75"),
    CaffeinePreset("Black Tea", 47f, "\uD83E\uDDCB"),
    CaffeinePreset("Energy Drink", 150f, "\u26A1"),
    CaffeinePreset("Pre-Workout", 200f, "\uD83D\uDD25")
)
