package com.bytelog.app.data.receipts

import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.repository.FoodSearch

/** A food a receipt line could be: on the shopping list, or one of your saved foods. */
data class MatchCandidate(
    val key: String,
    val foodId: String?,
    val name: String,
    val brand: String?,
    /** On the shopping list for the planned days (preferred when it's close). */
    val onList: Boolean,
    /** The saved food, for its units. */
    val food: FoodItem? = null
)

data class ReceiptMatch(val candidate: MatchCandidate, val score: Float)

/**
 * Matches receipt names to foods. Receipts shorten everything ("GRK YOG", "CHKN BRST BNLS", "ORG BNNA"), so each word
 * is tried as itself, as a known shortening, and as letters dropped from a word ("CHKN" is "chicken"). The food's
 * words that are found, and the receipt's words that find something, make the score.
 */
object ReceiptMatcher {
    /** Common shortenings on receipts, by the word they stand for. */
    private val abbreviations: Map<String, List<String>> = mapOf(
        "grk" to listOf("greek"), "yog" to listOf("yogurt"), "ygrt" to listOf("yogurt"), "yogrt" to listOf("yogurt"), "yoplt" to listOf("yoplait"),
        "chkn" to listOf("chicken"), "chix" to listOf("chicken"), "brst" to listOf("breast"), "bnls" to listOf("boneless"), "sknls" to listOf("skinless"),
        "thgh" to listOf("thigh"), "grnd" to listOf("ground"), "gr" to listOf("ground"), "bf" to listOf("beef"), "trky" to listOf("turkey"), "tky" to listOf("turkey"),
        "org" to listOf("organic"), "bnna" to listOf("banana"), "ban" to listOf("banana"), "bnn" to listOf("banana"), "appl" to listOf("apple"),
        "strwb" to listOf("strawberry"), "strw" to listOf("strawberry"), "blubry" to listOf("blueberry"), "blub" to listOf("blueberry"), "rasp" to listOf("raspberry"),
        "avo" to listOf("avocado"), "avoc" to listOf("avocado"), "tom" to listOf("tomato"), "toms" to listOf("tomato"), "pot" to listOf("potato"), "swt" to listOf("sweet"),
        "onn" to listOf("onion"), "brcl" to listOf("broccoli"), "broc" to listOf("broccoli"), "spin" to listOf("spinach"), "spnch" to listOf("spinach"), "lett" to listOf("lettuce"),
        "mlk" to listOf("milk"), "whl" to listOf("whole"), "wht" to listOf("white", "wheat"), "ww" to listOf("whole", "wheat"), "brd" to listOf("bread"), "bgl" to listOf("bagel"),
        "tort" to listOf("tortilla"), "tortl" to listOf("tortilla"), "chs" to listOf("cheese"), "chz" to listOf("cheese"), "ched" to listOf("cheddar"), "chdr" to listOf("cheddar"),
        "mozz" to listOf("mozzarella"), "parm" to listOf("parmesan"), "btr" to listOf("butter"), "bttr" to listOf("butter"), "pb" to listOf("peanut", "butter"), "pnut" to listOf("peanut"),
        "egg" to listOf("eggs"), "lg" to listOf("large"), "xl" to listOf("extra", "large"), "sm" to listOf("small"), "med" to listOf("medium"),
        "oj" to listOf("orange", "juice"), "jce" to listOf("juice"), "jc" to listOf("juice"), "bev" to listOf("beverage"), "wtr" to listOf("water"), "sprklng" to listOf("sparkling"),
        "rce" to listOf("rice"), "brwn" to listOf("brown"), "pst" to listOf("pasta"), "spag" to listOf("spaghetti"), "sce" to listOf("sauce"), "sauc" to listOf("sauce"),
        "oat" to listOf("oats", "oatmeal"), "oatml" to listOf("oatmeal"), "cer" to listOf("cereal"), "grnla" to listOf("granola"), "almd" to listOf("almond"), "alm" to listOf("almond"),
        "slmn" to listOf("salmon"), "salm" to listOf("salmon"), "tlpa" to listOf("tilapia"), "shrmp" to listOf("shrimp"), "tna" to listOf("tuna"), "sard" to listOf("sardines"),
        "frz" to listOf("frozen"), "fz" to listOf("frozen"), "frsh" to listOf("fresh"), "nat" to listOf("natural"), "pln" to listOf("plain"), "van" to listOf("vanilla"),
        "choc" to listOf("chocolate"), "crm" to listOf("cream"), "sr" to listOf("sour"), "hvy" to listOf("heavy"), "cott" to listOf("cottage"), "ckie" to listOf("cookie"),
        "cof" to listOf("coffee"), "coff" to listOf("coffee"), "bns" to listOf("beans"), "blk" to listOf("black"), "pnto" to listOf("pinto")
    )

    /** Words that say little about which food it is. */
    private val weak = setOf("organic", "fresh", "natural", "large", "small", "medium", "extra", "frozen", "plain", "the", "and", "with", "of", "ks", "gv", "kirkland", "signature", "great", "value")

    private fun words(s: String): List<String> = FoodSearch.normalize(s).split(' ').filter { it.isNotEmpty() && it.any(Char::isLetter) }

    /** The letters of [token] appear in [word] in order, starting with its first: "chkn" in "chicken". */
    private fun skeleton(token: String, word: String): Boolean {
        if (token.length < 3 || token[0] != word[0] || token.length > word.length) return false
        var i = 0
        for (c in word) if (i < token.length && c == token[i]) i++
        return i == token.length
    }

    /** How well a receipt token stands for a food word: 1 the same, less for a start or a shortening, 0 not at all. */
    fun tokenScore(token: String, word: String): Float {
        val t = FoodSearch.canonical(token); val w = FoodSearch.canonical(word)
        if (t == w) return 1f
        abbreviations[token]?.let { full -> if (full.any { FoodSearch.canonical(it) == w || w.startsWith(FoodSearch.canonical(it)) }) return 0.95f }
        if (t.length >= 3 && w.startsWith(t)) return 0.9f
        if (w.length >= 4 && t.startsWith(w)) return 0.85f
        if (skeleton(t, w)) return 0.75f
        return 0f
    }

    fun score(receiptName: String, c: MatchCandidate): Float {
        val tokens = words(receiptName).filter { it.length >= 2 || it in abbreviations }
        val foodWords = words(c.name)
        if (tokens.isEmpty() || foodWords.isEmpty()) return 0f
        val brandWords = c.brand?.let(::words).orEmpty()
        fun weight(w: String) = if (w in weak) 0.3f else 1f

        // Each food word: the best receipt token for it
        val foodTotal = foodWords.sumOf { weight(it).toDouble() }.toFloat()
        val foodFound = foodWords.sumOf { w -> (weight(w) * tokens.maxOf { tokenScore(it, w) }).toDouble() }.toFloat()
        // Each receipt token: does it stand for any of the food's words (or its brand)?
        val all = foodWords + brandWords
        val tokenTotal = tokens.sumOf { t -> weight(abbreviations[t]?.firstOrNull() ?: t).toDouble() }.toFloat()
        val tokenFound = tokens.sumOf { t -> (weight(abbreviations[t]?.firstOrNull() ?: t) * all.maxOf { tokenScore(t, it) }).toDouble() }.toFloat()
        if (foodFound <= 0f) return 0f
        val s = 0.6f * (foodFound / foodTotal) + 0.4f * (tokenFound / tokenTotal.coerceAtLeast(0.01f))
        return (s + if (c.onList) 0.08f else 0f).coerceAtMost(1f)
    }

    /** The likeliest foods for a receipt name, best first; only those that are a reasonable match. */
    fun matches(receiptName: String, candidates: List<MatchCandidate>, limit: Int = 5, threshold: Float = 0.5f): List<ReceiptMatch> =
        candidates.asSequence()
            .map { ReceiptMatch(it, score(receiptName, it)) }
            .filter { it.score >= threshold }
            .sortedWith(compareByDescending<ReceiptMatch> { it.score }.thenByDescending { it.candidate.onList }.thenBy { it.candidate.name.length })
            .distinctBy { it.candidate.key }
            .take(limit)
            .toList()
}
