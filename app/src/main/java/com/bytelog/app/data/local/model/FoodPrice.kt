package com.bytelog.app.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Where you buy a food and what it costs: [price] for [quantity] [unit] (a 32 oz tub for $4.99) at [store]. Every
 * field can be empty. Kept on the phone for the shopping list, and on the food in Sparky when it's one of yours.
 */
@Entity(tableName = "food_prices")
data class FoodPrice(
    /** The food's Sparky id, or its name and brand for a food Sparky doesn't have. */
    @PrimaryKey val key: String,
    val foodId: String? = null,
    val foodName: String,
    val brand: String? = null,
    val store: String? = null,
    val price: Float? = null,
    val quantity: Float? = null,
    val unit: String? = null,
    /** What the food comes in, when that isn't one of its units ("tub"): [quantity] [unit] is how much one holds. */
    val packageName: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    /** False while it's only on this phone (a food that isn't yours on Sparky, or saving there failed). */
    val savedToSparky: Boolean = false
) {
    val isEmpty: Boolean get() = store.isNullOrBlank() && price == null

    fun toSparky() = com.bytelog.app.data.repository.SparkyPrice(store, price, quantity, unit, packageName)

    /** "$4.99 per tub (32 oz)", "$0.25 for 1 medium". */
    fun describe(money: (Double) -> String): String? {
        val p = price ?: return null
        val amount = quantity?.let { q -> com.bytelog.app.data.repository.ShoppingAmount(q.toDouble(), unit.orEmpty()).label }
        return when {
            packageName != null && amount != null -> "${money(p.toDouble())} per $packageName ($amount)"
            amount != null -> "${money(p.toDouble())} for $amount"
            else -> money(p.toDouble())
        }
    }

    /** Price per one [unit], when both the price and the amount it buys are known. */
    val unitPrice: Float? get() = if (price != null && quantity != null && quantity > 0f) price / quantity else null
}

/** An item ticked off on the shopping list. */
@Entity(tableName = "shopping_checks")
data class ShoppingCheck(@PrimaryKey val key: String, val checkedAt: Long = System.currentTimeMillis())
