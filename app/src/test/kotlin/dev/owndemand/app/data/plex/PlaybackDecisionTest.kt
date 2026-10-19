package dev.owndemand.app.data.plex

import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.data.plex.dto.MediaContainerResponse
import dev.owndemand.app.data.plex.dto.MediaDto
import dev.owndemand.app.data.plex.dto.PartDto
import dev.owndemand.app.data.plex.dto.StreamDto
import dev.owndemand.app.domain.PlexServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client-side direct-play decision (M3 spike: PMS 1.43 ignores
 * X-Plex-Client-Profile, so the app decides from the item's streams).
 */
class PlaybackDecisionTest {

    private fun item(container: String, video: String, audio: String) =
        ItemDto(
            ratingKey = "12345",
            Media = listOf(
                MediaDto(
                    container = container,
                    Part = listOf(
                        PartDto(
                            key = "/library/parts/67890/1/file.mp4",
                            container = container,
                            Stream = listOf(
                                StreamDto(streamType = 1, codec = video),
                                StreamDto(streamType = 2, codec = audio),
                            ),
                        ),
                    ),
                ),
            ),
        )

    @Test
    fun `av1 mp4 aac direct plays`() {
        assertNotNull(PlaybackDecision.directPlayPart(item("mp4", "av1", "aac")))
    }

    @Test
    fun `h264 mkv aac direct plays`() {
        assertNotNull(PlaybackDecision.directPlayPart(item("mkv", "h264", "aac")))
    }

    @Test
    fun `hevc falls back to transcode`() {
        assertNull(PlaybackDecision.directPlayPart(item("mp4", "hevc", "aac")))
    }

    @Test
    fun `unsupported audio falls back to transcode`() {
        assertNull(PlaybackDecision.directPlayPart(item("mp4", "av1", "dts")))
    }

    @Test
    fun `item without media falls back to transcode`() {
        assertNull(PlaybackDecision.directPlayPart(ItemDto(ratingKey = "1")))
    }

    @Test
    fun `real plex stream shape parses (streamType + codec)`() {
        // Mirrors the actual /library/metadata payload shape (M1 scan).
        val json = """
            {"MediaContainer":{"size":1,"Metadata":[{
              "ratingKey":"12345","title":"Test","duration":"1634081",
              "Media":[{"container":"mp4","videoCodec":"av1","audioCodec":"aac",
                "Part":[{"key":"/library/parts/67890/1700000000/file.mp4",
                  "container":"mp4",
                  "Stream":[
                    {"id":1,"streamType":1,"codec":"av1","width":1920,"height":1080},
                    {"id":2,"streamType":2,"codec":"aac"},
                    {"id":3,"streamType":3,"codec":"srt","language":"eng"}
                  ]}]}]}]}}
        """.trimIndent()
        val parsed = PlexJson.decodeFromString<MediaContainerResponse>(json)
        val item = parsed.container.Metadata.single()
        val part = PlaybackDecision.directPlayPart(item)
        assertNotNull(part)
        assertEquals("/library/parts/67890/1700000000/file.mp4", part?.key)
        assertEquals(1, part?.subtitleStreams?.size)
    }

    @Test
    fun `hls url encodes the metadata path`() {
        val server = PlexServer("id", "My Server", "https://x.plex.direct:32400", "tok")
        val url = PlaybackDecision.hlsTranscodeUrl(server, "12345")
        assertEquals(
            "https://x.plex.direct:32400/video/:/transcode/universal/start.m3u8" +
                "?path=%2Flibrary%2Fmetadata%2F12345" +
                "&mediaIndex=0&partIndex=0&protocol=hls&timeout=10000",
            url,
        )
    }

    @Test
    fun `data saver hls url adds the verified quality caps`() {
        val server = PlexServer("id", "My Server", "https://x.plex.direct:32400", "tok")
        val url = PlaybackDecision.hlsTranscodeUrl(server, "12345", dataSaver = true)
        assertTrue(url.endsWith("&maxVideoBitrate=1200&videoQuality=480p&audioBitrate=128"))
    }

    @Test
    fun `subtitle url uses the sidecar key or the stream id`() {
        val server = PlexServer("id", "My Server", "https://x.plex.direct:32400", "tok")
        assertEquals(
            "https://x.plex.direct:32400/library/streams/4242",
            PlaybackDecision.subtitleUrl(
                server,
                StreamDto(id = 4242, key = "/library/streams/4242", streamType = 3),
            ),
        )
        // No key → derived from id.
        assertEquals(
            "https://x.plex.direct:32400/library/streams/7",
            PlaybackDecision.subtitleUrl(server, StreamDto(id = 7, streamType = 3)),
        )
        // Not a subtitle stream → null.
        assertNull(PlaybackDecision.subtitleUrl(server, StreamDto(id = 7, streamType = 1)))
    }

    @Test
    fun `speed label drops the decimal for whole rates`() {
        assertEquals("1x", dev.owndemand.app.ui.player.speedLabel(1f))
        assertEquals("2x", dev.owndemand.app.ui.player.speedLabel(2f))
        assertEquals("0.75x", dev.owndemand.app.ui.player.speedLabel(0.75f))
        assertEquals("1.5x", dev.owndemand.app.ui.player.speedLabel(1.5f))
    }

    @Test
    fun `artwork url appends token and blank path yields blank`() {
        val server = PlexServer("id", "My Server", "https://x.plex.direct:32400", "tok en")
        assertEquals(
            "https://x.plex.direct:32400/photo/1?X-Plex-Token=tok+en",
            PlaybackDecision.artworkUrl(server, "/photo/1"),
        )
        assertEquals("", PlaybackDecision.artworkUrl(server, ""))
    }

    @Test
    fun `artwork path already carrying params gets an ampersand separator`() {
        val server = PlexServer("id", "My Server", "https://x.plex.direct:32400", "tok")
        // Collection composite thumbs arrive as …/composite/<ts>?width=400&height=600.
        assertEquals(
            "https://x.plex.direct:32400/library/collections/1/composite/9" +
                "?width=400&height=600&X-Plex-Token=tok",
            PlaybackDecision.artworkUrl(
                server,
                "/library/collections/1/composite/9?width=400&height=600",
            ),
        )
    }
}
