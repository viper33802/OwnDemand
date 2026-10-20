package dev.owndemand.app.playback

import dev.owndemand.app.data.plex.dto.ItemDto
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Play a channel (Chunk 8). */
class ChannelQueueTest {

    private fun item(key: String, published: String = "", addedAt: Long = 0) = ItemDto(
        ratingKey = key, title = "Video $key", duration = 600_000,
        originallyAvailableAt = published, addedAt = addedAt,
    )

    // Upload dates disagree with the order the videos were added (a channel
    // downloaded in one go, back catalogue last).
    private val videos = listOf(
        item("b", published = "2024-03-01", addedAt = 300),
        item("a", published = "2023-05-10", addedAt = 400),
        item("c", published = "2025-01-20", addedAt = 100),
        item("d", addedAt = 1_800_000_000), // no upload date: added date (2027)
    )

    @Test
    fun `oldest and newest first follow the upload date`() {
        assertEquals(listOf("a", "b", "c", "d"), orderChannel(videos, ChannelOrder.OldestFirst).map { it.ratingKey })
        assertEquals(listOf("d", "c", "b", "a"), orderChannel(videos, ChannelOrder.NewestFirst).map { it.ratingKey })
    }

    @Test
    fun `a missing or odd upload date falls back to the added date`() {
        assertEquals(1_800_000_000L, publishedAt(item("x", published = "", addedAt = 1_800_000_000)))
        assertEquals(500L, publishedAt(item("y", published = "not a date", addedAt = 500)))
        assertEquals(19_737L * 86_400, publishedAt(item("z", published = "2024-01-15")))
    }

    @Test
    fun `shuffle plays every video exactly once`() {
        val many = (1..50).map { item("$it", addedAt = it.toLong()) }
        val shuffled = orderChannel(many, ChannelOrder.Shuffle, Random(7))
        assertEquals(many.map { it.ratingKey }.toSet(), shuffled.map { it.ratingKey }.toSet())
        assertEquals(many.size, shuffled.size)
        // A fresh order each time.
        val again = orderChannel(many, ChannelOrder.Shuffle, Random(8))
        assert(shuffled != again)
    }

    @Test
    fun `next walks the queue once and stops after the last video`() {
        val queue = PlayQueue("Orbit Notes", ChannelOrder.OldestFirst, orderChannel(videos, ChannelOrder.OldestFirst))
        assertEquals("b", queueNextKey(queue, "a"))
        assertEquals("d", queueNextKey(queue, "c"))
        assertNull(queueNextKey(queue, "d"))          // once through, no loop
        assertNull(queueNextKey(queue, "elsewhere"))   // not in this channel
    }

    @Test
    fun `remaining lists what is still to come`() {
        val queue = PlayQueue("Orbit Notes", ChannelOrder.OldestFirst, orderChannel(videos, ChannelOrder.OldestFirst))
        assertEquals(listOf("c", "d"), queueRemaining(queue, "b").map { it.ratingKey })
        assertEquals(emptyList<String>(), queueRemaining(queue, "d").map { it.ratingKey })
        assertEquals(4, queueRemaining(queue, "elsewhere").size)
    }
}
