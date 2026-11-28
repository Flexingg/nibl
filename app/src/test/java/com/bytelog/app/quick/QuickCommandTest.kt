package com.bytelog.app.quick

import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.widget.WidgetDestination
import com.bytelog.app.widget.WidgetLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickCommandTest {

    @Test fun readsAskLinks() {
        val r = QuickCommands.parseUrl("nibl://ask?q=what%20should%20I%20eat&key=abc")!!
        assertEquals(QuickCommand.Ask("what should I eat"), r.command)
        assertEquals("abc", r.key)
        assertEquals(QuickCommand.Ask("protein left today", web = true), QuickCommands.parseUrl("NIBL://ask?web=1&q=protein+left+today")!!.command)
        // The text in the path works too
        assertEquals(QuickCommand.Ask("hi there"), QuickCommands.parseUrl("nibl://ask/hi%20there")!!.command)
    }

    @Test fun readsFoodSearchAndChatLinks() {
        assertEquals(QuickCommand.FindFood("greek yogurt"), QuickCommands.parseUrl("nibl://food?q=greek%20yogurt")!!.command)
        assertEquals(QuickCommand.OpenSearch("eggs"), QuickCommands.parseUrl("nibl://search?query=eggs")!!.command)
        assertEquals(QuickCommand.OpenChat("plan my week"), QuickCommands.parseUrl("nibl://chat?q=plan%20my%20week")!!.command)
        // Nothing to look up: open the screen instead
        assertEquals(QuickCommand.OpenSearch(""), QuickCommands.parseUrl("nibl://food?q=")!!.command)
        assertEquals(QuickCommand.OpenChat(""), QuickCommands.parseUrl("nibl://ask")!!.command)
    }

    @Test fun ignoresOtherLinks() {
        assertNull(QuickCommands.parseUrl("https://example.com/ask?q=x"))
        assertNull(QuickCommands.parseUrl("nibl://delete?q=x"))
        assertNull(QuickCommands.parseUrl("not a link"))
    }

    @Test fun keepsTextALauncherDidNotEncode() {
        assertEquals(QuickCommand.FindFood("50% fat"), QuickCommands.parseUrl("nibl://food?q=50% fat")!!.command)
        // An unfilled placeholder is no search
        assertEquals(QuickCommand.OpenSearch(""), QuickCommands.parseUrl("nibl://food?q=%s")!!.command)
    }

    @Test fun readsIntents() {
        val r = QuickCommands.fromAction(QuickCommands.ACTION_ASK, "  log   2 eggs ", "k1", web = false)!!
        assertEquals(QuickCommand.Ask("log 2 eggs"), r.command)
        assertEquals("k1", r.key)
        assertEquals(QuickCommand.FindFood("oats"), QuickCommands.fromAction(QuickCommands.ACTION_FIND_FOOD, "oats", null)!!.command)
        assertNull(QuickCommands.fromAction("android.intent.action.MAIN", "x", null))
    }

    @Test fun askingNeedsTheRightKey() {
        val ask = QuickRequest(QuickCommand.Ask("delete everything"), key = "right")
        assertEquals(QuickCommand.Ask("delete everything"), QuickCommands.authorize(ask, "right"))
        // Wrong, missing or not set up: the question is typed into the chat instead of sent
        assertEquals(QuickCommand.OpenChat("delete everything"), QuickCommands.authorize(ask, "wrong"))
        assertEquals(QuickCommand.OpenChat("delete everything"), QuickCommands.authorize(ask.copy(key = null), "right"))
        assertEquals(QuickCommand.OpenChat("delete everything"), QuickCommands.authorize(ask, null))
        // Finding a food needs none
        val find = QuickRequest(QuickCommand.FindFood("eggs"), key = null)
        assertEquals(QuickCommand.FindFood("eggs"), QuickCommands.authorize(find, null))
    }

    @Test fun widgetLinksCarryTheSearchAndDraft() {
        val s = WidgetDestination.Search("Lunch", "chicken: grilled")
        assertEquals(s, WidgetLinks.parse(WidgetLinks.encode(s)))
        assertEquals(WidgetDestination.Search("Dinner"), WidgetLinks.parse(WidgetLinks.encode(WidgetDestination.Search("Dinner"))))
        val c = WidgetDestination.Chat("what's left: protein?")
        assertEquals(c, WidgetLinks.parse(WidgetLinks.encode(c)))
    }

    @Test fun foodsSurviveTheTripOnAButton() {
        val f = FoodItem(id = "off_123", name = "Greek Yogurt", brand = "Fage", servingSize = 170f, servingUnit = "g",
            calories = 100f, protein = 18f, carbs = 6f, fat = 0f, householdServings = "cup|245|1")
        val back = QuickFoodCodec.decode(QuickFoodCodec.encode(f))!!
        assertEquals(f.copy(lastUsedAt = back.lastUsedAt), back)
        assertNull(QuickFoodCodec.decode("{broken"))
    }

    @Test fun notificationsGetPlainText() {
        assertEquals("Protein: 120 g\n• eggs\n• toast", QuickNotifications.plain("**Protein:** 120 g\n- eggs\n- *toast*"))
        assertEquals("See USDA", QuickNotifications.plain("See [USDA](https://usda.gov)"))
    }
}
