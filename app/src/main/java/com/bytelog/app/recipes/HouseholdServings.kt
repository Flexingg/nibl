package com.bytelog.app.recipes

import com.bytelog.app.data.local.model.FoodItem

/**
 * A household measure of a food: one [unit] ("cookie", "slice", "medium", "sandwich") is [perUnit] of the food's
 * reference serving, and [quantity] of them is the usual amount ("2 cookies" from a "2 cookies (28 g)" label).
 */
data class HouseholdServing(val unit: String, val perUnit: Float, val quantity: Float = 1f)

/**
 * Household servings for foods, so logging offers "1 medium", "2 cookies", "1 cup" or "4 oz" instead of the
 * 100 g that nutrition databases describe foods by. They come from the food's own servings (Sparky variants, the
 * serving on an Open Food Facts label), typical sizes of whole foods, and sensible portions for generic
 * per-100 g foods.
 */
object HouseholdServings {
    /** Stored on a food as "cookie|0.5|2;slice|0.3|1". */
    fun encode(list: List<HouseholdServing>): String? =
        list.takeIf { it.isNotEmpty() }?.joinToString(";") { "${it.unit}|${it.perUnit}|${it.quantity}" }

    fun decode(s: String?): List<HouseholdServing> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(';').mapNotNull { part ->
            val f = part.split('|')
            val unit = f.getOrNull(0)?.trim().orEmpty()
            val per = f.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
            if (unit.isEmpty() || per <= 0f) null else HouseholdServing(unit, per, f.getOrNull(2)?.toFloatOrNull()?.takeIf { it > 0f } ?: 1f)
        }
    }

    /** Words that describe a size rather than name a thing, so they don't take a plural ("2 medium"). */
    private val sizeWords = setOf("small", "medium", "large", "extra large", "jumbo", "whole", "mini")

    /** Words ending in -ie, whose plural isn't -y -> -ies ("cookies" is cookie, not cooky). */
    private val ieWords = setOf("cookie", "pie", "brownie", "smoothie", "veggie", "hoagie", "pierogie", "goalie", "calorie", "sweetie", "birdie", "nightie", "tie")

    /** "cookies" -> "cookie", "slices" -> "slice", "sandwiches" -> "sandwich"; measures are left alone. */
    fun singular(unit: String): String {
        val u = unit.trim().lowercase()
        if (UnitConverter.canonical(u) != null || u in sizeWords) return u
        return when {
            u.endsWith("ches") || u.endsWith("shes") || u.endsWith("xes") || u.endsWith("sses") -> u.dropLast(2)
            u.endsWith("ies") && u.dropLast(1) in ieWords -> u.dropLast(1)
            u.endsWith("ies") && u.length > 4 -> u.dropLast(3) + "y"
            u.endsWith("s") && !u.endsWith("ss") && u.length > 3 -> u.dropLast(1)
            else -> u
        }
    }

    /** "2 cookie" reads "2 cookies"; sizes and measures don't change ("2 medium", "2 oz"). */
    fun plural(unit: String, quantity: Float): String {
        if (quantity <= 1.001f) return unit
        val u = unit.trim()
        if (UnitConverter.canonical(u) != null || u.lowercase() in sizeWords || u.endsWith("s")) return u
        return when {
            u.endsWith("ch") || u.endsWith("sh") || u.endsWith("x") -> u + "es"
            u.endsWith("y") && u.length > 2 && u[u.length - 2] !in "aeiou" -> u.dropLast(1) + "ies"
            else -> u + "s"
        }
    }

    // ----- typical sizes of whole foods -----

    /** Grams in one [unit] of foods matched by a word in their name. */
    private data class Typical(val words: List<String>, val unit: String, val grams: Float)

    private val typicals = listOf(
        Typical(listOf("banana"), "medium", 118f), Typical(listOf("apple"), "medium", 182f),
        Typical(listOf("orange", "navel"), "medium", 140f), Typical(listOf("pear"), "medium", 178f),
        Typical(listOf("peach", "nectarine"), "medium", 150f), Typical(listOf("plum"), "medium", 66f),
        Typical(listOf("mandarin", "clementine", "tangerine"), "medium", 74f), Typical(listOf("kiwi", "kiwifruit"), "medium", 69f),
        Typical(listOf("avocado"), "medium", 150f), Typical(listOf("mango"), "medium", 200f),
        Typical(listOf("lemon", "lime"), "medium", 60f), Typical(listOf("grapefruit"), "half", 123f),
        Typical(listOf("tomato"), "medium", 123f), Typical(listOf("potato"), "medium", 213f),
        Typical(listOf("sweet potato", "yam"), "medium", 130f), Typical(listOf("onion"), "medium", 110f),
        Typical(listOf("carrot"), "medium", 61f), Typical(listOf("cucumber"), "medium", 300f),
        Typical(listOf("bell pepper", "capsicum"), "medium", 119f), Typical(listOf("zucchini", "courgette"), "medium", 196f),
        Typical(listOf("egg", "eggs"), "large", 50f), Typical(listOf("egg white"), "large", 33f),
        Typical(listOf("bread", "toast"), "slice", 30f), Typical(listOf("banana bread", "zucchini bread"), "slice", 60f),
        Typical(listOf("bagel"), "bagel", 105f),
        Typical(listOf("english muffin"), "muffin", 57f), Typical(listOf("tortilla"), "tortilla", 49f),
        Typical(listOf("wrap"), "wrap", 220f),
        Typical(listOf("pita"), "pita", 60f), Typical(listOf("bun", "roll"), "bun", 50f),
        Typical(listOf("croissant"), "croissant", 57f), Typical(listOf("muffin"), "muffin", 113f),
        Typical(listOf("donut", "doughnut"), "donut", 60f), Typical(listOf("pancake"), "pancake", 38f),
        Typical(listOf("waffle"), "waffle", 75f), Typical(listOf("cookie", "biscuit"), "cookie", 15f),
        Typical(listOf("bacon"), "slice", 8f), Typical(listOf("sausage", "hot dog", "frankfurter"), "link", 45f),
        Typical(listOf("chicken breast"), "breast", 174f), Typical(listOf("chicken thigh"), "thigh", 116f),
        Typical(listOf("chicken drumstick", "drumstick"), "drumstick", 96f), Typical(listOf("chicken wing"), "wing", 32f),
        Typical(listOf("burger patty", "hamburger patty", "patty"), "patty", 113f),
        Typical(listOf("cheese slice", "sliced cheese", "american cheese", "string cheese"), "slice", 21f),
        Typical(listOf("date", "medjool"), "date", 24f), Typical(listOf("strawberry"), "medium", 12f),
        Typical(listOf("pizza"), "slice", 107f), Typical(listOf("sandwich"), "sandwich", 200f),
        Typical(listOf("burrito"), "burrito", 220f), Typical(listOf("taco"), "taco", 100f)
    )

    /** Words that mean the food is made from, not the whole thing ("apple juice", "egg noodles", "banana chips"). */
    private val madeFrom = setOf(
        "juice", "sauce", "chips", "crisps", "dried", "powder", "noodle", "noodles", "pasta", "flour", "oil", "jam",
        "jelly", "pie", "cake", "bar", "bars", "cereal", "spread", "puree", "soup", "smoothie", "yogurt", "yoghurt",
        "salad", "crumbs", "breadcrumbs", "vinegar", "butter", "milk", "drink", "shake", "sorbet", "ice", "cream",
        "paste", "ketchup", "salsa", "mash", "mashed", "fries", "hash", "dressing", "seasoning", "mix", "substitute",
        "extract", "syrup", "chutney", "relish", "pudding", "custard", "nog", "plant", "salt", "wine", "cider",
        "broth", "stock", "bouillon", "seasoned", "flavored", "flavour", "flavor"
    )

    /** Made foods: when one is named, it's the thing ("banana bread" is bread, "egg sandwich" is a sandwich). */
    private val dishes = setOf(
        "bread", "toast", "banana bread", "zucchini bread", "bagel", "english muffin", "muffin", "tortilla", "wrap",
        "pita", "bun", "roll", "croissant", "donut", "doughnut", "pancake", "waffle", "cookie", "biscuit", "pizza",
        "sandwich", "burrito", "taco", "burger patty", "hamburger patty", "patty", "sausage", "hot dog", "frankfurter"
    )

    private fun words(name: String) = name.lowercase().replace(Regex("[^a-z ]"), " ").split(' ').filter { it.isNotBlank() }

    private fun typicalFor(name: String): Typical? {
        val w = words(name)
        if (w.isEmpty() || w.any { it in madeFrom }) return null
        val text = " " + w.joinToString(" ") + " "
        // A made food wins over what it's made of, then the most specific match ("egg white" over "egg")
        return typicals.flatMap { t -> t.words.map { it to t } }
            .filter { (word, _) -> text.contains(" $word ") || text.contains(" ${word}s ") || text.contains(" ${word}es ") }
            .maxWithOrNull(compareBy<Pair<String, Typical>> { it.first in dishes }.thenBy { it.first.length })?.second
    }

    /** Grams in the food's reference serving, when it's a weight (or a volume with a known density). */
    private fun referenceGrams(food: FoodItem): Float? =
        UnitConverter.toGrams(food.servingSize.toDouble(), food.servingUnit, food.name)?.toFloat()?.takeIf { it > 0f }

    /** The typical piece of a whole food described by weight ("Banana, raw, 100 g" -> 1 medium = 118 g). */
    fun typical(food: FoodItem): HouseholdServing? {
        val ref = referenceGrams(food) ?: return null
        val t = typicalFor(food.name) ?: return null
        return HouseholdServing(t.unit, t.grams / ref, 1f)
    }

    /** The food's household servings: its own first, then a typical piece of a whole food. */
    fun forFood(food: FoodItem): List<HouseholdServing> {
        val own = decode(food.householdServings)
        val typical = typical(food)?.takeIf { t -> own.none { singular(it.unit) == singular(t.unit) } }
        return own + listOfNotNull(typical)
    }

    /** The household serving written as [unit] ("cookies" finds "cookie"), if the food has one. */
    fun find(food: FoodItem, unit: String): HouseholdServing? {
        if (unit.isBlank()) return null
        val u = singular(unit)
        val raw = unit.trim().lowercase()
        return forFood(food).firstOrNull { val s = singular(it.unit); s == u || s == raw || it.unit.lowercase() == raw }
    }

    // ----- sensible portions of generic per-100 g foods -----

    private val meats = setOf(
        "chicken", "beef", "pork", "steak", "turkey", "lamb", "salmon", "tuna", "cod", "tilapia", "fish", "shrimp",
        "prawn", "ham", "veal", "duck", "venison", "bison", "brisket", "tenderloin", "sirloin", "ribeye", "trout",
        "halibut", "mahi", "scallops", "crab", "lobster"
    )
    private val ounceFoods = setOf(
        "cheese", "cheddar", "mozzarella", "parmesan", "feta", "almonds", "almond", "peanuts", "cashews", "walnuts",
        "pecans", "pistachios", "nuts", "chips", "crisps", "crackers", "pretzels", "chocolate", "jerky", "candy",
        "popcorn", "seeds", "trail"
    )
    private val cupFoods = setOf(
        "rice", "pasta", "spaghetti", "macaroni", "noodles", "oats", "oatmeal", "porridge", "quinoa", "beans",
        "lentils", "chickpeas", "peas", "corn", "berries", "blueberries", "raspberries", "blackberries", "grapes",
        "spinach", "lettuce", "kale", "greens", "broccoli", "cauliflower", "yogurt", "yoghurt", "cottage", "milk",
        "juice", "soup", "cereal", "granola", "salad", "chili", "stew", "watermelon", "cantaloupe", "melon", "pineapple"
    )
    private val spoonFoods = setOf(
        "butter", "oil", "mayonnaise", "mayo", "honey", "jam", "jelly", "syrup", "dressing", "sauce", "ketchup",
        "mustard", "peanut butter", "almond butter", "hummus", "cream cheese", "sugar", "vinegar", "salsa", "pesto",
        "nutella", "spread"
    )

    /** True when the food is described per 100 g or 100 ml, the way nutrition databases do. */
    fun isPer100(food: FoodItem): Boolean =
        kotlin.math.abs(food.servingSize - 100f) < 0.01f && UnitConverter.canonical(food.servingUnit) in setOf("g", "ml")

    /**
     * What one tap should add of a food when you haven't logged it before: its own household serving, a typical
     * piece of a whole food, or for a generic per-100 g food a usual portion (4 oz of meat, 1 oz of cheese or
     * nuts, 1 cup of rice or milk, 1 tbsp of butter or sauce). Null keeps the food's own serving.
     */
    fun usualPortion(food: FoodItem): Pair<Float, String>? {
        decode(food.householdServings).firstOrNull()?.let { return it.quantity to it.unit }
        // A food that already has a real serving ("1 sandwich", "30 g" from a label) keeps it
        if (!isPer100(food)) return null
        typical(food)?.let { return 1f to it.unit }
        val w = words(food.name)
        val text = " " + w.joinToString(" ") + " "
        fun has(set: Set<String>) = set.any { text.contains(" $it ") }
        val volume = UnitConverter.dimension(food.servingUnit) == UnitConverter.Dimension.VOLUME
        val madeOf = w.any { it in madeFrom }
        val density = IngredientDensities.lookup(food.name)
        // Only a unit this food converts to (a cup of something needs its density)
        fun fits(q: Float, unit: String) = (q to unit).takeIf {
            UnitConverter.convert(q.toDouble(), unit, food.servingUnit, density) != null
        }
        return when {
            has(spoonFoods) -> fits(1f, "tbsp")
            volume -> fits(1f, "cup")
            has(meats) && !madeOf -> fits(4f, "oz")
            has(cupFoods) -> fits(1f, "cup")
            has(ounceFoods) -> fits(1f, "oz")
            else -> null
        }
    }
}
