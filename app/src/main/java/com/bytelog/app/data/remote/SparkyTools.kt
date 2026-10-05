package com.bytelog.app.data.remote

/**
 * The tool categories Sparky's chat endpoint can load (`toolCategories` on POST /api/chat).
 * The slugs mirror SparkyFitnessServer's `CHAT_TOOL_CATEGORY_SLUGS`. Tools within a category can both
 * read and write; only [writes] = false categories are read-only.
 */
data class SparkyToolCategory(
    val slug: String,
    val label: String,
    val description: String,
    val writes: Boolean = true
)

object SparkyTools {
    val categories: List<SparkyToolCategory> = listOf(
        SparkyToolCategory("food", "Food & meals", "Search, log, edit and create foods, diary entries, favorites, meals and meal plans, water containers"),
        SparkyToolCategory("exercise", "Exercise", "Read and log workouts, stats and workout plans"),
        SparkyToolCategory("checkin", "Check-ins", "Weight and measurements, sleep, symptoms, progress photos"),
        SparkyToolCategory("goals", "Goals", "Read and change calorie, macro and weekly goals"),
        SparkyToolCategory("reports", "Reports", "Daily and period reports and the dashboard", writes = false),
        SparkyToolCategory("coaching", "Coaching", "Coach tips, engagement nudges and the check-in wizard"),
        SparkyToolCategory("vision", "Photo estimates", "Estimate foods from photos"),
        SparkyToolCategory("profile", "Profile & habits", "Profile, habits, integrations and synced device data"),
        SparkyToolCategory("medications", "Medications", "Medication and supplement tracking")
    )

    val allSlugs: Set<String> = categories.map { it.slug }.toSet()

    /** What the server loads by default ("core" profile). */
    val coreSlugs: Set<String> = setOf("food", "exercise", "checkin", "goals")

    /** Keeps only known slugs, in the server's order. */
    fun normalize(slugs: Collection<String>): List<String> = categories.map { it.slug }.filter { it in slugs }
}
