package dev.owndemand.app.ui.browse

import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.data.plex.dto.TagDto
import dev.owndemand.app.ui.common.videoMenuOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRulesTest {

    private val now = 10_000_000L // epoch seconds
    private val day = 24 * 60 * 60L

    private fun item(
        key: String,
        addedAt: Long = now - day,
        viewOffset: Long = 0,
        viewCount: Int = 0,
        lastViewedAt: Long = 0,
        channel: String? = "Orbit Notes",
    ) = ItemDto(
        ratingKey = key, title = "Video $key", addedAt = addedAt, duration = 600_000,
        viewOffset = viewOffset, viewCount = viewCount, lastViewedAt = lastViewedAt,
        Collection = listOfNotNull(channel?.let { TagDto(it) }),
    )

    @Test
    fun `new means unplayed, no progress, added within 7 days`() {
        assertTrue(isNewVideo(item("a", addedAt = now - 6 * day), now))
        assertTrue(isNewVideo(item("b", addedAt = now - 7 * day), now))
        assertFalse(isNewVideo(item("c", addedAt = now - 8 * day), now))
        assertFalse(isNewVideo(item("d", viewOffset = 120_000), now))   // started
        assertFalse(isNewVideo(item("e", viewCount = 1), now))          // watched
        assertFalse(isNewVideo(item("f", addedAt = 0), now))            // unknown date
    }

    @Test
    fun `new counts per channel`() {
        val items = listOf(
            item("1"), item("2"), item("3", viewCount = 1),
            item("4", channel = "Trail Diaries"), item("5", channel = null),
        )
        assertEquals(mapOf("Orbit Notes" to 2, "Trail Diaries" to 1), newCountByChannel(items, now))
    }

    @Test
    fun `recently watched is most recent first and skips never-played`() {
        val items = listOf(
            item("a", viewCount = 1, lastViewedAt = 50),
            item("b"),
            item("c", viewOffset = 200_000, lastViewedAt = 90),
        )
        assertEquals(listOf("c", "a"), recentlyWatched(items).map { it.ratingKey })
    }

    @Test
    fun `menu options follow the watched state`() {
        val unwatched = videoMenuOptions(item("u"), hiddenFromContinue = false)
        assertTrue(unwatched.markWatched); assertFalse(unwatched.markUnwatched)
        assertFalse(unwatched.removeFromContinue); assertTrue(unwatched.goToChannel)

        val partial = videoMenuOptions(item("p", viewOffset = 200_000), hiddenFromContinue = false)
        assertTrue(partial.markWatched); assertTrue(partial.markUnwatched); assertTrue(partial.removeFromContinue)
        assertFalse(videoMenuOptions(item("p", viewOffset = 200_000), hiddenFromContinue = true).removeFromContinue)

        val watched = videoMenuOptions(item("w", viewCount = 1), hiddenFromContinue = false)
        assertFalse(watched.markWatched); assertTrue(watched.markUnwatched); assertFalse(watched.removeFromContinue)

        assertFalse(videoMenuOptions(item("n", channel = null), false).goToChannel)
    }
}
