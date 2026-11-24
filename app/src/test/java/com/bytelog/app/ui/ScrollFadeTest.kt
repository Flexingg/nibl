package com.bytelog.app.ui

import com.bytelog.app.ui.components.scrolledAwayFade
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollFadeTest {
    @Test fun aRowStaysFullyShownUntilMoreThanHalfOfItIsGone() {
        assertEquals(0f, scrolledAwayFade(0f), 0f)
        assertEquals(0f, scrolledAwayFade(0.3f), 0f)
        assertEquals("half gone, still fully shown", 0f, scrolledAwayFade(0.5f), 0f)
        assertEquals(0.5f, scrolledAwayFade(0.75f), 0.001f)
        assertEquals(1f, scrolledAwayFade(0.95f), 0f)
        assertEquals(1f, scrolledAwayFade(2f), 0f)
    }
}
