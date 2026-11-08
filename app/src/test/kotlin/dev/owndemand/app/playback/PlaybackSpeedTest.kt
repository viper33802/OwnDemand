package dev.owndemand.app.playback

import dev.owndemand.app.ui.player.speedLabel
import dev.owndemand.app.ui.tv.tvSpeedLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedTest {

    @Test
    fun `slider has a stop every 5 percent`() {
        // 0.5..2 in 0.05 steps = 31 positions = 29 inner stops.
        assertEquals(29, PlaybackSpeed.SLIDER_STEPS)
    }

    @Test
    fun `snap lands on 5 percent stops and stays in range`() {
        assertEquals(1.1f, PlaybackSpeed.snap(1f + 0.05f + 0.05f))
        assertEquals(1.05f, PlaybackSpeed.snap(1.0612f))
        assertEquals(1.1f, PlaybackSpeed.snap(1.08f))
        assertEquals(0.5f, PlaybackSpeed.snap(0.1f))
        assertEquals(2f, PlaybackSpeed.snap(3f))
    }

    @Test
    fun `step moves one stop and clamps at the ends`() {
        var s = 1f
        repeat(4) { s = PlaybackSpeed.step(s, 1) }
        assertEquals(1.2f, s)
        assertEquals(0.95f, PlaybackSpeed.step(1f, -1))
        assertEquals(2f, PlaybackSpeed.step(2f, 1))
        assertEquals(0.5f, PlaybackSpeed.step(0.5f, -1))
    }

    @Test
    fun `labels have no float noise or trailing zeros`() {
        assertEquals("1", PlaybackSpeed.number(1f))
        assertEquals("1.05", PlaybackSpeed.number(1.05f))
        assertEquals("1.1", PlaybackSpeed.number(1f + 0.1f + 0.0000001f))
        assertEquals("0.95", PlaybackSpeed.number(0.95f))
        assertEquals("1.15x", speedLabel(PlaybackSpeed.step(1.1f, 1)))
        assertEquals("1.05×", tvSpeedLabel(1.05f))
        assertEquals("Normal", tvSpeedLabel(PlaybackSpeed.step(1.05f, -1)))
    }

    @Test
    fun `tv list covers 5 percent steps from 0_9 to 1_25`() {
        listOf(0.9f, 0.95f, 1f, 1.05f, 1.1f, 1.15f, 1.2f, 1.25f).forEach { s ->
            assertTrue("$s", PlaybackSpeed.TV_OPTIONS.any { PlaybackSpeed.same(it, s) })
        }
        assertEquals(PlaybackSpeed.TV_OPTIONS, PlaybackSpeed.TV_OPTIONS.sorted())
        PlaybackSpeed.TV_OPTIONS.forEach { assertEquals(it, PlaybackSpeed.snap(it)) }
    }
}
