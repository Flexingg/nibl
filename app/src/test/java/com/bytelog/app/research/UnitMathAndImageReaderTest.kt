package com.bytelog.app.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitMathAndImageReaderTest {
    @Test fun convertsWeightsToWeightsAndVolumesToVolumes() {
        assertEquals(161.59, UnitMath.convert(5.7, "oz", "g")!!, 0.01)
        assertEquals(1.0, UnitMath.convert(16.0, "Ounces", "lb")!!, 1e-9)
        assertEquals(240.0, UnitMath.convert(1.0, "cup", "ml")!!, 1e-9)
        assertEquals(3.0, UnitMath.convert(1.0, "tbsp", "tsp")!!, 1e-6)
        assertEquals(8.115, UnitMath.convert(1.0, "cup", "fl oz")!!, 0.001)
        assertNull("a weight isn't a volume", UnitMath.convert(1.0, "oz", "ml"))
        assertNull(UnitMath.convert(1.0, "roll", "g"))
        assertEquals("counts match their plural", 2.0, UnitMath.convert(2.0, "Rolls", "roll")!!, 0.0)
        assertNull(UnitMath.convert(1.0, "roll", "piece"))
        assertTrue(UnitMath.isMeasure("FL OZ")); assertTrue(!UnitMath.isMeasure("serving"))
    }

    @Test fun readsANutritionTableIntoLinesForTheAssistant() {
        val raw = """Here you go: {"brand":"Panda Express","kind":"nutrition table","foods":[
            {"name":"Chow Mein","section":"Base","amount":9.4,"unit":"oz","portion_text":"9.4","calories":510,"protein":13,"carbs":80,"fat":20,"saturated_fat":3.5,"trans_fat":0,"cholesterol_mg":0,"sodium_mg":860,"fiber":6,"sugar":9,"potassium_mg":null,"calcium_mg":null,"iron_mg":null,"vitamin_a":null,"vitamin_c":null,"caffeine_mg":null,"unclear":null},
            {"name":"Orange Chicken","section":"Chicken","amount":5.7,"unit":"oz","portion_text":"5.7","calories":490,"protein":25,"carbs":51,"fat":23,"saturated_fat":5,"trans_fat":0,"cholesterol_mg":90,"sodium_mg":820,"fiber":0,"sugar":19,"potassium_mg":null,"calcium_mg":null,"iron_mg":null,"vitamin_a":null,"vitamin_c":null,"caffeine_mg":null,"unclear":"sugar digit blurry"},
            {"name":"Chicken Egg Roll","section":"Appetizers","amount":null,"unit":null,"portion_text":null,"calories":200,"protein":6,"carbs":20,"fat":10,"saturated_fat":null,"trans_fat":null,"cholesterol_mg":null,"sodium_mg":null,"fiber":null,"sugar":null,"potassium_mg":null,"calcium_mg":null,"iron_mg":null,"vitamin_a":null,"vitamin_c":null,"caffeine_mg":null,"unclear":null}
        ],"questions":["Is the egg roll per roll?"],"notes":null}"""
        val reading = FoodImageReader.parse(raw)!!
        assertEquals(3, reading.rows.size)
        assertEquals(mapOf("calories" to 510.0, "protein" to 13.0, "carbs" to 80.0, "fat" to 20.0, "saturated_fat" to 3.5, "trans_fat" to 0.0,
            "cholesterol_mg" to 0.0, "sodium_mg" to 860.0, "fiber" to 6.0, "sugar" to 9.0), reading.rows[0].nutrients)
        val text = FoodImageReader.toToolText(raw)
        assertTrue(text, text.startsWith("Read 3 foods from the photo (nutrition table), brand: Panda Express."))
        assertTrue(text, text.contains("[Base]\n1. Chow Mein — 9.4 oz: calories 510, protein 13"))
        assertTrue(text, text.contains("(unclear: sugar digit blurry)"))
        assertTrue(text, text.contains("[Appetizers]\n3. Chicken Egg Roll — portion not shown"))
        assertTrue(text, text.contains("1 food(s) have no portion size"))
        assertTrue(text, text.contains("- Is the egg roll per roll?"))
        assertTrue(FoodImageReader.toToolText("sorry").startsWith("Couldn't read foods"))
    }
}
