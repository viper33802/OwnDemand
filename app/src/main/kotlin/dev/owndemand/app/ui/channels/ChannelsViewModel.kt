package dev.owndemand.app.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owndemand.app.AppContainer
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
    }

    fun setSort(sort: HomeSort) {
        _sort.value = sort
    }

    /** Retry button. */
    fun loadMore() = loadAll()

    private fun loadAll() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)
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
                    error = e.localizedMessage ?: "Could not load this channel.",
                )
            }
        }
    }
}

/** Channel sort orders. Mixed is Home-only; here it means newest first. */
fun sortChannelVideos(items: List<ItemDto>, sort: HomeSort): List<ItemDto> = when (sort) {
    HomeSort.Oldest -> items.sortedBy { it.addedAt }
    HomeSort.TitleAsc -> items.sortedBy { it.title.lowercase() }
    HomeSort.DurationDesc -> items.sortedByDescending { it.duration }
    HomeSort.Newest, HomeSort.Mixed -> items.sortedByDescending { it.addedAt }
}
