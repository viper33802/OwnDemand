package dev.owndemand.app.ui.browse

import dev.owndemand.app.data.plex.dto.ItemDto

/** How long a video counts as "New" after it was added (user choice 2026-10-19). */
const val NEW_WINDOW_SECONDS = 7L * 24 * 60 * 60

/**
 * Part-watched videos, most recently watched first, minus the ones the
 * user removed — unless their progress moved on since (then they return).
 */
fun continueWatchingFor(
    items: List<ItemDto>,
    hidden: Map<String, Long>,
    limit: Int = Int.MAX_VALUE,
): List<ItemDto> =
    items.filter { it.isPartiallyWatched && hidden[it.ratingKey] != it.viewOffset }
        .sortedByDescending { it.lastViewedAt }
        .take(limit)

/** Watch history: anything played, most recent first. */
fun recentlyWatched(items: List<ItemDto>, limit: Int = 60): List<ItemDto> =
    items.filter { it.lastViewedAt > 0 }
        .sortedByDescending { it.lastViewedAt }
        .take(limit)

/** "New": never played (no progress) and added within [NEW_WINDOW_SECONDS]. */
fun isNewVideo(item: ItemDto, nowEpochSeconds: Long): Boolean =
    item.viewCount == 0 && item.viewOffset == 0L &&
        item.addedAt > 0 && nowEpochSeconds - item.addedAt <= NEW_WINDOW_SECONDS

/** Number of [isNewVideo] videos per channel (collection) title. */
fun newCountByChannel(items: List<ItemDto>, nowEpochSeconds: Long): Map<String, Int> {
    val counts = HashMap<String, Int>()
    for (item in items) {
        if (!isNewVideo(item, nowEpochSeconds)) continue
        item.Collection.mapTo(HashSet()) { it.tag }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
    }
    return counts
}
