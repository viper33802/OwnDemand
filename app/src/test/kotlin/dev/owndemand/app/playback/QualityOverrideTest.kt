package dev.owndemand.app.playback

import dev.owndemand.app.domain.VideoQuality
import dev.owndemand.app.ui.player.playerQualityLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityOverrideTest {
    @Test
    fun `in-player choice wins over the settings cap`() {
        assertEquals(VideoQuality.Q720, effectiveQuality(null, VideoQuality.Q720))
        assertEquals(VideoQuality.Q480, effectiveQuality(VideoQuality.Q480, VideoQuality.Q720))
        assertEquals(VideoQuality.Original, effectiveQuality(VideoQuality.Original, VideoQuality.Q480))
    }

    @Test
    fun `button label`() {
        assertEquals("Auto", playerQualityLabel(null))
        assertEquals("480p", playerQualityLabel(VideoQuality.Q480))
        assertEquals("Orig", playerQualityLabel(VideoQuality.Original))
    }
}
