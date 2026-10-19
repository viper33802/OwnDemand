package dev.owndemand.app.data

import dev.owndemand.app.data.plex.PlaybackDecision
import dev.owndemand.app.data.plex.PmsClient
import dev.owndemand.app.data.plex.dto.CollectionDto
import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.domain.PlexServer

/** Feed + item access plus URL building for the selected server. */
class PlexRepository(
    private val client: PmsClient,
    private val server: PlexServer,
) {
    val accessToken: String get() = server.accessToken

    suspend fun feed(sectionKey: String, start: Int, size: Int): Pair<List<ItemDto>, Int> =
        client.feed(sectionKey, start, size)

    suspend fun collections(sectionKey: String, start: Int, size: Int): Pair<List<CollectionDto>, Int> =
        client.collections(sectionKey, start, size)

    suspend fun collectionChildren(ratingKey: String, start: Int, size: Int): Pair<List<ItemDto>, Int> =
        client.collectionChildren(ratingKey, start, size)

    suspend fun item(ratingKey: String): ItemDto = client.item(ratingKey)

    fun artworkUrl(path: String): String = PlaybackDecision.artworkUrl(server, path)

    /** BIF scrub-preview frame for a part (see [PlaybackDecision]). */
    fun previewFrameUrl(partId: Long, timeMs: Long): String =
        PlaybackDecision.previewFrameUrl(server, partId, timeMs)

    /** Whole BIF strip for a part; parse with BifIndex.parse. */
    suspend fun bifBytes(partId: Long): ByteArray = client.bifBytes(partId)

    /**
     * Stream URI + mime type for an item, decided client-side
     * (see [PlaybackDecision]); null mime lets Media3 sniff.
     */
    fun streamUri(
        item: ItemDto,
        quality: dev.owndemand.app.domain.VideoQuality = dev.owndemand.app.domain.VideoQuality.Original,
        transcodeSession: String? = null,
    ): Pair<String, String?> {
        val part = PlaybackDecision.directPlayPart(item, quality)
        return if (part != null && part.key.isNotBlank()) {
            PlaybackDecision.directMediaUrl(server, part.key) to null
        } else {
            PlaybackDecision.hlsTranscodeUrl(server, item.ratingKey, quality, transcodeSession) to
                "application/x-mpegURL"
        }
    }

    /** Ends a server-side conversion the player no longer needs (best-effort). */
    suspend fun stopTranscode(session: String): Boolean = client.stopTranscode(session)

    /** First playable subtitle sidecar (SRT) for the item, if any. */
    /** Every SRT subtitle file of [item] (Plex sidecars), in server order. */
    fun subtitleSidecars(item: ItemDto): List<SubtitleSidecar> =
        item.Media.firstOrNull()?.Part.orEmpty()
            .flatMap { it.subtitleStreams }
            .filter { it.format.lowercase() == "srt" || it.codec.lowercase() == "srt" }
            .mapNotNull { stream ->
                val url = PlaybackDecision.subtitleUrl(server, stream) ?: return@mapNotNull null
                SubtitleSidecar(
                    url = url,
                    language = stream.languageTag ?: stream.languageCode,
                    label = stream.language ?: stream.displayTitle ?: "Subtitles",
                )
            }

    suspend fun reportTimeline(
        ratingKey: String,
        state: String,
        timeMs: Long,
        durationMs: Long,
    ): Boolean = client.reportTimeline(ratingKey, state, timeMs, durationMs)

    /** Marks a video watched on the server (see [PmsClient.scrobble]). */
    suspend fun scrobble(ratingKey: String): Boolean = client.scrobble(ratingKey)

    /** Marks a video unwatched on the server (see [PmsClient.unscrobble]). */
    suspend fun unscrobble(ratingKey: String): Boolean = client.unscrobble(ratingKey)
}

/** A subtitle file the player can load alongside the video. */
data class SubtitleSidecar(val url: String, val language: String?, val label: String)
