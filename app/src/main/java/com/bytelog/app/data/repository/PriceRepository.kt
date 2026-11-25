package com.bytelog.app.data.repository

import com.bytelog.app.data.local.db.FoodPriceDao
import com.bytelog.app.data.local.model.FoodPrice
import com.bytelog.app.data.local.model.ShoppingCheck
import com.bytelog.app.data.remote.SparkyApiClient
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import com.bytelog.app.data.remote.fetchFoodPrice
import com.bytelog.app.data.remote.saveFoodPrice

/**
 * Where foods are bought and what they cost. Saved on the phone (for the shopping list) and on the food in Sparky
 * when it's one of your own, so it follows you to other devices.
 */
class PriceRepository(
    private val dao: FoodPriceDao,
    private val api: SparkyApiClient?,
    private val today: () -> LocalDate = { LocalDate.now() }
) {
    val prices: Flow<List<FoodPrice>> = dao.all()
    val checks: Flow<List<ShoppingCheck>> = dao.checks()

    /** A food to look up: its Sparky id, name and brand. */
    data class FoodRef(val id: String, val name: String, val brand: String?)

    /** What saving did: on Sparky too, or only here (and why). */
    data class Saved(val price: FoodPrice?, val onSparky: Boolean, val problem: String? = null)

    suspend fun get(foodId: String?, name: String, brand: String?): FoodPrice? =
        dao.get(ShoppingList.keyOf(foodId, name, brand)) ?: dao.get(ShoppingList.keyOf(null, name, brand))

    /** A food's store and price: from the phone, else from Sparky (then kept on the phone). */
    suspend fun load(foodId: String?, name: String, brand: String?): FoodPrice? {
        get(foodId, name, brand)?.let { return it }
        if (foodId == null || !isSparkyId(foodId) || api == null) return null
        val p = api.fetchFoodPrice(foodId).getOrNull()?.takeUnless { it.isEmpty } ?: return null
        return FoodPrice(foodId, foodId, name, brand, p.store, p.price, p.quantity, p.unit, p.packageName, savedToSparky = true).also { dao.upsert(it) }
    }

    /** Saves a food's store and price; all empty clears them. */
    suspend fun save(foodId: String?, name: String, brand: String?, price: SparkyPrice): Saved {
        val key = ShoppingList.keyOf(foodId, name, brand)
        val local = FoodPrice(key, foodId, name, brand, price.store?.trim()?.ifBlank { null }, price.price, price.quantity, price.unit?.trim()?.ifBlank { null }, price.packageName?.trim()?.ifBlank { null })
        if (local.isEmpty && local.quantity == null) dao.delete(key) else dao.upsert(local)
        val stored = local.takeUnless { it.isEmpty && it.quantity == null }
        if (foodId == null || !isSparkyId(foodId) || api == null) return Saved(stored, onSparky = false, problem = "Saved on this phone. This food isn't in your Sparky foods.")
        return api.saveFoodPrice(foodId, price, today().toString()).fold(
            onSuccess = {
                stored?.let { dao.upsert(it.copy(savedToSparky = true)) }
                Saved(stored?.copy(savedToSparky = true), onSparky = true)
            },
            onFailure = { e ->
                val why = if (e.message?.contains("403") == true || e.message?.contains("Forbidden") == true) "it isn't one of your own foods" else (e.message ?: "Sparky couldn't be reached")
                Saved(stored, onSparky = false, problem = "Saved on this phone; not on Sparky ($why).")
            }
        )
    }

    /**
     * Loads prices saved on Sparky for foods this phone doesn't have one for yet, e.g. set on another device.
     * Returns how many were found.
     */
    suspend fun refreshFromSparky(foods: List<FoodRef>, limit: Int = 40): Int {
        val client = api ?: return 0
        var found = 0
        for (f in foods.filter { isSparkyId(it.id) }.distinctBy { it.id }.take(limit)) {
            if (dao.get(f.id) != null) continue
            val p = client.fetchFoodPrice(f.id).getOrNull() ?: continue
            if (p.isEmpty) continue
            dao.upsert(FoodPrice(f.id, f.id, f.name, f.brand, p.store, p.price, p.quantity, p.unit, p.packageName, savedToSparky = true))
            found++
        }
        return found
    }

    suspend fun check(key: String, on: Boolean) = if (on) dao.check(ShoppingCheck(key)) else dao.uncheck(key)

    /** Takes an item off the shopping list (bought, or already in the cupboard), or puts it back. */
    suspend fun remove(key: String, removed: Boolean) = if (removed) dao.check(ShoppingCheck(REMOVED + key)) else dao.uncheck(REMOVED + key)
    suspend fun clearChecks() = dao.clearChecks()

    companion object {
        /** Removed items are kept as ticks under this prefix. */
        const val REMOVED = "x:"

        /** Ticked and removed keys from the stored ticks. */
        fun split(checks: List<ShoppingCheck>): Pair<Set<String>, Set<String>> {
            val (removed, ticked) = checks.map { it.key }.partition { it.startsWith(REMOVED) }
            return ticked.toSet() to removed.map { it.removePrefix(REMOVED) }.toSet()
        }

        private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        /** Foods saved in Sparky have UUIDs; others (Open Food Facts results, chat plans) don't. */
        fun isSparkyId(id: String) = uuid.matches(id)
    }
}
