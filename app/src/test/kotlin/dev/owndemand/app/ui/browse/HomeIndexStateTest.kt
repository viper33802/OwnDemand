package dev.owndemand.app.ui.browse

import dev.owndemand.app.data.plex.dto.ItemDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home state follows the library index's items, loading and error flows. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeIndexStateTest {

    private fun item(key: String, addedAt: Long) =
        ItemDto(ratingKey = key, title = "Video $key", addedAt = addedAt, duration = 600_000)

    private val library = (1..40).map { item("$it", addedAt = it * 1_000L) }

    @Test
    fun loadingClearsAfterItemsArrive() = runTest(UnconfinedTestDispatcher()) {
        // Same publication order as LibraryIndex.ensureLoaded: loading on,
        // items set, then loading cleared in the finally block.
        val items = MutableStateFlow<List<ItemDto>?>(null)
        val loading = MutableStateFlow(false)
        val error = MutableStateFlow<String?>(null)
        var state = HomeUiState(loading = true)
        val job = launch {
            libraryIndexSnapshots(items, loading, error).collect {
                state = state.withIndex(it, seed = 7)
            }
        }

        loading.value = true
        assertTrue(state.loading)
        assertTrue(state.feed.isEmpty())

        items.value = library
        assertEquals(40, state.feed.size)
        assertEquals(40, state.totalSize)

        loading.value = false
        assertFalse("loading must clear once the index finishes", state.loading)
        assertEquals(40, state.feed.size)
        job.cancel()
    }

    @Test
    fun errorIsReportedAndLoadingClears() = runTest(UnconfinedTestDispatcher()) {
        val items = MutableStateFlow<List<ItemDto>?>(null)
        val loading = MutableStateFlow(true)
        val error = MutableStateFlow<String?>(null)
        var state = HomeUiState(loading = true)
        val job = launch {
            libraryIndexSnapshots(items, loading, error).collect {
                state = state.withIndex(it, seed = 7)
            }
        }

        error.value = "unreachable"
        loading.value = false
        assertEquals("unreachable", state.error)
        assertFalse(state.loading)
        assertTrue(state.feed.isEmpty())
        job.cancel()
    }

    @Test
    fun feedIsNotRebuiltWhenOnlyLoadingChanges() {
        val loaded = HomeUiState().withIndex(
            LibraryIndexSnapshot(library, loading = true, error = null),
            seed = 7,
        )
        val settled = loaded.withIndex(
            LibraryIndexSnapshot(library, loading = false, error = null),
            seed = 7,
        )
        assertSame(loaded.feed, settled.feed)
        assertFalse(settled.loading)
    }
}
