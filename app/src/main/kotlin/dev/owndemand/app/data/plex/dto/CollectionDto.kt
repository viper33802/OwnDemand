package dev.owndemand.app.data.plex.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /library/sections/{key}/collections — the channel collections
 * (AGENTS.md §4.1). Paged with X-Plex-Container-* headers like the feed.
 * Members come from the returned `key` (/library/collections/{id}/children).
 */
@Serializable
data class CollectionsResponse(
    @SerialName("MediaContainer") val container: CollectionsContainer = CollectionsContainer(),
)

@Serializable
data class CollectionsContainer(
    val size: Int = 0,
    val totalSize: Int = 0,
    val Metadata: List<CollectionDto> = emptyList(),
)

@Serializable
data class CollectionDto(
    val ratingKey: String = "",
    val key: String = "",
    val title: String = "",
    /** 2:3 composite artwork path (verified 200 image/jpeg). */
    val thumb: String = "",
    /**
     * Background art (the channel banner). Present when one was uploaded,
     * e.g. by tools/plex-collection-art.sh; blank otherwise.
     */
    val art: String = "",
    val childCount: Int = 0,
    val addedAt: Long = 0,
)

