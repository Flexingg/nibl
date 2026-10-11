package com.bytelog.app.ui.logging

/**
 * Decides which camera sightings of a barcode count as a scan. The camera sees the same code many times a second, so a
 * code counts once while it stays in view; it counts again only after it has been out of view for [leaveMs] (the
 * user moved it away and back), and different codes need at least [switchMs] between them.
 */
class ScanGate(private val leaveMs: Long = 1500, private val switchMs: Long = 700) {
    private var code: String? = null
    private var lastSeen = Long.MIN_VALUE / 2
    private var lastAccepted = Long.MIN_VALUE / 2

    /** True if this sighting is a new scan of [barcode] at [now] (milliseconds). */
    fun accept(barcode: String, now: Long): Boolean {
        val sameCode = barcode == code
        val gap = now - lastSeen
        lastSeen = now                              // every sighting keeps a code "in view"
        if (sameCode && gap <= leaveMs) return false
        if (!sameCode && now - lastAccepted < switchMs) return false
        code = barcode
        lastAccepted = now
        return true
    }
}
