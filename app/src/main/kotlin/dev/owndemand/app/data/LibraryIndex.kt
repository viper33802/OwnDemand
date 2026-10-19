package dev.owndemand.app.data

import dev.owndemand.app.AppContainer
import dev.owndemand.app.data.plex.dto.CollectionDto
import dev.owndemand.app.data.plex.dto.ItemDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The whole selected library in memory (M5). The library is a few hundred
 * items and pages of 100 come back in ~100 ms on the LAN, so search,
 * sorting, watched filters and the mixed home feed all work against this
 * index instead of round-tripping Plex (whose `/library/search` is not
 * reliably section-scoped).
 *
 * [ensureLoaded] is idempotent per section; a section/server change
 * reloads. [invalidate] drops everything (sign-out, library switch);
 * [refresh] reloads in place for pull-to-refresh, keeping the current
 * items visible until the new ones arrive. [refreshItem] re-reads one
 * video (after playback finishes or is left) and publishes it on [updates].
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

    /** True while a pull-to-refresh reload runs over already-shown items. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * Bumped on every completed full load. Lets observers tell a full
     * reload (rebuild, reorder is fine) from a single-item patch from
     * [refreshItem] (patch in place, keep the order the user is looking at).
     */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** Fresh copies of single videos (watched state / resume offset changed). */
    private val _updates = MutableSharedFlow<ItemDto>(extraBufferCapacity = 16)
    val updates: SharedFlow<ItemDto> = _updates.asSharedFlow()

    private var loadedSectionKey: String? = null
    private var inFlight = false

    fun invalidate() {
        loadedSectionKey = null
        _items.value = null
        _collections.value = null
    }

    suspend fun ensureLoaded(container: AppContainer) = load(container, force = false)

    /** Pull-to-refresh: reload the whole library, keeping current items shown. */
    suspend fun refresh(container: AppContainer) = load(container, force = true)

    /**
     * Re-reads one video from the server and swaps it into the index (if
     * present), then publishes it on [updates] for screens holding their
     * own copies (channel pages, Up next). Best-effort: errors are ignored.
     */
    suspend fun refreshItem(repository: PlexRepository, ratingKey: String) {
        val fresh = runCatching { repository.item(ratingKey) }.getOrNull() ?: return
        _items.value?.let { _items.value = replaceItem(it, fresh) }
        _updates.emit(fresh)
    }

    private suspend fun load(container: AppContainer, force: Boolean) {
        val sectionKey = container.currentSectionKey ?: return
        if (inFlight) return
        if (!force && loadedSectionKey == sectionKey && _items.value != null) return
        inFlight = true
        val inPlace = force && _items.value != null && loadedSectionKey == sectionKey
        if (inPlace) _refreshing.value = true else _loading.value = true
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
            _generation.value += 1
        } catch (e: Exception) {
            _error.value = e.localizedMessage ?: "Could not load the library."
        } finally {
            _loading.value = false
            _refreshing.value = false
            inFlight = false
        }
    }

    companion object {
        private const val PAGE_SIZE = 100
    }
}

/** [items] with the entry matching [fresh]'s ratingKey replaced; same list if absent. */
fun replaceItem(items: List<ItemDto>, fresh: ItemDto): List<ItemDto> {
    val i = items.indexOfFirst { it.ratingKey == fresh.ratingKey }
    if (i < 0) return items
    return items.toMutableList().also { it[i] = fresh }
}
