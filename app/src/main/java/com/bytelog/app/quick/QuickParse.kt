package com.bytelog.app.quick

/**
 * Reads the text of a water, weight, fast or caffeine command, as typed in a search bar: "16 oz", "500ml",
 * "182.4", "82 kg 18%", "start 18:6", "stop", "coffee 95".
 */
object QuickParse {
    private val number = Regex("""(\d+(?:[.,]\d+)?)""")
    private const val ML_PER_OZ = 29.5735f

    private val undoWords = setOf("undo", "remove", "delete", "minus", "-", "takeback", "back", "oops")

    fun water(text: String): QuickCommand {
        val t = text.lowercase().trim()
        if (t.isEmpty() || t in setOf("bottle", "glass", "cup", "drink", "add", "+", "log")) return QuickCommand.AddWater(null)
        if (t.replace(" ", "") in undoWords || t.startsWith("undo") || t.startsWith("remove") || t.startsWith("-")) return QuickCommand.UndoWater
        val m = number.find(t) ?: return QuickCommand.Unclear("Water takes an amount, like 16 oz or 500 ml, or undo")
        val n = m.value.replace(',', '.').toFloat()
        val unit = t.substring(m.range.last + 1).trim()
        val oz = when {
            unit.startsWith("ml") || unit.startsWith("millil") -> n / ML_PER_OZ
            unit.startsWith("l") && !unit.startsWith("lb") -> n * 1000f / ML_PER_OZ
            unit.startsWith("cup") -> n * 8f
            unit.startsWith("gal") -> n * 128f
            else -> n // oz, fl oz or a plain number
        }
        if (oz <= 0f || oz > 300f) return QuickCommand.Unclear("That's a lot of water: try an amount like 16 oz")
        return QuickCommand.AddWater(Math.round(oz * 10f) / 10f)
    }

    fun weight(text: String): QuickCommand {
        val t = text.lowercase().replace(',', '.')
        val matches = number.findAll(t).toList()
        val first = matches.firstOrNull() ?: return QuickCommand.Unclear("Weight takes a number, like 182.4 or 82.5 kg")
        val w = first.value.toFloat()
        val after = t.substring(first.range.last + 1).trimStart()
        val unit = when {
            after.startsWith("kg") || after.startsWith("kilo") -> "kg"
            after.startsWith("lb") || after.startsWith("pound") -> "lbs"
            else -> null
        }
        // A second number with "%" (or "bf", "fat") is body fat
        val fat = matches.drop(1).firstOrNull { m ->
            val tail = t.substring(m.range.last + 1).trimStart()
            tail.startsWith("%") || tail.startsWith("bf") || tail.startsWith("fat") || t.substring(0, m.range.first).trimEnd().endsWith("bf")
        }?.value?.toFloat()?.takeIf { it in 2f..70f }
        if (w < 20f || w > 1000f) return QuickCommand.Unclear("$w doesn't look like a weight")
        return QuickCommand.LogWeight(w, unit, fat)
    }

    private val protocol = Regex("""\b(\d{2})\s*[:\-/]\s*(\d{1,2})\b""")

    fun fast(text: String): QuickCommand {
        val t = text.lowercase().trim()
        val words = t.split(' ').filter { it.isNotBlank() }
        val verb = words.firstOrNull().orEmpty()
        val proto = protocol.find(t)?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }
            ?: if ("omad" in words) "23-1" else null
        return when {
            verb in setOf("stop", "end", "break", "finish", "done", "eat") -> QuickCommand.EndFast
            verb in setOf("start", "begin", "go") || proto != null -> QuickCommand.StartFast(proto)
            verb.isEmpty() || verb in setOf("status", "check", "how", "info") -> QuickCommand.FastStatus
            else -> QuickCommand.Unclear("Fasting takes start (optionally 16:8, 18:6, OMAD…), stop or status")
        }
    }

    fun caffeine(text: String, defaultName: String = "Caffeine"): QuickCommand {
        val t = text.trim()
        val m = number.find(t) ?: return QuickCommand.Unclear("Caffeine takes an amount in mg, like coffee 95")
        val mg = m.value.replace(',', '.').toFloat()
        if (mg <= 0f || mg > 1000f) return QuickCommand.Unclear("$mg mg doesn't look right")
        val name = (t.substring(0, m.range.first) + " " + t.substring(m.range.last + 1))
            .replace(Regex("""(?i)\bmg\b"""), " ")
            .replace(Regex("\\s+"), " ").trim()
            .ifEmpty { defaultName }
            .replaceFirstChar { it.titlecase() }
        return QuickCommand.LogCaffeine(name, mg)
    }
}
