package com.bytelog.app.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bytelog.app.data.local.model.MealTemplate
import kotlinx.coroutines.flow.Flow

@Dao
interface MealTemplateDao {

    @Query("SELECT * FROM meal_templates ORDER BY name ASC")
    fun getAllTemplates(): Flow<List<MealTemplate>>

    @Query("SELECT * FROM meal_templates WHERE id = :id LIMIT 1")
    fun getTemplateById(id: String): Flow<MealTemplate?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplates(templates: List<MealTemplate>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: MealTemplate)

    @Query("DELETE FROM meal_templates WHERE id = :id")
    suspend fun deleteTemplate(id: String)

    @Query("DELETE FROM meal_templates")
    suspend fun clearTemplates()
}
