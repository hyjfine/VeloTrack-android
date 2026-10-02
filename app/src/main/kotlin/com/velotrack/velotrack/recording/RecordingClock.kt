package com.velotrack.velotrack.recording

/** Active recording time, independent of wall-clock changes and time spent paused. */
class RecordingClock(private val now: () -> Long) {
    private var accumulated = 0L
    private var anchor: Long? = null
    val elapsedMs: Long get() = accumulated + (anchor?.let { (now() - it).coerceAtLeast(0L) } ?: 0L)

    fun restore(elapsedMs: Long = 0L) {
        accumulated = elapsedMs.coerceAtLeast(0L)
        anchor = null
    }

    fun resume() { if (anchor == null) anchor = now() }

    fun pause() {
        accumulated = elapsedMs
        anchor = null
    }
}
