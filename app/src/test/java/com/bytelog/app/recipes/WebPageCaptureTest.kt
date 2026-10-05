package com.bytelog.app.recipes

import com.bytelog.app.data.repository.RecipeFetchException
import com.bytelog.app.data.repository.RecipeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageCaptureTest {

    private fun resource(name: String) = javaClass.classLoader!!.getResource("recipes/$name")!!.readText()

    @Test fun decodesEvaluateJavascriptStrings() {
        assertEquals("<p>Hi \"you\"</p>\nok", WebPageCapture.decodeJsResult("\"\\u003Cp>Hi \\\"you\\\"\\u003C/p>\\nok\""))
        assertNull(WebPageCapture.decodeJsResult("null"))
        assertNull(WebPageCapture.decodeJsResult(null))
        assertNull(WebPageCapture.decodeJsResult("\"\""))
        assertNull(WebPageCapture.decodeJsResult("42"))
    }

    @Test fun onlyWebLinksAreLoadable() {
        assertTrue(WebPageCapture.isLoadable("https://example.com/r"))
        assertTrue(WebPageCapture.isLoadable("http://example.com/r"))
        assertFalse(WebPageCapture.isLoadable("market://details?id=x"))
        assertFalse(WebPageCapture.isLoadable("intent://x#Intent;end"))
        assertFalse(WebPageCapture.isLoadable("file:///sdcard/a.html"))
        assertFalse(WebPageCapture.isLoadable(null))
    }

    /**
     * The page builds its Schema.org recipe with JavaScript. A plain HTTP fetch sees only the loader
     * script (no recipe), while the DOM read in a real browser (captured from Chromium and encoded the
     * way Android's evaluateJavascript returns it) contains the recipe.
     */
    @Test fun renderedDomHasTheRecipeButTheRawSourceDoesNot() {
        val plain = RecipeRepository.recipeFromHtml(resource("js-rendered-source.html"), "https://x.test/r")
        val failure = plain.exceptionOrNull() as RecipeFetchException
        assertTrue("a browser may help", failure.canTryBrowser)

        val html = WebPageCapture.decodeJsResult(resource("webview-result.json"))!!
        val rendered = RecipeRepository.recipeFromHtml(html, "https://x.test/r").getOrThrow()
        assertEquals("Rendered Chili & Beans", rendered.name)
        assertEquals(4.0, rendered.yieldServings!!, 1e-9)
        assertEquals(listOf("1 lb ground turkey", "1 (15 oz) can black beans"), rendered.ingredientLines)
        assertEquals(480, rendered.steps[0].timerSeconds)
        assertEquals(1200, rendered.steps[1].timerSeconds)
    }

    @Test fun recipeWithoutIngredientsAsksForBrowser() {
        val html = """<script type="application/ld+json">{"@type":"Recipe","name":"Teaser"}</script>"""
        val e = RecipeRepository.recipeFromHtml(html, "https://x.test").exceptionOrNull() as RecipeFetchException
        assertTrue(e.canTryBrowser)
    }
}
