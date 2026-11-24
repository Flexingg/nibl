package com.bytelog.app.research

import com.bytelog.app.data.remote.SparkyTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SparkyToolCatalogTest {

    private fun read(tool: String, args: String = "{}") = SparkyToolCatalog.isRead(tool, obj(args))

    @Test fun manageToolsFollowTheirAction() {
        assertTrue(read("sparky_manage_food", """{"action":"search_food","food_name":"rice"}"""))
        assertTrue(read("sparky_manage_food", """{"action":"lookup_food_nutrition","food_name":"rice"}"""))
        assertTrue(read("sparky_manage_food", """{"action":"list_diary"}"""))
        assertTrue(read("sparky_manage_checkin", """{"action":"get_biometrics_history"}"""))
        assertFalse(read("sparky_manage_food", """{"action":"log_food","food_name":"rice"}"""))
        assertFalse(read("sparky_manage_food", """{"action":"create_food"}"""))
        assertFalse(read("sparky_manage_food", """{"action":"delete_entry","entry_id":"1"}"""))
        assertFalse(read("sparky_manage_food", """{"action":"update_food_variant"}"""))
        assertFalse(read("sparky_manage_goals", """{"action":"set_goals"}"""))
        assertFalse(read("sparky_manage_food", """{"action":"copy_from_yesterday"}"""))
    }

    @Test fun aManageToolWithoutAnActionCountsAsAWrite() {
        assertFalse(read("sparky_manage_food", """{"food_name":"rice"}"""))
        assertFalse(read("sparky_manage_profile"))
    }

    @Test fun plainReadToolsAreReads() {
        assertTrue(read("sparky_get_food_diary"))
        assertTrue(read("sparky_search_foods", """{"food_name":"x"}"""))
        assertTrue(read("sparky_list_foods"))
        assertTrue(read("sparky_get_report"))
        assertTrue(read("sparky_analyze_trends"))
    }

    @Test fun unknownAndPhotoToolsAskFirst() {
        assertFalse(read("sparky_log_food_photo"))
        assertFalse(read("sparky_something_new"))
        assertFalse(read("sparky_scan_label"))
    }

    @Test fun categoriesFollowTheServerLayout() {
        assertEquals("food", SparkyToolCatalog.categoryFor("sparky_manage_food"))
        assertEquals("food", SparkyToolCatalog.categoryFor("sparky_manage_water_containers"))
        assertEquals("checkin", SparkyToolCatalog.categoryFor("sparky_manage_symptoms"))
        assertEquals("reports", SparkyToolCatalog.categoryFor("sparky_get_dashboard"))
        assertEquals("coaching", SparkyToolCatalog.categoryFor("sparky_daily_checkin_wizard"))
        assertNull(SparkyToolCatalog.categoryFor("sparky_something_new"))
    }

    @Test fun categorySelectionFiltersTools() {
        val core = SparkyTools.coreSlugs
        assertTrue(SparkyToolCatalog.isAllowed("sparky_manage_food", core))
        assertFalse(SparkyToolCatalog.isAllowed("sparky_get_report", core))
        assertFalse(SparkyToolCatalog.isAllowed("sparky_manage_medications", core))
        // A tool this app doesn't know is only offered when every category is on.
        assertFalse(SparkyToolCatalog.isAllowed("sparky_something_new", core))
        assertTrue(SparkyToolCatalog.isAllowed("sparky_something_new", SparkyTools.allSlugs))
    }

    @Test fun everyToolOfTheServerIsMapped() {
        // The 52 tools registered for chat in SparkyFitnessServer/ai/tools (dev-only tools excluded).
        val serverTools = """sparky_analyze_food_image sparky_analyze_trends sparky_check_engagement sparky_daily_checkin_wizard sparky_detect_patterns
            sparky_generate_coaching_plan sparky_get_barcode sparky_get_caffeine_kinetics sparky_get_contextual_nudge sparky_get_daily_exercise_totals
            sparky_get_daily_report sparky_get_dashboard sparky_get_exercise_details sparky_get_exercise_diary sparky_get_exercise_progress
            sparky_get_exercise_stats sparky_get_exercise_usage sparky_get_food_details sparky_get_food_diary sparky_get_food_usage sparky_get_goal_snapshot
            sparky_get_health_summary sparky_get_integrations sparky_get_logging_streak sparky_get_nutrition_summary sparky_get_recent_exercise_entries
            sparky_get_recent_food_entries sparky_get_report sparky_get_sleep_science sparky_get_synced_data sparky_list_exercises sparky_list_foods
            sparky_log_food_photo sparky_manage_allergens sparky_manage_checkin sparky_manage_custom_nutrients sparky_manage_exercise sparky_manage_favorites
            sparky_manage_food sparky_manage_goals sparky_manage_habits sparky_manage_meal_plans sparky_manage_medications sparky_manage_profile
            sparky_manage_progress_photos sparky_manage_symptoms sparky_manage_water_containers sparky_manage_workout_plans sparky_scan_label
            sparky_search_exercises sparky_search_foods""".split(Regex("\\s+"))
        assertEquals(51, serverTools.size)
        assertEquals(emptyList<String>(), serverTools.filter { SparkyToolCatalog.categoryFor(it) == null })
    }

    @Test fun changesReadAsPlainLanguage() {
        val today = java.time.LocalDate.of(2026, 11, 24)
        fun h(tool: String, args: String) = SparkyToolCatalog.humanize(tool, obj(args), today)
        assertEquals("Log food: 2 serving Greek yogurt · Breakfast · today · 150 kcal" to emptyList<String>(),
            h("sparky_manage_food", """{"action":"log_food","food_name":"Greek yogurt","quantity":2.0,"unit":"serving","meal_type":"breakfast","entry_date":"2026-11-24","calories":150}"""))
        assertEquals("Log food: 1.5 cup rice · yesterday", h("sparky_manage_food", """{"action":"log_food","food_name":"rice","quantity":1.5,"unit":"cup","date":"2026-11-23"}""").first)
        assertEquals("Log food: oats · Fri 20 Nov", h("sparky_manage_food", """{"action":"log_food","food_name":"oats","date":"2026-11-20"}""").first)
        // What isn't in the headline is listed, readably; nulls are skipped
        val (head, details) = h("sparky_manage_food", """{"action":"delete_entry","entry_id":"e-42","reason":null,"items":[1,2]}""")
        assertEquals("Delete entry", head)
        assertEquals(listOf("Entry id: e-42", "Items: 2 items"), details)
        // No action: named after the tool
        assertEquals("Goals: 1800 kcal", h("sparky_manage_goals", """{"calories":1800}""").first)
    }
}

