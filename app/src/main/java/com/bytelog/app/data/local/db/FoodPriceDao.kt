package com.bytelog.app.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bytelog.app.data.local.model.FoodPrice
import com.bytelog.app.data.local.model.ShoppingCheck
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodPriceDao {
    @Query("SELECT * FROM food_prices")
    fun all(): Flow<List<FoodPrice>>

    @Query("SELECT * FROM food_prices WHERE `key` = :key")
    suspend fun get(key: String): FoodPrice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(price: FoodPrice)

    @Query("DELETE FROM food_prices WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM shopping_checks")
    fun checks(): Flow<List<ShoppingCheck>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun check(check: ShoppingCheck)

    @Query("DELETE FROM shopping_checks WHERE `key` = :key")
    suspend fun uncheck(key: String)

    @Query("DELETE FROM shopping_checks")
    suspend fun clearChecks()

    /** Ticks from an earlier shop. */
    @Query("DELETE FROM shopping_checks WHERE checkedAt < :before")
    suspend fun clearChecksBefore(before: Long)
}
