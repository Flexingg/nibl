package com.bytelog.app.research

/**
 * Units a serving can be measured in, and converting between them: weights to weights and volumes to volumes.
 * Anything else ("serving", "piece", "roll") only matches itself (a plural "rolls" is the same as "roll").
 */
object UnitMath {
    private enum class Kind { MASS, VOLUME }

    /** Grams or millilitres in one of each unit. */
    private val units: Map<String, Pair<Kind, Double>> = buildMap {
        fun add(kind: Kind, size: Double, vararg names: String) = names.forEach { put(it, kind to size) }
        add(Kind.MASS, 1.0, "g", "gram", "grams", "gr", "gm")
        add(Kind.MASS, 1000.0, "kg", "kilogram", "kilograms")
        add(Kind.MASS, 0.001, "mg", "milligram", "milligrams")
        add(Kind.MASS, 28.349523125, "oz", "ounce", "ounces", "wt oz", "net wt oz")
        add(Kind.MASS, 453.59237, "lb", "lbs", "pound", "pounds")
        add(Kind.VOLUME, 1.0, "ml", "milliliter", "milliliters", "millilitre", "millilitres", "cc")
        add(Kind.VOLUME, 1000.0, "l", "liter", "liters", "litre", "litres")
        add(Kind.VOLUME, 10.0, "cl")
        add(Kind.VOLUME, 100.0, "dl")
        add(Kind.VOLUME, 4.92892159375, "tsp", "teaspoon", "teaspoons")
        add(Kind.VOLUME, 14.78676478125, "tbsp", "tablespoon", "tablespoons")
        add(Kind.VOLUME, 29.5735295625, "fl oz", "floz", "fl. oz", "fluid ounce", "fluid ounces")
        add(Kind.VOLUME, 240.0, "cup", "cups")   // US nutrition-label cup
        add(Kind.VOLUME, 473.176473, "pint", "pints", "pt")
        add(Kind.VOLUME, 946.352946, "quart", "quarts", "qt")
        add(Kind.VOLUME, 3785.411784, "gallon", "gallons", "gal")
    }

    /** A unit name as it's compared: lower case, single spaces, no trailing dot. */
    fun normalize(unit: String): String = unit.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.')

    private fun singular(u: String) = when {
        u.endsWith("ies") && u.length > 4 -> u.dropLast(3) + "y"
        u.endsWith("es") && (u.endsWith("ches") || u.endsWith("shes") || u.endsWith("sses")) -> u.dropLast(2)
        u.endsWith("s") && !u.endsWith("ss") && u.length > 2 -> u.dropLast(1)
        else -> u
    }

    /** True for g, oz, ml, cup and the like (units that convert); false for "serving", "piece", "roll". */
    fun isMeasure(unit: String): Boolean = units.containsKey(normalize(unit))

    /**
     * [amount] [from] expressed in [to], or null when they don't convert (a weight to a volume, or two different
     * count units). Count units convert only to themselves.
     */
    fun convert(amount: Double, from: String, to: String): Double? {
        val f = normalize(from); val t = normalize(to)
        val a = units[f]; val b = units[t]
        if (a != null && b != null) return if (a.first == b.first) amount * a.second / b.second else null
        if (a == null && b == null && singular(f) == singular(t)) return amount
        return null
    }
}
