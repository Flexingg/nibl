package com.bytelog.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import com.bytelog.app.data.local.model.BatchConsumption
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealTemplate
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.IngredientAlias
import com.bytelog.app.data.local.model.PrepBatch
import com.bytelog.app.data.local.model.RecipeDetails
import com.bytelog.app.data.local.model.WaterLogEntry
import com.bytelog.app.data.local.model.WeightEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

@Database(
    entities = [
        FoodItem::class,
        FoodEntry::class,
        MealType::class,
        WaterLogEntry::class,
        WeightEntry::class,
        MealTemplate::class,
        RecipeDetails::class,
        PrepBatch::class,
        BatchConsumption::class,
        IngredientAlias::class,
        com.bytelog.app.data.local.model.PlannedEntry::class,
        com.bytelog.app.data.local.model.FoodPrice::class,
        com.bytelog.app.data.local.model.ShoppingCheck::class
    ],
    version = 9,
    exportSchema = true
)
abstract class ByteLogDatabase : RoomDatabase() {

    abstract fun foodDao(): FoodDao
    abstract fun foodEntryDao(): FoodEntryDao
    abstract fun mealTypeDao(): MealTypeDao
    abstract fun waterLogDao(): WaterLogDao
    abstract fun weightDao(): WeightDao
    abstract fun mealTemplateDao(): MealTemplateDao
    abstract fun recipeDetailsDao(): RecipeDetailsDao
    abstract fun prepBatchDao(): PrepBatchDao
    abstract fun ingredientAliasDao(): IngredientAliasDao
    abstract fun plannedEntryDao(): PlannedEntryDao
    abstract fun foodPriceDao(): FoodPriceDao

    companion object {
        @Volatile
        private var INSTANCE: ByteLogDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `water_log_entries` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `entryDate` TEXT NOT NULL,
                        `waterMl` REAL NOT NULL,
                        `containerId` INTEGER,
                        `containerName` TEXT,
                        `loggedAt` TEXT,
                        `syncStatus` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `weight_entries` (
                        `entryDate` TEXT NOT NULL PRIMARY KEY,
                        `weight` REAL NOT NULL,
                        `unit` TEXT NOT NULL,
                        `bodyFatPercentage` REAL,
                        `syncStatus` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `meal_templates` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `description` TEXT,
                        `servingSize` REAL NOT NULL,
                        `servingUnit` TEXT NOT NULL,
                        `totalServings` REAL NOT NULL,
                        `calories` REAL NOT NULL,
                        `protein` REAL NOT NULL,
                        `carbs` REAL NOT NULL,
                        `fat` REAL NOT NULL,
                        `foodsJson` TEXT NOT NULL,
                        `isPublic` INTEGER NOT NULL,
                        `createdAt` TEXT,
                        `syncStatus` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** Household servings on foods ("2 cookies", "1 medium"). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `foods` ADD COLUMN `householdServings` TEXT")
            }
        }

        /** Where Sparky got an entry or drink (e.g. imported from Health Connect), so it isn't written back there. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `food_entries` ADD COLUMN `source` TEXT")
                db.execSQL("ALTER TABLE `food_entries` ADD COLUMN `sourceId` TEXT")
                db.execSQL("ALTER TABLE `water_log_entries` ADD COLUMN `source` TEXT")
                db.execSQL("ALTER TABLE `water_log_entries` ADD COLUMN `sourceId` TEXT")
            }
        }

        /** Where foods are bought and what they cost, and the shopping list's ticks. Additive only. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `food_prices` (`key` TEXT NOT NULL, `foodId` TEXT, `foodName` TEXT NOT NULL, `brand` TEXT, " +
                        "`store` TEXT, `price` REAL, `quantity` REAL, `unit` TEXT, `updatedAt` INTEGER NOT NULL, `savedToSparky` INTEGER NOT NULL, PRIMARY KEY(`key`))"
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS `shopping_checks` (`key` TEXT NOT NULL, `checkedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            }
        }

        /** A package name on prices ("tub" = 32 oz). Additive only. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `food_prices` ADD COLUMN `packageName` TEXT")
            }
        }

        /** Food planned for days ahead. Additive only. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `planned_entries` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `foodId` TEXT,
                        `foodName` TEXT NOT NULL,
                        `brandName` TEXT,
                        `mealTypeId` TEXT NOT NULL,
                        `mealName` TEXT NOT NULL,
                        `quantity` REAL NOT NULL,
                        `unit` TEXT NOT NULL,
                        `calories` REAL NOT NULL,
                        `protein` REAL NOT NULL,
                        `carbs` REAL NOT NULL,
                        `fat` REAL NOT NULL,
                        `entryDate` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_planned_entries_entryDate` ON `planned_entries` (`entryDate`)")
            }
        }

        /** Recipes, cook mode and meal-prep batches. Additive only: no existing table changes. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recipe_details` (
                        `templateId` TEXT NOT NULL,
                        `prepTimeMinutes` INTEGER,
                        `cookTimeMinutes` INTEGER,
                        `sourceUrl` TEXT,
                        `imageUrl` TEXT,
                        `stepsJson` TEXT NOT NULL,
                        `notes` TEXT,
                        `fridgeDays` INTEGER,
                        `freezerDays` INTEGER,
                        `storageNotes` TEXT,
                        `rawIngredientLinesJson` TEXT NOT NULL,
                        `siteNutritionJson` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`templateId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `prep_batches` (
                        `id` TEXT NOT NULL,
                        `templateId` TEXT,
                        `name` TEXT NOT NULL,
                        `cookedAt` INTEGER NOT NULL,
                        `bestBy` INTEGER,
                        `location` TEXT NOT NULL,
                        `calories` REAL NOT NULL,
                        `protein` REAL NOT NULL,
                        `carbs` REAL NOT NULL,
                        `fat` REAL NOT NULL,
                        `portions` INTEGER,
                        `cookedWeightG` REAL,
                        `scale` REAL NOT NULL,
                        `ingredientsJson` TEXT NOT NULL,
                        `sparkyFoodId` TEXT,
                        `status` TEXT NOT NULL,
                        `finishedAt` INTEGER,
                        `discardedFraction` REAL NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_prep_batches_status` ON `prep_batches` (`status`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `batch_consumptions` (
                        `foodEntryId` TEXT NOT NULL,
                        `batchId` TEXT NOT NULL,
                        `portions` REAL,
                        `grams` REAL,
                        `fraction` REAL NOT NULL,
                        `loggedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`foodEntryId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_batch_consumptions_batchId` ON `batch_consumptions` (`batchId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ingredient_aliases` (
                        `normalizedName` TEXT NOT NULL,
                        `foodId` TEXT NOT NULL,
                        `foodName` TEXT NOT NULL,
                        `servingSize` REAL NOT NULL,
                        `servingUnit` TEXT NOT NULL,
                        `calories` REAL NOT NULL,
                        `protein` REAL NOT NULL,
                        `carbs` REAL NOT NULL,
                        `fat` REAL NOT NULL,
                        `gramsPerUnitJson` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`normalizedName`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context, scope: CoroutineScope): ByteLogDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ByteLogDatabase::class.java,
                    "bytelog_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    // Recipes and batches are user-authored local data: never wipe them on upgrade.
                    // Only downgrades (e.g. sideloading an older APK) fall back to a rebuild.
                    .fallbackToDestructiveMigrationOnDowngrade()
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
                        database.mealTypeDao().insertMealTypes(MealType.DEFAULT_MEAL_TYPES)
                    }
                }
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                INSTANCE?.let { database ->
                    scope.launch(Dispatchers.IO) {
                        // Purge any legacy demo starter foods from previous runs
                        database.foodDao().deleteDemoFoods()
                        // If user has synced meal types from Sparky, purge redundant placeholder meal types
                        if (database.mealTypeDao().getCount() > 4) {
                            database.mealTypeDao().purgeDefaultMealTypes()
                        }
                    }
                }
            }
        }
    }
}
