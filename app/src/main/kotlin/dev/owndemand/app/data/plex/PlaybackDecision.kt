package dev.owndemand.app.data.plex

import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.data.plex.dto.PartDto
import dev.owndemand.app.data.plex.dto.StreamDto
import dev.owndemand.app.domain.PlexServer

/**
 * Client-side playback decision (M3 spike: PMS 1.43 ignores
 * X-Plex-Client-Profile, so the app decides from the item's own streams).
 *
 * Direct play when the part is a progressive container with a codec our
 * targets decode (AV1 via dav1d, H264 everywhere) and AAC audio; otherwise
 * fall back to the server's HLS transcode.
 */
object PlaybackDecision {

    private val DIRECT_CONTAINERS = setOf("mp4", "m4v", "mov", "mkv")
    private val DIRECT_VIDEO_CODECS = setOf("av1", "h264")
    private val DIRECT_AUDIO_CODECS = setOf("aac", "mp3", "ac3", "eac3")

    /** The part to direct-play, or null → use the HLS transcode. */
    fun directPlayPart(item: ItemDto): PartDto? =
        item.Media.firstOrNull()?.Part?.firstOrNull() { part ->
            val media = item.Media.firstOrNull()
            val container = part.container.ifBlank { media?.container.orEmpty() }.lowercase()
            val video = (part.videoStream?.codec?.takeIf { it.isNotBlank() }
                ?: media?.videoCodec).orEmpty().lowercase()
            val audio = (part.audioStreams.firstOrNull()?.codec?.takeIf { it.isNotBlank() }
                ?: media?.audioCodec).orEmpty().lowercase()
            container in DIRECT_CONTAINERS &&
                video in DIRECT_VIDEO_CODECS &&
                (audio.isEmpty() || audio in DIRECT_AUDIO_CODECS)
        }

    fun hlsTranscodeUrl(
        server: PlexServer,
        itemKey: String,
        dataSaver: Boolean = false,
    ): String =
        server.baseUrl + "/video/:/transcode/universal/start.m3u8" +
            "?path=" + encode("/library/metadata/$itemKey") +
            "&mediaIndex=0&partIndex=0&protocol=hls&timeout=10000" +
            // Quality caps verified against a live server 2026-10-16 (480p/1200k
            // yields RESOLUTION=718x404, BANDWIDTH=895000).
            if (dataSaver) "&maxVideoBitrate=1200&videoQuality=480p&audioBitrate=128" else ""

    /**
     * SRT sidecar for a subtitle stream (`/library/streams/{id}`; verified
     * plain SRT text, every Youtarr item ships English captions).
     */
    fun subtitleUrl(server: PlexServer, stream: StreamDto): String? {
        if (stream.streamType != 3 || stream.id == 0L) return null
        val path = stream.key.ifBlank { "/library/streams/${stream.id}" }
        return server.baseUrl + path
    }

    /**
     * Direct-play part URL. Media3 sends the token as a header; the query
     * param is kept for components that cannot set headers (none today).
     */
    fun directMediaUrl(server: PlexServer, partKey: String): String =
        server.baseUrl + partKey

    /**
     * Artwork URL with the token as a query parameter. Collection composite
     * paths already carry `?width=…&height=…`, so the separator varies.
     */
    fun artworkUrl(server: PlexServer, path: String): String = when {
        path.isBlank() -> ""
        else -> server.baseUrl + path +
            (if (path.contains('?')) "&" else "?") + "X-Plex-Token=" + encode(server.accessToken)
    }

    /**
     * BIF preview frame for a part at [timeMs]
     * (`/library/parts/{id}/indexes/sd/{ms}`), used for scrub thumbnails.
     * Times are bucketed by the caller so Coil caches hits.
     */
    fun previewFrameUrl(server: PlexServer, partId: Long, timeMs: Long): String =
        "${server.baseUrl}/library/parts/$partId/indexes/sd/${timeMs.coerceAtLeast(0)}" +
            "?X-Plex-Token=" + encode(server.accessToken)

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
