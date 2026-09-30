package com.bytelog.app.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bytelog.app.data.local.model.FoodItem
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodDao {
    @Query("""
        SELECT * FROM foods 
        WHERE name LIKE '%' || :query || '%' OR brand LIKE '%' || :query || '%'
        ORDER BY 
            CASE WHEN name LIKE :query || '%' THEN 1 ELSE 2 END,
            useCount DESC, 
            lastUsedAt DESC 
        LIMIT 50
    """)
    fun searchFoods(query: String): Flow<List<FoodItem>>

    @Query("SELECT * FROM foods ORDER BY lastUsedAt DESC LIMIT :limit")
    fun getRecentFoods(limit: Int = 25): Flow<List<FoodItem>>

    @Query("SELECT * FROM foods WHERE isFavorite = 1 ORDER BY useCount DESC, name ASC")
    fun getFavoriteFoods(): Flow<List<FoodItem>>

    @Query("""
        SELECT * FROM foods 
        WHERE mealTypeAffinity = :affinity OR mealTypeAffinity IS NULL
        ORDER BY useCount DESC, lastUsedAt DESC 
        LIMIT :limit
    """)
    fun getSuggestedFoodsForMeal(affinity: String, limit: Int = 20): Flow<List<FoodItem>>

    @Query("SELECT * FROM foods WHERE barcode = :barcode LIMIT 1")
    suspend fun getFoodByBarcode(barcode: String): FoodItem?

    @Query("SELECT * FROM foods WHERE id = :id LIMIT 1")
    suspend fun getFoodById(id: String): FoodItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFoods(foods: List<FoodItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFood(food: FoodItem)

    @Update
    suspend fun updateFood(food: FoodItem)

    @Query("UPDATE foods SET useCount = useCount + 1, lastUsedAt = :timestamp WHERE id = :foodId")
    suspend fun recordFoodUsage(foodId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE foods SET isFavorite = :isFavorite WHERE id = :foodId")
    suspend fun setFavorite(foodId: String, isFavorite: Boolean)

    @Query("SELECT COUNT(*) FROM foods")
    suspend fun getCount(): Int

    @Query("DELETE FROM foods")
    suspend fun clearAllFoods()

    @Query("""
        DELETE FROM foods WHERE name IN (
            'Rolled Oats', 'Greek Yogurt 0%', 'Whole Eggs (Large)', 'Blueberries (Fresh)',
            'Chicken Breast (Cooked, Boneless)', 'Jasmine White Rice (Cooked)', 'Atlantic Salmon (Baked)',
            'Extra Virgin Olive Oil', 'Whey Protein Isolate', 'Banana (Medium)', 'Peanut Butter (Creamy)'
        ) OR barcode IN ('030000010105', '689544082006', '748927028669', '040112', '051500255162')
    """)
    suspend fun deleteDemoFoods()
}
