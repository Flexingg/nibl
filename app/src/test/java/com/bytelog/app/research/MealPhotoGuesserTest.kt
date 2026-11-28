package com.bytelog.app.research

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPhotoGuesserTest {

    @Test fun eachFoodComesWithItsGuessesBestFirst() {
        val raw = """Here you go: {"summary":"Chicken rice bowl","confidence":"medium","confidence_reason":"Sauce hides the rice",
            "items":[
              {"guesses":[
                {"name":"Grilled chicken breast","grams":120,"portion":"1 fillet","calories":198,"protein":37,"carbs":0,"fat":4.3,"fiber":0,"confidence":"medium","why":"grill marks, no skin"},
                {"name":"Grilled chicken thigh","grams":120,"portion":"1 fillet","calories":250,"protein":30,"carbs":0,"fat":14,"fiber":0,"confidence":"low","why":"could be thigh"}]},
              {"guesses":[
                {"name":"White rice","grams":150,"portion":"1 cup","calories":195,"protein":4,"carbs":42,"fat":0.4,"fiber":0.6,"confidence":"high","why":""},
                {"name":"White rice","grams":150,"portion":"1 cup","calories":195,"protein":4,"carbs":42,"fat":0.4,"fiber":0.6,"confidence":"high","why":""},
                {"name":"White rice","grams":220,"portion":"1.5 cups","calories":286,"protein":6,"carbs":62,"fat":0.6,"fiber":0.9,"confidence":"medium","why":"bigger bowl"}]},
              {"guesses":[]}],
            "questions":["Was the chicken cooked in oil?",""]} Thanks!"""
        val est = MealPhotoGuesser.parse(raw, "Claude")!!
        assertEquals("Chicken rice bowl", est.summary)
        assertEquals("Claude", est.estimatedBy)
        assertEquals(listOf("Was the chicken cooked in oil?"), est.questions)
        assertEquals("a food with no guesses is dropped", 2, est.items.size)

        val chicken = est.items[0]
        assertEquals("Grilled chicken breast", chicken.name)
        assertEquals("grill marks, no skin", chicken.reason)
        assertEquals(listOf("Grilled chicken thigh"), chicken.alternatives.map { it.name })
        assertEquals(250f, chicken.alternatives[0].calories)

        // Repeated guesses collapse; a portion guess stays
        val rice = est.items[1]
        assertEquals(listOf(220f), rice.alternatives.map { it.grams })
        assertNull("an empty reason is no reason", rice.reason)
    }

    @Test fun aModelThatSkipsTheGuessesListStillCounts() {
        val est = MealPhotoGuesser.parse("""{"summary":"Apple","items":[{"name":"Apple","grams":180,"calories":95,"protein":"0.5 g"}]}""")!!
        assertEquals("Apple", est.items.single().name)
        assertEquals(0.5f, est.items.single().protein)
        assertTrue(est.items.single().alternatives.isEmpty())
        assertEquals("medium", est.confidence)
    }

    @Test fun nothingUsableIsNull() {
        assertNull(MealPhotoGuesser.parse("I can't see any food."))
        assertNull(MealPhotoGuesser.parse("""{"summary":"x","items":[{"guesses":[{"name":"No calories"}]}]}"""))
        assertNull(MealPhotoGuesser.parse("{not json"))
    }

    @Test fun theUsersNoteAndTheShapeAreInTheInstructions() {
        val text = MealPhotoGuesser.instructions("cooked in butter")
        assertTrue(text.contains("cooked in butter"))
        assertTrue(text.contains("2 or 3 guesses"))
        assertTrue(text.contains("\"guesses\""))
    }

    @Test fun estimatesGoThroughTheProvidersPhotoReading() = runBlocking {
        var seen: List<Pair<String, String>> = emptyList()
        val provider = object : LlmProvider {
            override val id = ProviderId.GEMINI
            override val searchesWebNatively = false
            override val readsImages = true
            override fun newSession(system: String, history: List<ChatTurn>, userText: String, tools: List<ToolSpec>) = error("unused")
            override suspend fun listModels() = Result.success(emptyList<String>())
            override suspend fun lookAt(instructions: String, images: List<Pair<String, String>>, schema: JsonObject, ask: String, maxTokens: Int): String {
                seen = images
                return """{"summary":"Toast","items":[{"guesses":[{"name":"Toast","grams":30,"calories":80}]}]}"""
            }
        }
        val est = MealPhotoGuesser(provider).estimate(listOf("QUJD" to "image/jpeg"), null).getOrThrow()
        assertEquals(listOf("QUJD" to "image/jpeg"), seen)
        assertEquals("Gemini", est.estimatedBy)

        val garbled = object : LlmProvider by provider {
            override suspend fun lookAt(instructions: String, images: List<Pair<String, String>>, schema: JsonObject, ask: String, maxTokens: Int) = "no idea"
        }
        assertTrue(MealPhotoGuesser(garbled).estimate(emptyList(), null).exceptionOrNull() is ProviderException)
    }
}
