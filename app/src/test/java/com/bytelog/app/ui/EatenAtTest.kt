package com.bytelog.app.ui

import com.bytelog.app.ui.components.EatenAt
import com.bytelog.app.ui.components.EatenAtText
import com.bytelog.app.ui.screens.eatenTimePrefix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class EatenAtTest {
    private val today = LocalDate.of(2026, 11, 25)

    @Test fun labelsReadNaturally() {
        assertEquals("Today · now", EatenAtText.label(EatenAt(today, null), today, use24h = false, nowWhenToday = true))
        assertEquals("Today", EatenAtText.label(EatenAt(today, null), today, use24h = false))
        assertEquals("Yesterday · 7:30 PM", EatenAtText.label(EatenAt(today.minusDays(1), LocalTime.of(19, 30)), today, false))
        assertEquals("Mon, Nov 23 · 08:05", EatenAtText.label(EatenAt(today.minusDays(2), LocalTime.of(8, 5)), today, true))
        assertEquals("Dec 31, 2025", EatenAtText.label(EatenAt(LocalDate.of(2025, 12, 31), null), today, false))
    }

    @Test fun timesAreReadFromAnyShapeSparkySends() {
        assertEquals(LocalTime.of(8, 15), EatenAtText.parse("08:15"))
        assertEquals(LocalTime.of(8, 15, 30), EatenAtText.parse("08:15:30"))
        assertEquals(LocalTime.of(21, 5), EatenAtText.parse("2026-11-25T21:05:00.000Z"))
        assertNull(EatenAtText.parse(""))
        assertNull(EatenAtText.parse("soon"))
        assertEquals("08:15:00", EatenAtText.stored(LocalTime.of(8, 15, 42)))
    }

    @Test fun diaryRowsShowTheTimeWhenThereIsOne() {
        assertEquals("8:15 AM • ", eatenTimePrefix("08:15:00", use24h = false))
        assertEquals("", eatenTimePrefix(null, use24h = false))
    }
}
