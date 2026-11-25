package com.bytelog.app.ui

import com.bytelog.app.ui.fasting.FastEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FastEditTest {
    private val now = Instant.parse("2026-11-25T12:00:00Z")
    private fun at(s: String) = Instant.parse(s)

    @Test fun sensibleTimesSave() {
        assertNull(FastEdit.problem(at("2026-11-24T20:00:00Z"), at("2026-11-25T12:00:00Z"), now))
        assertNull("an ongoing fast only needs a start", FastEdit.problem(at("2026-11-25T08:00:00Z"), null, now))
        assertEquals("16h 0m", FastEdit.length(at("2026-11-24T20:00:00Z"), at("2026-11-25T12:00:00Z")))
        assertEquals("45m", FastEdit.length(at("2026-11-25T11:00:00Z"), at("2026-11-25T11:45:00Z")))
    }

    @Test fun impossibleTimesDont() {
        assertTrue(FastEdit.problem(at("2026-11-25T10:00:00Z"), at("2026-11-25T09:00:00Z"), now)!!.contains("end after it starts"))
        assertTrue(FastEdit.problem(at("2026-11-25T10:00:00Z"), at("2026-11-25T13:00:00Z"), now)!!.contains("future"))
        assertTrue(FastEdit.problem(at("2026-11-25T13:00:00Z"), null, now)!!.contains("future"))
        assertTrue(FastEdit.problem(at("2026-11-01T10:00:00Z"), at("2026-11-20T10:00:00Z"), now)!!.contains("two weeks"))
    }
}
