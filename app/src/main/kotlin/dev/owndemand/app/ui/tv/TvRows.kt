package dev.owndemand.app.ui.tv

import dev.owndemand.app.data.plex.dto.CollectionDto
import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.ui.browse.HomeSort
import dev.owndemand.app.ui.browse.buildHomeFeed
import dev.owndemand.app.ui.browse.continueWatchingFor
import dev.owndemand.app.ui.browse.isNewVideo
import dev.owndemand.app.ui.browse.recentlyWatched
import dev.owndemand.app.ui.browse.unwatchedOnly

/*
 * TV Home (Chunk 6): the library as horizontal rows. Pure, unit-tested;
 * built from the same in-memory index as the phone feed.
 */

/** One Home row: a titled strip of videos, optionally a channel's. */
data class TvRow(
    val key: String,
    val title: String,
    val items: List<ItemDto>,
    /** Set for a channel's row: its "View channel" tile and avatar. */
    val channel: CollectionDto? = null,
)

/** Videos per row; channel rows end with a "View channel" tile. */
const val TV_ROW_LIMIT = 24

/** Channel rows on Home, most recently active first. */
const val TV_CHANNEL_ROWS = 12

/**
 * Continue watching, New (unwatched, added this week), For you (the smart
 * mix minus what New already shows), one row per recently active channel,
 * then Recently watched. Empty rows are left out.
 */
fun tvHomeRows(
    items: List<ItemDto>,
    collections: List<CollectionDto>,
    hiddenContinue: Map<String, Long>,
    nowEpochSeconds: Long,
    seed: Int,
): List<TvRow> {
    val rows = ArrayList<TvRow>()
    rows += TvRow("continue", "Continue watching", continueWatchingFor(items, hiddenContinue, TV_ROW_LIMIT))
    val new = items.filter { isNewVideo(it, nowEpochSeconds) }
        .sortedByDescending { it.addedAt }
        .take(TV_ROW_LIMIT)
    rows += TvRow("new", "New", new)
    val newKeys = new.mapTo(HashSet()) { it.ratingKey }
    val forYou = buildHomeFeed(unwatchedOnly(items), HomeSort.Mixed, seed)
        .filterNot { it.ratingKey in newKeys }
        .take(TV_ROW_LIMIT)
    rows += TvRow("foryou", "For you", forYou)
    rows += channelRows(items, collections, TV_CHANNEL_ROWS)
    rows += TvRow("recent", "Recently watched", recentlyWatched(items, TV_ROW_LIMIT))
    return rows.filter { it.items.isNotEmpty() }
}

/**
 * Channels with something unwatched, ordered by their newest video; each
 * row is that channel's videos, unwatched first, newest first within.
 */
fun channelRows(
    items: List<ItemDto>,
    collections: List<CollectionDto>,
    limit: Int,
): List<TvRow> {
    val byChannel = HashMap<String, MutableList<ItemDto>>()
    for (item in items) {
        // A video can belong to several collections (AGENTS §4.2).
        item.Collection.mapTo(HashSet()) { it.tag }.forEach { tag ->
            byChannel.getOrPut(tag) { ArrayList() } += item
        }
    }
    val channelsByTitle = collections.associateBy { it.title }
    return byChannel
        .filter { (_, videos) -> videos.any { it.viewCount == 0 } }
        .entries
        .sortedByDescending { (_, videos) -> videos.maxOf { it.addedAt } }
        .take(limit)
        .map { (title, videos) ->
            TvRow(
                key = "channel-$title",
                title = title,
                items = videos
                    .sortedWith(compareBy<ItemDto> { it.viewCount > 0 }.thenByDescending { it.addedAt })
                    .take(TV_ROW_LIMIT),
                channel = channelsByTitle[title],
            )
        }
}

/** Number of new videos per channel title, for the Channels list badges. */
fun newCounts(items: List<ItemDto>, nowEpochSeconds: Long): Map<String, Int> =
    dev.owndemand.app.ui.browse.newCountByChannel(items, nowEpochSeconds)
