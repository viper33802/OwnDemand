package dev.owndemand.app.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvPlayerRulesTest {

    @Test
    fun `a tap seeks 10 s and holding speeds up`() {
        assertEquals(10_000L, tvSeekStepMs(0))
        assertEquals(10_000L, tvSeekStepMs(3))
        assertEquals(30_000L, tvSeekStepMs(4))
        assertEquals(30_000L, tvSeekStepMs(11))
        assertEquals(60_000L, tvSeekStepMs(12))
    }

    @Test
    fun `seeking stays inside the video`() {
        assertEquals(0L, tvSeekTarget(5_000, -10_000, 600_000))
        assertEquals(599_000L, tvSeekTarget(595_000, 10_000, 600_000))
        assertEquals(40_000L, tvSeekTarget(30_000, 10_000, 600_000))
        // Unknown duration: only the start is a limit.
        assertEquals(40_000L, tvSeekTarget(30_000, 10_000, 0))
    }

    @Test
    fun `speed labels`() {
        assertEquals("Normal", tvSpeedLabel(1f))
        assertEquals("2×", tvSpeedLabel(2f))
        assertEquals("1.25×", tvSpeedLabel(1.25f))
        assertEquals("0.5×", tvSpeedLabel(0.5f))
    }
}
