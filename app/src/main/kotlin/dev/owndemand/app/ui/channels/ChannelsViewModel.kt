package dev.owndemand.app.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owndemand.app.AppContainer
import dev.owndemand.app.data.replaceItem
import dev.owndemand.app.data.plex.dto.CollectionDto
import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.ui.browse.HomeSort
import dev.owndemand.app.ui.common.PagedListState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Channel collections of the selected library, paged (server order = A-Z). */
class ChannelsViewModel(private val container: AppContainer) : ViewModel() {
    companion object {
        const val PAGE_SIZE = 40
    }

    private val _state = MutableStateFlow(PagedListState<CollectionDto>())
    val state: StateFlow<PagedListState<CollectionDto>> = _state.asStateFlow()

    private var loadedCount = 0
    private var loading = false

    init {
        loadMore()
    }

    /**
     * Pull-to-refresh (tablet rail, alongside the open channel): reloads as
     * many channels as are already shown, in place.
     */
    fun refresh() {
        if (loading) return
        loading = true
        _state.value = _state.value.copy(refreshing = true, error = null)
        viewModelScope.launch {
            try {
                val sectionKey = container.currentSectionKey
                    ?: error("no library selected")
                val (page, total) = container.repository()
                    .collections(sectionKey, 0, maxOf(PAGE_SIZE, loadedCount))
                loadedCount = page.size
                _state.value = PagedListState(items = page, totalSize = total)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    refreshing = false,
                    error = e.localizedMessage ?: "Could not refresh channels.",
                )
            } finally {
                loading = false
            }
        }
    }

    fun loadMore() {
        if (loading || _state.value.loadedAll) return
        loading = true
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val sectionKey = container.currentSectionKey
                    ?: error("no library selected")
                val (page, total) = container.repository()
                    .collections(sectionKey, loadedCount, PAGE_SIZE)
                loadedCount += page.size
                _state.value = _state.value.copy(
                    items = _state.value.items + page,
                    totalSize = total,
                    loading = false,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.localizedMessage ?: "Could not load channels.",
                )
            } finally {
                loading = false
            }
        }
    }
}

/**
 * One channel's videos. The whole channel is loaded up front — channels are
 * small enough (the library's largest is a few hundred items) — so sorting
 * covers every video, not just the loaded page (user request 2026-10-16).
 */
class ChannelDetailViewModel(
    private val container: AppContainer,
    private val ratingKey: String,
) : ViewModel() {
    companion object {
        const val PAGE_SIZE = 100
    }

    private val _state = MutableStateFlow(PagedListState<ItemDto>())
    val state: StateFlow<PagedListState<ItemDto>> = _state.asStateFlow()

    private val _sort = MutableStateFlow(HomeSort.Newest)
    val sort: StateFlow<HomeSort> = _sort.asStateFlow()

    /** The channel's videos in the order the sort menu picked. */
    val videos: StateFlow<List<ItemDto>> =
        combine(_state, _sort) { s, sort -> sortChannelVideos(s.items, sort) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        loadAll()
        // A video finished or was left somewhere: swap in its fresh copy
        // (watched chip, progress) if it belongs to this channel.
        viewModelScope.launch {
            container.libraryIndex.updates.collect { fresh ->
                val s = _state.value
                val patched = replaceItem(s.items, fresh)
                if (patched !== s.items) _state.value = s.copy(items = patched)
            }
        }
    }

    fun setSort(sort: HomeSort) {
        _sort.value = sort
    }

    /** Retry button. */
    fun loadMore() = loadAll()

    /** Pull-to-refresh: reload the channel, keeping its videos shown. */
    fun refresh() = loadAll(inPlace = true)

    private fun loadAll(inPlace: Boolean = false) {
        if (_state.value.loading || _state.value.refreshing) return
        _state.value = if (inPlace) {
            _state.value.copy(refreshing = true, error = null)
        } else {
            _state.value.copy(loading = true, error = null)
        }
        viewModelScope.launch {
            try {
                val all = ArrayList<ItemDto>()
                var start = 0
                while (true) {
                    val (page, total) = container.repository()
                        .collectionChildren(ratingKey, start, PAGE_SIZE)
                    if (page.isEmpty() || all.size >= total) break
                    all += page
                    start += page.size
                }
                _state.value = PagedListState(items = all, totalSize = all.size)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    refreshing = false,
                    error = e.localizedMessage ?: "Could not load this channel.",
                )
            }
        }
    }
}

/**
 * Channel sort orders. Mixed is Home-only; here it means newest first.
 * Newest / oldest go by upload date, like playing the channel (user choice
 * 2026-10-20); Home keeps the date added.
 */
fun sortChannelVideos(items: List<ItemDto>, sort: HomeSort): List<ItemDto> = when (sort) {
    HomeSort.Oldest -> dev.owndemand.app.playback.orderChannel(items, dev.owndemand.app.playback.ChannelOrder.OldestFirst)
    HomeSort.TitleAsc -> items.sortedBy { it.title.lowercase() }
    HomeSort.DurationDesc -> items.sortedByDescending { it.duration }
    HomeSort.Newest, HomeSort.Mixed ->
        dev.owndemand.app.playback.orderChannel(items, dev.owndemand.app.playback.ChannelOrder.NewestFirst)
}
