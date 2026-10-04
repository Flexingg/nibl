package com.bytelog.app.recipes

/**
 * Unit-aware quantity conversion for food math.
 *
 * Mass and volume units convert exactly within their own dimension. Volume → mass
 * needs a density (g/ml), taken from [IngredientDensities] by ingredient name.
 * Anything else (cans, cloves, "serving") is not convertible here and returns null,
 * so callers can flag the line instead of guessing.
 */
object UnitConverter {

    enum class Dimension { MASS, VOLUME, COUNT }

    data class UnitDef(val canonical: String, val dimension: Dimension, val toBase: Double)

    // Base units: grams for mass, millilitres for volume.
    private val units: Map<String, UnitDef> = buildMap {
        fun add(def: UnitDef, vararg aliases: String) {
            put(def.canonical, def)
            aliases.forEach { put(it, def) }
        }
        add(UnitDef("g", Dimension.MASS, 1.0), "gram", "grams", "gr", "grs", "gm", "gms")
        add(UnitDef("kg", Dimension.MASS, 1000.0), "kilogram", "kilograms", "kgs", "kilo", "kilos")
        add(UnitDef("mg", Dimension.MASS, 0.001), "milligram", "milligrams")
        add(UnitDef("oz", Dimension.MASS, 28.349523125), "ounce", "ounces", "onz")
        add(UnitDef("lb", Dimension.MASS, 453.59237), "lbs", "pound", "pounds", "#")

        add(UnitDef("ml", Dimension.VOLUME, 1.0), "milliliter", "milliliters", "millilitre", "millilitres", "mls", "cc")
        add(UnitDef("l", Dimension.VOLUME, 1000.0), "liter", "liters", "litre", "litres", "ltr")
        add(UnitDef("dl", Dimension.VOLUME, 100.0), "deciliter", "deciliters")
        add(UnitDef("tsp", Dimension.VOLUME, 4.92892159375), "teaspoon", "teaspoons", "tsps", "t", "tspn")
        add(UnitDef("tbsp", Dimension.VOLUME, 14.78676478125), "tablespoon", "tablespoons", "tbsps", "tbs", "tbl", "tbls", "tblsp", "T")
        add(UnitDef("fl oz", Dimension.VOLUME, 29.5735295625), "floz", "fl. oz", "fl.oz", "fluid ounce", "fluid ounces")
        add(UnitDef("cup", Dimension.VOLUME, 236.5882365), "cups", "c", "c.")
        add(UnitDef("pint", Dimension.VOLUME, 473.176473), "pints", "pt", "pts")
        add(UnitDef("quart", Dimension.VOLUME, 946.352946), "quarts", "qt", "qts")
        add(UnitDef("gallon", Dimension.VOLUME, 3785.411784), "gallons", "gal")
        add(UnitDef("pinch", Dimension.VOLUME, 0.3), "pinches")
        add(UnitDef("dash", Dimension.VOLUME, 0.6), "dashes")
    }

    /** Count-like units we recognise when parsing but cannot convert without a food-specific weight. */
    val countUnits: Set<String> = setOf(
        "serving", "servings", "piece", "pieces", "pc", "pcs", "clove", "cloves", "can", "cans",
        "jar", "jars", "package", "packages", "pkg", "pkgs", "packet", "packets", "slice", "slices",
        "stick", "sticks", "sprig", "sprigs", "bunch", "bunches", "head", "heads", "stalk", "stalks",
        "large", "medium", "small", "whole", "container", "containers", "bag", "bags", "bottle",
        "bottles", "box", "boxes", "block", "blocks", "fillet", "fillets", "leaf", "leaves",
        "handful", "handfuls", "portion", "portions", "egg", "eggs"
    )

    /**
     * Normalises a unit string to its canonical form ("Tablespoons" → "tbsp").
     * Single-letter "T" (tablespoon) and "t" (teaspoon) are case-sensitive by convention.
     */
    fun canonical(unit: String): String? {
        val raw = unit.trim().trimEnd('.')
        if (raw == "T" || raw == "Tbsp" || raw == "TBSP") return "tbsp"
        if (raw == "t") return "tsp"
        return units[raw.lowercase()]?.canonical
    }

    fun dimension(unit: String): Dimension? {
        val c = canonical(unit) ?: return if (unit.trim().lowercase() in countUnits) Dimension.COUNT else null
        return units[c]?.dimension
    }

    fun isKnownUnit(unit: String): Boolean = canonical(unit) != null || unit.trim().lowercase() in countUnits

    /**
     * Converts [quantity] from [fromUnit] to [toUnit].
     * @param densityGPerMl used only for volume ↔ mass conversions.
     * @return null when the units cannot be converted.
     */
    fun convert(quantity: Double, fromUnit: String, toUnit: String, densityGPerMl: Double? = null): Double? {
        if (fromUnit.trim().equals(toUnit.trim(), ignoreCase = true)) return quantity
        val from = canonical(fromUnit)?.let { units[it] } ?: return null
        val to = canonical(toUnit)?.let { units[it] } ?: return null
        val base = quantity * from.toBase
        return when {
            from.dimension == to.dimension -> base / to.toBase
            from.dimension == Dimension.VOLUME && to.dimension == Dimension.MASS && densityGPerMl != null ->
                base * densityGPerMl / to.toBase
            from.dimension == Dimension.MASS && to.dimension == Dimension.VOLUME && densityGPerMl != null && densityGPerMl > 0 ->
                base / densityGPerMl / to.toBase
            else -> null
        }
    }

    /** Converts to grams, using the ingredient name to look up a density for volume units. */
    fun toGrams(quantity: Double, unit: String, ingredientName: String? = null): Double? {
        val density = ingredientName?.let { IngredientDensities.lookup(it) }
        return convert(quantity, unit, "g", density)
    }

    /**
     * The ratio of a logged amount to a food's reference serving, honouring units.
     * Falls back to a plain `quantity / servingSize` when units are the same or not
     * convertible (e.g. both are "serving"), which matches the historic behaviour.
     */
    fun servingRatio(
        quantity: Float,
        unit: String,
        servingSize: Float,
        servingUnit: String,
        ingredientName: String? = null
    ): Float {
        if (servingSize <= 0f) return 1f
        if (unit.isBlank() || servingUnit.isBlank() || unit.trim().equals(servingUnit.trim(), ignoreCase = true)) {
            return quantity / servingSize
        }
        val density = ingredientName?.let { IngredientDensities.lookup(it) }
        val converted = convert(quantity.toDouble(), unit, servingUnit, density)
        if (converted != null) return (converted / servingSize).toFloat()
        // "1 serving" of a food whose reference is "150 g" means one reference serving.
        val u = unit.trim().lowercase()
        if (u == "serving" || u == "servings" || u == "portion" || u == "portions") return quantity
        return quantity / servingSize
    }
}

/**
 * Approximate densities (g per ml) for common recipe ingredients measured by volume.
 * Matched by the longest keyword contained in the ingredient name.
 */
object IngredientDensities {
    private val table: List<Pair<String, Double>> = listOf(
        "all-purpose flour" to 0.53, "all purpose flour" to 0.53, "bread flour" to 0.55,
        "whole wheat flour" to 0.51, "almond flour" to 0.4, "coconut flour" to 0.5, "flour" to 0.53,
        "cornstarch" to 0.54, "corn starch" to 0.54, "cocoa" to 0.42,
        "powdered sugar" to 0.5, "icing sugar" to 0.5, "confectioners" to 0.5,
        "brown sugar" to 0.9, "sugar" to 0.85, "honey" to 1.42, "maple syrup" to 1.32, "syrup" to 1.33,
        "molasses" to 1.4,
        "olive oil" to 0.91, "coconut oil" to 0.92, "oil" to 0.92, "butter" to 0.96, "ghee" to 0.91,
        "heavy cream" to 1.0, "sour cream" to 1.0, "cream cheese" to 1.0, "cream" to 1.0,
        "greek yogurt" to 1.05, "yogurt" to 1.03, "milk" to 1.03, "buttermilk" to 1.03,
        "cottage cheese" to 0.95, "ricotta" to 1.03,
        "shredded cheese" to 0.45, "grated parmesan" to 0.42, "parmesan" to 0.42, "cheese" to 0.45,
        "water" to 1.0, "broth" to 1.0, "stock" to 1.0, "wine" to 0.99, "vinegar" to 1.01,
        "soy sauce" to 1.15, "tamari" to 1.15, "fish sauce" to 1.2, "worcestershire" to 1.1,
        "ketchup" to 1.1, "mustard" to 1.05, "mayonnaise" to 0.91, "mayo" to 0.91, "salsa" to 1.0,
        "tomato paste" to 1.1, "tomato sauce" to 1.03, "marinara" to 1.03, "crushed tomatoes" to 1.05,
        "diced tomatoes" to 1.0, "coconut milk" to 0.98, "lemon juice" to 1.03, "lime juice" to 1.03,
        "juice" to 1.04, "peanut butter" to 1.08, "almond butter" to 1.08, "tahini" to 1.0,
        "rolled oats" to 0.38, "oats" to 0.38, "quinoa" to 0.72, "rice" to 0.8, "lentils" to 0.8,
        "dry beans" to 0.75, "black beans" to 0.75, "chickpeas" to 0.7, "beans" to 0.75,
        "pasta" to 0.45, "breadcrumbs" to 0.25, "panko" to 0.2, "granola" to 0.45,
        "chocolate chips" to 0.72, "raisins" to 0.65, "almonds" to 0.6, "walnuts" to 0.5,
        "pecans" to 0.45, "peanuts" to 0.6, "nuts" to 0.55, "seeds" to 0.6, "chia" to 0.65,
        "frozen peas" to 0.6, "peas" to 0.6, "corn" to 0.65, "spinach" to 0.13, "kale" to 0.1,
        "lettuce" to 0.2, "cabbage" to 0.37, "onion" to 0.6, "carrot" to 0.55, "celery" to 0.5,
        "bell pepper" to 0.55, "pepper" to 0.55, "broccoli" to 0.38, "mushroom" to 0.3,
        "zucchini" to 0.55, "potato" to 0.65, "sweet potato" to 0.65, "berries" to 0.6,
        "blueberries" to 0.62, "strawberries" to 0.6, "banana" to 0.6,
        "salt" to 1.2, "kosher salt" to 0.6, "baking soda" to 0.9, "baking powder" to 0.9,
        "garlic powder" to 0.5, "onion powder" to 0.5, "paprika" to 0.45, "cumin" to 0.45,
        "chili powder" to 0.45, "cinnamon" to 0.5, "oregano" to 0.2, "basil" to 0.2,
        "black pepper" to 0.5, "spice" to 0.45, "seasoning" to 0.45, "minced garlic" to 0.95,
        "garlic" to 0.6, "ginger" to 0.6, "ground beef" to 0.95, "ground turkey" to 0.95,
        "chicken" to 0.6, "shredded chicken" to 0.45, "tofu" to 0.95, "egg white" to 1.03, "egg" to 1.03
    ).sortedByDescending { it.first.length }

    /**
     * The head noun usually comes last ("low-sodium chicken broth" is broth, not chicken),
     * so prefer the keyword that ends latest in the name, then the longest.
     */
    fun lookup(name: String): Double? {
        val n = name.lowercase()
        return table.mapNotNull { (key, density) ->
            val idx = n.lastIndexOf(key)
            if (idx >= 0) Triple(idx + key.length, key.length, density) else null
        }.maxWithOrNull(compareBy<Triple<Int, Int, Double>> { it.first }.thenBy { it.second })?.third
    }
}
