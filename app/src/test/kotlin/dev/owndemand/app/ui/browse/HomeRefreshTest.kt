package dev.owndemand.app.ui.browse

import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.data.replaceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Single-video updates patch Home in place; full reloads rebuild it. */
class HomeRefreshTest {

    private fun item(key: String, addedAt: Long = key.toLong(), viewOffset: Long = 0, viewCount: Int = 0) =
        ItemDto(
            ratingKey = key,
            title = "Video $key",
            addedAt = addedAt,
            duration = 600_000,
            viewOffset = viewOffset,
            viewCount = viewCount,
        )

    private val library = (1..30).map { item("$it") } +
        item("31", viewOffset = 200_000) + item("32", viewOffset = 300_000)

    private fun loaded(generation: Int = 1): HomeUiState =
        HomeUiState().withIndex(LibraryIndexSnapshot(library, false, null, generation), seed = 7)

    @Test
    fun `replaceItem swaps the matching video and keeps the list when absent`() {
        val fresh = item("5", viewCount = 1)
        val replaced = replaceItem(library, fresh)
        assertSame(fresh, replaced.first { it.ratingKey == "5" })
        assertEquals(library.size, replaced.size)
        assertSame(library, replaceItem(library, item("999")))
    }

    @Test
    fun `finished video leaves the feed and the order of the rest is kept`() {
        val before = loaded()
        val target = before.feed[3]
        val updated = replaceItem(library, target.copy(viewCount = 1))
        val after = before.withIndex(LibraryIndexSnapshot(updated, false, null, generation = 1), seed = 7)

        assertTrue(after.feed.none { it.ratingKey == target.ratingKey })
        assertEquals(before.feed.filter { it.ratingKey != target.ratingKey }.map { it.ratingKey },
            after.feed.map { it.ratingKey })
    }

    @Test
    fun `continue watching updates progress, drops finished, adds newly part-watched first`() {
        val before = loaded()
        assertEquals(setOf("31", "32"), before.continueWatching.map { it.ratingKey }.toSet())

        var updated = replaceItem(library, item("31", viewOffset = 450_000)) // progress moved
        updated = replaceItem(updated, item("32", viewCount = 1))            // finished
        updated = replaceItem(updated, item("7", viewOffset = 120_000))      // started and left
        val after = before.withIndex(LibraryIndexSnapshot(updated, false, null, generation = 1), seed = 7)

        assertEquals(listOf("7", "31"), after.continueWatching.map { it.ratingKey })
        assertEquals(450_000L, after.continueWatching.first { it.ratingKey == "31" }.viewOffset)
    }

    @Test
    fun `a new generation rebuilds instead of patching`() {
        val before = loaded(generation = 1)
        val fresh = library.map { it.copy() } // a full reload: new list, new objects
        val patchedSameGen = before.withIndex(LibraryIndexSnapshot(fresh, false, null, generation = 1), seed = 7)
        val rebuilt = before.withIndex(LibraryIndexSnapshot(fresh, false, null, generation = 2), seed = 99)

        assertEquals(before.feed.map { it.ratingKey }, patchedSameGen.feed.map { it.ratingKey })
        assertEquals(2, rebuilt.indexGeneration)
        assertNotEquals("new seed reorders after a full reload",
            before.feed.map { it.ratingKey }, rebuilt.feed.map { it.ratingKey })
    }

    @Test
    fun `refreshing flag passes through without touching the feed`() {
        val before = loaded()
        val during = before.withIndex(
            LibraryIndexSnapshot(library, false, null, generation = 1, refreshing = true), seed = 7,
        )
        assertTrue(during.refreshing)
        assertSame(before.feed, during.feed)
    }
}
