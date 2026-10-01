package com.bytelog.app.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bytelog.app.data.local.model.MealType
import kotlinx.coroutines.flow.Flow

@Dao
interface MealTypeDao {
    @Query("SELECT * FROM meal_types ORDER BY sortOrder ASC")
    fun getAllMealTypes(): Flow<List<MealType>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMealTypes(mealTypes: List<MealType>)

    @Query("SELECT COUNT(*) FROM meal_types")
    suspend fun getCount(): Int

    @Query("DELETE FROM meal_types")
    suspend fun clearAllMealTypes()

    @Query("DELETE FROM meal_types WHERE id IN ('breakfast', 'lunch', 'dinner', 'snacks')")
    suspend fun purgeDefaultMealTypes()
}
