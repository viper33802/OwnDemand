package dev.owndemand.app.playback

import dev.owndemand.app.data.plex.dto.ItemDto
import kotlin.random.Random

/*
 * Play a channel (Chunk 8): every video of one channel, in one order, once
 * through. While a channel plays, its queue replaces Up next for autoplay
 * and every "next" button. Pure, unit-tested.
 */

enum class ChannelOrder(val label: String) {
    OldestFirst("Oldest first"),
    NewestFirst("Newest first"),
    Shuffle("Shuffle"),
}

/** A channel being played: its videos in play order. */
data class PlayQueue(
    val channelTitle: String,
    val order: ChannelOrder,
    val items: List<ItemDto>,
) {
    val keys: Set<String> = items.mapTo(HashSet()) { it.ratingKey }
}

/**
 * When a video was published: its original upload date when Plex has one
 * (`originallyAvailableAt`, `yyyy-MM-dd`), else when it was added to the
 * library. Epoch seconds.
 */
fun publishedAt(item: ItemDto): Long =
    item.uploadDate?.toEpochDay()?.let { it * 86_400L } ?: item.addedAt

/** The channel's videos in [order]; shuffle is a fresh random order each time. */
fun orderChannel(
    items: List<ItemDto>,
    order: ChannelOrder,
    random: Random = Random.Default,
): List<ItemDto> {
    val oldestFirst = compareBy<ItemDto>({ publishedAt(it) }, { it.addedAt }, { it.ratingKey })
    return when (order) {
        ChannelOrder.OldestFirst -> items.sortedWith(oldestFirst)
        ChannelOrder.NewestFirst -> items.sortedWith(oldestFirst.reversed())
        ChannelOrder.Shuffle -> items.shuffled(random)
    }
}

/** The video after [currentKey] in the queue; null at the end (once through). */
fun queueNextKey(queue: PlayQueue, currentKey: String?): String? {
    val index = queue.items.indexOfFirst { it.ratingKey == currentKey }
    if (index < 0) return null
    return queue.items.getOrNull(index + 1)?.ratingKey
}

/** What is still to come after [currentKey] (the watch screen's Up next). */
fun queueRemaining(queue: PlayQueue, currentKey: String?): List<ItemDto> {
    val index = queue.items.indexOfFirst { it.ratingKey == currentKey }
    return if (index < 0) queue.items else queue.items.drop(index + 1)
}
