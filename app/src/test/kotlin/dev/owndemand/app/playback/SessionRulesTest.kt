package dev.owndemand.app.playback

import dev.owndemand.app.data.plex.dto.ItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Notification / lock-screen / headset / PiP controls (Chunk 4). */
class SessionRulesTest {

    private fun item(key: String, viewCount: Int = 0, viewOffset: Long = 0) = ItemDto(
        ratingKey = key, title = "Video $key", duration = 600_000,
        viewCount = viewCount, viewOffset = viewOffset,
    )

    @Test
    fun `previous restarts after 5 s or with no history, else goes back`() {
        assertEquals(PreviousAction.Restart, previousAction(positionMs = 1_000, history = emptyList()))
        assertEquals(PreviousAction.Restart, previousAction(positionMs = 5_001, history = listOf("a")))
        assertEquals(PreviousAction.PlayPrevious("b"), previousAction(positionMs = 5_000, history = listOf("a", "b")))
        assertEquals(PreviousAction.PlayPrevious("b"), previousAction(positionMs = 0, history = listOf("a", "b")))
    }

    @Test
    fun `next is the first Up-next video not finished and not played this session`() {
        val library = listOf(item("1"), item("2", viewCount = 1), item("3"), item("4", viewOffset = 60_000), item("5"))
        assertEquals("3", pickNextVideo(library, currentKey = "1", played = setOf("1"))?.ratingKey)
        // Part-watched counts as not finished.
        assertEquals("4", pickNextVideo(library, currentKey = "3", played = setOf("1", "3"))?.ratingKey)
        // Repeated next never loops back to a skipped video.
        assertEquals("5", pickNextVideo(library, currentKey = "4", played = setOf("1", "3", "4"))?.ratingKey)
        assertNull(pickNextVideo(library, currentKey = "5", played = setOf("1", "3", "4", "5")))
    }

    @Test
    fun `next stays within the Up-next window`() {
        val library = (1..UP_NEXT_SIZE).map { item("$it", viewCount = 1) } + item("late")
        assertNull(pickNextVideo(library, currentKey = "1", played = emptySet()))
        // A current video outside the window does not shrink it.
        assertEquals("late", pickNextVideo(listOf(item("late")), currentKey = "x", played = emptySet())?.ratingKey)
    }

    @Test
    fun `history drops repeats and keeps the latest`() {
        assertEquals(listOf("a", "b"), pushHistory(listOf("a"), "b"))
        assertEquals(listOf("b", "a"), pushHistory(listOf("a", "b"), "a"))
        val long = (1..HISTORY_LIMIT).map { "$it" }
        val pushed = pushHistory(long, "new")
        assertEquals(HISTORY_LIMIT, pushed.size)
        assertEquals("2", pushed.first())
        assertEquals("new", pushed.last())
    }
}
