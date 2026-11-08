package dev.owndemand.app.data.plex

import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.data.plex.dto.MediaDto
import dev.owndemand.app.data.plex.dto.PartDto
import dev.owndemand.app.data.plex.dto.StreamDto
import dev.owndemand.app.domain.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct play only when this device can decode the video (Android TV
 * spike: a device without an AV1 decoder played sound over a black
 * picture).
 */
class DecoderSupportDecisionTest {

    private fun video(codec: String, width: Int = 1920, height: Int = 1080) = ItemDto(
        ratingKey = "1",
        Media = listOf(
            MediaDto(
                container = "mp4",
                Part = listOf(
                    PartDto(
                        key = "/library/parts/1/1/file.mp4",
                        container = "mp4",
                        Stream = listOf(
                            StreamDto(streamType = 1, codec = codec, width = width, height = height),
                            StreamDto(streamType = 2, codec = "aac"),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** Decodes only [codecs], up to [maxHeight]. */
    private fun device(vararg codecs: String, maxHeight: Int = 2160) =
        DecoderSupport { codec, _, height -> codec in codecs && height <= maxHeight }

    @Test
    fun `av1 without a decoder goes to conversion`() {
        assertNull(PlaybackDecision.directPlayPart(video("av1"), device("h264")))
    }

    @Test
    fun `av1 with a decoder plays directly`() {
        assertNotNull(PlaybackDecision.directPlayPart(video("av1"), device("av1", "h264")))
    }

    @Test
    fun `h264 is unaffected by a missing av1 decoder`() {
        assertNotNull(PlaybackDecision.directPlayPart(video("h264"), device("h264")))
    }

    @Test
    fun `a size the decoder cannot handle goes to conversion`() {
        assertNull(PlaybackDecision.directPlayPart(video("av1", 3840, 2160), device("av1", maxHeight = 1080)))
    }

    @Test
    fun `the decoder is asked with the stream's size`() {
        val asked = mutableListOf<Triple<String, Int, Int>>()
        PlaybackDecision.directPlayPart(video("av1", 1280, 720)) { c, w, h -> asked += Triple(c, w, h); true }
        assertEquals(listOf(Triple("av1", 1280, 720)), asked)
    }

    @Test
    fun `quality caps still apply when the codec is decodable`() {
        val d = device("av1")
        assertNotNull(PlaybackDecision.directPlayPart(video("av1"), VideoQuality.Original, d))
        assertNull(PlaybackDecision.directPlayPart(video("av1"), VideoQuality.Q720, d))
    }

    @Test
    fun `no decoder overrides an uncapped quality`() {
        assertNull(PlaybackDecision.directPlayPart(video("av1"), VideoQuality.Original, device("h264")))
    }

    @Test
    fun `fallback only for a direct file whose video cannot play, once`() {
        fun f(direct: Boolean = true, video: Boolean = true, playable: Boolean = false, done: Boolean = false) =
            PlaybackDecision.needsConversionFallback(direct, video, playable, done)
        assertTrue(f())
        assertFalse(f(direct = false)) // already a conversion
        assertFalse(f(video = false)) // audio-only file / tracks not known yet
        assertFalse(f(playable = true))
        assertFalse(f(done = true)) // never loops
    }
}
