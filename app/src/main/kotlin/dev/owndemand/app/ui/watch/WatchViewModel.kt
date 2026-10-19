package dev.owndemand.app.ui.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owndemand.app.AppContainer
import dev.owndemand.app.data.replaceItem
import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.playback.PlaybackManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for watching exactly one video. Instances are cached per
 * ratingKey for the activity's lifetime (WatchScreen keys them), so a VM
 * must never switch to another video — otherwise reopening video A later
 * returns A's cached VM showing whatever it was switched to. Up-next taps
 * therefore navigate (MainViewModel.play) instead of reloading this VM.
 */
class WatchViewModel(
    private val container: AppContainer,
    private val ratingKey: String,
) : ViewModel() {

    private val _item = MutableStateFlow<ItemDto?>(null)
    val item: StateFlow<ItemDto?> = _item.asStateFlow()

    private val _upNext = MutableStateFlow<List<ItemDto>>(emptyList())
    val upNext: StateFlow<List<ItemDto>> = _upNext.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val playback: PlaybackManager = container.playbackManager()

    init {
        load()
        // Keep Up next current when a video elsewhere finishes or is left.
        viewModelScope.launch {
            container.libraryIndex.updates.collect { fresh ->
                val list = _upNext.value
                val patched = replaceItem(list, fresh)
                if (patched !== list) _upNext.value = patched
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _error.value = null
            try {
                val repo = container.repository()
                val detail = repo.item(ratingKey)
                _item.value = detail
                // If the miniplayer already holds this video, keep its
                // position; only start fresh streams for a new item.
                if (playback.currentRatingKey != ratingKey ||
                    playback.player.currentMediaItem == null
                ) {
                    playback.play(detail)
                }
                if (_upNext.value.isEmpty()) {
                    val sectionKey = container.currentSectionKey ?: return@launch
                    val (feed, _) = repo.feed(sectionKey, 0, 40)
                    _upNext.value = feed.filter { it.ratingKey != ratingKey }
                }
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Could not load this video."
            }
        }
    }

    /**
     * Called whenever the watch screen (re)opens this video. The VM is
     * cached, so init (and its load) runs only the first time; on later
     * entries the shared player may hold another video (e.g. one docked in
     * the miniplayer) or nothing (miniplayer closed). In both cases reload:
     * fresh detail (current resume offset) and a stream switch. Re-entering
     * the video that is already playing keeps its position.
     */
    fun onEnter() {
        if (_item.value == null) return // first load still in flight
        if (playback.currentRatingKey != ratingKey ||
            playback.player.currentMediaItem == null
        ) {
            load()
        }
    }
}
