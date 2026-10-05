package com.bytelog.app.research

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** JSON Schema helpers for handing MCP tool schemas to different providers. */
object SchemaUtil {

    /** A schema every provider accepts for "no arguments". */
    val EMPTY_OBJECT: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", JsonObject(emptyMap()))
    }

    /** Anthropic and OpenAI-style APIs take JSON Schema as-is; they only need a top-level object. */
    fun asObjectSchema(schema: JsonObject?): JsonObject {
        if (schema == null) return EMPTY_OBJECT
        return if (schema["type"]?.jsonPrimitive?.contentOrNull == "object" || schema["properties"] != null) {
            JsonObject(schema.filterKeys { it != "\$schema" })
        } else EMPTY_OBJECT
    }

    private val geminiKeys = setOf("type", "description", "properties", "required", "enum", "items", "nullable", "format", "minimum", "maximum", "title")

    /**
     * Gemini's function schemas are an OpenAPI subset: no `$schema`, `additionalProperties`, `$ref`,
     * `anyOf`/`oneOf` of objects, or type arrays. This keeps the supported keys, merges union branches into one
     * object (all properties optional) and turns `["string","null"]` into `nullable`. Sparky re-validates every
     * call, so a looser schema is safe.
     */
    fun forGemini(schema: JsonObject?): JsonObject = (sanitize(asObjectSchema(schema)) as? JsonObject) ?: EMPTY_OBJECT

    private fun sanitize(el: JsonElement): JsonElement {
        if (el !is JsonObject) return el
        var node: JsonObject = el
        // Union: merge object branches, otherwise take the first branch.
        val union = (node["anyOf"] ?: node["oneOf"]) as? JsonArray
        if (union != null) node = mergeUnion(node, union)

        val out = linkedMapOf<String, JsonElement>()
        var nullable = false
        for ((k, v) in node) {
            if (k !in geminiKeys) continue
            when (k) {
                "type" -> when (v) {
                    is JsonArray -> {
                        val types = v.mapNotNull { it.jsonPrimitive.contentOrNull }
                        nullable = "null" in types
                        out["type"] = JsonPrimitive(types.firstOrNull { it != "null" } ?: "string")
                    }
                    else -> out["type"] = v
                }
                "properties" -> out["properties"] = JsonObject((v as JsonObject).mapValues { (_, p) -> sanitize(p) })
                "items" -> out["items"] = sanitize(v)
                // Gemini enums are lists of strings.
                "enum" -> out["enum"] = JsonArray((v as JsonArray).map { JsonPrimitive(it.jsonPrimitive.content) })
                else -> out[k] = v
            }
        }
        if (nullable) out["nullable"] = JsonPrimitive(true)
        // Gemini needs `type`; infer it when a union/ref flattened it away.
        if ("type" !in out) out["type"] = JsonPrimitive(if ("properties" in out) "object" else if ("items" in out) "array" else "string")
        if (out["type"]?.jsonPrimitive?.contentOrNull == "object" && "properties" !in out) out["properties"] = JsonObject(emptyMap())
        // `required` must only name properties that survived.
        val props = (out["properties"] as? JsonObject)?.keys
        (out["required"] as? JsonArray)?.let { req ->
            val kept = req.filter { props != null && it.jsonPrimitive.contentOrNull in props }
            if (kept.isEmpty()) out.remove("required") else out["required"] = JsonArray(kept)
        }
        return JsonObject(out)
    }

    private fun mergeUnion(parent: JsonObject, branches: JsonArray): JsonObject {
        val objectBranches = branches.mapNotNull { it as? JsonObject }.filter { it["type"]?.jsonPrimitive?.contentOrNull == "object" || it["properties"] != null }
        if (objectBranches.isNotEmpty()) {
            val props = linkedMapOf<String, JsonElement>()
            objectBranches.forEach { b ->
                (b["properties"] as? JsonObject)?.forEach { (name, p) ->
                    val existing = props[name]
                    // Same property in several branches (e.g. an `action` enum): merge enum values.
                    props[name] = if (existing is JsonObject && p is JsonObject) mergeEnum(existing, p) else existing ?: p
                }
            }
            return buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(props))
                parent["description"]?.let { put("description", it) }
            }
        }
        // Union of primitives (e.g. string | number | null): use the first non-null branch.
        val first = branches.mapNotNull { it as? JsonObject }.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull != "null" }
        val nullable = branches.any { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "null" }
        return JsonObject(buildMap {
            parent.forEach { (k, v) -> if (k != "anyOf" && k != "oneOf") put(k, v) }
            first?.forEach { (k, v) -> put(k, v) }
            if (nullable) put("nullable", JsonPrimitive(true))
        })
    }

    private fun mergeEnum(a: JsonObject, b: JsonObject): JsonObject {
        val ea = a["enum"] as? JsonArray
        val eb = b["enum"] as? JsonArray
        if (ea == null || eb == null) return a
        val merged = (ea + eb).distinct()
        return JsonObject(a + ("enum" to JsonArray(merged)))
    }

    /** Extracts the `tools[].inputSchema` of an MCP tools/list entry. */
    fun schemaOf(tool: JsonObject): JsonObject = asObjectSchema(tool["inputSchema"] as? JsonObject)
}
