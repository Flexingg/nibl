package com.bytelog.app.ui.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanGateTest {
    @Test fun aCodeHeldInViewCountsOnce() {
        val g = ScanGate()
        assertTrue(g.accept("111", 0))
        // the camera sees it ~30 times a second for several seconds
        for (t in 33..6000 step 33) assertFalse("sighting at $t ms", g.accept("111", t.toLong()))
    }

    @Test fun movingTheCodeAwayAndBackScansItAgain() {
        val g = ScanGate()
        assertTrue(g.accept("111", 0))
        assertFalse(g.accept("111", 400))
        assertTrue("gone for 2 s", g.accept("111", 2400))
        assertFalse(g.accept("111", 2433))
    }

    @Test fun aDifferentCodeCountsButNotInstantly() {
        val g = ScanGate()
        assertTrue(g.accept("111", 0))
        assertFalse("jitter from a half-read second code", g.accept("222", 100))
        assertTrue(g.accept("222", 900))
        assertFalse(g.accept("222", 933))
    }
}
