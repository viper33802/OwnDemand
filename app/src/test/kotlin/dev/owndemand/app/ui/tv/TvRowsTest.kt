package dev.owndemand.app.ui.tv

import dev.owndemand.app.data.plex.dto.CollectionDto
import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.data.plex.dto.TagDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRowsTest {

    private val now = 1_800_000_000L
    private val day = 86_400L

    private fun video(
        key: String,
        channel: String = "A",
        addedDaysAgo: Long = 30,
        viewCount: Int = 0,
        offsetMs: Long = 0,
        lastViewedAt: Long = 0,
        vararg moreChannels: String,
    ) = ItemDto(
        ratingKey = key,
        title = "$channel - $key",
        addedAt = now - addedDaysAgo * day,
        duration = 600_000,
        viewCount = viewCount,
        viewOffset = offsetMs,
        lastViewedAt = lastViewedAt,
        Collection = (listOf(channel) + moreChannels).map { TagDto(it) },
    )

    private fun rows(items: List<ItemDto>, collections: List<CollectionDto> = emptyList()) =
        tvHomeRows(items, collections, emptyMap(), now, seed = 1)

    @Test
    fun `rows come in the planned order and empty rows are dropped`() {
        val items = listOf(
            video("cw", offsetMs = 120_000, lastViewedAt = now - 10),
            video("new", addedDaysAgo = 1),
            video("old", addedDaysAgo = 60),
            video("seen", viewCount = 1, lastViewedAt = now - 100),
        )
        val keys = rows(items).map { it.key }
        assertEquals(listOf("continue", "new", "foryou", "channel-A", "recent"), keys)
        assertTrue(rows(emptyList()).isEmpty())
    }

    @Test
    fun `for you leaves out what New already shows`() {
        val items = listOf(video("new", addedDaysAgo = 1), video("old", addedDaysAgo = 60))
        val forYou = rows(items).first { it.key == "foryou" }.items.map { it.ratingKey }
        assertEquals(listOf("old"), forYou)
    }

    @Test
    fun `channel rows - most recently active first, unwatched first within`() {
        val items = listOf(
            video("a-old", channel = "A", addedDaysAgo = 20),
            video("a-seen", channel = "A", addedDaysAgo = 2, viewCount = 1),
            video("b-new", channel = "B", addedDaysAgo = 1),
            video("c-seen", channel = "C", addedDaysAgo = 0, viewCount = 1),
        )
        val channelRows = channelRows(items, listOf(CollectionDto(title = "B", ratingKey = "9")), 10)
        // C has nothing unwatched; B's newest beats A's newest.
        assertEquals(listOf("B", "A"), channelRows.map { it.title })
        assertEquals(listOf("a-old", "a-seen"), channelRows[1].items.map { it.ratingKey })
        assertEquals("9", channelRows[0].channel?.ratingKey)
        assertEquals(null, channelRows[1].channel)
    }

    @Test
    fun `a video in two collections shows in both channel rows`() {
        val items = listOf(video("x", "A", 1, 0, 0, 0, "B"))
        assertEquals(setOf("A", "B"), channelRows(items, emptyList(), 10).map { it.title }.toSet())
    }

    @Test
    fun `channel row count is capped`() {
        val items = (1..20).map { video("v$it", channel = "C$it", addedDaysAgo = it.toLong()) }
        val capped = channelRows(items, emptyList(), TV_CHANNEL_ROWS)
        assertEquals(TV_CHANNEL_ROWS, capped.size)
        assertEquals("C1", capped.first().title)
        assertFalse(capped.any { it.title == "C20" })
    }
}
