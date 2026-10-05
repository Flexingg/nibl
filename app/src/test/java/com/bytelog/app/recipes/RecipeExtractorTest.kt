package com.bytelog.app.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeExtractorTest {

    @Test fun yoastGraphWithSections() {
        val html = """
            <html><head><title>Chili | Blog</title>
            <script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[
              {"@type":"WebPage","name":"Turkey Chili"},
              {"@type":"Recipe","name":"Turkey Chili &amp; Beans",
               "image":["https://x.test/s.jpg","https://x.test/l.jpg"],
               "recipeYield":["6","6 servings"],
               "prepTime":"PT15M","cookTime":"PT1H","totalTime":"PT1H15M",
               "recipeIngredient":["1 lb ground turkey","1 (15 oz) can black beans, drained"],
               "recipeInstructions":[
                 {"@type":"HowToSection","name":"Brown the meat","itemListElement":[
                   {"@type":"HowToStep","text":"Heat oil in a pot."},
                   {"@type":"HowToStep","text":"Cook turkey for 8-10 minutes."}]},
                 {"@type":"HowToSection","name":"Simmer","itemListElement":[
                   {"@type":"HowToStep","text":"Add beans and simmer 30 minutes."}]}
               ],
               "nutrition":{"@type":"NutritionInformation","calories":"320 kcal","proteinContent":"28 g"}}
            ]}
            </script></head><body></body></html>
        """.trimIndent()
        val r = RecipeExtractor.extract(html, "https://x.test/chili")!!
        assertEquals("Turkey Chili & Beans", r.name)
        assertEquals(6.0, r.yieldServings!!, 1e-9)
        assertEquals(15, r.prepMinutes)
        assertEquals(60, r.cookMinutes)
        assertEquals(2, r.ingredientLines.size)
        assertEquals(3, r.steps.size)
        assertEquals("Brown the meat", r.steps[1].section)
        assertEquals(480, r.steps[1].timerSeconds)
        assertEquals("Simmer", r.steps[2].section)
        assertEquals("https://x.test/l.jpg", r.imageUrl)
        assertEquals(320f, r.siteNutrition!!.calories!!, 0.01f)
    }

    @Test fun topLevelArrayAndArrayType() {
        val html = """<script type="application/ld+json">
            [{"@type":"Organization","name":"Site"},
             {"@type":["Recipe","NewsArticle"],"name":"Overnight Oats","recipeYield":"Makes 2 jars",
              "recipeIngredient":["1 cup rolled oats","1 cup milk"],
              "recipeInstructions":"Mix everything.\nRefrigerate 8 hours."}]
            </script>"""
        val r = RecipeExtractor.extract(html)!!
        assertEquals("Overnight Oats", r.name)
        assertEquals(2.0, r.yieldServings!!, 1e-9)
        assertEquals(2, r.steps.size)
        assertEquals(8 * 3600, r.steps[1].timerSeconds)
    }

    @Test fun htmlInstructionList() {
        val html = """<script type="application/ld+json">
            {"@type":"Recipe","name":"Toast","recipeIngredient":["1 slice bread"],
             "recipeInstructions":"<ol><li>Toast the bread.</li><li>Butter it.</li></ol>"}
            </script>"""
        val r = RecipeExtractor.extract(html)!!
        assertEquals(listOf("Toast the bread.", "Butter it."), r.steps.map { it.text })
    }

    @Test fun microdataFallback() {
        val html = """
            <div itemscope itemtype="https://schema.org/Recipe">
              <h1 itemprop="name">Pancakes</h1>
              <meta itemprop="recipeYield" content="4">
              <meta itemprop="cookTime" content="PT20M">
              <li itemprop="recipeIngredient">1 cup flour</li>
              <li itemprop="recipeIngredient">1 egg</li>
              <div itemprop="recipeInstructions"><ol><li>Whisk.</li><li>Fry 2 minutes per side.</li></ol></div>
            </div>"""
        val r = RecipeExtractor.extract(html)!!
        assertEquals("Pancakes", r.name)
        assertEquals(4.0, r.yieldServings!!, 1e-9)
        assertEquals(20, r.cookMinutes)
        assertEquals(2, r.ingredientLines.size)
        assertEquals(2, r.steps.size)
    }

    @Test fun pageWithoutRecipe() {
        assertNull(RecipeExtractor.extract("<html><body><p>Hello</p></body></html>"))
    }

    @Test fun isoDurations() {
        assertEquals(90, RecipeExtractor.parseIsoDurationMinutes("PT1H30M"))
        assertEquals(45, RecipeExtractor.parseIsoDurationMinutes("P0DT0H45M"))
        assertEquals(1440, RecipeExtractor.parseIsoDurationMinutes("P1D"))
        assertNull(RecipeExtractor.parseIsoDurationMinutes("PT0M"))
    }

    @Test fun findUrlInSharedText() {
        assertEquals("https://x.test/a?b=1", RecipeExtractor.findUrl("Best Chili Ever\nhttps://x.test/a?b=1"))
        assertNull(RecipeExtractor.findUrl("no link here"))
    }

    @Test fun pastedText() {
        val r = RecipeTextParser.parse(
            """
            Garlic Shrimp Rice Bowls
            Serves 4

            Ingredients
            1 lb shrimp, peeled
            2 cups cooked rice
            3 cloves garlic, minced

            Directions
            1. Saute garlic 1 minute.
            2. Add shrimp and cook 3-4 minutes.
            """.trimIndent()
        )
        assertEquals("Garlic Shrimp Rice Bowls", r.name)
        assertEquals(4.0, r.yieldServings!!, 1e-9)
        assertEquals(3, r.ingredientLines.size)
        assertEquals(2, r.steps.size)
        assertEquals("Saute garlic 1 minute.", r.steps[0].text)
        assertEquals(180, r.steps[1].timerSeconds)
        assertNotNull(r.steps[0].timerSeconds)
    }

    @Test fun timerDetection() {
        val t = TimerDetector.detect("Bake for 20-25 minutes, then rest 1 hour")
        assertEquals(2, t.size)
        assertEquals(1200, t[0].seconds)
        assertEquals(1500, t[0].maxSeconds)
        assertEquals(3600, t[1].seconds)
        assertTrue(TimerDetector.detect("Add 3 medium onions").isEmpty())
    }
}
