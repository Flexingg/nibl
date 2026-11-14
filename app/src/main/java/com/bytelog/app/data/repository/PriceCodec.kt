package com.bytelog.app.data.repository

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/** What's stored on a food in Sparky: where it's bought and its price for an amount. Every field can be null. */
data class SparkyPrice(val store: String? = null, val price: Float? = null, val quantity: Float? = null, val unit: String? = null) {
    val isEmpty get() = store == null && price == null && quantity == null && unit == null
}

/**
 * Store and price live in the `custom_nutrients` map of a food's default serving in Sparky, under Nibl's own keys.
 * Sparky's reports only read the custom nutrients you've defined, so these never count towards any total, and a
 * food without them simply has no store or price.
 */
object PriceCodec {
    const val STORE = "nibl_store"
    const val PRICE = "nibl_price"
    const val QUANTITY = "nibl_price_qty"
    const val UNIT = "nibl_price_unit"
    const val UPDATED = "nibl_price_date"
    private val keys = setOf(STORE, PRICE, QUANTITY, UNIT, UPDATED)

    fun read(custom: JsonObject?): SparkyPrice {
        if (custom == null) return SparkyPrice()
        fun s(k: String) = (custom[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        fun f(k: String) = (custom[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.floatOrNull ?: it.contentOrNull?.trim()?.toFloatOrNull() }
        return SparkyPrice(s(STORE), f(PRICE)?.takeIf { it >= 0f }, f(QUANTITY)?.takeIf { it > 0f }, s(UNIT))
    }

    /** [existing] with Nibl's keys set from [price]: other custom nutrients are kept, and empty fields are left out. */
    fun write(existing: JsonObject?, price: SparkyPrice, today: String): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        existing?.forEach { (k, v) -> if (k !in keys) out[k] = v }
        price.store?.trim()?.takeIf { it.isNotEmpty() }?.let { out[STORE] = JsonPrimitive(it) }
        price.price?.let { out[PRICE] = JsonPrimitive(it) }
        price.quantity?.let { out[QUANTITY] = JsonPrimitive(it) }
        price.unit?.trim()?.takeIf { it.isNotEmpty() }?.let { out[UNIT] = JsonPrimitive(it) }
        if (!price.isEmpty) out[UPDATED] = JsonPrimitive(today)
        return JsonObject(out)
    }
}
