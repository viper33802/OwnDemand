package dev.owndemand.app.data

import dev.owndemand.app.AppContainer
import dev.owndemand.app.data.plex.dto.CollectionDto
import dev.owndemand.app.data.plex.dto.ItemDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The whole selected library in memory (M5). The library is a few hundred
 * items and pages of 100 come back in ~100 ms on the LAN, so search,
 * sorting, watched filters and the mixed home feed all work against this
 * index instead of round-tripping Plex (whose `/library/search` is not
 * reliably section-scoped).
 *
 * [ensureLoaded] is idempotent per section; a section/server change
 * reloads. [invalidate] forces a reload (sign-out, manual refresh).
 */
class LibraryIndex {
    /** null = not loaded yet. */
    private val _items = MutableStateFlow<List<ItemDto>?>(null)
    val items: StateFlow<List<ItemDto>?> = _items.asStateFlow()

    /** Channels (Collections) for search's channel matches. */
    private val _collections = MutableStateFlow<List<CollectionDto>?>(null)
    val collections: StateFlow<List<CollectionDto>?> = _collections.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var loadedSectionKey: String? = null
    private var inFlight = false

    fun invalidate() {
        loadedSectionKey = null
        _items.value = null
        _collections.value = null
    }

    suspend fun ensureLoaded(container: AppContainer) {
        val sectionKey = container.currentSectionKey ?: return
        if (inFlight) return
        if (loadedSectionKey == sectionKey && _items.value != null) return
        inFlight = true
        _loading.value = true
        _error.value = null
        try {
            val repository = container.repository()
            val all = ArrayList<ItemDto>(800)
            var start = 0
            while (true) {
                val (page, total) = repository.feed(sectionKey, start, PAGE_SIZE)
                if (page.isEmpty()) break
                all += page
                start += page.size
                if (start >= total) break
            }
            val channels = ArrayList<CollectionDto>(64)
            start = 0
            while (true) {
                val (page, total) = repository.collections(sectionKey, start, PAGE_SIZE)
                if (page.isEmpty()) break
                channels += page
                start += page.size
                if (start >= total) break
            }
            _items.value = all
            _collections.value = channels
            loadedSectionKey = sectionKey
        } catch (e: Exception) {
            _error.value = e.localizedMessage ?: "Could not load the library."
        } finally {
            _loading.value = false
            inFlight = false
        }
    }

    companion object {
        private const val PAGE_SIZE = 100
    }
}
