package com.bytelog.app.research

import com.bytelog.app.data.remote.SparkyApiClient
import com.bytelog.app.data.remote.addFoodImages
import com.bytelog.app.data.remote.createFoodJson
import com.bytelog.app.data.remote.createVariantJson
import com.bytelog.app.data.remote.deleteFood
import com.bytelog.app.data.remote.deleteVariant
import com.bytelog.app.data.remote.fetchFoodVariants
import com.bytelog.app.data.remote.foodJson
import com.bytelog.app.data.remote.listMyFoods
import com.bytelog.app.data.remote.updateFoodJson
import com.bytelog.app.data.remote.updateVariantJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

/**
 * The assistant's tools for the user's food library on Sparky: find and read foods, create them with as many
 * serving sizes as they come in (each with full nutrition, or defined as equal to another, "1 roll = 2.75 oz"),
 * edit names, notes, barcodes and servings, choose the default serving, add pictures, and delete. Reads run freely;
 * every change is shown to the user for approval first (all the changes of one step together).
 *
 * [images] are the photos the user attached to this question (base64, media type), numbered from 1. [vision], when
 * set, lets the assistant read foods off a photo of a menu, label or nutrition table (`read_food_image`).
 */
class FoodLibraryTools(
    private val api: SparkyApiClient,
    private val images: List<Pair<String, String>> = emptyList(),
    private val vision: LlmProvider? = null
) {
    val imageCount get() = images.size

    fun specs(): List<ToolSpec> = listOfNotNull(
        FIND, GET, CREATE, EDIT, ADD_SERVING, EDIT_SERVING, DELETE_SERVING,
        if (images.isNotEmpty()) SET_IMAGE else null, DELETE,
        if (images.isNotEmpty() && vision?.readsImages == true) READ_IMAGE else null
    )

    fun handles(name: String) = name in NAMES

    /** True for a tool that changes the user's data (it needs approval). */
    fun isWrite(name: String) = name in WRITES

    /**
     * The approval lines for a change: one for most tools, one per food for a batch create. Plain language, never
     * raw JSON.
     */
    fun approvalLines(name: String, args: JsonObject): List<String> = when (name) {
        CREATE.name -> foodsOf(args).map { f ->
            val servings = (f["servings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val first = servings.firstOrNull()
            val brand = f.text("brand")?.let { " ($it)" }.orEmpty()
            val more = (servings.size - 1).takeIf { it > 0 }?.let { " · +$it more serving${if (it == 1) "" else "s"}" }.orEmpty()
            "New food: ${f.text("name") ?: "?"}$brand${first?.let { " — ${servingLine(it)}" }.orEmpty()}$more"
        }
        EDIT.name -> listOf("Edit food ${args.text("food_id")?.take(8)}…: " + listOfNotNull(
            args.text("name")?.let { "name \"$it\"" }, args.text("brand")?.let { "brand \"$it\"" },
            args.text("barcode")?.let { "barcode $it" }, args.text("notes")?.let { "notes" }
        ).joinToString(", ").ifBlank { "no changes" })
        ADD_SERVING.name -> listOf("Add serving to ${args.text("food_name") ?: "food"}: ${servingLine(args)}" + if (args.bool("make_default") == true) " (default)" else "")
        EDIT_SERVING.name -> listOf("Change serving of ${args.text("food_name") ?: "food"}: ${servingLine(args)}" + if (args.bool("make_default") == true) " (make default)" else "")
        DELETE_SERVING.name -> listOf("Delete a serving of ${args.text("food_name") ?: "food"} (${args.text("serving_id")?.take(8)}…)")
        SET_IMAGE.name -> listOf("Add photo ${args.num("image")?.toInt() ?: 1} to ${args.text("food_name") ?: "food"}" + if (args.bool("replace") == true) " (replacing its pictures)" else "")
        DELETE.name -> listOf((if (args.text("mode") == "hide") "Hide" else "Delete") + " food ${args.text("food_name") ?: args.text("food_id")}")
        else -> listOf(name)
    }

    /** Lines under a single change's headline: every serving of a new food, in plain words. */
    fun approvalDetails(name: String, args: JsonObject): List<String> = when (name) {
        CREATE.name -> foodsOf(args).singleOrNull()?.let { f ->
            (f["servings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { "Serving: ${servingLine(it)}" } +
                listOfNotNull(f.text("notes")?.let { "Notes: $it" }, f.num("image")?.let { "Picture: photo ${it.toInt()}" })
        }.orEmpty()
        else -> emptyList()
    }

    suspend fun run(name: String, args: JsonObject): ToolOutput = runCatching {
        when (name) {
            FIND.name -> find(args)
            GET.name -> get(args.req("food_id"))
            CREATE.name -> create(args)
            EDIT.name -> edit(args)
            ADD_SERVING.name -> addServing(args)
            EDIT_SERVING.name -> editServing(args)
            DELETE_SERVING.name -> api.deleteVariant(args.req("serving_id")).getOrThrow().let { ToolOutput("Deleted the serving.", false) }
            SET_IMAGE.name -> setImage(args)
            DELETE.name -> {
                val hide = args.text("mode") == "hide"
                api.deleteFood(args.req("food_id"), hideOnly = hide).getOrThrow()
                ToolOutput(if (hide) "Hidden from search (past diary entries kept)." else "Deleted from the library (past diary entries kept).", false)
            }
            READ_IMAGE.name -> readImage(args)
            else -> ToolOutput("Unknown tool $name.", true)
        }
    }.getOrElse { ToolOutput("Couldn't do that: ${it.message ?: it.javaClass.simpleName}", true) }

    // ----- reads -----

    private suspend fun find(args: JsonObject): ToolOutput {
        val q = args.text("query").orEmpty()
        val foods = api.listMyFoods(q, perPage = 25).getOrThrow()
        if (foods.isEmpty()) return ToolOutput("No foods of the user's own match \"$q\".", false)
        return ToolOutput("The user's own foods matching \"$q\" (default serving):\n" + foods.joinToString("\n") { f ->
            val n = f.nutrients
            "- ${f.name}${f.brand?.let { " ($it)" }.orEmpty()} · food_id ${f.id} · ${num(n.servingSize)} ${n.servingUnit}: ${num(n.calories)} kcal, P ${num(n.protein)} C ${num(n.carbs)} F ${num(n.fat)}"
        }, false)
    }

    private suspend fun get(foodId: String): ToolOutput {
        val food = api.foodJson(foodId).getOrThrow()
        val variants = api.fetchFoodVariants(foodId).getOrThrow()
        return ToolOutput(buildString {
            append("${food.text("name")}${food.text("brand")?.let { " ($it)" }.orEmpty()} · food_id $foodId\n")
            food.text("barcode")?.let { append("Barcode: $it\n") }
            food.text("notes")?.takeIf { it.isNotBlank() }?.let { append("Notes: $it\n") }
            val pics = (food["images"] as? JsonArray)?.size ?: 0
            append("Pictures: $pics\n")
            append("Servings:\n")
            variants.forEach { v ->
                append("- serving_id ${v.text("id")}${if (v.bool("is_default") == true) " (default)" else ""}: ")
                append("${num(v.num("serving_size"))} ${v.text("serving_unit")} = ")
                append(NUTRIENTS.mapNotNull { (key, column) -> v.num(column)?.takeIf { it != 0.0 || key in CORE }?.let { "$key ${num(it)}" } }.joinToString(", "))
                append('\n')
            }
        }.trimEnd(), false)
    }

    // ----- changes -----

    /** Creates each food with its first serving, then adds the others. One food's failure doesn't stop the rest. */
    private suspend fun create(args: JsonObject): ToolOutput {
        val foods = foodsOf(args)
        if (foods.isEmpty()) return ToolOutput("create_foods needs foods: each with a name and at least one serving.", true)
        val lines = foods.map { f -> runCatching { createOne(f) }.getOrElse { "✗ ${f.text("name") ?: "?"}: ${it.message}" } }
        val failed = lines.count { it.startsWith("✗") }
        return ToolOutput(lines.joinToString("\n"), failed == lines.size)
    }

    private suspend fun createOne(f: JsonObject): String {
        val name = f.req("name")
        val servings = (f["servings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (servings.isEmpty()) throw IllegalArgumentException("no servings given")
        val resolved = resolveServings(servings, existing = emptyList())
        val defaultIndex = (f.num("default_serving")?.toInt()?.minus(1) ?: 0).coerceIn(0, resolved.size - 1)
        val first = resolved[defaultIndex]
        val payload = JsonObject(variantColumns(first) + listOfNotNull(
            "name" to JsonPrimitive(name.trim()),
            "brand" to (f.text("brand")?.let { JsonPrimitive(it) } ?: JsonNull),
            "is_custom" to JsonPrimitive(true),
            "is_default" to JsonPrimitive(true),
            "is_quick_food" to JsonPrimitive(false),
            "source" to JsonPrimitive(if (f.bool("estimated") == true) "ai_estimate" else "imported"),
            f.text("notes")?.let { "notes" to JsonPrimitive(it) }
        ).toMap())
        val food = api.createFoodJson(payload).getOrThrow()
        val foodId = food.req("id")
        // A barcode is set afterwards: Sparky answers a create whose barcode it knows with that existing food
        f.text("barcode")?.takeIf { it.isNotBlank() }?.let { api.updateFoodJson(foodId, buildJsonObject { put("barcode", JsonPrimitive(it)) }) }
        val extra = resolved.filterIndexed { i, _ -> i != defaultIndex }.map { s ->
            api.createVariantJson(JsonObject(variantColumns(s) + mapOf("food_id" to JsonPrimitive(foodId), "is_default" to JsonPrimitive(false))))
                .fold({ "" }, { " (serving ${num(s.amount)} ${s.unit} failed: ${it.message})" })
        }
        val image = f.num("image")?.toInt()?.let { attachImage(foodId, it, emptyList()) }.orEmpty()
        return "✓ $name · food_id $foodId · ${resolved.size} serving${if (resolved.size == 1) "" else "s"}, default ${num(first.amount)} ${first.unit} = ${num(first.nutrients["calories"] ?: 0.0)} kcal" +
            extra.joinToString("") + image
    }

    private suspend fun edit(args: JsonObject): ToolOutput {
        val fields = buildJsonObject {
            args.text("name")?.let { put("name", JsonPrimitive(it.trim())) }
            if (args.containsKey("brand")) put("brand", args.text("brand")?.let { JsonPrimitive(it) } ?: JsonNull)
            if (args.containsKey("barcode")) put("barcode", args.text("barcode")?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull)
            if (args.containsKey("notes")) put("notes", args.text("notes")?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        if (fields.isEmpty()) return ToolOutput("Nothing to change: give name, brand, barcode or notes.", true)
        api.updateFoodJson(args.req("food_id"), fields).getOrThrow()
        return ToolOutput("Saved: ${fields.keys.joinToString(", ")}.", false)
    }

    private suspend fun addServing(args: JsonObject): ToolOutput {
        val foodId = args.req("food_id")
        val existing = existingServings(foodId)
        val s = resolveServings(listOf(args), existing).single()
        val made = api.createVariantJson(JsonObject(variantColumns(s) + mapOf(
            "food_id" to JsonPrimitive(foodId), "is_default" to JsonPrimitive(args.bool("make_default") == true)
        ))).getOrThrow()
        return ToolOutput("Added ${num(s.amount)} ${s.unit} = ${num(s.nutrients["calories"] ?: 0.0)} kcal" +
            (made.text("id")?.let { " · serving_id $it" }.orEmpty()) + if (args.bool("make_default") == true) " (now the default)" else "", false)
    }

    /** Changes a serving: its size, its unit, any nutrients given (the rest stay), and whether it's the default. */
    private suspend fun editServing(args: JsonObject): ToolOutput {
        val foodId = args.req("food_id"); val servingId = args.req("serving_id")
        val existing = existingServings(foodId)
        val current = existing.firstOrNull { it.id == servingId } ?: return ToolOutput("That food has no serving $servingId. Read it with get_food for its serving ids.", true)
        val fields = linkedMapOf<String, JsonElement>("food_id" to JsonPrimitive(foodId))
        val newAmount = args.num("amount"); val newUnit = args.text("unit")
        val sameAs = args["same_as"] as? JsonObject
        if (sameAs != null) {
            // Its size changes and its nutrition is worked out from another serving
            val s = resolveServings(listOf(buildJsonObject {
                put("amount", JsonPrimitive(newAmount ?: current.amount)); put("unit", JsonPrimitive(newUnit ?: current.unit)); put("same_as", sameAs)
            }), existing.filter { it.id != servingId }).single()
            fields += variantColumns(s)
        } else {
            newAmount?.let { fields["serving_size"] = JsonPrimitive(it) }
            newUnit?.let { fields["serving_unit"] = JsonPrimitive(it.trim()) }
            (args["nutrition"] as? JsonObject)?.let { n -> NUTRIENTS.forEach { (key, column) -> n.num(key)?.let { fields[column] = JsonPrimitive(it) } } }
            if (args.bool("scale_nutrition") == true && newAmount != null && current.amount > 0) {
                // Same food, different amount: every nutrient follows
                val k = newAmount / current.amount
                current.nutrients.forEach { (column, v) -> if (column !in fields) fields[column] = JsonPrimitive(round(v * k)) }
            }
        }
        args.bool("make_default")?.let { fields["is_default"] = JsonPrimitive(it) }
        api.updateVariantJson(servingId, JsonObject(fields)).getOrThrow()
        return ToolOutput("Saved the serving (${fields.keys.filter { it != "food_id" }.joinToString(", ")}).", false)
    }

    private suspend fun setImage(args: JsonObject): ToolOutput {
        val foodId = args.req("food_id")
        val which = args.num("image")?.toInt() ?: 1
        val keep = if (args.bool("replace") == true) emptyList()
            else (api.foodJson(foodId).getOrThrow()["images"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return ToolOutput(attachImage(foodId, which, keep).trim().ifBlank { "Added the picture." }, false)
    }

    private suspend fun attachImage(foodId: String, which: Int, keep: List<String>): String {
        val (data, type) = images.getOrNull(which - 1) ?: return " (no photo $which was attached, so no picture)"
        val bytes = java.util.Base64.getMimeDecoder().decode(data)
        return api.addFoodImages(foodId, keep, listOf(bytes to type)).fold({ " · picture added" }, { " (picture failed: ${it.message})" })
    }

    private suspend fun readImage(args: JsonObject): ToolOutput {
        val provider = vision ?: return ToolOutput("This model can't read photos.", true)
        val which = args.num("image")?.toInt() ?: 1
        val image = images.getOrNull(which - 1) ?: return ToolOutput("There's no photo $which attached.", true)
        val raw = provider.lookAt(FoodImageReader.instructions(args.text("focus")), listOf(image), FoodImageReader.SCHEMA, ask = "Read every food in this image.", maxTokens = 16000)
        return ToolOutput(FoodImageReader.toToolText(raw), false)
    }

    // ----- servings -----

    /** A serving ready to send: its size and unit, and its nutrients by Sparky column. */
    private data class Serving(val amount: Double, val unit: String, val nutrients: Map<String, Double>, val id: String? = null)

    private suspend fun existingServings(foodId: String): List<Serving> =
        api.fetchFoodVariants(foodId).getOrThrow().mapNotNull { v ->
            val amount = v.num("serving_size") ?: return@mapNotNull null
            Serving(amount, v.text("serving_unit").orEmpty(), NUTRIENTS.mapNotNull { (_, c) -> v.num(c)?.let { c to it } }.toMap(), v.text("id"))
        }

    /**
     * Each serving with its nutrients: given directly ("nutrition"), or worked out from another serving of the same
     * food ("same_as": this serving equals that amount of a unit the food already has, or that an earlier serving in
     * the same list has). Weights convert to weights and volumes to volumes (oz to g, cups to ml).
     */
    private fun resolveServings(servings: List<JsonObject>, existing: List<Serving>): List<Serving> {
        val done = mutableListOf<Serving>()
        servings.forEach { s ->
            val amount = s.num("amount")?.takeIf { it > 0 } ?: throw IllegalArgumentException("every serving needs an amount above 0")
            val unit = s.text("unit")?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("every serving needs a unit")
            val nutrition = s["nutrition"] as? JsonObject
            val sameAs = s["same_as"] as? JsonObject
            val nutrients: Map<String, Double> = when {
                nutrition != null -> {
                    val n = NUTRIENTS.mapNotNull { (key, column) -> nutrition.num(key)?.let { column to it } }.toMap()
                    if ("calories" !in n) throw IllegalArgumentException("serving $amount $unit has no calories")
                    n
                }
                sameAs != null -> {
                    val eqAmount = sameAs.num("amount") ?: throw IllegalArgumentException("same_as needs an amount")
                    val eqUnit = sameAs.text("unit") ?: throw IllegalArgumentException("same_as needs a unit")
                    // A serving it can be converted from: earlier in this list first, then the food's own
                    val base = (done + existing).firstNotNullOfOrNull { b -> UnitMath.convert(eqAmount, eqUnit, b.unit)?.let { b to it } }
                        ?: throw IllegalArgumentException("nothing to work out $amount $unit from: no serving in ${UnitMath.normalize(eqUnit)} or a unit that converts to it")
                    val (b, inBase) = base
                    val k = inBase / b.amount
                    b.nutrients.mapValues { (_, v) -> round(v * k) }
                }
                else -> throw IllegalArgumentException("serving $amount $unit needs nutrition or same_as")
            }
            done += Serving(amount, unit, nutrients)
        }
        return done
    }

    private fun variantColumns(s: Serving): Map<String, JsonElement> =
        linkedMapOf<String, JsonElement>("serving_size" to JsonPrimitive(s.amount), "serving_unit" to JsonPrimitive(s.unit)) +
            CORE_COLUMNS.associateWith { JsonPrimitive(s.nutrients[it] ?: 0.0) } +
            s.nutrients.filterKeys { it !in CORE_COLUMNS }.mapValues { JsonPrimitive(it.value) }

    private fun servingLine(s: JsonObject): String {
        val amount = s.num("amount")?.let(::num) ?: "?"
        val unit = s.text("unit") ?: ""
        val n = s["nutrition"] as? JsonObject
        val same = s["same_as"] as? JsonObject
        return when {
            n != null -> "$amount $unit: ${n.num("calories")?.let(::num) ?: "?"} kcal, P ${n.num("protein")?.let(::num) ?: "?"} C ${n.num("carbs")?.let(::num) ?: "?"} F ${n.num("fat")?.let(::num) ?: "?"}"
            same != null -> "$amount $unit = ${same.num("amount")?.let(::num)} ${same.text("unit")}"
            else -> "$amount $unit"
        }.trim()
    }

    private fun foodsOf(args: JsonObject): List<JsonObject> = (args["foods"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    companion object {
        /** Tool nutrient names (per serving) and the Sparky columns they're stored in. */
        val NUTRIENTS: List<Pair<String, String>> = listOf(
            "calories" to "calories", "protein" to "protein", "carbs" to "carbs", "fat" to "fat",
            "saturated_fat" to "saturated_fat", "trans_fat" to "trans_fat", "polyunsaturated_fat" to "polyunsaturated_fat",
            "monounsaturated_fat" to "monounsaturated_fat", "cholesterol_mg" to "cholesterol", "sodium_mg" to "sodium",
            "potassium_mg" to "potassium", "fiber" to "dietary_fiber", "sugar" to "sugars", "vitamin_a" to "vitamin_a",
            "vitamin_c" to "vitamin_c", "calcium_mg" to "calcium", "iron_mg" to "iron", "caffeine_mg" to "caffeine_mg"
        )
        private val CORE = setOf("calories", "protein", "carbs", "fat")
        private val CORE_COLUMNS = listOf("calories", "protein", "carbs", "fat")

        private fun prop(type: String, description: String) = buildJsonObject { put("type", JsonPrimitive(type)); put("description", JsonPrimitive(description)) }
        private fun obj(required: List<String>, vararg props: Pair<String, JsonObject>) = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", JsonObject(props.toMap()))
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
        private val nutritionSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("description", JsonPrimitive("Nutrition for this serving. calories, protein, carbs and fat in kcal and grams; the *_mg ones in milligrams; vitamin_a in mcg RAE, vitamin_c in mg. Leave out what isn't known."))
            put("properties", JsonObject(NUTRIENTS.associate { (k, _) -> k to buildJsonObject { put("type", JsonPrimitive("number")) } }))
            put("required", JsonArray(listOf(JsonPrimitive("calories"))))
        }
        private val sameAsSchema = obj(listOf("amount", "unit"),
            "amount" to prop("number", "How much of the other unit this serving equals."),
            "unit" to prop("string", "A unit the food already has (or an earlier serving in the list has), or one that converts to it: g, oz, lb, ml, fl oz, cup, tbsp, tsp."))
        private val servingSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("description", JsonPrimitive("One way to measure the food. Give nutrition, or same_as to work it out from another serving (\"1 roll\" same_as 2.75 oz)."))
            put("properties", JsonObject(mapOf(
                "amount" to prop("number", "Size of the serving, e.g. 6.2 or 1."),
                "unit" to prop("string", "Its unit: oz, g, ml, cup, serving, roll, piece…"),
                "nutrition" to nutritionSchema,
                "same_as" to sameAsSchema
            )))
            put("required", JsonArray(listOf(JsonPrimitive("amount"), JsonPrimitive("unit"))))
        }

        val FIND = ToolSpec("find_my_foods", "Search the user's own food library (their custom foods) by name. Returns food_ids and default servings. Use it before creating a food, to edit an existing one instead of making a copy.",
            obj(listOf("query"), "query" to prop("string", "Words in the food's name or brand.")))
        val GET = ToolSpec("get_food", "Read one of the user's foods: name, brand, barcode, notes, how many pictures, and every serving with its serving_id and full nutrition.",
            obj(listOf("food_id"), "food_id" to prop("string", "The food's id.")))
        val CREATE = ToolSpec("create_foods",
            "Create one or more foods in the user's library, each with all its serving sizes (e.g. per portion and per 100 g, or \"1 roll\" and ounces). " +
                "Every food in one call is shown to the user for approval together. Check find_my_foods first so you don't duplicate one.",
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject {
                    put("foods", buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put("items", buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put("properties", JsonObject(mapOf(
                                "name" to prop("string", "The food's name as the user would search it, e.g. \"Orange Chicken\"."),
                                "brand" to prop("string", "Brand or restaurant, e.g. \"Panda Express\"."),
                                "barcode" to prop("string", "UPC/EAN if known."),
                                "notes" to prop("string", "Anything worth keeping (source, what a portion is)."),
                                "servings" to buildJsonObject { put("type", JsonPrimitive("array")); put("items", servingSchema) },
                                "default_serving" to prop("integer", "Which serving (1-based) is the default. Default 1."),
                                "image" to prop("integer", "Number of an attached photo to use as the food's picture (only if the user wants that)."),
                                "estimated" to prop("boolean", "True when the nutrition is your estimate, not from a label or official table.")
                            )))
                            put("required", JsonArray(listOf(JsonPrimitive("name"), JsonPrimitive("servings"))))
                        })
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("foods"))))
            })
        val EDIT = ToolSpec("edit_food", "Change a food's name, brand, barcode or notes (only what you send; an empty barcode removes it).",
            obj(listOf("food_id"), "food_id" to prop("string", "The food's id."), "name" to prop("string", "New name."),
                "brand" to prop("string", "New brand."), "barcode" to prop("string", "New barcode."), "notes" to prop("string", "New notes.")))
        val ADD_SERVING = ToolSpec("add_serving", "Add another serving size to a food: with its own nutrition, or same_as an amount of a unit the food has (\"1 cup\" same_as 240 ml).",
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(mapOf(
                    "food_id" to prop("string", "The food's id."), "food_name" to prop("string", "The food's name (for the approval)."),
                    "amount" to prop("number", "Size of the serving."), "unit" to prop("string", "Its unit."),
                    "nutrition" to nutritionSchema, "same_as" to sameAsSchema,
                    "make_default" to prop("boolean", "Make it the food's default serving.")
                )))
                put("required", JsonArray(listOf("food_id", "amount", "unit").map { JsonPrimitive(it) }))
            })
        val EDIT_SERVING = ToolSpec("edit_serving",
            "Change one serving of a food: size, unit, any nutrients (others stay), or make it the default. scale_nutrition: true scales every nutrient with a new amount. same_as recalculates it from another serving.",
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(mapOf(
                    "food_id" to prop("string", "The food's id."), "serving_id" to prop("string", "The serving's id (from get_food)."),
                    "food_name" to prop("string", "The food's name (for the approval)."),
                    "amount" to prop("number", "New size."), "unit" to prop("string", "New unit."),
                    "nutrition" to buildJsonObject { put("type", JsonPrimitive("object")); put("description", JsonPrimitive("Only the nutrients that change, same names as in create_foods.")) },
                    "scale_nutrition" to prop("boolean", "Scale every nutrient with the new amount."),
                    "same_as" to sameAsSchema,
                    "make_default" to prop("boolean", "Make it the default serving.")
                )))
                put("required", JsonArray(listOf("food_id", "serving_id").map { JsonPrimitive(it) }))
            })
        val DELETE_SERVING = ToolSpec("delete_serving", "Delete one serving size of a food (not its last one).",
            obj(listOf("food_id", "serving_id"), "food_id" to prop("string", "The food's id."), "serving_id" to prop("string", "The serving's id."),
                "food_name" to prop("string", "The food's name (for the approval).")))
        val SET_IMAGE = ToolSpec("set_food_image", "Use a photo the user attached as a food's picture.",
            obj(listOf("food_id"), "food_id" to prop("string", "The food's id."), "food_name" to prop("string", "The food's name (for the approval)."),
                "image" to prop("integer", "Which attached photo (1 = first). Default 1."), "replace" to prop("boolean", "Replace the food's pictures instead of adding to them.")))
        val DELETE = ToolSpec("delete_food", "Delete a food from the user's library (past diary entries stay), or hide it from search.",
            obj(listOf("food_id"), "food_id" to prop("string", "The food's id."), "food_name" to prop("string", "The food's name (for the approval)."),
                "mode" to prop("string", "\"delete\" (default) or \"hide\".")))
        val READ_IMAGE = ToolSpec("read_food_image",
            "Read the foods in an attached photo: a restaurant nutrition table, a menu, a label or a recipe card. Returns every food with its portion and nutrition, " +
                "plus anything unclear. Use it before creating foods from a photo.",
            obj(emptyList(), "image" to prop("integer", "Which attached photo (1 = first). Default 1."),
                "focus" to prop("string", "What to read or how: e.g. \"only the chicken section\", or what the user said about it.")))

        val ALL = listOf(FIND, GET, CREATE, EDIT, ADD_SERVING, EDIT_SERVING, DELETE_SERVING, SET_IMAGE, DELETE, READ_IMAGE)
        private val NAMES = ALL.map { it.name }.toSet()
        private val WRITES = setOf(CREATE, EDIT, ADD_SERVING, EDIT_SERVING, DELETE_SERVING, SET_IMAGE, DELETE).map { it.name }.toSet()

        private fun round(v: Double) = Math.round(v * 100) / 100.0
        internal fun num(v: Double?): String = v?.let { d ->
            if (d == Math.floor(d) && kotlin.math.abs(d) < 1e9) d.toLong().toString() else String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')
        } ?: "?"
        private fun num(v: Float) = num(v.toDouble())
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() }
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.lowercase()?.let { if (it == "true") true else if (it == "false") false else null }
private fun JsonObject.req(key: String): String = text(key) ?: throw IllegalArgumentException("$key is needed")
