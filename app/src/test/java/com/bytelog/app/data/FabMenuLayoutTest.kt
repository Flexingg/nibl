package com.bytelog.app.data

import com.bytelog.app.data.repository.FabAction
import com.bytelog.app.data.repository.FabMenuLayout
import com.bytelog.app.ui.diary.diaryMenuItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FabMenuLayoutTest {
    @Test fun theDefaultPutsSettingsOnTopAndSearchNextToThePlus() {
        val d = FabMenuLayout.DEFAULT
        assertEquals(FabAction.SETTINGS, d.order.first()); assertEquals(FabAction.SEARCH, d.order.last())
        assertTrue(d.isDefault)
        assertEquals(d, FabMenuLayout.parse(null)); assertEquals(d, FabMenuLayout.parse(""))
    }

    @Test fun anOrderSurvivesSavingAndHiddenActionsStayHidden() {
        val l = FabMenuLayout.DEFAULT.move(6, 0).toggle(FabAction.FAMILY, false)
        assertEquals(FabAction.SEARCH, l.order.first())
        assertEquals(l, FabMenuLayout.parse(l.encode()))
        assertTrue(FabAction.FAMILY !in l.visible)
    }

    @Test fun settingsCanNeverBeHidden() {
        val l = FabMenuLayout.DEFAULT.toggle(FabAction.SETTINGS, false)
        assertTrue(FabAction.SETTINGS in l.visible)
        assertTrue(FabAction.SETTINGS in FabMenuLayout.parse("-settings,search").visible)
    }

    @Test fun unknownKeysDropAndNewActionsAppear() {
        val l = FabMenuLayout.parse("search,teleport,scan,search")
        // The saved ones keep their order; the rest come back at their default spots
        assertTrue(l.order.indexOf(FabAction.SEARCH) < l.order.indexOf(FabAction.SCAN))
        assertEquals(FabAction.SETTINGS, l.order.first())
        assertEquals(FabAction.entries.toSet(), l.order.toSet())
        assertEquals(FabAction.entries.size, l.order.size)
    }

    @Test fun theMenuFollowsTheOrderAndSkipsWhatTheAppCantDo() {
        var opened = ""
        val layout = FabMenuLayout.parse("search,family,settings,-ask")
        val items = diaryMenuItems(layout, mapOf(
            FabAction.SEARCH to { opened = "search" }, FabAction.SETTINGS to { opened = "settings" }, FabAction.ASK to { opened = "ask" }
        ))
        // Family has no handler (not set up), Ask is hidden
        assertEquals(listOf("Search food", "Settings"), items.map { it.label })
        items[1].onClick(); assertEquals("settings", opened)
    }
}
