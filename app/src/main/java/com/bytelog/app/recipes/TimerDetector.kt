package com.bytelog.app.recipes

/** Finds cooking durations in step text, e.g. "Bake for 20-25 minutes" or "simmer 1 hour". */
object TimerDetector {

    data class DetectedTimer(val label: String, val seconds: Int, val maxSeconds: Int? = null)

    private val pattern = Regex(
        """(\d+(?:[.,]\d+)?|\d+\s*1/2|½)\s*(?:(?:-|–|to)\s*(\d+(?:[.,]\d+)?))?\s*(hours?|hrs?|h\b|minutes?|mins?|m\b|seconds?|secs?|s\b)""",
        RegexOption.IGNORE_CASE
    )

    fun detect(text: String): List<DetectedTimer> = pattern.findAll(text).mapNotNull { m ->
        val unitSeconds = unitToSeconds(m.groupValues[3]) ?: return@mapNotNull null
        val lo = parseAmount(m.groupValues[1]) ?: return@mapNotNull null
        val hi = m.groupValues[2].takeIf { it.isNotBlank() }?.let { parseAmount(it) }
        val seconds = (lo * unitSeconds).toInt()
        if (seconds <= 0 || seconds > 24 * 3600) return@mapNotNull null
        DetectedTimer(
            label = m.value.trim(),
            seconds = seconds,
            maxSeconds = hi?.let { (it * unitSeconds).toInt() }
        )
    }.toList()

    fun firstTimerSeconds(text: String): Int? = detect(text).firstOrNull()?.seconds

    private fun parseAmount(s: String): Double? {
        val t = s.trim().replace(',', '.')
        if (t == "½") return 0.5
        Regex("""^(\d+)\s*1/2$""").find(t)?.let { return it.groupValues[1].toDouble() + 0.5 }
        return t.toDoubleOrNull()
    }

    private fun unitToSeconds(unit: String): Int? {
        val u = unit.lowercase()
        return when {
            u.startsWith("h") -> 3600
            u.startsWith("m") -> 60
            u.startsWith("s") -> 1
            else -> null
        }
    }

    fun format(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
            else -> String.format(java.util.Locale.US, "%d:%02d", m, s)
        }
    }
}
