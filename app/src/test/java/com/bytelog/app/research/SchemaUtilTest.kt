package com.bytelog.app.research

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaUtilTest {

    @Test fun mcpSchemaIsKeptForClaudeAndDeepSeekExceptTheDraftMarker() {
        val tool = obj(resource("mcp-tools-list.json")).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray[0].jsonObject
        val schema = SchemaUtil.schemaOf(tool)
        assertNull(schema["\$schema"])
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertTrue(schema["properties"]!!.jsonObject.containsKey("food_name"))
    }

    @Test fun missingSchemaBecomesAnEmptyObject() {
        assertEquals(SchemaUtil.EMPTY_OBJECT, SchemaUtil.asObjectSchema(null))
        assertEquals(SchemaUtil.EMPTY_OBJECT, SchemaUtil.asObjectSchema(obj("""{"type":"string"}""")))
    }

    @Test fun geminiDropsUnsupportedKeywords() {
        val g = SchemaUtil.forGemini(obj("""{"${'$'}schema":"x","type":"object","additionalProperties":false,
            "properties":{"q":{"type":"string","description":"d","additionalProperties":false,"minLength":2},"n":{"type":"number"}},"required":["q"]}"""))
        assertNull(g["\$schema"]); assertNull(g["additionalProperties"])
        val q = g["properties"]!!.jsonObject["q"]!!.jsonObject
        assertEquals(setOf("type", "description"), q.keys)
        assertEquals(listOf("q"), g["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun geminiMergesUnionBranchesIntoOneObject() {
        // The shape zod produces for a manage_* tool: one branch per action.
        val g = SchemaUtil.forGemini(obj("""{"type":"object","anyOf":[
            {"type":"object","properties":{"action":{"type":"string","enum":["search_food"]},"food_name":{"type":"string"}},"required":["action"]},
            {"type":"object","properties":{"action":{"type":"string","enum":["log_food"]},"quantity":{"type":"number"}},"required":["action"]}]}"""))
        val props = g["properties"]!!.jsonObject
        assertEquals(setOf("action", "food_name", "quantity"), props.keys)
        assertEquals(listOf("search_food", "log_food"), props["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(g["anyOf"])
    }

    @Test fun geminiTurnsTypeArraysIntoNullable() {
        val g = SchemaUtil.forGemini(obj("""{"type":"object","properties":{"note":{"type":["string","null"]},
            "tags":{"type":"array","items":{"type":["integer","null"]}}}}"""))
        val note = g["properties"]!!.jsonObject["note"]!!.jsonObject
        assertEquals("string", note["type"]!!.jsonPrimitive.content)
        assertEquals("true", note["nullable"]!!.jsonPrimitive.content)
        assertEquals("integer", g["properties"]!!.jsonObject["tags"]!!.jsonObject["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun geminiKeepsNumericEnumsAsStringsAndPrunesRequired() {
        val g = SchemaUtil.forGemini(obj("""{"type":"object","properties":{"level":{"type":"integer","enum":[1,2,3]}},"required":["level","gone"]}"""))
        assertEquals(listOf("1", "2", "3"), g["properties"]!!.jsonObject["level"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("level"), g["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun geminiAddsATypeWhereAUnionRemovedIt() {
        val g = SchemaUtil.forGemini(obj("""{"type":"object","properties":{"v":{"anyOf":[{"type":"string"},{"type":"number"},{"type":"null"}]}}}"""))
        val v = g["properties"]!!.jsonObject["v"]!!.jsonObject
        assertEquals("string", v["type"]!!.jsonPrimitive.content)
        assertEquals("true", v["nullable"]!!.jsonPrimitive.content)
        assertFalse(v.containsKey("anyOf"))
    }
}
