package com.bytelog.app.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickParseTest {
    private fun url(u: String) = QuickCommands.parseUrl(u)!!.command

    @Test fun water() {
        assertEquals(QuickCommand.AddWater(16f), url("nibl://water?q=16"))
        assertEquals(QuickCommand.AddWater(16f), url("nibl://water?oz=16%20oz"))
        assertEquals(QuickCommand.AddWater(16.9f), url("nibl://water?q=500ml"))
        assertEquals(QuickCommand.AddWater(16f), url("nibl://water?q=2%20cups"))
        assertEquals(QuickCommand.AddWater(33.8f), url("nibl://water?q=1%20L"))
        // Nothing (or the unfilled %s): your usual container
        assertEquals(QuickCommand.AddWater(null), url("nibl://water"))
        assertEquals(QuickCommand.AddWater(null), url("nibl://water?q=%s"))
        assertEquals(QuickCommand.UndoWater, url("nibl://water?q=undo"))
        assertEquals(QuickCommand.UndoWater, url("nibl://water/remove"))
        assertTrue(url("nibl://water?q=lots") is QuickCommand.Unclear)
        assertTrue(url("nibl://water?q=5000") is QuickCommand.Unclear)
    }

    @Test fun weight() {
        assertEquals(QuickCommand.LogWeight(182.4f), url("nibl://weight?q=182.4"))
        assertEquals(QuickCommand.LogWeight(82.5f, "kg"), url("nibl://weight?q=82,5%20kg"))
        assertEquals(QuickCommand.LogWeight(182f, "lbs", 18.5f), url("nibl://weight?q=182lbs%2018.5%25"))
        assertEquals(QuickCommand.LogWeight(182f, null, 18f), url("nibl://weight?q=182 bf 18"))
        assertTrue(url("nibl://weight?q=heavy") is QuickCommand.Unclear)
        assertTrue(url("nibl://weight?q=5") is QuickCommand.Unclear)
    }

    @Test fun fasting() {
        assertEquals(QuickCommand.FastStatus, url("nibl://fast"))
        assertEquals(QuickCommand.StartFast(null), url("nibl://fast?q=start"))
        assertEquals(QuickCommand.StartFast("18-6"), url("nibl://fast/start?q=18:6"))
        assertEquals(QuickCommand.StartFast("20-4"), url("nibl://fast?q=20-4"))
        assertEquals(QuickCommand.StartFast("23-1"), url("nibl://fast?q=start%20omad"))
        assertEquals(QuickCommand.EndFast, url("nibl://fast/stop"))
        assertEquals(QuickCommand.EndFast, url("nibl://fast?q=break"))
        assertTrue(url("nibl://fast?q=banana") is QuickCommand.Unclear)
    }

    @Test fun caffeine() {
        assertEquals(QuickCommand.LogCaffeine("Coffee", 95f), url("nibl://caffeine?q=coffee%2095"))
        assertEquals(QuickCommand.LogCaffeine("Energy drink", 160f), url("nibl://caffeine?q=energy drink 160mg"))
        assertEquals(QuickCommand.LogCaffeine("Coffee", 120f), url("nibl://coffee?q=120"))
        assertEquals(QuickCommand.LogCaffeine("Caffeine", 50f), url("nibl://caffeine?mg=50"))
        assertTrue(url("nibl://caffeine?q=coffee") is QuickCommand.Unclear)
    }

    @Test fun todayAndIntents() {
        assertEquals(QuickCommand.Today, url("nibl://today"))
        assertEquals(QuickCommand.AddWater(12f), QuickCommands.fromAction(QuickCommands.ACTION_WATER, "12", null)!!.command)
        assertEquals(QuickCommand.EndFast, QuickCommands.fromAction(QuickCommands.ACTION_FAST, "stop", null)!!.command)
    }

    @Test fun changesNeedTheKeyOrAConfirmation() {
        val water = QuickRequest(QuickCommand.AddWater(16f), key = null)
        assertTrue(QuickCommands.needsConfirm(water, "k"))
        assertTrue(QuickCommands.needsConfirm(water, null))
        assertFalse(QuickCommands.needsConfirm(water.copy(key = "k"), "k"))
        // Showing things needs neither
        assertFalse(QuickCommands.needsConfirm(QuickRequest(QuickCommand.Today, null), null))
        assertFalse(QuickCommands.needsConfirm(QuickRequest(QuickCommand.FastStatus, null), null))
        // A question without the key still opens the chat rather than asking for a confirmation
        assertFalse(QuickCommands.needsConfirm(QuickRequest(QuickCommand.Ask("hi"), null), "k"))
    }

    @Test fun commandsSurviveTheTripOnAButton() {
        listOf(QuickCommand.AddWater(16f), QuickCommand.UndoWater, QuickCommand.LogWeight(80f, "kg", 15f),
            QuickCommand.StartFast("18-6"), QuickCommand.EndFast, QuickCommand.LogCaffeine("Tea", 40f), QuickCommand.Today
        ).forEach { assertEquals(it, QuickTasks.decode(QuickTasks.encode(it))) }
    }
}
