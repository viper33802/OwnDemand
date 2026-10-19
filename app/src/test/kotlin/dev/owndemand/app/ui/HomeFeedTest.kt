package dev.owndemand.app.ui

import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.ui.browse.HomeSort
import dev.owndemand.app.ui.browse.continueWatchingFor
import dev.owndemand.app.ui.browse.buildHomeFeed
import dev.owndemand.app.ui.browse.unwatchedOnly
import dev.owndemand.app.ui.search.searchChannels
import dev.owndemand.app.ui.search.searchItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 pure logic: continue-watching selection, mixed feed, local search. */
class HomeFeedTest {

    private fun item(
        key: String,
        title: String = "Video $key",
        addedAt: Long = 0,
        duration: Long = 600_000,
        viewOffset: Long = 0,
        viewCount: Int = 0,
        lastViewedAt: Long = 0,
        channel: String? = null,
    ) = ItemDto(
        ratingKey = key,
        title = title,
        addedAt = addedAt,
        duration = duration,
        viewOffset = viewOffset,
        viewCount = viewCount,
        lastViewedAt = lastViewedAt,
        Collection = if (channel != null)
            listOf(dev.owndemand.app.data.plex.dto.TagDto(tag = channel)) else emptyList(),
    )

    private val library = (1..60).map { i ->
        item(
            key = "k$i",
            title = "Video ${'a' + (i % 26)}$i",
            addedAt = i.toLong(),
        )
    }

    // ---- continue watching ----

    @Test
    fun `continue watching offers only partially watched items`() {
        val items = listOf(
            item("a", viewOffset = 300_000, viewCount = 1, lastViewedAt = 10),
            item("b", viewOffset = 120_000, viewCount = 1, lastViewedAt = 99),
            item("c"), // never watched
            item("d", viewOffset = 590_000, viewCount = 1), // near the end → not offered
            item("e", viewOffset = 10_000, viewCount = 1), // barely started → not offered
        )
        val got = continueWatchingFor(items, emptyMap()).map { it.ratingKey }
        assertEquals(listOf("b", "a"), got) // most recently watched first
    }

    @Test
    fun `continue watching respects limit`() {
        val items = (1..30).map { item("k$it", viewOffset = 100_000, viewCount = 1) }
        assertEquals(15, continueWatchingFor(items, emptyMap(), limit = 15).size)
    }

    @Test
    fun `removed videos stay hidden until their progress moves on`() {
        val items = listOf(
            item("a", viewOffset = 300_000, viewCount = 1, lastViewedAt = 10),
            item("b", viewOffset = 120_000, viewCount = 1, lastViewedAt = 99),
        )
        assertEquals(listOf("a"), continueWatchingFor(items, mapOf("b" to 120_000L)).map { it.ratingKey })
        // Watched further after hiding -> back in the row.
        assertEquals(listOf("b", "a"), continueWatchingFor(items, mapOf("b" to 60_000L)).map { it.ratingKey })
    }

    @Test
    fun `home feed is unwatched only`() {
        val items = listOf(
            item("u1"), item("u2"),
            item("w", viewCount = 1),
            item("p", viewOffset = 200_000, viewCount = 1), // partial → resume row only
        )
        assertEquals(
            listOf("u1", "u2"),
            unwatchedOnly(items).map { it.ratingKey },
        )
    }

    @Test
    fun `partially watched is not fully watched`() {
        assertFalse(item("p", viewOffset = 300_000, viewCount = 1).isFullyWatched)
        assertTrue(item("f", viewOffset = 0, viewCount = 2).isFullyWatched)
        // Finished to the very edge still counts as finished.
        assertTrue(item("e", viewOffset = 595_000, viewCount = 1).isFullyWatched)
        assertFalse(item("n", viewCount = 0).isFullyWatched)
    }

    // ---- feed ordering ----

    @Test
    fun `mixed feed keeps the two freshest items on top and every item once`() {
        val feed = buildHomeFeed(library, HomeSort.Mixed, seed = 42)
        assertEquals(library.size, feed.size)
        assertEquals(library.map { it.ratingKey }.toSet(), feed.map { it.ratingKey }.toSet())
        val newest = library.sortedByDescending { it.addedAt }.take(2).map { it.ratingKey }
        assertEquals(newest, feed.take(2).map { it.ratingKey })
    }

    @Test
    fun `mixed feed order changes between seeds below the head`() {
        val a = buildHomeFeed(library, HomeSort.Mixed, seed = 1).drop(2).map { it.ratingKey }
        val b = buildHomeFeed(library, HomeSort.Mixed, seed = 2).drop(2).map { it.ratingKey }
        assertFalse(a == b)
    }

    @Test
    fun `mixed feed is stable for the same seed`() {
        val a = buildHomeFeed(library, HomeSort.Mixed, seed = 7).map { it.ratingKey }
        val b = buildHomeFeed(library, HomeSort.Mixed, seed = 7).map { it.ratingKey }
        assertEquals(a, b)
    }

    @Test
    fun `mixed feed mixes in older picks near the top`() {
        // With 60 items, mixing starts right after the 2-item head, so the
        // first 25 rows must include picks beyond the 25 newest.
        val feed = buildHomeFeed(library, HomeSort.Mixed, seed = 5)
        val newestFirst = library.sortedByDescending { it.addedAt }.map { it.ratingKey }
        val headSet = newestFirst.take(2).toSet()
        val top = feed.take(25).filter { it.ratingKey !in headSet }
        assertTrue("expected mixed picks near the top, got ${top.map { it.ratingKey }}",
            top.isNotEmpty())
        // And not simply newest order.
        assertTrue(top.map { it.ratingKey } != newestFirst.drop(2).take(top.size))
    }

    @Test
    fun `mixed feed random picks favor unwatched`() {
        val items = (1..60).map { i ->
            item(key = "k$i", title = "V$i", addedAt = i.toLong(), viewCount = if (i % 2 == 0) 1 else 0)
        }
        val feed = buildHomeFeed(items, HomeSort.Mixed, seed = 9)
        assertEquals(items.size, feed.size)
        // Structure after the 2-item head: recent, recent, random, ...
        // so every third slot from index 4 is a random pick — those must
        // all be unwatched while unwatched picks last.
        for (i in 4 until 40 step 3) {
            assertEquals("slot $i should be an unwatched random pick",
                0, feed[i].viewCount)
        }
    }

    @Test
    fun `explicit sorts`() {
        val newest = buildHomeFeed(library, HomeSort.Newest, 0).map { it.ratingKey }
        assertEquals("k60", newest.first())
        val oldest = buildHomeFeed(library, HomeSort.Oldest, 0).map { it.ratingKey }
        assertEquals("k1", oldest.first())
        val az = buildHomeFeed(library, HomeSort.TitleAsc, 0).map { it.title }
        assertEquals(az.sorted(), az)
        val longest = buildHomeFeed(
            listOf(item("s", duration = 60_000), item("l", duration = 600_000)),
            HomeSort.DurationDesc, 0,
        ).map { it.ratingKey }
        assertEquals(listOf("l", "s"), longest)
    }

    // ---- search ----

    @Test
    fun `search matches titles first then channels, case-insensitive`() {
        val items = listOf(
            item("a", title = "Guitar Solo Live", channel = "MusicDaily"),
            item("b", title = "Pedalboard Tour", channel = "GuitarGear"),
            item("c", title = "Cooking", channel = "FoodNet"),
        )
        assertEquals(listOf("a", "b"), searchItems(items, "guitar").map { it.ratingKey })
        assertEquals(emptyList<String>(), searchItems(items, "  "))
    }

    @Test
    fun `channel search matches collection names`() {
        val channels = listOf(
            dev.owndemand.app.data.plex.dto.CollectionDto(ratingKey = "c1", title = "Guitar Gear Lab"),
            dev.owndemand.app.data.plex.dto.CollectionDto(ratingKey = "c2", title = "Cooking Network"),
        )
        assertEquals(listOf("c1"), searchChannels(channels, "GUITAR").map { it.ratingKey })
        assertEquals(emptyList<String>(), searchChannels(channels, ""))
    }
}
