package dev.owndemand.app.ui.channels

import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.ui.browse.HomeSort
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelSortTest {

    private fun item(key: String, title: String, addedAt: Long, duration: Long) =
        ItemDto(ratingKey = key, title = title, addedAt = addedAt, duration = duration)

    private val items = listOf(
        item("b", "Beta", 200, 60_000),
        item("a", "alpha", 100, 90_000),
        item("c", "Gamma", 300, 30_000),
    )

    @Test
    fun `newest first is the default order`() {
        assertEquals(
            listOf("c", "b", "a"),
            sortChannelVideos(items, HomeSort.Newest).map { it.ratingKey },
        )
    }

    @Test
    fun `oldest first reverses addedAt`() {
        assertEquals(
            listOf("a", "b", "c"),
            sortChannelVideos(items, HomeSort.Oldest).map { it.ratingKey },
        )
    }

    @Test
    fun `title sort ignores case`() {
        assertEquals(
            listOf("a", "b", "c"),
            sortChannelVideos(items, HomeSort.TitleAsc).map { it.ratingKey },
        )
    }

    @Test
    fun `longest first sorts by duration`() {
        assertEquals(
            listOf("a", "b", "c"),
            sortChannelVideos(items, HomeSort.DurationDesc).map { it.ratingKey },
        )
    }
}
