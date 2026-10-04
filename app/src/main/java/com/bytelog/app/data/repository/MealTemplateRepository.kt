package com.bytelog.app.data.repository

import com.bytelog.app.data.local.db.MealTemplateDao
import com.bytelog.app.data.local.model.MealTemplate
import com.bytelog.app.data.local.model.TemplateIngredient
import com.bytelog.app.data.remote.SparkyApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MealTemplateRepository(
    private val mealTemplateDao: MealTemplateDao,
    private val sparkyApiClient: SparkyApiClient,
    private val appScope: CoroutineScope
) {
    val templates: Flow<List<MealTemplate>> = mealTemplateDao.getAllTemplates()

    init {
        refreshTemplates()
    }

    fun refreshTemplates() {
        appScope.launch(Dispatchers.IO) {
            val remoteResult = sparkyApiClient.fetchMealTemplates()
            if (remoteResult.isSuccess) {
                val list = remoteResult.getOrDefault(emptyList())
                if (list.isNotEmpty()) {
                    mealTemplateDao.insertTemplates(list)
                }
            }
        }
    }

    suspend fun createTemplateFromDiary(
        date: String,
        mealType: String,
        mealName: String,
        description: String? = null,
        isPublic: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        val result = sparkyApiClient.createMealFromDiary(date, mealType, mealName, description, isPublic)
        if (result.isSuccess) {
            refreshTemplates()
        }
        result
    }

    suspend fun createRecipeTemplate(
        name: String,
        description: String? = null,
        servingSize: Float = 1f,
        servingUnit: String = "serving",
        totalServings: Float = 1f,
        foods: List<TemplateIngredient>,
        notes: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val result = sparkyApiClient.createMealTemplate(
            name = name,
            description = description,
            servingSize = servingSize,
            servingUnit = servingUnit,
            totalServings = totalServings,
            foods = foods,
            notes = notes
        )
        if (result.isSuccess) {
            refreshTemplates()
        }
        result
    }

    suspend fun logTemplateToDiary(
        templateId: String,
        mealType: String,
        mealTypeId: String?,
        date: String,
        quantity: Float = 1f
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        sparkyApiClient.logMealTemplate(
            templateId = templateId,
            mealType = mealType,
            mealTypeId = mealTypeId,
            date = date,
            quantity = quantity
        )
    }

    suspend fun deleteTemplate(templateId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        mealTemplateDao.deleteTemplate(templateId)
        sparkyApiClient.deleteMealTemplate(templateId)
    }
}
