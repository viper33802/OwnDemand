package dev.owndemand.app.ui.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owndemand.app.AppContainer
import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.playback.PlaybackManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WatchViewModel(
    private val container: AppContainer,
    initialRatingKey: String,
) : ViewModel() {

    private val _item = MutableStateFlow<ItemDto?>(null)
    val item: StateFlow<ItemDto?> = _item.asStateFlow()

    private val _upNext = MutableStateFlow<List<ItemDto>>(emptyList())
    val upNext: StateFlow<List<ItemDto>> = _upNext.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val playback: PlaybackManager = container.playbackManager()

    init {
        load(initialRatingKey)
    }

    fun load(ratingKey: String) {
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

    fun playNext(item: ItemDto) {
        load(item.ratingKey)
    }

    /**
     * (Re)start playback if the player was stopped since this VM was
     * created — covers re-entering the watch screen for the same video
     * after closing the miniplayer.
     */
    fun replayIfIdle() {
        val item = _item.value ?: return
        if (playback.player.currentMediaItem == null) playback.play(item)
    }
}
