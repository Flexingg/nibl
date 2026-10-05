package com.bytelog.app.research

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

fun resource(name: String): String =
    object {}.javaClass.classLoader!!.getResource("research/$name")!!.readText()
