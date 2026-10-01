package com.bytelog.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.data.local.model.MealType
import com.bytelog.app.data.local.model.WaterLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

@Database(
    entities = [
        FoodItem::class,
        FoodEntry::class,
        MealType::class,
        WaterLogEntry::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ByteLogDatabase : RoomDatabase() {

    abstract fun foodDao(): FoodDao
    abstract fun foodEntryDao(): FoodEntryDao
    abstract fun mealTypeDao(): MealTypeDao
    abstract fun waterLogDao(): WaterLogDao

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

        fun getDatabase(context: Context, scope: CoroutineScope): ByteLogDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ByteLogDatabase::class.java,
                    "bytelog_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
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
