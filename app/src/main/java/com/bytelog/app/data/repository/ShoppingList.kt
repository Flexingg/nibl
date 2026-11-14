package com.bytelog.app.data.repository

import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.FoodPrice
import com.bytelog.app.ui.logging.Portions
import com.bytelog.app.data.local.model.PlannedEntry
import com.bytelog.app.recipes.IngredientDensities
import com.bytelog.app.recipes.UnitConverter
import java.util.Locale
import kotlin.math.ceil

/** An amount on the shopping list: "3 cup", "450 g", "2 piece". */
data class ShoppingAmount(val quantity: Double, val unit: String) {
    val label: String get() = "${trim(quantity)} ${plural(unit, quantity)}".trim()

    companion object {
        private val pluralMeasures = setOf("cup", "pint", "quart", "gallon", "pinch", "dash")

        /** "4 pieces", "2 cups"; abbreviations ("g", "oz") and "each" stay as they are. */
        fun plural(unit: String, q: Double): String {
            val u = unit.trim()
            if (q == 1.0 || u.isEmpty() || u.endsWith("s") || u.contains(' ')) return u
            val c = UnitConverter.canonical(u)
            val pluralise = when {
                c != null -> c in pluralMeasures
                u.equals("each", true) || u.equals("dozen", true) -> false
                else -> u.length > 2 && u.all { it.isLetter() }
            }
            return if (!pluralise) u else if (u.endsWith("ch") || u.endsWith("sh") || u.endsWith("x")) u + "es" else u + "s"
        }

        fun trim(q: Double): String = when {
            q >= 10 || q % 1.0 == 0.0 -> String.format(Locale.getDefault(), "%.0f", q)
            else -> String.format(Locale.getDefault(), "%.1f", q).trimEnd('0').trimEnd('.', ',')
        }
    }
}

/** One thing to buy: how much of it the plan needs, on which days, and what it should cost. */
data class ShoppingItem(
    val key: String,
    val name: String,
    val brand: String?,
    val foodId: String?,
    val amounts: List<ShoppingAmount>,
    /** Units a price for this food can be given in: the food's own (its serving, household measures, what they convert to). */
    val units: List<String>,
    val days: List<String>,
    val price: FoodPrice?,
    /** Packages to buy, when the price says what one holds and the units convert. */
    val packages: Int?,
    /** What those packages cost. Null when it can't be worked out. */
    val cost: Double?,
    /** What a dollar buys of it, when its price and nutrition are known. */
    val value: FoodValue? = null
) {
    val store: String? get() = price?.store?.takeIf { it.isNotBlank() }
    val amountLabel: String get() = amounts.joinToString(" + ") { it.label }

    /** "Buy 2 × 32 oz", "Buy 2 tubs (32 oz)", or "Buy 4" for things priced one at a time. */
    val buyLabel: String? get() {
        val n = packages ?: return null
        val q = price?.quantity ?: return null
        val u = price.unit.orEmpty()
        price.packageName?.let { pkg -> return "Buy $n ${ShoppingAmount.plural(pkg, n.toDouble())} (${ShoppingAmount(q.toDouble(), u).label} each)" }
        return if (q == 1f && ShoppingList.normalUnit(u) == "each") "Buy $n" else "Buy $n × ${ShoppingAmount(q.toDouble(), u).label}"
    }
}

data class ShoppingListResult(val items: List<ShoppingItem>) {
    /** Stores in order (named first, A to Z), then items with no store. */
    val byStore: List<Pair<String?, List<ShoppingItem>>> get() =
        items.groupBy { it.store }.toList().sortedWith(compareBy({ it.first == null }, { it.first?.lowercase() }))
    val total: Double get() = items.sumOf { it.cost ?: 0.0 }
    val priced: Int get() = items.count { it.cost != null }
}

/**
 * The shopping list for planned food: everything planned over some days, one line per food, with amounts added up
 * across days and meals (converted to one unit where they convert), and its cost where you've saved a price.
 */
object ShoppingList {
    /** A planned food's identity on the list (and for its price): its saved id, else its name and brand. */
    fun keyOf(foodId: String?, name: String, brand: String?): String =
        foodId?.takeIf { it.isNotBlank() } ?: ("n:" + FoodSearch.identity(name, brand))

    /** A unit written one way: canonical where it's a measure ("cups" is "cup"), else lower case and singular. */
    fun normalUnit(unit: String): String {
        val u = unit.trim()
        UnitConverter.canonical(u)?.let { return it }
        val l = u.lowercase().trimEnd('.')
        if (l in eachWords) return "each"
        if (l == "dozens" || l == "doz") return "dozen"
        return when {
            l.isEmpty() -> "serving"
            l.endsWith("ies") && l.length > 4 -> l.dropLast(3) + "y"
            l.endsWith("ches") || l.endsWith("shes") || l.endsWith("xes") -> l.dropLast(2)
            l.endsWith("s") && !l.endsWith("ss") && l.length > 3 -> l.dropLast(1)
            else -> l
        }
    }

    /** Units that mean one of something. */
    private val eachWords = setOf("each", "ea", "piece", "pieces", "pc", "pcs", "item", "items", "unit", "units", "whole", "count", "ct", "x")

    private fun density(name: String) = IngredientDensities.lookup(name)

    /** [amount] in [unit], or null when it won't convert. */
    fun convert(amount: ShoppingAmount, unit: String, name: String): Double? {
        val to = normalUnit(unit)
        val from = normalUnit(amount.unit)
        if (from == to) return amount.quantity
        if (from == "each" && to == "dozen") return amount.quantity / 12.0
        if (from == "dozen" && to == "each") return amount.quantity * 12.0
        return UnitConverter.convert(amount.quantity, amount.unit, unit, density(name))
    }

    /** Adds [a] to [amounts], into an amount already there when the units match or convert. */
    private fun add(amounts: MutableList<ShoppingAmount>, a: ShoppingAmount, name: String) {
        for (i in amounts.indices) {
            val into = amounts[i]
            val same = normalUnit(into.unit) == normalUnit(a.unit)
            val dimA = UnitConverter.dimension(a.unit); val dimB = UnitConverter.dimension(into.unit)
            val converted = when {
                same -> a.quantity
                dimA != null && dimA == dimB && dimA != UnitConverter.Dimension.COUNT -> UnitConverter.convert(a.quantity, a.unit, into.unit)
                else -> null
            } ?: continue
            amounts[i] = into.copy(quantity = into.quantity + converted)
            return
        }
        amounts += a
    }

    /**
     * The units a price can be given in for [food]: its own (serving, household measures, what they convert to) plus
     * the shop measures of the same kind (lb and kg for food weighed in grams, quarts and gallons for millilitres),
     * which all convert exactly.
     */
    fun priceUnits(food: FoodItem): List<String> {
        val out = Portions.unitOptions(food).toMutableList()
        fun add(u: String) { if (out.none { (UnitConverter.canonical(it) ?: normalUnit(it)) == u }) out += u }
        val dims = out.mapNotNull { UnitConverter.canonical(it)?.let { c -> UnitConverter.dimension(c) } }.toSet()
        if (UnitConverter.Dimension.MASS in dims) { add("lb"); add("kg") }
        if (UnitConverter.Dimension.VOLUME in dims) { add("fl oz"); add("l"); add("quart"); add("gallon") }
        return out
    }

    /** Whether [unit] is one of [food]'s price units (so its serving maths applies). */
    fun isFoodUnit(food: FoodItem, unit: String): Boolean {
        val c = UnitConverter.canonical(unit) ?: normalUnit(unit)
        return priceUnits(food).any { (UnitConverter.canonical(it) ?: normalUnit(it)) == c }
    }

    /** The units a price can use for a food: its own, or for a food the phone doesn't have, those of what's planned. */
    fun unitsFor(food: FoodItem?, name: String, plannedUnits: List<String>): List<String> {
        if (food != null) return priceUnits(food)
        val base = plannedUnits.firstOrNull { UnitConverter.canonical(it) != null } ?: plannedUnits.firstOrNull() ?: "serving"
        val out = priceUnits(FoodItem(id = "", name = name, servingSize = 1f, servingUnit = base)).toMutableList()
        plannedUnits.forEach { u -> if (out.none { normalUnit(it) == normalUnit(u) }) out += u }
        return out
    }

    /**
     * Builds the list. [priceOf] finds a food's saved price by its key; [foodOf] the saved food by its id, so its
     * servings and household measures ("2 servings", "1 medium") convert to what a price is given in.
     */
    fun build(
        planned: List<PlannedEntry>,
        priceOf: (String) -> FoodPrice? = { null },
        foodOf: (String) -> FoodItem? = { null }
    ): ShoppingListResult {
        val servingOf: (String) -> Pair<Float, String>? = { id -> foodOf(id)?.let { it.servingSize to it.servingUnit } }
        val groups = LinkedHashMap<String, MutableList<PlannedEntry>>()
        // The same food planned under another id (re-saved, from the chat) merges by name
        val byName = HashMap<String, String>()
        for (p in planned) {
            val identity = FoodSearch.identity(p.foodName, p.brandName)
            val key = byName[identity] ?: keyOf(p.foodId, p.foodName, p.brandName).also { byName[identity] = it }
            groups.getOrPut(key) { mutableListOf() } += p
        }
        val items = groups.map { (key, entries) ->
            val first = entries.firstOrNull { it.foodId != null } ?: entries.first()
            val amounts = mutableListOf<ShoppingAmount>()
            for (e in entries) {
                val u = normalUnit(e.unit)
                val serving = if (u == "serving" || u == "portion") e.foodId?.let(servingOf) else null
                val a = if (serving != null && serving.first > 0f) ShoppingAmount(e.quantity.toDouble() * serving.first, serving.second)
                else ShoppingAmount(e.quantity.toDouble(), if (u == "serving" && e.quantity != 1f) "servings" else e.unit.ifBlank { "serving" })
                add(amounts, a, e.foodName)
            }
            val price = priceOf(key) ?: priceOf(keyOf(null, first.foodName, first.brandName))
            val food = entries.firstNotNullOfOrNull { e -> e.foodId?.let(foodOf) }
            val raw = entries.map { ShoppingAmount(it.quantity.toDouble(), it.unit) }
            val (packages, cost) = food?.let { f -> costByServings(f, raw, price) } ?: costOf(amounts, price, first.foodName)
            ShoppingItem(
                key, first.foodName, first.brandName, first.foodId, amounts, unitsFor(food, first.foodName, entries.map { it.unit }),
                entries.map { it.entryDate }.distinct().sorted(), price, packages, cost,
                value = FoodValue.of(food ?: FoodValue.foodOf(first), price)
            )
        }
        return ShoppingListResult(items.sortedBy { it.name.lowercase() })
    }

    /**
     * Packages and cost through the food's own servings, so any of its units convert ("3 medium" against a price for
     * "2 lb"). Null when the price or an amount isn't in one of the food's units.
     */
    fun costByServings(food: FoodItem, amounts: List<ShoppingAmount>, price: FoodPrice?): Pair<Int?, Double?>? {
        val p = price?.price ?: return null
        val q = price.quantity ?: return null
        val unit = price.unit ?: return null
        if (q <= 0f || !isFoodUnit(food, unit) || amounts.any { !isFoodUnit(food, it.unit) }) return null
        val per = food.servingRatio(q, unit).toDouble()
        if (per <= 0.0) return null
        val needed = amounts.sumOf { food.servingRatio(it.quantity.toFloat(), it.unit).toDouble() }
        val packages = ceil(needed / per - 1e-6).toInt().coerceAtLeast(1)
        return packages to packages * p.toDouble()
    }

    /** Packages to buy and their cost: whole packages, since that's what you pay for. */
    fun costOf(amounts: List<ShoppingAmount>, price: FoodPrice?, name: String): Pair<Int?, Double?> {
        val p = price?.price ?: return null to null
        val q = price.quantity
        val unit = price.unit
        if (q == null || q <= 0f || unit.isNullOrBlank()) return null to null
        var needed = 0.0
        for (a in amounts) needed += convert(a, unit, name) ?: return null to null
        val packages = ceil(needed / q - 1e-6).toInt().coerceAtLeast(1)
        return packages to packages * p.toDouble()
    }

    fun money(v: Double): String = java.text.NumberFormat.getCurrencyInstance(Locale.getDefault()).format(v)

    /** The list as text to share or paste, grouped by store. [checked] items are ticked. */
    fun asText(list: ShoppingListResult, checked: Set<String> = emptySet(), title: String = "Shopping list"): String = buildString {
        append(title).append('\n')
        for ((store, items) in list.byStore) {
            append('\n').append(store ?: "Anywhere").append('\n')
            for (i in items) {
                append(if (i.key in checked) "☑ " else "☐ ").append(i.name)
                i.brand?.let { append(" (").append(it).append(')') }
                append(" — ").append(i.amountLabel)
                i.buyLabel?.let { append(" · ").append(it.replaceFirstChar { c -> c.lowercase() }) }
                i.cost?.let { append(" · ").append(money(it)) }
                append('\n')
            }
        }
        if (list.priced > 0) append("\nAbout ").append(money(list.total)).append(if (list.priced < list.items.size) " for the items with prices\n" else "\n")
    }
}
