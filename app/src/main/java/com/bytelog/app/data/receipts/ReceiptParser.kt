package com.bytelog.app.data.receipts

import com.bytelog.app.recipes.UnitConverter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A line of text read from a photo, with where it sat on the page (pixels; top-left origin). */
data class OcrLine(val text: String, val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/** A pack size printed in an item's name: "32OZ", "5 LB", "12 CT". */
data class PackSize(val quantity: Float, val unit: String)

/** One thing bought, as printed on the receipt. */
data class ReceiptItem(
    /** The text as printed ("GRK YOG 32OZ"). */
    val raw: String,
    /** The name with codes, sizes and prices taken off ("GRK YOG"). */
    val name: String,
    /** What the line cost in total. */
    val price: Float,
    /** How many were bought ("2 @ 1.50"), 1 when not printed. */
    val count: Int = 1,
    /** Weighed items: the weight and the price per unit of it ("1.52 lb @ 2.99/lb"). */
    val weight: PackSize? = null,
    val pricePerWeightUnit: Float? = null,
    /** A pack size in the name. */
    val size: PackSize? = null
) {
    /** What one of them cost. */
    val unitPrice: Float get() = if (count > 1) price / count else price
}

data class Receipt(val store: String?, val date: LocalDate?, val items: List<ReceiptItem>, val total: Float?)

/**
 * Reads a shop receipt from the text recognised on a photo of it: the store, the date, and each item with its price.
 * Totals, tax, payment, change and loyalty lines are left out; "2 @ 1.50" and "1.52 lb @ 2.99/lb" lines are joined to
 * the item they belong to, and discounts are taken off the item above them.
 */
object ReceiptParser {
    private val money = Regex("""(-?)\$?\s?(\d{1,4}(?:[.,]\d{2}))\s*(-?)\s*[A-Z*#]{0,3}$""")
    private val skipWords = listOf(
        "SUBTOTAL", "SUB TOTAL", "TOTAL", "TAX", "BALANCE", "CHANGE", "CASH", "VISA", "MASTERCARD", "MASTER CARD", "AMEX",
        "DEBIT", "CREDIT", "CARD", "TEND", "PAYMENT", "AMOUNT DUE", "APPROVED", "AUTH", "REF #", "ITEMS SOLD", "# ITEMS",
        "NUMBER OF ITEMS", "SAVINGS", "YOU SAVED", "TOTAL SAVINGS", "REWARDS", "POINTS", "BOTTLE DEPOSIT", "BAG FEE",
        "THANK YOU", "STORE #", "TEL", "PHONE", "MEMBER", "CASHIER", "TERMINAL", "TRANS", "INVOICE", "RECEIPT", "VOID", "REFUND"
    )
    private val discount = Regex("""\b(DISCOUNT|COUPON|INSTANT SAVINGS|PROMO|SAVE|MARKDOWN|OFF|DEAL|CPN|TPD|MFR|SALE)\b""")
    private val knownStores = listOf(
        "COSTCO", "WALMART", "TARGET", "KROGER", "ALDI", "TRADER JOE", "WHOLE FOODS", "SAFEWAY", "PUBLIX", "HEB", "H-E-B",
        "MEIJER", "WEGMANS", "SPROUTS", "FOOD LION", "GIANT", "STOP & SHOP", "ALBERTSONS", "VONS", "RALPHS", "FRED MEYER",
        "HY-VEE", "WINCO", "LIDL", "SAM'S CLUB", "SAMS CLUB", "BJ'S", "FOOD 4 LESS", "SMITH'S", "KING SOOPERS", "TESCO",
        "SAINSBURY", "ASDA", "MORRISONS", "WAITROSE", "LOBLAWS", "NO FRILLS", "METRO", "SOBEYS", "WOOLWORTHS", "COLES"
    )
    private val weighed = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(lb|lbs|kg|g|oz)\s*@\s*\$?\s?(\d+(?:[.,]\d+)?)\s*/\s*(lb|kg|g|oz)""")
    private val multi = Regex("""(?i)^\s*(\d{1,3})\s*@\s*\$?\s?(\d+[.,]\d{2})""")
    private val sizeInName = Regex("""(?i)(\d+/\d+|\d+(?:[.,]\d+)?)\s*(fl\s?oz|oz|lb|lbs|g|kg|ml|l|ltr|gal|ct|pk|pack|dz|doz)\b""")
    private val leadingCode = Regex("""^\s*(\d{4,14}|E\s+\d+|[A-Z]?\d{5,})\s+""")
    private val dates = listOf(
        Regex("""\b(\d{1,2})/(\d{1,2})/(\d{2,4})\b""") to "M/d/y",
        Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""") to "y-M-d"
    )

    private fun num(s: String): Float? {
        if ('/' in s) { val (a, b) = s.split('/', limit = 2); return a.toFloatOrNull()?.let { x -> b.toFloatOrNull()?.takeIf { it > 0f }?.let { x / it } } }
        return s.replace(',', '.').toFloatOrNull()
    }

    /** The text in reading order: lines at the same height (name on the left, price on the right) joined into rows. */
    fun rows(lines: List<OcrLine>): List<String> {
        if (lines.isEmpty()) return emptyList()
        if (lines.all { it.top == 0 && it.bottom == 0 }) return lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (l in sorted) {
            val row = rows.lastOrNull()
            val ref = row?.let { r -> r.sumOf { it.centerY } / r.size }
            val tol = (row?.maxOf { it.height } ?: l.height).coerceAtLeast(l.height) / 2
            if (row != null && ref != null && kotlin.math.abs(l.centerY - ref) <= tol) row += l else rows += mutableListOf(l)
        }
        return rows.map { r -> r.sortedBy { it.left }.joinToString("  ") { it.text.trim() } }
    }

    fun parse(lines: List<OcrLine>): Receipt = parseRows(rows(lines))

    fun parseRows(rows: List<String>): Receipt {
        val store = storeOf(rows)
        val date = rows.firstNotNullOfOrNull { dateOf(it) }
        val items = mutableListOf<ReceiptItem>()
        var total: Float? = null
        // A "2 @ 1.50" or weight line printed before its item's line, waiting for it
        var pendingCount: Int? = null
        var pendingWeight: Pair<PackSize, Float>? = null
        // A name printed on a line of its own, whose price comes on the weight line below it
        var pendingName: String? = null

        for (raw in rows) {
            val line = raw.trim().replace(Regex("\\s+"), " ")
            if (line.isEmpty()) continue
            val upper = line.uppercase(Locale.US)
            val priceMatch = money.find(upper)
            val isTotal = Regex("""\bTOTAL\b""").containsMatchIn(upper) && !upper.contains("SUB") && !upper.contains("SAVING")
            if (isTotal && priceMatch != null) { total = num(priceMatch.groupValues[2]); pendingName = null; continue }

            val w = weighed.find(line)
            if (w != null) {
                val weight = PackSize(num(w.groupValues[1]) ?: 0f, w.groupValues[2].lowercase().removeSuffix("s"))
                val per = num(w.groupValues[3]) ?: 0f
                val lineTotal = priceMatch?.takeIf { it.range.first > w.range.last }?.let { num(it.groupValues[2]) }
                val last = items.lastOrNull()
                val name = pendingName
                when {
                    // The weight line under its item ("BANANAS 1.24" then "2.10 lb @ 0.59/lb")
                    lineTotal == null && last != null && last.weight == null && kotlin.math.abs(last.price - weight.quantity * per) < 0.05f ->
                        items[items.lastIndex] = last.copy(weight = weight, pricePerWeightUnit = per)
                    // The weight line with the total, under a name-only line
                    lineTotal != null && name != null ->
                        items += ReceiptItem("$name $line", cleanName(name), lineTotal, weight = weight, pricePerWeightUnit = per)
                    // Before its item
                    else -> pendingWeight = weight to per
                }
                pendingName = null
                continue
            }

            val m = multi.find(line)
            if (m != null) {
                val count = m.groupValues[1].toInt()
                val each = num(m.groupValues[2]) ?: 0f
                val last = items.lastOrNull()
                if (last != null && last.count == 1 && kotlin.math.abs(last.price - count * each) < 0.05f) items[items.lastIndex] = last.copy(count = count)
                else pendingCount = count
                continue
            }

            // A negative amount is a saving on the item above, whatever the line calls it
            if (priceMatch != null && (priceMatch.groupValues[1] == "-" || priceMatch.groupValues[3] == "-") && items.isNotEmpty()) {
                // Costco and others print the item's number on the saving ("/ 987654"); else it's the item above
                val codes = Regex("""\b\d{5,}\b""").findAll(upper.substring(0, priceMatch.range.first)).map { it.value.trimStart('0') }.toList()
                val at = items.indexOfLast { item -> codes.any { c -> c.isNotEmpty() && Regex("""\b0*$c\b""").containsMatchIn(item.raw) } }
                    .takeIf { it >= 0 } ?: items.lastIndex
                val target = items[at]
                items[at] = target.copy(price = (target.price - (num(priceMatch.groupValues[2]) ?: 0f)).coerceAtLeast(0f))
                pendingName = null
                continue
            }
            if (skipWords.any { upper.contains(it) }) { pendingName = null; continue }
            if (priceMatch == null) {
                pendingName = line.takeIf { it.count(Char::isLetter) >= 3 }
                continue
            }
            pendingName = null

            val price = num(priceMatch.groupValues[2]) ?: continue
            val negative = priceMatch.groupValues[1] == "-" || priceMatch.groupValues[3] == "-"
            val label = upper.substring(0, priceMatch.range.first).trim()
            if (items.isNotEmpty() && (negative || (discount.containsMatchIn(label) && price < items.last().price))) {
                // A discount on the item above
                val last = items.last()
                items[items.lastIndex] = last.copy(price = (last.price - price).coerceAtLeast(0f))
                continue
            }
            if (negative) continue
            val name = cleanName(label)
            if (name.length < 2 || name.count(Char::isLetter) < 2 || price <= 0f) continue

            var item = ReceiptItem(raw = line, name = name, price = price, size = sizeOf(label))
            pendingCount?.let { c -> if (c > 1) item = item.copy(count = c) }
            pendingWeight?.let { (wt, per) -> item = item.copy(weight = wt, pricePerWeightUnit = per) }
            pendingCount = null; pendingWeight = null
            items += item
        }
        return Receipt(store, date, items, total)
    }

    /** The name without item codes, sizes, tax flags and stray symbols, in title case words as printed. */
    fun cleanName(label: String): String {
        var s = label.replace(leadingCode, "")
        s = s.replace(sizeInName, " ")
        s = s.replace(Regex("""[^A-Za-z0-9&'% ]"""), " ")
        s = s.replace(Regex("""\b\d{5,}\b"""), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** A pack size in a name, in a unit Nibl knows ("32OZ" is 32 oz, "12CT" is 12 each). */
    fun sizeOf(label: String): PackSize? {
        val m = sizeInName.find(label) ?: return null
        val q = num(m.groupValues[1]) ?: return null
        val u = m.groupValues[2].lowercase().replace(" ", "")
        val unit = when (u) {
            "floz" -> "fl oz"; "lbs" -> "lb"; "ltr" -> "l"; "gal" -> "gallon"
            "ct", "pk", "pack" -> "each"; "dz", "doz" -> "dozen"
            else -> UnitConverter.canonical(u) ?: u
        }
        return PackSize(q, unit)
    }

    private fun storeOf(rows: List<String>): String? {
        val head = rows.take(8).map { it.uppercase(Locale.US) }
        for (r in head) knownStores.firstOrNull { r.contains(it) }?.let { s ->
            return s.lowercase().split(' ', '-').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
                .replace("Joe", "Joe's").replace("Joe'ss", "Joe's").replace("Heb", "H-E-B").replace("H E B", "H-E-B")
        }
        // Else the first line that reads like a name (letters, not an address or a phone number)
        return rows.take(4).map { it.trim() }.firstOrNull { r ->
            r.length in 3..32 && r.count { it.isLetter() } >= r.length / 2 && money.find(r.uppercase()) == null && !Regex("\\d{3}").containsMatchIn(r)
        }?.lowercase()?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
    }

    private fun dateOf(row: String): LocalDate? {
        for ((re, kind) in dates) {
            val m = re.find(row) ?: continue
            return runCatching {
                when (kind) {
                    "M/d/y" -> {
                        var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
                        LocalDate.of(y, m.groupValues[1].toInt(), m.groupValues[2].toInt())
                    }
                    else -> LocalDate.parse(m.value, DateTimeFormatter.ISO_LOCAL_DATE)
                }
            }.getOrNull() ?: continue
        }
        return null
    }
}
