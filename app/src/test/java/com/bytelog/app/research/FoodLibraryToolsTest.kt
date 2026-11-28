package com.bytelog.app.research

import com.bytelog.app.data.remote.SparkyApiClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** The assistant's food-library tools against a fake Sparky server that keeps foods and servings in memory. */
class FoodLibraryToolsTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SparkyApiClient

    private data class Req(val method: String, val path: String, val body: String, val contentType: String?)
    private val requests = mutableListOf<Req>()
    /** food id → its servings, as Sparky stores them. */
    private val variants = linkedMapOf<String, MutableList<JsonObject>>()
    private var nextId = 1

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                synchronized(requests) { requests += Req(request.method.orEmpty(), path, body, request.getHeader("Content-Type")) }
                fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
                return when {
                    request.method == "POST" && path == "/api/foods" -> {
                        val id = "food-${nextId++}"
                        val o = Json.parseToJsonElement(body) as JsonObject
                        variants[id] = mutableListOf(JsonObject(o + mapOf("id" to JsonPrimitive("v-${nextId++}"), "food_id" to JsonPrimitive(id))))
                        json("""{"id":"$id","name":${o["name"]}}""")
                    }
                    request.method == "POST" && path == "/api/foods/food-variants" -> {
                        val o = Json.parseToJsonElement(body) as JsonObject
                        val v = JsonObject(o + ("id" to JsonPrimitive("v-${nextId++}")))
                        variants.getOrPut(o["food_id"]!!.jsonPrimitive.content) { mutableListOf() } += v
                        json(v.toString())
                    }
                    request.method == "GET" && path.startsWith("/api/foods/food-variants?food_id=") ->
                        json(variants[path.substringAfter("=")].orEmpty().toString())
                    request.method == "GET" && path.startsWith("/api/foods/foods-paginated") ->
                        json("""{"foods":[{"id":"food-9","name":"Orange Chicken","brand":"Panda Express","default_variant":{"serving_size":5.7,"serving_unit":"oz","calories":490,"protein":25,"carbs":51,"fat":23}}]}""")
                    request.method == "GET" && path.startsWith("/api/foods/") ->
                        json("""{"id":"${path.substringAfterLast('/')}","name":"Orange Chicken","brand":"Panda Express","images":["a.jpg"]}""")
                    request.method == "PUT" || request.method == "DELETE" -> json("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        api = SparkyApiClient(baseUrl = server.url("").toString().trimEnd('/'), apiKey = "k")
    }

    @After fun tearDown() = server.shutdown()

    private fun sent(method: String, path: String) = synchronized(requests) { requests.filter { it.method == method && it.path == path } }
    private fun body(r: Req) = Json.parseToJsonElement(r.body) as JsonObject
    private fun JsonObject.d(key: String) = this[key]!!.jsonPrimitive.double

    @Test fun createsFoodsWithEveryServingAndWorksOutSameAsServings() = runBlocking {
        val tools = FoodLibraryTools(api)
        val out = tools.run("create_foods", obj("""{"foods":[
            {"name":"Orange Chicken","brand":"Panda Express","servings":[
                {"amount":5.7,"unit":"oz","nutrition":{"calories":490,"protein":25,"carbs":51,"fat":23,"saturated_fat":5,"sodium_mg":820,"fiber":0,"sugar":19,"cholesterol_mg":90}},
                {"amount":100,"unit":"g","same_as":{"amount":100,"unit":"g"}},
                {"amount":1,"unit":"plate","same_as":{"amount":11.4,"unit":"oz"}}]},
            {"name":"Chow Mein","brand":"Panda Express","servings":[{"amount":9.4,"unit":"oz","nutrition":{"calories":510,"protein":13,"carbs":80,"fat":20}}],"barcode":"0123"}
        ]}"""))
        assertFalse(out.text, out.isError)
        assertTrue(out.text, out.text.contains("✓ Orange Chicken") && out.text.contains("3 servings") && out.text.contains("✓ Chow Mein"))

        val creates = sent("POST", "/api/foods").map(::body)
        assertEquals(2, creates.size)
        val first = creates[0]
        assertEquals("Orange Chicken", first["name"]!!.jsonPrimitive.content)
        assertEquals("Panda Express", first["brand"]!!.jsonPrimitive.content)
        assertEquals(5.7, first.d("serving_size"), 0.0)
        assertEquals("oz", first["serving_unit"]!!.jsonPrimitive.content)
        assertEquals(820.0, first.d("sodium"), 0.0)
        assertEquals(0.0, first.d("dietary_fiber"), 0.0)
        assertEquals(19.0, first.d("sugars"), 0.0)
        assertEquals(90.0, first.d("cholesterol"), 0.0)
        assertEquals("imported", first["source"]!!.jsonPrimitive.content)
        assertNull("a barcode isn't sent on create", creates[1]["barcode"])
        assertTrue("the barcode is set after", requests.any { it.method == "PUT" && it.body == """{"barcode":"0123"}""" })

        // 100 g = 100 / (5.7 × 28.35) of the portion; the plate is two portions
        val extra = sent("POST", "/api/foods/food-variants").map(::body)
        assertEquals(2, extra.size)
        val per100 = extra.first { it["serving_unit"]!!.jsonPrimitive.content == "g" }
        assertEquals(490 * 100 / (5.7 * 28.349523125), per100.d("calories"), 0.01)
        assertEquals(false, per100["is_default"]!!.jsonPrimitive.content.toBoolean())
        val plate = extra.first { it["serving_unit"]!!.jsonPrimitive.content == "plate" }
        assertEquals(980.0, plate.d("calories"), 0.01)
        assertEquals(1640.0, plate.d("sodium"), 0.01)
        assertEquals("food-1", plate["food_id"]!!.jsonPrimitive.content)
    }

    @Test fun aFoodThatCantBeWorkedOutFailsAloneAndSaysWhy() = runBlocking {
        val out = FoodLibraryTools(api).run("create_foods", obj("""{"foods":[
            {"name":"Rice","servings":[{"amount":1,"unit":"cup","same_as":{"amount":150,"unit":"g"}}]},
            {"name":"Egg roll","servings":[{"amount":1,"unit":"roll","nutrition":{"calories":200}}]}]}"""))
        assertFalse("one food went through", out.isError)
        assertTrue(out.text, out.text.contains("✗ Rice") && out.text.contains("nothing to work out"))
        assertTrue(out.text.contains("✓ Egg roll"))
        assertEquals(1, sent("POST", "/api/foods").size)
    }

    @Test fun addsAServingFromTheFoodsOwnAndMakesItTheDefault() = runBlocking {
        variants["food-9"] = mutableListOf(obj("""{"id":"v-a","serving_size":5.7,"serving_unit":"oz","calories":490,"protein":25,"carbs":51,"fat":23,"is_default":true}"""))
        val out = FoodLibraryTools(api).run("add_serving", obj("""{"food_id":"food-9","amount":1,"unit":"bowl","same_as":{"amount":8,"unit":"oz"},"make_default":true}"""))
        assertFalse(out.text, out.isError)
        val made = body(sent("POST", "/api/foods/food-variants").single())
        assertEquals(490 * 8 / 5.7, made.d("calories"), 0.01)
        assertEquals("bowl", made["serving_unit"]!!.jsonPrimitive.content)
        assertEquals(true, made["is_default"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun editsAServingScalingItsNutrition() = runBlocking {
        variants["food-9"] = mutableListOf(obj("""{"id":"v-a","serving_size":5.7,"serving_unit":"oz","calories":490,"protein":25,"carbs":51,"fat":23,"sodium":820}"""))
        val out = FoodLibraryTools(api).run("edit_serving", obj("""{"food_id":"food-9","serving_id":"v-a","amount":11.4,"scale_nutrition":true,"nutrition":{"protein":60}}"""))
        assertFalse(out.text, out.isError)
        val put = body(sent("PUT", "/api/foods/food-variants/v-a").single())
        assertEquals("food-9", put["food_id"]!!.jsonPrimitive.content)
        assertEquals(11.4, put.d("serving_size"), 0.0)
        assertEquals(980.0, put.d("calories"), 0.01)
        assertEquals("a nutrient given wins over scaling", 60.0, put.d("protein"), 0.0)
        assertEquals(1640.0, put.d("sodium"), 0.01)

        val missing = FoodLibraryTools(api).run("edit_serving", obj("""{"food_id":"food-9","serving_id":"nope","amount":2}"""))
        assertTrue(missing.isError)
    }

    @Test fun editsDeletesAndReads() = runBlocking {
        val tools = FoodLibraryTools(api)
        assertFalse(tools.run("edit_food", obj("""{"food_id":"food-9","name":"Orange Chicken (Panda)","barcode":""}""")).isError)
        val put = body(sent("PUT", "/api/foods/food-9").single())
        assertEquals("Orange Chicken (Panda)", put["name"]!!.jsonPrimitive.content)
        assertTrue("an empty barcode removes it", put["barcode"].toString() == "null")
        assertTrue(tools.run("edit_food", obj("""{"food_id":"food-9"}""")).isError)

        assertFalse(tools.run("delete_serving", obj("""{"food_id":"food-9","serving_id":"v-z"}""")).isError)
        assertEquals(1, sent("DELETE", "/api/foods/food-variants/v-z").size)
        assertFalse(tools.run("delete_food", obj("""{"food_id":"food-9","mode":"hide"}""")).isError)
        assertEquals(1, sent("DELETE", "/api/foods/food-9?mode=hide").size)

        val found = tools.run("find_my_foods", obj("""{"query":"orange"}"""))
        assertTrue(found.text, found.text.contains("food_id food-9") && found.text.contains("5.7 oz: 490 kcal"))
        variants["food-9"] = mutableListOf(obj("""{"id":"v-a","serving_size":5.7,"serving_unit":"oz","calories":490,"protein":25,"carbs":51,"fat":23,"sodium":820,"is_default":true}"""))
        val read = tools.run("get_food", obj("""{"food_id":"food-9"}"""))
        assertTrue(read.text, read.text.contains("serving_id v-a (default): 5.7 oz = calories 490") && read.text.contains("sodium_mg 820") && read.text.contains("Pictures: 1"))
    }

    @Test fun attachesAPhotoAfterTheFoodsPictures() = runBlocking {
        val photo = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val tools = FoodLibraryTools(api, images = listOf(photo to "image/jpeg"))
        assertTrue(tools.specs().any { it.name == "set_food_image" })
        assertFalse("no vision model, no reading", tools.specs().any { it.name == "read_food_image" })
        val out = tools.run("set_food_image", obj("""{"food_id":"food-9"}"""))
        assertFalse(out.text, out.isError)
        val upload = requests.last { it.method == "PUT" && it.path == "/api/foods/food-9" }
        assertTrue(upload.contentType!!.startsWith("multipart/form-data"))
        assertTrue(upload.body, upload.body.contains("""{"images":["a.jpg","__new__0"]}"""))
        assertTrue(upload.body.contains("name=\"images\"; filename=\"food-0.jpg\""))
        assertTrue(tools.run("set_food_image", obj("""{"food_id":"food-9","image":3}""")).text.contains("no photo 3"))
    }

    @Test fun approvalsArePlainLanguageOneLinePerFood() {
        val tools = FoodLibraryTools(api)
        val args = obj("""{"foods":[{"name":"Orange Chicken","brand":"Panda Express","servings":[{"amount":5.7,"unit":"oz","nutrition":{"calories":490,"protein":25,"carbs":51,"fat":23}},{"amount":100,"unit":"g","same_as":{"amount":100,"unit":"g"}}]},
            {"name":"Chow Mein","servings":[{"amount":9.4,"unit":"oz","nutrition":{"calories":510}}]}]}""")
        val lines = tools.approvalLines("create_foods", args)
        assertEquals(listOf(
            "New food: Orange Chicken (Panda Express) — 5.7 oz: 490 kcal, P 25 C 51 F 23 · +1 more serving",
            "New food: Chow Mein — 9.4 oz: 510 kcal, P ? C ? F ?"
        ), lines)
        assertTrue(tools.isWrite("create_foods")); assertFalse(tools.isWrite("get_food")); assertFalse(tools.isWrite("read_food_image"))
    }

    @Test fun theAgentAsksOnceForAllTheNewFoodsAndReadsThePhoto() = runBlocking {
        val photo = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1))
        val reading = """{"brand":"Panda Express","kind":"nutrition table","foods":[{"name":"Orange Chicken","section":"Chicken","amount":5.7,"unit":"oz","portion_text":"5.7","calories":490,"protein":25,"carbs":51,"fat":23,"saturated_fat":5,"trans_fat":0,"cholesterol_mg":90,"sodium_mg":820,"fiber":0,"sugar":19,"potassium_mg":null,"calcium_mg":null,"iron_mg":null,"vitamin_a":null,"vitamin_c":null,"caffeine_mg":null,"unclear":null}],"questions":[],"notes":null}"""
        val vision = object : LlmProvider {
            override val id = ProviderId.GEMINI
            override val searchesWebNatively = true
            override val readsImages = true
            var asked = ""
            override fun newSession(system: String, history: List<ChatTurn>, userText: String, tools: List<ToolSpec>): LlmSession {
                var step = 0
                return object : LlmSession {
                    override suspend fun next(toolResults: List<ToolResult>): LlmTurn = when (step++) {
                        0 -> LlmTurn("", listOf(ToolCall("1", "read_food_image", obj("{}"))))
                        1 -> {
                            assertTrue(toolResults.single().text, toolResults.single().text.contains("1. Orange Chicken — 5.7 oz: calories 490"))
                            LlmTurn("", listOf(
                                ToolCall("2", "create_foods", obj("""{"foods":[{"name":"Orange Chicken","brand":"Panda Express","servings":[{"amount":5.7,"unit":"oz","nutrition":{"calories":490,"protein":25,"carbs":51,"fat":23}}]},{"name":"Fried Rice","brand":"Panda Express","servings":[{"amount":9.3,"unit":"oz","nutrition":{"calories":520}}]}]}""")),
                                ToolCall("3", "add_serving", obj("""{"food_id":"food-9","food_name":"Kung Pao","amount":100,"unit":"g","nutrition":{"calories":150}}"""))
                            ))
                        }
                        else -> LlmTurn("Added 2 foods.")
                    }
                }
            }
            override suspend fun listModels() = Result.success(emptyList<String>())
            override suspend fun lookAt(instructions: String, images: List<Pair<String, String>>, schema: JsonObject, ask: String, maxTokens: Int): String {
                asked = ask; return reading
            }
        }
        val approvals = mutableListOf<PendingAction>()
        val noSparky = object : SparkyToolBackend {
            override suspend fun listTools() = Result.success(emptyList<ToolSpec>())
            override suspend fun call(name: String, args: JsonObject) = Result.success(ToolOutput("", false))
        }
        val agent = ResearchAgent(vision, noSparky, null, WebFetcher(), setOf("food"), true, { approvals += it; true },
            today = { LocalDate.of(2026, 11, 28) }, foodTools = FoodLibraryTools(api, listOf(photo to "image/jpeg"), vision))
        val r = agent.run(emptyList(), "add these").getOrThrow()
        assertEquals("Added 2 foods.", r.text)
        assertTrue(r.wroteData)
        assertEquals("Read every food in this image.", vision.asked)
        val a = approvals.single()
        assertEquals(3, a.items.size)
        assertTrue(a.items[0].startsWith("New food: Orange Chicken (Panda Express)"))
        assertTrue(a.items[2].startsWith("Add serving to Kung Pao: 100 g"))
        assertEquals(2, sent("POST", "/api/foods").size)
        assertTrue(r.actionNote!!.contains("read_food_image") && r.actionNote!!.contains("Orange Chicken"))
    }
}
