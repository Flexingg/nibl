package com.bytelog.app.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientLineParserTest {

    private fun p(s: String) = IngredientLineParser.parse(s)

    @Test fun mixedFractionPoundsWithPrepNote() {
        val r = p("1 1/2 lbs boneless skinless chicken breast, diced")
        assertEquals(1.5, r.quantity!!, 1e-9)
        assertEquals("lb", r.unit)
        assertEquals("boneless skinless chicken breast", r.name)
        assertEquals("diced", r.note)
        assertEquals(680.4, r.grams!!, 0.1)
        assertEquals(ParsedIngredient.Confidence.HIGH, r.confidence)
    }

    @Test fun tablespoonsOfOilUseDensity() {
        val r = p("2 tbsp extra virgin olive oil")
        assertEquals(2.0, r.quantity!!, 1e-9)
        assertEquals("tbsp", r.unit)
        assertEquals("extra virgin olive oil", r.name)
        assertEquals(26.9, r.grams!!, 0.2) // 2 × 14.79 ml × 0.91
    }

    @Test fun unicodeFractionsAndAttachedDigits() {
        assertEquals(0.5, p("½ cup milk").quantity!!, 1e-9)
        assertEquals(1.5, p("1½ cups flour").quantity!!, 1e-9)
        assertEquals(0.25, p("¼ tsp salt").quantity!!, 1e-9)
    }

    @Test fun packageSizeInParentheses() {
        val r = p("1 (14.5 oz) can diced tomatoes")
        assertEquals("can", r.unit)
        assertEquals("tomatoes", r.name)
        assertEquals(411.1, r.grams!!, 0.2)
    }

    @Test fun rangesUseMidpoint() {
        val r = p("2-3 cloves garlic, minced")
        assertEquals(2.0, r.quantity!!, 1e-9)
        assertEquals(3.0, r.quantityMax!!, 1e-9)
        assertEquals("clove", r.unit)
        assertEquals("garlic", r.name)
    }

    @Test fun dualUnitsPreferFirstMetric() {
        val r = p("200 g (7 oz) all-purpose flour")
        assertEquals("g", r.unit)
        assertEquals(200.0, r.grams!!, 1e-9)
        assertEquals("all-purpose flour", r.name)
    }

    @Test fun countWithoutUnit() {
        val r = p("3 large eggs")
        assertEquals(3.0, r.quantity!!, 1e-9)
        assertNull(r.unit)
        assertTrue(r.name.contains("eggs"))
        assertNull(r.grams)
    }

    @Test fun toTasteIsOptional() {
        val r = p("Salt and pepper, to taste")
        assertTrue(r.optional)
        assertNull(r.quantity)
        assertEquals("Salt and pepper", r.name)
    }

    @Test fun wordNumbers() {
        val r = p("a pinch of cayenne")
        assertEquals(1.0, r.quantity!!, 1e-9)
        assertEquals("pinch", r.unit)
        assertEquals("cayenne", r.name)
    }

    @Test fun leadingPrepWordMovedToNote() {
        val r = p("1 cup chopped onion")
        assertEquals("onion", r.name)
        assertEquals("chopped", r.note)
        assertNotNull(r.grams)
    }

    @Test fun abbreviatedCapitalT() {
        assertEquals("tbsp", p("1 T butter").unit)
        assertEquals("tsp", p("1 t vanilla").unit)
    }

    // Lines taken from real recipe pages.

    @Test fun statedMetricWeightWins() {
        val r = p("3 tablespoons (26g) kosher salt or 6 tablespoons table salt")
        assertEquals("kosher salt", r.name)
        assertEquals(26.0, r.grams!!, 1e-9)
        assertEquals(ParsedIngredient.Confidence.HIGH, r.confidence)
        assertEquals(450.0, p("1 pound (450g) dried dark red kidney beans").grams!!, 1e-9)
    }

    @Test fun statedMillilitresUseDensityOfHeadNoun() {
        val r = p("1 quart (900ml) low-sodium chicken broth, divided")
        assertEquals("low-sodium chicken broth", r.name)
        assertEquals(900.0, r.grams!!, 1e-9)
    }

    @Test fun packageWithMixedUnits() {
        val r = p("1 (28-ounce; 794g) can crushed tomatoes")
        assertEquals("can", r.unit)
        assertEquals("tomatoes", r.name)
        assertEquals("crushed", r.note)
        assertEquals(794.0, r.grams!!, 1e-9)
    }

    @Test fun containerWordDroppedAfterWeight() {
        val r = p("400g can chopped tomatoes")
        assertEquals("tomatoes", r.name)
        assertEquals(400.0, r.grams!!, 1e-9)
    }

    @Test fun sizeAndWholeMovedToNote() {
        assertEquals("yellow onion", p("1 large yellow onion, diced fine (about 1 1/2 cups)").name)
        assertEquals("ancho", p("3 whole ancho, pasilla, or mulato chiles, seeded").name)
    }

    @Test fun leavesStayInName() {
        assertEquals("bay leaves", p("2 bay leaves").name)
    }

    @Test fun trailingCountUnit() {
        val r = p("2 garlic cloves crushed")
        assertEquals("clove", r.unit)
        assertEquals("garlic", r.name)
    }

    @Test fun unknownDensityStillEstimates() {
        val r = p("1 teaspoon (5ml) Marmite")
        assertEquals(5.0, r.grams!!, 1e-9)
        assertEquals(ParsedIngredient.Confidence.MEDIUM, r.confidence)
    }
}
