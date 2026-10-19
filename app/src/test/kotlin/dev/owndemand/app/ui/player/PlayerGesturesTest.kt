package dev.owndemand.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGesturesTest {

    @Test
    fun `quick same-direction steps accumulate into one bubble`() {
        var b: SeekBurst? = null
        listOf(0L, 300L, 600L, 900L).forEach { t -> b = nextSeekBurst(b, forward = true, nowMs = t) }
        assertEquals(40_000L, b!!.totalMs)
        assertEquals("+40s", b!!.label)
    }

    @Test
    fun `a step after the bubble expired starts over`() {
        val first = nextSeekBurst(null, forward = true, nowMs = 0)
        val later = nextSeekBurst(first, forward = true, nowMs = SEEK_BURST_WINDOW_MS + 1)
        assertEquals(SEEK_STEP_MS, later.totalMs)
    }

    @Test
    fun `changing direction starts a new count`() {
        var b = nextSeekBurst(null, forward = true, nowMs = 0)
        b = nextSeekBurst(b, forward = true, nowMs = 200)
        b = nextSeekBurst(b, forward = false, nowMs = 400)
        assertFalse(b.forward)
        assertEquals("−10s", b.label)
    }

    @Test
    fun `burst is live only inside the window`() {
        val b = SeekBurst(forward = true, totalMs = 10_000, lastAtMs = 1_000)
        assertTrue(b.isLive(1_000 + SEEK_BURST_WINDOW_MS))
        assertFalse(b.isLive(1_001 + SEEK_BURST_WINDOW_MS))
    }

    @Test
    fun `drag classification`() {
        assertEquals(PlayerDrag.Undecided, classifyPlayerDrag(5f, -5f, fullscreen = false))
        assertEquals(PlayerDrag.Dock, classifyPlayerDrag(4f, 40f, fullscreen = false))
        assertEquals(PlayerDrag.Fullscreen, classifyPlayerDrag(4f, -40f, fullscreen = false))
        // Upward in fullscreen is not ours; sideways belongs to the scrubber.
        assertEquals(PlayerDrag.Ignore, classifyPlayerDrag(4f, -40f, fullscreen = true))
        assertEquals(PlayerDrag.Ignore, classifyPlayerDrag(40f, -10f, fullscreen = false))
        // Mostly sideways upward drag is not a fullscreen swipe.
        assertEquals(PlayerDrag.Ignore, classifyPlayerDrag(50f, -30f, fullscreen = false))
        // Downward still docks in fullscreen, as before.
        assertEquals(PlayerDrag.Dock, classifyPlayerDrag(0f, 30f, fullscreen = true))
    }
}
