package dev.owndemand.app.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owndemand.app.AppContainer
import dev.owndemand.app.data.plex.dto.ItemDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class HomeSort(val label: String) {
    Mixed("Smart mix"),
    Newest("Newest first"),
    Oldest("Oldest first"),
    TitleAsc("Title A–Z"),
    DurationDesc("Longest first"),
}

data class HomeUiState(
    val feed: List<ItemDto> = emptyList(),
    val continueWatching: List<ItemDto> = emptyList(),
    val totalSize: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val sort: HomeSort = HomeSort.Mixed,
    /** Pull-to-refresh in progress (the current feed stays on screen). */
    val refreshing: Boolean = false,
    /** The index list the feed was last built from (identity-compared). */
    val indexItems: List<ItemDto>? = null,
    /** The index's full-load generation the feed was last rebuilt for. */
    val indexGeneration: Int = -1,
    /** Videos removed from Continue watching (see AppSettings.hiddenContinue). */
    val hiddenContinue: Map<String, Long> = emptyMap(),
)

/**
 * Home feed over the in-memory [dev.owndemand.app.data.LibraryIndex]:
 * a Continue-watching row plus a feed of **unwatched items only** (user
 * direction 2026-10-16) in either the "smart mix" order (freshest two on
 * top, then recent runs interleaved with random picks on a per-session
 * seed) or an explicit sort.
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState(loading = true))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** Fixed for the ViewModel's lifetime → stable order while browsing. */
    private val sessionSeed = Random.nextInt()

    init {
        viewModelScope.launch {
            container.libraryIndex.ensureLoaded(container)
        }
        viewModelScope.launch {
            // Observe loading and error too, not just items: the index
            // publishes its items *before* clearing its loading flag, so
            // sampling loading.value on an items emission left the
            // progress row up forever.
            val index = container.libraryIndex
            libraryIndexSnapshots(
                index.items, index.loading, index.error, index.generation, index.refreshing,
            ).collect { snapshot ->
                _state.value = _state.value.withIndex(snapshot, sessionSeed)
            }
        }
        // Removing a video from Continue watching updates the row at once.
        viewModelScope.launch {
            container.settingsRepository.settings.collect { s ->
                val st = _state.value
                if (s.hiddenContinue == st.hiddenContinue) return@collect
                _state.value = st.copy(
                    hiddenContinue = s.hiddenContinue,
                    continueWatching = st.indexItems
                        ?.let { continueWatchingFor(it, s.hiddenContinue, CONTINUE_ROW_LIMIT) }
                        ?: st.continueWatching,
                )
            }
        }
    }

    /** Pull-to-refresh: reload the library, keeping the current feed shown. */
    fun refresh() {
        viewModelScope.launch { container.libraryIndex.refresh(container) }
    }

    fun retry() {
        container.libraryIndex.invalidate()
        viewModelScope.launch { container.libraryIndex.ensureLoaded(container) }
    }

    fun setSort(sort: HomeSort) {
        _state.value = _state.value.copy(sort = sort)
        container.libraryIndex.items.value?.let { rebuild(it) }
    }

    /** Home always lands on the smart mix (user direction 2026-10-16). */
    fun resetSort() {
        if (_state.value.sort == HomeSort.Mixed) return
        _state.value = _state.value.copy(sort = HomeSort.Mixed)
        container.libraryIndex.items.value?.let { rebuild(it) }
    }

    private fun rebuild(all: List<ItemDto>) {
        _state.value = _state.value.rebuilt(all, sessionSeed)
    }
}

/** One observation of [dev.owndemand.app.data.LibraryIndex]'s published state. */
data class LibraryIndexSnapshot(
    val items: List<ItemDto>?,
    val loading: Boolean,
    val error: String?,
    /** Full-load generation; a change means "rebuild", same means "patch". */
    val generation: Int = 0,
    val refreshing: Boolean = false,
)

/** The index's published state as one stream. */
fun libraryIndexSnapshots(
    items: Flow<List<ItemDto>?>,
    loading: Flow<Boolean>,
    error: Flow<String?>,
    generation: Flow<Int> = flowOf(0),
    refreshing: Flow<Boolean> = flowOf(false),
): Flow<LibraryIndexSnapshot> = combine(items, loading, error, generation, refreshing, ::LibraryIndexSnapshot)

/**
 * Applies an index snapshot. A new full load (first load, pull-to-refresh)
 * rebuilds the feed; a single-video update (same generation, new list) is
 * patched in place so the cards the user is looking at do not reshuffle.
 */
fun HomeUiState.withIndex(snapshot: LibraryIndexSnapshot, seed: Int): HomeUiState {
    val items = snapshot.items
    val base = when {
        items == null -> this
        indexItems == null || snapshot.generation != indexGeneration -> rebuilt(items, seed)
        items !== indexItems -> patched(items)
        else -> this
    }
    return base.copy(
        loading = snapshot.loading,
        error = snapshot.error,
        refreshing = snapshot.refreshing,
        totalSize = items?.size ?: 0,
        indexItems = items,
        indexGeneration = if (items != null) snapshot.generation else indexGeneration,
    )
}

/**
 * Patches the current feed with fresher copies of its videos, keeping its
 * order (a video that is now watched leaves the unwatched-only feed), and
 * recomputes Continue watching — most recently watched first, so the video
 * just left moves to the front.
 */
fun HomeUiState.patched(all: List<ItemDto>, limit: Int = CONTINUE_ROW_LIMIT): HomeUiState {
    val byKey = all.associateBy { it.ratingKey }
    val newFeed = feed.mapNotNull { old ->
        val fresh = byKey[old.ratingKey] ?: old
        fresh.takeIf { it.viewCount == 0 }
    }
    return copy(feed = newFeed, continueWatching = continueWatchingFor(all, hiddenContinue, limit))
}

/** Recomputes the feed and Continue-watching row for the current sort. */
fun HomeUiState.rebuilt(all: List<ItemDto>, seed: Int): HomeUiState = copy(
    // Home shows unwatched content only; resume-able videos live
    // in the Continue watching row (user direction 2026-10-16).
    feed = buildHomeFeed(unwatchedOnly(all), sort, seed),
    continueWatching = continueWatchingFor(all, hiddenContinue, CONTINUE_ROW_LIMIT),
)

/** Videos in the Home row; the Library tab shows them all. */
const val CONTINUE_ROW_LIMIT = 15

/** Never played at all — what the home feed is made of. */
fun unwatchedOnly(items: List<ItemDto>): List<ItemDto> = items.filter { it.viewCount == 0 }

/**
 * Feed ordering. [Mixed] keeps the freshest [MIXED_HEAD] items on top,
 * then a seeded shuffle of recent + random library picks, then the rest
 * newest-first — so the feed is fresh, varied, and never identical
 * between sessions (user request 2026-10-16).
 */
fun buildHomeFeed(
    items: List<ItemDto>,
    sort: HomeSort,
    seed: Int,
): List<ItemDto> {
    val newest = items.sortedByDescending { it.addedAt }
    return when (sort) {
        HomeSort.Newest -> newest
        HomeSort.Oldest -> newest.sortedBy { it.addedAt }
        HomeSort.TitleAsc -> items.sortedBy { it.title.lowercase() }
        HomeSort.DurationDesc -> items.sortedByDescending { it.duration }
        HomeSort.Mixed -> {
            // After the freshest MIXED_HEAD items, alternate short runs of
            // recent videos with random *unwatched* picks (user direction
            // 2026-10-16), so fresh content leads and discovery is woven in.
            val head = newest.take(MIXED_HEAD)
            val used = head.mapTo(LinkedHashSet()) { it.ratingKey }
            val recentRest = newest.drop(MIXED_HEAD)
            val randomUnwatched = recentRest.filter { it.viewCount == 0 }
                .shuffled(Random(seed.toLong() * 31 + 7))
            val result = ArrayList<ItemDto>(items.size)
            result += head
            var ri = 0
            var ui = 0
            while (ri < recentRest.size || ui < randomUnwatched.size) {
                repeat(MIXED_RECENT_RUN) {
                    while (ri < recentRest.size) {
                        val c = recentRest[ri++]
                        if (used.add(c.ratingKey)) { result += c; break }
                    }
                }
                while (ui < randomUnwatched.size) {
                    val c = randomUnwatched[ui++]
                    if (used.add(c.ratingKey)) { result += c; break }
                }
            }
            result
        }
    }
}

private const val MIXED_HEAD = 2
private const val MIXED_RECENT_RUN = 2
