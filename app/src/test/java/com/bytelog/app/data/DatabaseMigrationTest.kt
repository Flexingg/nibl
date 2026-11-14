package com.bytelog.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bytelog.app.data.local.db.ByteLogDatabase
import com.bytelog.app.data.local.model.BatchConsumption
import com.bytelog.app.data.local.model.PrepBatch
import com.bytelog.app.data.local.model.RecipeDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Upgrades a real version-3 database file (as shipped before recipes) with the production
 * migrations and checks Room accepts the result and existing data survives.
 */
// Same sandbox settings as the screen tests: Robolectric's native libraries can only bind to
// one sandbox per JVM, so differing configs in one test run break whichever runs second.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class DatabaseMigrationTest {

    private val newTables = setOf("recipe_details", "prep_batches", "batch_consumptions", "ingredient_aliases")

    @Test
    fun migrate3To4KeepsDataAndAddsRecipeTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)

        // Build the v3 schema from the exported v4 schema minus the tables v4 adds.
        val schemaFile = File(System.getProperty("user.dir"), "schemas/com.bytelog.app.data.local.db.ByteLogDatabase/4.json")
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                if (table in newTables) continue
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx ->
                    for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            db.execSQL(
                "INSERT INTO food_entries (id, foodName, mealTypeId, mealName, quantity, unit, calories, protein, carbs, fat, entryDate, syncStatus, createdAt) " +
                    "VALUES ('e1', 'Oats', 'breakfast', 'Breakfast', 40, 'g', 150, 5, 27, 3, '2026-10-04', 'synced', 0)"
            )
            db.version = 3
        }

        val room = Room.databaseBuilder(context, ByteLogDatabase::class.java, name)
            .addMigrations(ByteLogDatabase.MIGRATION_1_2, ByteLogDatabase.MIGRATION_2_3, ByteLogDatabase.MIGRATION_3_4, ByteLogDatabase.MIGRATION_4_5, ByteLogDatabase.MIGRATION_5_6, ByteLogDatabase.MIGRATION_6_7, ByteLogDatabase.MIGRATION_7_8)
            .allowMainThreadQueries()
            .build()
        try {
            // Opening validates every table against the v4 schema.
            val entries = room.foodEntryDao().getEntriesForDateOnce("2026-10-04")
            assertEquals(1, entries.size)
            assertEquals("Oats", entries.first().foodName)

            room.recipeDetailsDao().upsert(RecipeDetails(templateId = "t1", stepsJson = "[]", fridgeDays = 4))
            assertNotNull(room.recipeDetailsDao().get("t1"))
            room.prepBatchDao().upsert(PrepBatch(id = "b1", name = "Chili", cookedAt = 0, calories = 2000f, protein = 150f, carbs = 200f, fat = 60f, portions = 5))
            room.prepBatchDao().insertConsumption(BatchConsumption(foodEntryId = "e1", batchId = "b1", portions = 1f, fraction = 0.2f))
            assertEquals(1, room.prepBatchDao().observeActive().first().size)
            room.prepBatchDao().onEntriesDeleted(listOf("e1"))
            assertEquals(0, room.prepBatchDao().consumptionsFor("b1").size)
        } finally {
            room.close()
        }
    }

    @Test
    fun migrate4To5KeepsFoodsAndAddsHouseholdServings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test-5.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File(System.getProperty("user.dir"), "schemas/com.bytelog.app.data.local.db.ByteLogDatabase/4.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx ->
                    for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            db.execSQL(
                "INSERT INTO foods (id, name, servingSize, servingUnit, calories, protein, carbs, fat, fiber, isFavorite, useCount, lastUsedAt) " +
                    "VALUES ('f1', 'Oreo', 100, 'g', 480, 5, 70, 20, 2, 1, 7, 0)"
            )
            db.version = 4
        }
        val room = Room.databaseBuilder(context, ByteLogDatabase::class.java, name)
            .addMigrations(ByteLogDatabase.MIGRATION_4_5, ByteLogDatabase.MIGRATION_5_6, ByteLogDatabase.MIGRATION_6_7, ByteLogDatabase.MIGRATION_7_8)
            .allowMainThreadQueries()
            .build()
        try {
            val food = room.foodDao().getFoodById("f1")!!
            assertEquals(7, food.useCount)
            assertEquals(null, food.householdServings)
            room.foodDao().setHouseholdServings("f1", "cookie|0.14|2")
            assertEquals("cookie|0.14|2", room.foodDao().getFoodById("f1")!!.householdServings)
            // 5 -> 6: the plan table is there and empty
            assertEquals(0, room.plannedEntryDao().forDate("2026-10-31").first().size)
            room.plannedEntryDao().insert(listOf(com.bytelog.app.data.local.model.PlannedEntry(
                id = "p1", foodName = "Oats", mealTypeId = "breakfast", mealName = "Breakfast", quantity = 1f, unit = "cup",
                calories = 300f, protein = 10f, carbs = 54f, fat = 5f, entryDate = "2026-10-31")))
            assertEquals("Oats", room.plannedEntryDao().forDate("2026-10-31").first().single().foodName)
        } finally {
            room.close()
        }
    }
}
