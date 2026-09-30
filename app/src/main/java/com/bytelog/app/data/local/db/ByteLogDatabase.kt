package com.bytelog.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

@Database(
    entities = [
        FoodItem::class,
        FoodEntry::class,
        MealType::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ByteLogDatabase : RoomDatabase() {

    abstract fun foodDao(): FoodDao
    abstract fun foodEntryDao(): FoodEntryDao
    abstract fun mealTypeDao(): MealTypeDao

    companion object {
        @Volatile
        private var INSTANCE: ByteLogDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): ByteLogDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ByteLogDatabase::class.java,
                    "bytelog_database"
                )
                    .addCallback(DatabaseCallback(scope))
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback(
            private val scope: CoroutineScope
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    scope.launch(Dispatchers.IO) {
                        populateInitialData(database.foodDao(), database.mealTypeDao())
                    }
                }
            }

            private suspend fun populateInitialData(foodDao: FoodDao, mealTypeDao: MealTypeDao) {
                // Populate default meal types
                mealTypeDao.insertMealTypes(MealType.DEFAULT_MEAL_TYPES)

                // Populate high-speed instant starter foods
                val starterFoods = listOf(
                    FoodItem(
                        id = UUID.randomUUID().toString(),
                        name = "Rolled Oats",
                        brand = "Quaker",
                        barcode = "030000010105",
                        servingSize = 40f,
                        servingUnit = "g",
                        calories = 150f,
                        protein = 5f,
                        carbs = 27f,
                        fat = 3f,
                        fiber = 4f,
                        isFavorite = true,
                        useCount = 12,
                        mealTypeAffinity = "breakfast"
                    ),
                    FoodItem(
                        id = UUID.randomUUID().toString(),
                        name = "Greek Yogurt 0%",
                        brand = "Fage",
                        barcode = "689544082006",
                        servingSize = 170f,
                        servingUnit = "g",
                        calories = 90f,
                        protein = 18f,
                        carbs = 5f,
                        fat = 0f,
                        fiber = 0f,
                        isFavorite = true,
                        useCount = 15,
                        mealTypeAffinity = "breakfast"
                    ),
                    FoodItem(
                        id = UUID.randomUUID().toString(),
                        name = "Whole Eggs (Large)",
                        brand = "USDA",
                        servingSize = 50f,
                        servingUnit = "egg",
                        calories = 72f,
                        protein = 6.3f,
                        carbs = 0.4f,
                        fat = 4.8f,
                        fiber = 0f,
                        isFavorite = true,
                        useCount = 20,
                        mealTypeAffinity = "breakfast"
                    ),
                    FoodItem(
                        id = UUID.randomUUID().toString(),
                        name = "Blueberries (Fresh)",
                        brand = "Generic",
                        servingSize = 100f,
                        servingUnit = "g",
                        calories = 57f,
                        protein = 0.7f,
                        carbs = 14.5f,
                        fat = 0.3f,
                        fiber = 2.4f,
                        isFavorite = true,
                        useCount = 8,
                        mealTypeAffinity = "breakfast"
                    ),
                    FoodItem(
                        id = UUID.randomUUID().toString(),
                        name = "Chicken Breast (Cooked, Boneless)",
                        brand = "USDA",
                        servingSize = 100f,
                        servingUnit = "g",
                        calories = 165f,
                        protein = 31f,
                        carbs = 0f,
                        fat = 3.6f,
                        fiber = 0f,
                        isFavorite = true,
                        useCount = 25,
                        mealTypeAffinity = "lunch"
                    ),
                    FoodItem(
                        id = UUID.randomUUID().toString(),
                        name = "Jasmine White Rice (Cooked)",
                        brand = "Generic",
                        servingSize = 150f,
                        servingUnit = "g",
                        calories = 195f,
                        protein = 4.1f,
                        carbs = 43f,
                        fat = 0.4f,
                        fiber = 0.6f,
                        isFavorite = true,
                        useCount = 18,
                        mealTypeAffinity = "lunch"
                    ),
                    FoodItem(
                        id = UUID.randomUUID().toString(),
                        name = "Atlantic Salmon (Baked)",
                        brand = "Generic",
                        servingSize = 150f,
                        servingUnit = "g",
                        calories = 310f,
                        protein = 34f,
                        carbs = 0f,
                        fat = 18f,
                        fiber = 0f,
                        isFavorite = false,
                        useCount = 7,
                        mealTypeAffinity = "dinner"
                    ),
                    FoodItem(
                        id = UUID.randomUUID().toString(),
                        name = "Extra Virgin Olive Oil",
                        brand = "Generic",
                        servingSize = 14f,
                        servingUnit = "ml",
                        calories = 119f,
                        protein = 0f,
                        carbs = 0f,
                        fat = 13.5f,
                        fiber = 0f,
                        isFavorite = false,
                        useCount = 9,
                        mealTypeAffinity = "dinner"
                    ),
                    FoodItem(
                        id = UUID.randomUUID().toString(),
                        name = "Whey Protein Isolate",
                        brand = "Optimum Nutrition",
                        barcode = "748927028669",
                        servingSize = 30f,
                        servingUnit = "g",
                        calories = 120f,
                        protein = 24f,
                        carbs = 3f,
                        fat = 1f,
                        fiber = 0.5f,
                        isFavorite = true,
                        useCount = 16,
                        mealTypeAffinity = "snacks"
                    ),
                    FoodItem(
                        id = UUID.randomUUID().toString(),
                        name = "Banana (Medium)",
                        brand = "Chiquita",
                        barcode = "040112",
                        servingSize = 118f,
                        servingUnit = "g",
                        calories = 105f,
                        protein = 1.3f,
                        carbs = 27f,
                        fat = 0.3f,
                        fiber = 3.1f,
                        isFavorite = true,
                        useCount = 14,
                        mealTypeAffinity = "snacks"
                    ),
                    FoodItem(
                        id = UUID.randomUUID().toString(),
                        name = "Peanut Butter (Creamy)",
                        brand = "Jif",
                        barcode = "051500255162",
                        servingSize = 32f,
                        servingUnit = "g",
                        calories = 190f,
                        protein = 7f,
                        carbs = 8f,
                        fat = 16f,
                        fiber = 2f,
                        isFavorite = false,
                        useCount = 11,
                        mealTypeAffinity = "snacks"
                    )
                )
                foodDao.insertFoods(starterFoods)
            }
        }
    }
}
