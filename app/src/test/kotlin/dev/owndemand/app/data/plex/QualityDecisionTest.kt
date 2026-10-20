package dev.owndemand.app.data.plex

import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.data.plex.dto.MediaDto
import dev.owndemand.app.data.plex.dto.PartDto
import dev.owndemand.app.data.plex.dto.StreamDto
import dev.owndemand.app.data.settings.initialQuality
import dev.owndemand.app.domain.PlexServer
import dev.owndemand.app.domain.VideoQuality
import dev.owndemand.app.ui.watch.pickAutoplayNext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityDecisionTest {

    private fun video(height: Int) = ItemDto(
        ratingKey = "1", duration = 600_000,
        Media = listOf(
            MediaDto(
                container = "mp4", videoCodec = "h264", audioCodec = "aac",
                Part = listOf(
                    PartDto(
                        key = "/library/parts/9/1/file.mp4", container = "mp4",
                        Stream = listOf(
                            StreamDto(streamType = 1, codec = "h264", height = height),
                            StreamDto(streamType = 2, codec = "aac"),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `original plays the file as-is`() {
        assertNotNull(PlaybackDecision.directPlayPart(video(1080), VideoQuality.Original))
    }

    @Test
    fun `a cap plays videos at or below it as-is and converts taller ones`() {
        assertNotNull(PlaybackDecision.directPlayPart(video(1080), VideoQuality.Q1080))
        assertNotNull(PlaybackDecision.directPlayPart(video(720), VideoQuality.Q720))
        assertNull(PlaybackDecision.directPlayPart(video(1080), VideoQuality.Q720))
        assertNull(PlaybackDecision.directPlayPart(video(720), VideoQuality.Q480))
    }

    @Test
    fun `unknown height never exceeds a cap`() {
        assertNull(PlaybackDecision.directPlayPart(video(0), VideoQuality.Q720))
        assertNotNull(PlaybackDecision.directPlayPart(video(0), VideoQuality.Original))
    }

    @Test
    fun `transcode url carries the cap and a unique session`() {
        val server = PlexServer("id", "S", "https://x.plex.direct:32400", "tok")
        val url = PlaybackDecision.hlsTranscodeUrl(server, "12345", VideoQuality.Q720, session = "abc-1")
        assertTrue(url.contains("&videoResolution=1280x720&maxVideoBitrate=4000"))
        assertTrue(url.endsWith("&session=abc-1"))
        assertTrue(!PlaybackDecision.hlsTranscodeUrl(server, "1", VideoQuality.Original).contains("videoResolution"))
    }

    @Test
    fun `upgrade from data saver keeps behaviour, fresh installs get defaults`() {
        assertEquals(VideoQuality.Q480 to VideoQuality.Q480, initialQuality(dataSaverWasOn = true, existingInstall = true))
        assertEquals(VideoQuality.Original to VideoQuality.Original, initialQuality(false, existingInstall = true))
        assertEquals(VideoQuality.Original to VideoQuality.Q720, initialQuality(false, existingInstall = false))
    }

    @Test
    fun `autoplay picks the first up-next video not finished`() {
        val up = listOf(
            ItemDto(ratingKey = "w", duration = 600_000, viewCount = 1),
            ItemDto(ratingKey = "p", duration = 600_000, viewOffset = 200_000, viewCount = 1),
            ItemDto(ratingKey = "n", duration = 600_000),
        )
        assertEquals("p", pickAutoplayNext(up)?.ratingKey)
        assertNull(pickAutoplayNext(up.take(1)))
    }
}
