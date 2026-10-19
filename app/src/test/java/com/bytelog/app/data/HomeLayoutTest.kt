package com.bytelog.app.data

import com.bytelog.app.data.repository.HomeLayout
import com.bytelog.app.data.repository.HomeSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    @Test fun storesOrderAndHiddenSectionsAndReadsThemBack() {
        val layout = HomeLayout.DEFAULT.move(HomeSection.values().indexOf(HomeSection.TRENDS), 0).toggle(HomeSection.FAMILY, visible = false)
        assertEquals(HomeSection.TRENDS, layout.order.first())
        assertTrue(HomeSection.FAMILY !in layout.visible)
        assertEquals(layout, HomeLayout.parse(layout.encode()))
        assertTrue(layout.encode().contains("-family"))
    }

    @Test fun emptyOrBrokenValuesFallBackToTheDefault() {
        assertEquals(HomeLayout.DEFAULT, HomeLayout.parse(null))
        assertEquals(HomeLayout.DEFAULT, HomeLayout.parse(""))
        assertEquals(HomeLayout.DEFAULT, HomeLayout.parse("nonsense,,meals,meals"))
        assertTrue(HomeLayout.DEFAULT.isDefault)
    }

    @Test fun sectionsAddedLaterSlotInAtTheirDefaultPlace() {
        // Saved before "What fits?" and the trends card existed (and with an unknown section from the future)
        val old = HomeLayout.parse("meals,quick_meals,fridge,someday_section")
        assertEquals(HomeSection.values().size, old.order.size)
        assertEquals(listOf(HomeSection.WEEKLY_REVIEW, HomeSection.MEALS), old.order.take(2))
        assertTrue(old.order.indexOf(HomeSection.MEALS) < old.order.indexOf(HomeSection.QUICK_LOG))
    }

    @Test fun mealsCannotBeHidden() {
        assertTrue(HomeSection.MEALS in HomeLayout.DEFAULT.toggle(HomeSection.MEALS, visible = false).visible)
        assertTrue(HomeSection.MEALS in HomeLayout.parse("-meals").visible)
    }

    @Test fun movingKeepsEverySectionOnce() {
        var l = HomeLayout.DEFAULT
        l = l.move(0, 6).move(6, 2).move(3, 3).move(-1, 2).move(1, 99)
        assertEquals(HomeSection.values().toSet(), l.order.toSet())
        assertEquals(HomeSection.values().size, l.order.size)
    }
}
