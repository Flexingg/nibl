package com.bytelog.app.data.receipts

import com.bytelog.app.data.local.model.FoodItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReceiptParsingTest {
    private val costco = """
        COSTCO
        WHOLESALE
        Kirkland #123
        1234 MAIN ST SEATTLE WA
        11/12/26 14:32
        E 1234567 KS GRK YOG 48OZ   6.49 N
        E 987654 CHKN BRST BNLS 6.89LB   18.99 N
        E 555555 ORG BNNA 3LB    2.49 N
        0000012345 / 987654   3.00-
        E 22222 COFFEE BEANS 2.5LB   15.99 N
        2 @ 4.99
        E 33333 ALMOND MILK 1/2GAL  9.98 N
        SUBTOTAL  50.94
        TAX  0.00
        **** TOTAL  50.94
        VISA TEND  50.94
        CHANGE DUE 0.00
        TOTAL NUMBER OF ITEMS SOLD = 6
        THANK YOU
    """.trimIndent().lines()

    @Test fun readsACostcoStyleReceipt() {
        val r = ReceiptParser.parseRows(costco)
        assertEquals("Costco", r.store)
        assertEquals(LocalDate.of(2026, 11, 12), r.date)
        assertEquals(50.94f, r.total)
        assertEquals(listOf("KS GRK YOG", "CHKN BRST BNLS", "ORG BNNA", "COFFEE BEANS", "ALMOND MILK"), r.items.map { it.name })
        val yog = r.items[0]
        assertEquals(6.49f, yog.price); assertEquals(PackSize(48f, "oz"), yog.size)
        // The instant savings line comes off the chicken
        assertEquals(15.99f, r.items[1].price, 0.001f)
        // COFFEE is not a discount, whatever its OFF
        assertEquals(15.99f, r.items[3].price)
        // "2 @ 4.99" belongs to the almond milk below it
        assertEquals(2, r.items[4].count); assertEquals(4.99f, r.items[4].unitPrice, 0.001f)
    }

    @Test fun weighedItemsAndNamesOnTheirOwnLine() {
        val r = ReceiptParser.parseRows("""
            KROGER
            BANANAS
            2.10 lb @ 0.59 /lb   1.24
            HONEYCRISP APPLES   4.20
            2.34 lb @ 1.79/lb
            LARGE EGGS 12CT   3.29 F
            SC   KRO PLUS SAVINGS   0.50-
            BALANCE DUE   8.23
        """.trimIndent().lines())
        assertEquals("Kroger", r.store)
        assertEquals(listOf("BANANAS", "HONEYCRISP APPLES", "LARGE EGGS"), r.items.map { it.name })
        assertEquals(PackSize(2.10f, "lb"), r.items[0].weight); assertEquals(0.59f, r.items[0].pricePerWeightUnit)
        assertEquals(1.24f, r.items[0].price)
        assertEquals(PackSize(2.34f, "lb"), r.items[1].weight)
        assertEquals(PackSize(12f, "each"), r.items[2].size)
        assertEquals(2.79f, r.items[2].price, 0.001f)
    }

    @Test fun rowsAreBuiltFromWhereTheTextSits() {
        // The recogniser gives the name and the price as separate lines on the same row
        val lines = listOf(
            OcrLine("TRADER JOE'S", 100, 10, 400, 40),
            OcrLine("4.49", 600, 101, 680, 129), OcrLine("ORGANIC SPINACH", 20, 100, 300, 130),
            OcrLine("BROWN RICE", 20, 150, 250, 178), OcrLine("2.99", 600, 152, 680, 180)
        )
        assertEquals(listOf("TRADER JOE'S", "ORGANIC SPINACH  4.49", "BROWN RICE  2.99"), ReceiptParser.rows(lines))
        val r = ReceiptParser.parse(lines)
        assertEquals("Trader Joe's", r.store)
        assertEquals(listOf(4.49f, 2.99f), r.items.map { it.price })
    }

    @Test fun nothingUsefulIsAnEmptyReceipt() {
        val r = ReceiptParser.parseRows(listOf("hello", "world"))
        assertTrue(r.items.isEmpty()); assertNull(r.total)
    }

    private fun cand(name: String, brand: String? = null, onList: Boolean = false) =
        MatchCandidate("k:$name", null, name, brand, onList, FoodItem("id-$name", name))

    @Test fun abbreviatedNamesFindTheirFoods() {
        val foods = listOf(
            cand("Greek Yogurt", "Fage", onList = true), cand("Plain Yogurt"), cand("Chicken Breast", onList = true), cand("Chicken Thighs"),
            cand("Banana", onList = true), cand("Bread"), cand("Coffee"), cand("Almond Milk"), cand("Whole Milk"), cand("Large Eggs"),
            cand("Brown Rice"), cand("Spinach"), cand("Apples")
        )
        fun best(n: String) = ReceiptMatcher.matches(n, foods).firstOrNull()?.candidate?.name
        assertEquals("Greek Yogurt", best("KS GRK YOG"))
        assertEquals("Chicken Breast", best("CHKN BRST BNLS"))
        assertEquals("Banana", best("ORG BNNA"))
        assertEquals("Coffee", best("COFFEE BEANS"))
        assertEquals("Almond Milk", best("ALMOND MILK"))
        assertEquals("Whole Milk", best("WHL MLK"))
        assertEquals("Large Eggs", best("LARGE EGGS"))
        assertEquals("Spinach", best("ORGANIC SPINACH"))
        assertEquals("Apples", best("HONEYCRISP APPLES"))
        assertNull("nothing like it", best("PAPER TOWELS"))
        assertNull(best("BATTERIES AA"))
    }
}
