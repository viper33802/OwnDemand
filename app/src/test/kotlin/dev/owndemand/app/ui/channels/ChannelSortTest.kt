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
    fun `newest and oldest go by upload date, not the date added`() {
        // Back catalogue downloaded in one go: added order disagrees with upload order.
        val catalogue = listOf(
            ItemDto(ratingKey = "late-upload", addedAt = 100, originallyAvailableAt = "2025-06-01"),
            ItemDto(ratingKey = "early-upload", addedAt = 300, originallyAvailableAt = "2019-02-10"),
            ItemDto(ratingKey = "mid-upload", addedAt = 200, originallyAvailableAt = "2022-11-30"),
        )
        assertEquals(listOf("late-upload", "mid-upload", "early-upload"),
            sortChannelVideos(catalogue, HomeSort.Newest).map { it.ratingKey })
        assertEquals(listOf("early-upload", "mid-upload", "late-upload"),
            sortChannelVideos(catalogue, HomeSort.Oldest).map { it.ratingKey })
    }

    @Test
    fun `upload date reads Plex's yyyy-MM-dd and ignores anything else`() {
        assertEquals(java.time.LocalDate.of(2026, 10, 18), ItemDto(originallyAvailableAt = "2026-10-18").uploadDate)
        assertEquals(null, ItemDto(originallyAvailableAt = "").uploadDate)
        assertEquals(null, ItemDto(originallyAvailableAt = "October").uploadDate)
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
