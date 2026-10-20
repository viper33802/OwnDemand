package dev.owndemand.app.data.plex.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Unified item DTO for both feed lists (`/library/sections/{k}/all`) and
 * full metadata (`/library/metadata/{k}`); every field has a default
 * because list payloads are leaner than detail payloads.
 */
@Serializable
data class MediaContainerResponse(
    @SerialName("MediaContainer") val container: PlexContainer = PlexContainer(),
)

@Serializable
data class PlexContainer(
    val size: Int = 0,
    val totalSize: Int = 0,
    val Metadata: List<ItemDto> = emptyList(),
)

@Serializable
data class ItemDto(
    val ratingKey: String = "",
    val key: String = "",
    val title: String = "",
    val addedAt: Long = 0,
    val lastViewedAt: Long = 0,
    val duration: Long = 0,
    val viewOffset: Long = 0,
    val viewCount: Int = 0,
    val thumb: String = "",
    val summary: String = "",
    val type: String = "",
    val year: Int = 0,
    val originallyAvailableAt: String = "",
    val Collection: List<TagDto> = emptyList(),
    val Media: List<MediaDto> = emptyList(),
) {
    /** Channel identity = first Collection tag (M1: collections are channels). */
    val channelName: String? get() = Collection.firstOrNull()?.tag

    /** Partially watched (resume-able), ignoring edges. */
    val isPartiallyWatched: Boolean
        get() = viewOffset > 30_000 && duration > 0 && viewOffset < duration - 30_000

    /** Finished: watched at least once and not sitting mid-stream. */
    val isFullyWatched: Boolean get() = viewCount > 0 && !isPartiallyWatched
}

@Serializable
data class TagDto(val tag: String = "")

@Serializable
data class MediaDto(
    val id: Long = 0,
    val container: String = "",
    val videoCodec: String = "",
    val audioCodec: String = "",
    val bitrate: Int = 0,
    val Part: List<PartDto> = emptyList(),
)

@Serializable
data class PartDto(
    val key: String = "",
    /** Numeric part id — addresses `/library/parts/{id}/…` (BIF previews). */
    val id: Long = 0,
    val container: String = "",
    val file: String = "",
    /** Plex `indexes` attribute; "sd" means BIF preview frames exist. */
    val indexes: String? = null,
    val Stream: List<StreamDto> = emptyList(),
) {
    val videoStream: StreamDto? get() = Stream.firstOrNull { it.streamType == 1 }
    val audioStreams: List<StreamDto> get() = Stream.filter { it.streamType == 2 }
    val subtitleStreams: List<StreamDto> get() = Stream.filter { it.streamType == 3 }
    val hasPreviewFrames: Boolean get() = !indexes.isNullOrBlank() && id != 0L
}

@Serializable
data class StreamDto(
    val id: Long = 0,
    /** Sidecar address (`/library/streams/{id}`) for subtitle streams. */
    val key: String = "",
    val index: Int = -1,
    val codec: String = "",
    /** Subtitle container, e.g. "srt" (Youtarr ships SRT sidecars). */
    val format: String = "",
    val type: String = "",
    /** 1 = video, 2 = audio, 3 = subtitle (Plex `streamType`). */
    val streamType: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val language: String? = null,
    /** BCP-47 tag, e.g. "en" (newer PMS). */
    val languageTag: String? = null,
    /** ISO 639-2 code, e.g. "eng". */
    val languageCode: String? = null,
    /** PMS display title, e.g. "English (SRT External)". */
    val displayTitle: String? = null,
    val selected: Boolean = false,
)
